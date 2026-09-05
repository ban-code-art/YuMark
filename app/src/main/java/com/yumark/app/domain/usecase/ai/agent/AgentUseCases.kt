package com.yumark.app.domain.usecase.ai.agent

import com.yumark.app.R
import com.yumark.app.core.text.ContentHash
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.UiMessage
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.AgentStep
import com.yumark.app.domain.model.AgentStatusCode
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.AiTool
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.ConversationStatus
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageAttachment
import com.yumark.app.domain.model.MessageContent
import com.yumark.app.domain.model.MessageRole
import com.yumark.app.domain.model.StreamEvent
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.repository.AiConfigRepository
import com.yumark.app.domain.repository.AgentTaskRepository
import com.yumark.app.domain.repository.ConversationRepository
import com.yumark.app.domain.repository.DocumentVersionRepository
import com.yumark.app.domain.usecase.CreateDocumentUseCase
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import com.yumark.app.domain.usecase.SaveDocumentUseCase
import com.yumark.app.domain.usecase.ai.DocumentContextTools
import com.yumark.app.domain.usecase.ai.EditException
import com.yumark.app.domain.usecase.ai.ExecuteDocumentToolUseCase
import com.yumark.app.data.ai.AiAdapterFactory
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

sealed class AgentMessageState {
    object UserMessageSaved : AgentMessageState()
    data class AssistantMessageStarted(val messageId: String) : AgentMessageState()
    data class Streaming(val text: String) : AgentMessageState()
    data class ActionProposed(val messageId: String, val action: AgentAction) : AgentMessageState()
    data class ToolStep(val step: AgentStep) : AgentMessageState()

    /**
     * 一条提示性通知。[UiMessage] 而不是 String：`domain` 拿不到 Context，解析在界面层。
     *
     * 其中一条通知同时被写进 Room 当作助手消息正文（模型本轮没返回内容时的解释文本），
     * 那条只能是 [UiMessage.Raw]——存进库的必须是确定的字符串。
     */
    data class Notice(val message: UiMessage) : AgentMessageState()
    data class Completed(val fullText: String) : AgentMessageState()
    data class Error(val message: UiMessage) : AgentMessageState()
}

/**
 * Agent 对话：**单一工具优先循环**（重构后）。
 *
 * 不再预生成固定计划；模型在带工具的流式循环中自主决定：调读工具取证、用 update_plan 维护
 * todo、用 create/edit_document 提出写改动。返回纯文本即视为收敛。写改动经审批门确认后落库。
 *
 * 保留：doom-loop 重复签名检测、最大轮次、空响应兜底、取消/异常 finally 终态化。
 */
class SendAgentMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val configRepository: AiConfigRepository,
    private val adapterFactory: AiAdapterFactory,
    private val imageProcessor: com.yumark.app.core.image.ImageProcessor,
    private val agentTaskRepository: AgentTaskRepository,
    private val executeDocumentTool: ExecuteDocumentToolUseCase,
    private val buildWriteProposal: BuildWriteProposalUseCase,
    private val webSearchService: com.yumark.app.data.ai.web.WebSearchService,
    private val memoryService: com.yumark.app.data.ai.memory.MemoryService,
    private val ragPipeline: com.yumark.app.data.ai.rag.RagPipeline
) {
    operator fun invoke(
        conversationId: String,
        userMessage: String,
        currentDocumentId: String?,
        currentDocumentName: String?,
        currentDocumentContent: String?,
        attachments: List<MessageAttachment> = emptyList()
    ): Flow<AgentMessageState> = flow {
        val conversationBeforeTurn = conversationRepository.observeConversation(conversationId).first()
        conversationBeforeTurn?.let { conversation ->
            conversationRepository.updateConversation(conversation.copy(status = ConversationStatus.WORKING))
        }

        val userMsg = Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = userMessage,
            attachments = attachments
        )
        conversationRepository.addMessage(userMsg)
        emit(AgentMessageState.UserMessageSaved)

        val config = configRepository.observeConfig().first()
        if (config.apiKey.isBlank() || config.modelName.isBlank()) {
            conversationRepository.observeConversation(conversationId).first()?.let { conversation ->
                conversationRepository.updateConversation(conversation.copy(status = ConversationStatus.IDLE))
            }
            emit(AgentMessageState.Error(UiMessage.Res(R.string.ai_error_not_configured)))
            return@flow
        }

        val effectiveUserMessage = buildDocumentCreationFollowUpMessage(
            priorMessages = conversationBeforeTurn?.messages.orEmpty(),
            userMessage = userMessage
        )

        val adapter = adapterFactory.createAdapter(config)

        // 历史（纯文本，排除本轮用户消息——本轮单独构造，可能带图）
        val priorMessages = conversationRepository.observeConversation(conversationId).first()
            ?.messages.orEmpty()
            .filter { it.id != userMsg.id && !it.isStreaming && it.content.isNotBlank() }
            .map { ChatMessage(role = it.role.name.lowercase(), content = it.content) }

        val currentTurn = if (attachments.isEmpty()) {
            ChatMessage(role = "user", content = effectiveUserMessage)
        } else {
            val imageParts = attachments.mapNotNull { imageProcessor.readForVision(it).getOrNull() }
            val parts = buildList<MessageContent> {
                add(MessageContent.Text(effectiveUserMessage.ifBlank { "请分析这张图片。" }))
                addAll(imageParts)
            }
            ChatMessage(role = "user", content = effectiveUserMessage, contentParts = parts)
        }

        val assistant = Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true
        )
        conversationRepository.addMessage(assistant)
        emit(AgentMessageState.AssistantMessageStarted(assistant.id))

        val workingMessages = ArrayList(priorMessages).apply { add(currentTurn) }
        // 意图驱动的工具裁剪：只发送与本轮用户意图相关的工具，避免全量注入污染上下文。
        val appContext = com.yumark.app.domain.usecase.ai.intent.AppContext(
            hasOpenDocument = currentDocumentId != null && !currentDocumentContent.isNullOrBlank(),
            hasSelection = false,
            hasContextTags = false,
            hasRecentEdit = currentDocumentId != null
        )
        val intent = com.yumark.app.domain.usecase.ai.intent.IntentDetector.detect(effectiveUserMessage, appContext)
        val allTools = DocumentContextTools.getAllTools()
        val tools = com.yumark.app.domain.usecase.ai.intent.ToolSelector.selectTools(intent, allTools)
        val systemPrompt = buildAgentSystemPrompt(currentDocumentName, currentDocumentContent, tools)
        val full = StringBuilder()
        /** 上一轮已经显示给用户的正文（[full] 每轮清空，出错时不能用它判断「这条消息是空的」）。 */
        var shownSoFar = ""
        val agentSteps = ArrayList<AgentStep>()
        var lastToolSignature: String? = null

        // 复用本会话既有任务（若有）；否则任务由 update_plan 懒创建。
        var taskId: String? = agentTaskRepository.getTaskByConversationId(conversationId)?.task?.id
        var taskFinalized = false

        suspend fun finalizeTask(status: AgentTaskStatus, summary: String, blockingReason: String? = null) {
            taskFinalized = true
            val id = taskId ?: return
            val aggregate = agentTaskRepository.getTaskByConversationId(conversationId) ?: return
            val existing = aggregate.task
            if (existing.id != id) return
            // 模型常把某步标 in_progress、做完工具后直接跳最终结论/动作，不再补一条收尾 update_plan，
            // 该步于是永冻 RUNNING。任务一旦落终态，冷启动那条 reconcile 路径靠 liveStatuses 就选不到
            // 它了（理由同下方 finally 块与 AgentChatViewModel.stop()），时间线会继续画脉冲而状态胶囊
            // 已是终态——finally/stop 只覆盖了「中断」路径，正常 COMPLETED/BLOCKED/FAILED 收尾这里漏了。
            // 按任务终态给残留 RUNNING 步一个对应终态：完成→DONE、阻塞→BLOCKED、其余(失败/兜底)→FAILED。
            val stepTerminal = when (status) {
                AgentTaskStatus.COMPLETED -> AgentTaskStepStatus.DONE
                AgentTaskStatus.BLOCKED -> AgentTaskStepStatus.BLOCKED
                else -> AgentTaskStepStatus.FAILED
            }
            aggregate.steps
                .filter { it.status == AgentTaskStepStatus.RUNNING }
                .forEach { agentTaskRepository.markStepStatus(it.id, stepTerminal) }
            agentTaskRepository.updateTask(
                existing.copy(
                    status = status,
                    updatedAt = System.currentTimeMillis(),
                    currentStepId = null,
                    finalSummary = summary,
                    blockingReason = blockingReason
                )
            )
        }

        /** 执行只读工具（read/list/search/web_search 等）并回填结果。callingStep 已由调用方发出。 */
        suspend fun runReadOnlyTool(
            call: ToolCall,
            executor: suspend (ToolCall) -> Result<String>
        ) {
            executor(call).fold(
                onSuccess = { content ->
                    val truncated = truncateToolResult(content, call.name)
                    workingMessages.add(
                        ChatMessage(role = "tool", content = truncated, toolCallId = call.id, toolName = call.name)
                    )
                    val done = AgentStep.ToolDone(call.name, true, summarize(truncated))
                    agentSteps.add(done)
                    emit(AgentMessageState.ToolStep(done))
                },
                onFailure = { e ->
                    // web_search / search_knowledge 的失败原文最常带着 `?api_key=…` 的完整 URL，
                    // 这里是三个 failureDetail 调用点里风险最高的一个。
                    val msg = failureDetail(e, "工具执行失败")
                    workingMessages.add(
                        ChatMessage(role = "tool", content = "ERROR: $msg", toolCallId = call.id, toolName = call.name)
                    )
                    val done = AgentStep.ToolDone(call.name, false, summarize(msg))
                    agentSteps.add(done)
                    emit(AgentMessageState.ToolStep(done))
                }
            )
        }

        try {
            for (turn in 1..MAX_TURNS) {
                // `full` 是「本轮」的正文，每轮开头清空。清空前先记下上一轮已经显示给用户的那份：
                // 出错分支要靠它判断这条消息是不是真的什么都没产生过（见下面的 StreamEvent.Error）。
                if (full.isNotBlank()) shownSoFar = full.toString()
                full.clear()
                var pendingCalls: List<ToolCall>? = null
                var errored = false
                // 本轮正文是否被输出上限砍断（见 StreamEvent.Done.truncated）。
                // 一轮一份：上一轮截断过不代表这一轮也截断。
                var truncated = false

                adapter.sendChatStream(
                    workingMessages,
                    AiRequestConfig(
                        model = config.modelName,
                        temperature = config.temperature,
                        maxTokens = config.maxTokens,
                        systemPrompt = systemPrompt
                    ),
                    tools = tools
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            full.append(event.text)
                            conversationRepository.updateMessage(
                                assistant.copy(content = full.toString(), isStreaming = true)
                            )
                            emit(AgentMessageState.Streaming(event.text))
                        }
                        is StreamEvent.ToolCallComplete -> pendingCalls = (pendingCalls ?: emptyList()) + event.calls
                        is StreamEvent.ToolCallDelta -> Unit
                        // 截断当场就告诉用户：这一位再往下会挡掉隐式整篇覆盖（见收敛分支），
                        // 但挡掉之后界面上就只剩一段没头没尾的正文，不给提示的话用户只会
                        // 觉得「AI 答了一半还不肯动手」。放在这里而不是收敛分支：
                        // 带工具调用的轮次同样会被砍断（那会让 arguments 的 JSON 不完整），
                        // 每一轮至多提示一次。
                        is StreamEvent.Done -> if (event.truncated) {
                            truncated = true
                            emit(AgentMessageState.Notice(UiMessage.Res(R.string.ai_notice_response_truncated)))
                        }
                        is StreamEvent.Error -> {
                            errored = true
                            // 删这条消息的前提是「它从头到尾什么都没给用户看过」。不能只看 `full`：
                            // 它每轮开头都被清空，第二轮一开始就断网时 `full` 是空的，而第一轮的正文
                            // 和整条工具时间线都还挂在这条消息上——按 `full` 判断会把它们一起删掉，
                            // 用户眼前那条消息凭空消失。
                            val shown = full.toString().ifBlank { shownSoFar }
                            if (shown.isBlank() && agentSteps.isEmpty()) {
                                conversationRepository.deleteMessage(assistant.id)
                            } else {
                                // steps 必须一起写回：其余几处收尾都带上了，只有这里漏了，
                                // 于是一出错用户刚看着走完的步骤就从气泡里消失。
                                conversationRepository.updateMessage(
                                    assistant.copy(
                                        content = shown,
                                        isStreaming = false,
                                        steps = agentSteps.toList()
                                    )
                                )
                            }
                            conversationRepository.observeConversation(conversationId).first()?.let {
                                conversationRepository.updateConversation(it.copy(status = ConversationStatus.IDLE))
                            }
                            taskFinalized = true  // 错误分支不再终态化任务（保持其最后状态），避免 finally 误改
                            emit(AgentMessageState.Error(event.message))
                        }
                    }
                }
                if (errored) return@flow

                val calls = pendingCalls
                if (calls.isNullOrEmpty()) {
                    // ① 收敛：纯文本最终答复（兼容 [[ACTION]] 文本协议 / 弱模型隐式整篇识别）
                    val text = full.toString()
                    val action = parseAgentAction(text, currentDocumentId)
                        ?: extractImplicitWriteAction(
                            text, effectiveUserMessage, currentDocumentId, currentDocumentName,
                            truncated = truncated
                        )

                    if (text.isBlank() && action == null) {
                        val notice = "AI 本轮没有返回任何内容。可能原因：所选模型不支持函数调用、" +
                            "max tokens 过小、或为推理模型（输出在 reasoning 字段）。" +
                            "请重试，或在设置里更换模型 / 调大 max tokens。"
                        conversationRepository.updateMessage(
                            assistant.copy(content = notice, isStreaming = false, steps = agentSteps.toList())
                        )
                        finalizeTask(
                            AgentTaskStatus.BLOCKED,
                            AgentStatusCode.SUMMARY_EMPTY_RESPONSE.encode(),
                            AgentStatusCode.BLOCKED_EMPTY_RESPONSE.encode()
                        )
                        markCompleted(conversationId)
                        // notice 已作为助手消息正文写进 Room，通知这一路只能原样透出同一段字符串
                        emit(AgentMessageState.Notice(UiMessage.Raw(notice)))
                        emit(AgentMessageState.Completed(notice))
                        return@flow
                    }

                    val chatText = if (action != null) {
                        conversationalPreamble(text).ifBlank { action.description.ifBlank { "已生成文档内容，请在下方预览确认。" } }
                    } else {
                        text
                    }
                    conversationRepository.updateMessage(
                        assistant.copy(content = chatText, isStreaming = false, agentAction = action, steps = agentSteps.toList())
                    )
                    // 摘要优先用模型自己的话（那是自由文本，原样落库、原样显示）；
                    // 兜底才用稳定码，否则英文环境下这条摘要永远是中文。
                    finalizeTask(
                        AgentTaskStatus.COMPLETED,
                        action?.description
                            ?: text.take(80).ifBlank { AgentStatusCode.SUMMARY_COMPLETED.encode() }
                    )
                    markCompleted(conversationId)
                    if (action != null) emit(AgentMessageState.ActionProposed(assistant.id, action))
                    else if (text.contains("[[ACTION]]"))
                        emit(AgentMessageState.Notice(UiMessage.Res(R.string.agent_notice_bad_action_block)))
                    emit(AgentMessageState.Completed(text))
                    return@flow
                }

                // ② doom-loop：完全相同的一组工具调用重复出现 → 停
                val signature = calls.joinToString("|") { "${it.name}(${it.arguments})" }
                if (signature == lastToolSignature) {
                    val text = full.toString().ifBlank { "（已停止：检测到重复的工具调用）" }
                    conversationRepository.updateMessage(
                        assistant.copy(content = text, isStreaming = false, steps = agentSteps.toList())
                    )
                    finalizeTask(
                        AgentTaskStatus.BLOCKED,
                        text,
                        AgentStatusCode.BLOCKED_DUPLICATE_TOOL_CALL.encode()
                    )
                    markCompleted(conversationId)
                    emit(AgentMessageState.Completed(text))
                    return@flow
                }
                lastToolSignature = signature

                // 把本轮 assistant 工具调用消息加入上下文（role=assistant + tool_calls）
                workingMessages.add(
                    ChatMessage(role = "assistant", content = full.toString().ifBlank { null }, toolCalls = calls)
                )

                // ③ 执行工具：读/计划工具内联回填续跑；首个写提议成功即结束本轮等待审批
                var proposal: AgentAction? = null
                for (call in calls) {
                    val callingStep = AgentStep.ToolCalling(call.name, summarize(call.arguments))
                    agentSteps.add(callingStep)
                    emit(AgentMessageState.ToolStep(callingStep))

                    when (call.name) {
                        "create_document", "edit_document" -> {
                            buildWriteProposal(call, currentDocumentId).fold(
                                onSuccess = { act ->
                                    proposal = act
                                    val done = AgentStep.ToolDone(call.name, true, "write proposal ready")
                                    agentSteps.add(done)
                                    emit(AgentMessageState.ToolStep(done))
                                },
                                onFailure = { e ->
                                    val msg = failureDetail(e, "写操作失败")
                                    workingMessages.add(
                                        ChatMessage(
                                            role = "tool",
                                            content = "ERROR: $msg",
                                            toolCallId = call.id,
                                            toolName = call.name
                                        )
                                    )
                                    val done = AgentStep.ToolDone(call.name, false, summarize(msg))
                                    agentSteps.add(done)
                                    emit(AgentMessageState.ToolStep(done))
                                }
                            )
                        }
                        "update_plan" -> {
                            val result = runCatching { applyPlan(conversationId, effectiveUserMessage, call) }
                            result.onSuccess { taskId = it }
                            val summary = result.fold(
                                { "已更新计划" },
                                { "计划更新失败：${failureDetail(it, "未知原因")}" }
                            )
                            workingMessages.add(
                                ChatMessage(role = "tool", content = summary, toolCallId = call.id, toolName = call.name)
                            )
                            val done = AgentStep.ToolDone(call.name, result.isSuccess, summary)
                            agentSteps.add(done)
                            emit(AgentMessageState.ToolStep(done))
                        }
                        "web_search" -> runReadOnlyTool(call) { webSearchService.search(it) }
                        "save_memory", "search_memory", "list_memories" -> runReadOnlyTool(call) { memoryService.execute(it) }
                        "search_knowledge", "knowledge_stats" -> runReadOnlyTool(call) { ragPipeline.execute(it) }
                        else -> {
                            // 只读工具：read_document / list_documents / search_in_project
                            runReadOnlyTool(call) { executeDocumentTool(it) }
                        }
                    }
                    if (proposal != null) break
                }

                val act = proposal
                if (act != null) {
                    val text = full.toString()
                    val chatText = conversationalPreamble(text)
                        .ifBlank { act.description.ifBlank { "已生成文档改动，请在下方预览确认。" } }
                    conversationRepository.updateMessage(
                        assistant.copy(content = chatText, isStreaming = false, agentAction = act, steps = agentSteps.toList())
                    )
                    finalizeTask(
                        AgentTaskStatus.COMPLETED,
                        act.description.ifBlank { AgentStatusCode.SUMMARY_ACTION_PROPOSED.encode() }
                    )
                    markCompleted(conversationId)
                    emit(AgentMessageState.ActionProposed(assistant.id, act))
                    emit(AgentMessageState.Completed(text))
                    return@flow
                }
                // 否则带着工具结果进入下一轮
            }

            // ④ 达到最大步数仍未收敛
            val text = full.toString().ifBlank { "（已达最大步数 $MAX_TURNS，已停止）" }
            conversationRepository.updateMessage(
                assistant.copy(content = text, isStreaming = false, steps = agentSteps.toList())
            )
            finalizeTask(AgentTaskStatus.FAILED, text, AgentStatusCode.BLOCKED_MAX_STEPS.encode(MAX_TURNS))
            markCompleted(conversationId)
            emit(AgentMessageState.Completed(text))
        } finally {
            // 兜底：取消/异常导致未正常终态化时，收束任务与会话，防中间态残留。
            if (!taskFinalized) {
                withContext(NonCancellable) {
                    taskId?.let { id ->
                        agentTaskRepository.getTaskByConversationId(conversationId)?.let { agg ->
                            val t = agg.task
                            if (t.id == id && (t.status == AgentTaskStatus.EXECUTING ||
                                    t.status == AgentTaskStatus.PLANNING || t.status == AgentTaskStatus.REPLANNING)) {
                                // 先退回还挂在 RUNNING 的步骤，再改判任务——与
                                // AgentTaskDao.reconcileInterrupted 同序，理由见
                                // AgentChatViewModel.stop()：任务一旦落到终态，冷启动那条复位
                                // 路径就靠 liveStatuses 选不到它了，RUNNING 步骤会永久停在
                                // 活动态，时间线继续画脉冲，而状态胶囊已经是「执行中断」。
                                agg.steps
                                    .filter { it.status == AgentTaskStepStatus.RUNNING }
                                    .forEach {
                                        agentTaskRepository.markStepStatus(
                                            it.id, AgentTaskStepStatus.PENDING
                                        )
                                    }
                                agentTaskRepository.updateTask(
                                    t.copy(
                                        status = AgentTaskStatus.FAILED,
                                        updatedAt = System.currentTimeMillis(),
                                        currentStepId = null,
                                        blockingReason = AgentStatusCode.BLOCKED_INTERRUPTED.encode()
                                    )
                                )
                            }
                        }
                    }
                    conversationRepository.observeConversation(conversationId).first()?.let {
                        if (it.status == ConversationStatus.WORKING) {
                            conversationRepository.updateConversation(it.copy(status = ConversationStatus.IDLE))
                        }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun markCompleted(conversationId: String) {
        conversationRepository.observeConversation(conversationId).first()?.let {
            conversationRepository.updateConversation(
                it.copy(updatedAt = System.currentTimeMillis(), status = ConversationStatus.COMPLETED)
            )
        }
    }

    /** 应用模型的 update_plan：懒创建任务、整体替换步骤、推导任务状态。返回 taskId。 */
    private suspend fun applyPlan(conversationId: String, goal: String, call: ToolCall): String {
        val payload = agentRuntimeJson.decodeFromString(PlanPayload.serializer(), call.arguments)
        val now = System.currentTimeMillis()
        val existing = agentTaskRepository.getTaskByConversationId(conversationId)?.task
        val taskIdValue = existing?.id ?: UUID.randomUUID().toString()
        val steps = payload.steps.mapIndexed { index, s ->
            AgentTaskStep(
                id = UUID.randomUUID().toString(),
                taskId = taskIdValue,
                title = s.title.trim().ifBlank { "步骤 ${index + 1}" },
                description = "",
                status = mapPlanStatus(s.status),
                order = index,
                completionCriteria = s.title.trim()
            )
        }
        val derived = deriveTaskStatus(steps)
        if (existing == null) {
            agentTaskRepository.createTask(
                AgentTask(
                    id = taskIdValue,
                    conversationId = conversationId,
                    goal = goal.take(120).ifBlank { "Agent 任务" },
                    status = derived,
                    createdAt = now,
                    updatedAt = now
                ),
                steps
            )
        } else {
            agentTaskRepository.replaceSteps(taskIdValue, steps)
            // replaceSteps 重铸了全部步骤 UUID，existing.currentStepId 若被保留就指向已删除的
            // 步骤——一个悬空指针。今天没有任何代码写非 null 的 currentStepId，UI 侧
            // toUiStateOrNull 的回退链也恰好把它遮蔽掉；但显式清空才不靠「碰巧没人写」
            // 与「碰巧有回退」两条侥幸成立。
            agentTaskRepository.updateTask(
                existing.copy(status = derived, updatedAt = now, currentStepId = null)
            )
        }
        return taskIdValue
    }
}

/**
 * 执行 Agent 操作。权限：仅创建 / 编辑，需用户已批准。
 * 复用 [CreateDocumentUseCase] / [SaveDocumentUseCase] 以保持字数统计、时间戳逻辑一致。
 */
class ExecuteAgentActionUseCase @Inject constructor(
    private val createDocumentUseCase: CreateDocumentUseCase,
    private val saveDocumentUseCase: SaveDocumentUseCase,
    private val loadDocumentUseCase: LoadDocumentUseCase,
    private val conversationRepository: ConversationRepository,
    private val documentVersionRepository: DocumentVersionRepository
) {
    /** @return 受影响文档的 id（CREATE 为新文档，EDIT 为目标文档） */
    suspend operator fun invoke(
        message: Message,
        action: AgentAction,
        finalContent: String? = null
    ): Result<String> = runCatching {
        val documentId = when (action.type) {
            AgentActionType.CREATE_DOCUMENT -> createFromAction(action, action.description)
            AgentActionType.EDIT_DOCUMENT -> {
                val targetId = action.targetDocumentId
                if (targetId == null) {
                    // 只可能是降级修复之前落库、冷启动后重新水合出来的旧提议（parseAgentAction 现在
                    // 不会再产出无 target 的 EDIT）。content 同样是整篇正文，落成新文档远好于抛异常：
                    // 用户批准过的内容不丢，也不会得到一句笼统的「操作失败」然后永远卡在那张卡片上。
                    createFromAction(action, implicitDocumentTitle(action.content))
                } else {
                    val doc = loadDocumentUseCase(targetId).getOrThrow()
                    requireUnchangedBase(action, doc.content)
                    // 覆盖前先把改动前内容入历史，保证可回退到 Agent 修改之前
                    snapshotVersion(targetId, doc.content, doc.wordCount)
                    // finalContent：用户在 diff 闸门逐 hunk 审阅后合成的内容；为空则回退整篇覆盖
                    saveDocumentUseCase(doc.copy(content = finalContent ?: action.content)).getOrThrow()
                    snapshotVersion(targetId)  // 改动后内容入历史，与手动保存路径一致
                    targetId
                }
            }
        }
        conversationRepository.updateMessage(
            message.copy(agentAction = action.copy(status = AgentActionStatus.EXECUTED))
        )
        documentId
    }

    /** 建文档 → 写入正文 → 落首个历史版本。CREATE 与「无 target 的 EDIT」降级共用。 */
    private suspend fun createFromAction(action: AgentAction, rawTitle: String): String {
        val doc = createDocumentUseCase(rawTitle.take(50).ifBlank { "AI 生成文档" }).getOrThrow()
        saveDocumentUseCase(doc.copy(content = action.content)).getOrThrow()
        snapshotVersion(doc.id)  // 新建文档：AI 生成内容落首个历史版本
        return doc.id
    }

    /**
     * 提议的基线还在不在。
     *
     * [AgentAction.content] 是「提议生成那一刻的原文 + 模型的编辑」合成的新全文，批准时整篇
     * 覆盖目标文档。提议随消息落库，批准可以晚到下一次冷启动之后——这期间用户在编辑器里改了
     * 几行、WebDAV 拉回了远端版本，覆盖就把那些改动无声吃掉了（历史版本能翻回来，但用户不会
     * 知道发生过）。所以写入前重新读一遍文档，指纹不一致就拒绝，让用户让 AI 重新生成。
     *
     * [AgentAction.baseContentHash] 为 null 的是本字段落地之前存下的老提议，无基线可比，
     * 按从前的行为放行。
     */
    private fun requireUnchangedBase(action: AgentAction, currentContent: String) {
        val expected = action.baseContentHash ?: return
        if (ContentHash.of(currentContent) == expected) return
        throw EditException(
            uiMessage = UiMessage.Res(R.string.agent_edit_base_changed),
            modelHint = "目标文档在本次提议生成后已被改动，提议已过期。"
        )
    }

    /**
     * 落一条历史版本快照（去重）。与编辑器手动保存路径行为一致，best-effort：
     * 历史记录失败不影响文档已保存的事实。
     * 省略 [content]/[wordCount] 时重新读取已落库文档——[SaveDocumentUseCase] 会重算字数，
     * 直接读库才能拿到准确的字数与最终内容。
     */
    private suspend fun snapshotVersion(documentId: String, content: String? = null, wordCount: Int? = null) {
        runCatching {
            val snapshotContent: String
            val snapshotWordCount: Int
            if (content != null && wordCount != null) {
                snapshotContent = content
                snapshotWordCount = wordCount
            } else {
                val saved = loadDocumentUseCase(documentId).getOrThrow()
                snapshotContent = saved.content
                snapshotWordCount = saved.wordCount
            }
            documentVersionRepository.snapshotIfChanged(documentId, snapshotContent, snapshotWordCount)
        }
    }
}

private const val MAX_TURNS = 10

/** 编辑场景把当前文档全文塞进系统提示的字符上限；超过则退回大纲 + read_document。 */
private const val FULL_DOC_CONTEXT_BUDGET = 6000

@Serializable
private data class PlanPayload(val steps: List<PlanStepPayload> = emptyList())

@Serializable
private data class PlanStepPayload(val title: String = "", val status: String = "pending")

private fun mapPlanStatus(raw: String): AgentTaskStepStatus = when (raw.trim().lowercase()) {
    "in_progress", "running", "doing" -> AgentTaskStepStatus.RUNNING
    "done", "completed", "complete" -> AgentTaskStepStatus.DONE
    "blocked" -> AgentTaskStepStatus.BLOCKED
    "failed" -> AgentTaskStepStatus.FAILED
    "skipped" -> AgentTaskStepStatus.SKIPPED
    else -> AgentTaskStepStatus.PENDING
}

private fun deriveTaskStatus(steps: List<AgentTaskStep>): AgentTaskStatus = when {
    steps.any { it.status == AgentTaskStepStatus.BLOCKED || it.status == AgentTaskStepStatus.FAILED } -> AgentTaskStatus.BLOCKED
    steps.isNotEmpty() && steps.all { it.status == AgentTaskStepStatus.DONE || it.status == AgentTaskStepStatus.SKIPPED } -> AgentTaskStatus.COMPLETED
    else -> AgentTaskStatus.EXECUTING
}

internal fun buildDocumentCreationFollowUpMessage(
    priorMessages: List<Message>,
    userMessage: String
): String {
    val stableMessages = priorMessages
        .filter { !it.isStreaming && it.content.isNotBlank() }
    val lastMessage = stableMessages.lastOrNull()
    if (lastMessage?.role != MessageRole.ASSISTANT || !isDocumentCreationClarification(lastMessage.content)) {
        return userMessage
    }

    val originalIndex = stableMessages
        .dropLast(1)
        .indexOfLast { message ->
            message.role == MessageRole.USER && isDocumentCreationRequest(message.content)
        }
    if (originalIndex < 0) return userMessage

    val originalRequest = stableMessages[originalIndex].content
    val priorFollowUps = stableMessages
        .drop(originalIndex + 1)
        .dropLast(1)
        .filter { it.role == MessageRole.USER }
        .map { it.content }

    val followUps = priorFollowUps + userMessage
    return "原始需求：$originalRequest\n补充信息：${followUps.joinToString("\n")}"
}

private fun isDocumentCreationClarification(content: String): Boolean =
    content.contains("在创建之前我还需要确认") &&
        content.contains("新建文档")

private fun isDocumentCreationRequest(userMessage: String): Boolean {
    val normalized = userMessage.lowercase()
    return listOf("创建", "新建", "生成", "写一份", "写一篇", "起草", "撰写", "create", "draft", "write")
        .any { normalized.contains(it) } &&
        listOf("文档", "md", "markdown", "笔记", "文章", "document", "note")
            .any { normalized.contains(it) }
}

/**
 * 工具失败详情：这一行字同时走两个出口——回喂给模型的 `role=tool` 消息，和界面上的步骤卡片。
 *
 * 所以必须过 [ErrorHandler.safeDetail]：异常原文里可能带着 `?key=AIza…` 这样的凭证、
 * `/data/user/0/com.yumark.app/…` 这样的内部路径，直接拼进去等于两边同时泄一次。
 * 原文为空时退回给定的中文兜底，而不是让用户在步骤卡片上看到一个裸类名。
 */
private fun failureDetail(e: Throwable, fallback: String): String =
    if (e.message.isNullOrBlank()) fallback else ErrorHandler.safeDetail(e)

/** 单行摘要，用于步骤展示。 */
private fun summarize(s: String, max: Int = 60): String {
    val oneLine = s.replace("\n", " ").trim()
    return if (oneLine.length <= max) oneLine else oneLine.take(max) + "…"
}

/** 工具结果回填前截断，避免单步爆窗。按工具名取各自预算。 */
private fun truncateToolResult(s: String, toolName: String): String {
    val budget = com.yumark.app.core.util.ContextBudget.toolResultBudget(toolName)
    return if (s.length <= budget) s else s.take(budget) + "\n…（结果过长已截断）"
}

/** 构建 Agent 系统提示：工具优先、外科式编辑、模型驱动 todo，注入当前文档上下文。
 *  工具列表与说明按本轮实际下发的 [tools] 动态生成，避免提示描述与 tools 参数不一致。 */
internal fun buildAgentSystemPrompt(
    documentName: String?,
    documentContent: String?,
    tools: List<AiTool>
): String {
    val docContext = if (documentName != null) {
        val content = documentContent.orEmpty()
        val detail = if (content.isNotBlank() && content.length <= FULL_DOC_CONTEXT_BUDGET) {
            "完整内容如下（编辑时基于此给出可唯一定位的 old_string）：\n$content"
        } else {
            val outline = content.takeIf { it.isNotBlank() }
                ?.let { com.yumark.app.core.util.documentOutline(it) } ?: "(空)"
            "$outline\n（文档较大，仅给出大纲；需要某段确切原文时用 read_document 获取）"
        }
        "当前打开的文档：《$documentName》\n$detail"
    } else {
        "当前没有打开的文档。"
    }

    val toolNames = tools.map { it.name }.toSet()
    val hasWrite = "create_document" in toolNames || "edit_document" in toolNames
    val hasEdit = "edit_document" in toolNames
    val hasPlan = "update_plan" in toolNames

    val toolList = if (tools.isEmpty()) {
        "（本轮无可用工具，请直接用自身知识作答。）"
    } else {
        tools.joinToString("\n") { "- ${it.name}：${it.description}" }
    }

    val editBlock = if (hasEdit) {
        """
        # 外科式编辑与整篇重写（重要）
        用户要求修改文档时，必须让改动经过审批门（diff 预览）。**绝不能只在回复里写出改后内容就宣称"已完成/已修改"——那样文档不会被改动，用户也看不到审批。** 在产生审批门之前，不要说"已完成修改"。
        按改动范围选一种方式：
        1. **局部修改** → 调用 edit_document，提交 old_string→new_string（外科式编辑）：
           - old_string 与文档**完全一致**且**唯一定位**（带上下文）；不确定就先 read_document。
           - new_string 是改好后的真实正文，不要写"修改说明/改进要点"。
           - 命中不唯一时补上下文或设 replace_all=true；未命中会收到错误说明，据此修正后重试。
        2. **整篇润色 / 重写 / 改写全文** → 在 ```markdown 围栏内输出**完整的改后文档正文**（围栏外只放一句说明），它会自动转为整篇 diff 审批（等价于整体替换）。**不要用 edit_document 做整篇重写**——old_string 过长极易失配、且把整篇当一处替换是错误用法。
        - 文档较大、缺确切原文时先 read_document 获取目标片段，再 edit_document。
        """.trimIndent()
    } else ""

    val planBlock = if (hasPlan) {
        """
        # 计划（update_plan）
        任务需要多步时，先用 update_plan 列出步骤（pending/in_progress/done/blocked），
        并在推进时更新各步状态；单步小任务可不调用。
        """.trimIndent()
    } else ""

    val approvalBlock = if (hasWrite) {
        """
        # 审批
        任何创建/编辑都会先以预览或逐行 diff 呈现给用户，由用户确认后才真正写入。放心提出改动；但务必保证内容完整、准确。

        # 仅当端点不支持函数调用时
        （你确实无法发起 edit_document / create_document 工具调用）才改为：把**完整文档正文放进一个 ```markdown 围栏代码块**（围栏外只放一句说明），用户仍会收到审批预览，并非直接生效。围栏内必须是文档真实正文，**不能是"改了哪些地方"的说明或要点清单**。能调用工具时不要走这条路径——局部修改走 edit_document，整篇重写走上文的围栏输出。
        """.trimIndent()
    } else ""

    return buildString {
        append("""
            # 角色
            你是 YuMark 的文档助手，帮用户检索、整理、创作与改写 Markdown 笔记。

            # 工作方式（工具优先）
            你可以调用工具来完成任务，并基于工具结果继续推理，直到给出最终答复。
            本轮可用工具：
        """.trimIndent())
        append("\n").append(toolList)
        append("\n普通问答 / 解释：直接用文字回答，不要调用写工具，也不要整篇输出文档。")
        if (editBlock.isNotBlank()) { append("\n\n").append(editBlock) }
        if (planBlock.isNotBlank()) { append("\n\n").append(planBlock) }
        if (approvalBlock.isNotBlank()) { append("\n\n").append(approvalBlock) }
        append("\n\n# 当前上下文\n").append(docContext)
    }.trimIndent()
}

/** 从 AI 回复中解析 [[ACTION]] 操作意图（降级文本协议）。返回 null 表示无操作。 */
internal fun parseAgentAction(text: String, currentDocumentId: String?): AgentAction? {
    val start = text.indexOf("[[ACTION]]")
    if (start < 0) return null
    val end = text.indexOf("[[/ACTION]]", start)
    if (end < 0) return null

    val block = text.substring(start + "[[ACTION]]".length, end)
    val contentMarker = block.indexOf("[[CONTENT]]")
    if (contentMarker < 0) return null

    val header = block.substring(0, contentMarker)
    val content = block.substring(contentMarker + "[[CONTENT]]".length).trim()

    val typeStr = Regex("type:\\s*(CREATE_DOCUMENT|EDIT_DOCUMENT)")
        .find(header)?.groupValues?.get(1) ?: return null
    val description = Regex("description:\\s*(.+)")
        .find(header)?.groupValues?.get(1)?.trim().orEmpty()
    if (content.isBlank()) return null

    val parsedType = AgentActionType.valueOf(typeStr)
    // 模型声称要编辑、但此刻没有打开任何文档：EDIT_DOCUMENT 取不到 targetDocumentId，卡片仍会渲染出
    // 「批准」按钮（UI 只在 targetDocumentId != null 时才走 diff 闸门），点下去在 ExecuteAgentActionUseCase
    // 里撞上 error(...)，而 onFailureReport 刻意挡掉原始 message，用户只看到一句笼统的「操作失败」，
    // 重试多少次都一样。两种协议下 [[CONTENT]] 都是整篇正文，所以此时降级成 CREATE_DOCUMENT 才是模型
    // 意图的正确落地——与 extractImplicitWriteAction 一致：那条路径的 editIntent 定义本身就要求
    // currentDocumentId != null，无文档可编辑时同样退回 CREATE_DOCUMENT。
    val degradeToCreate = parsedType == AgentActionType.EDIT_DOCUMENT && currentDocumentId == null
    val type = if (degradeToCreate) AgentActionType.CREATE_DOCUMENT else parsedType
    return AgentAction(
        type = type,
        // 降级后 description 会被 ExecuteAgentActionUseCase 当作新文档标题，而编辑指令写出来的
        // description（"修改这篇文档的结构"之类）当标题很怪，改用正文首个标题行。
        description = if (degradeToCreate) implicitDocumentTitle(content) else description,
        targetDocumentId = if (type == AgentActionType.EDIT_DOCUMENT) currentDocumentId else null,
        content = content
    )
}

/**
 * 降级识别（弱端点）：模型未发写工具、未按 [[ACTION]] 协议，而是直接把完整 Markdown 正文
 * 写在回复里时，把正文识别为待批准 [AgentAction]（复用 diff/审批门，不绕过审批）。
 *
 * @param truncated 本轮正文是否被输出上限砍断（[com.yumark.app.domain.model.StreamEvent.Done]）。
 *        为 true 时不再产出编辑提案，理由见函数体内的注释。
 */
internal fun extractImplicitWriteAction(
    text: String,
    userMessage: String,
    currentDocumentId: String?,
    currentDocumentName: String?,
    truncated: Boolean = false
): AgentAction? {
    if (text.isBlank()) return null
    if (text.contains("[[ACTION]]")) return null

    val createIntent = isDocumentCreationRequest(userMessage)
    // 已有打开文档时，编辑意图无需再强求出现"文档/这篇"等名词——上下文已明确指向当前文档。
    val editIntent = currentDocumentId != null && isDocumentEditRequest(userMessage, requireDocNoun = false)
    if (!createIntent && !editIntent) return null

    // 被砍断的正文绝不拿去整篇覆盖既有文档。半截正文照样能通过下面「像不像文档」的判定，
    // 而 EDIT_DOCUMENT 在 ExecuteAgentActionUseCase 里是整篇替换；隐式提案又没有
    // baseContentHash，requireUnchangedBase 拦不住它。用户在 diff 卡片上点一下批准
    // （每个 hunk 默认都是勾选状态）就把文档后半部分删掉了——历史版本能救回来，
    // 可没人知道自己需要去救。
    //
    // 只挡编辑、不挡新建：新建是另起一篇，半截内容顶多是废稿，删掉就行，毁不到已有数据。
    // 这里也刻意不回落到 CREATE——用户要的是「改这篇」，凭空多出一篇半截新文档同样是意外。
    if (truncated && editIntent) return null

    // 剥离对话前言、解开 ```markdown 围栏，得到纯文档正文
    val body = extractDocumentBody(text)
    if (body.isBlank()) return null

    val looksLikeDocument = body.length >= IMPLICIT_DOC_MIN_CHARS || hasDocumentStructure(body)
    if (!looksLikeDocument) return null

    // editIntent 的定义里已含 currentDocumentId != null，K2 会据此把它智能转换为非空，无需重复判空。
    return if (editIntent) {
        AgentAction(
            type = AgentActionType.EDIT_DOCUMENT,
            description = "编辑文档${currentDocumentName?.let { "：$it" }.orEmpty()}",
            targetDocumentId = currentDocumentId,
            content = body
        )
    } else {
        val title = implicitDocumentTitle(body)
        AgentAction(
            type = AgentActionType.CREATE_DOCUMENT,
            description = title,
            content = body
        )
    }
}

/**
 * 从模型回复中抽取"纯文档正文"，剥离前言/围栏：
 * 1) 优先取 ```markdown / ```md / ``` 围栏内部；2) 否则从首个标题起丢弃前言；3) 都不命中原样返回。
 *
 * 围栏分支注意：模型常把整篇文档用一对围栏包裹，而正文内部又含 ``` 代码块。
 * 若用非贪婪正则 `([\s\S]*?)````，会在内部首个 ``` 处误闭合，**截断其后全部正文**。
 * 故改为：取首个围栏开启行之后到文本末尾，再剥掉末尾最后一个独占一行的 ``` 闭合标记。
 */
internal fun extractDocumentBody(text: String): String {
    val lines = text.lines()
    val openIdx = lines.indexOfFirst {
        it.trim().equals("```", ignoreCase = true) ||
            it.trim().equals("```markdown", ignoreCase = true) ||
            it.trim().equals("```md", ignoreCase = true)
    }
    if (openIdx >= 0) {
        val inner = lines.subList(openIdx + 1, lines.size).toMutableList()
        // 从末尾跳过空行，首个非空行若为 ``` 视为外层闭合，剥掉。
        var i = inner.lastIndex
        while (i >= 0 && inner[i].isBlank()) i--
        if (i >= 0 && inner[i].trim() == "```") inner.removeAt(i)
        val body = inner.joinToString("\n").trim()
        if (body.isNotBlank()) return body
    }
    val headingIdx = lines.indexOfFirst { it.trimStart().startsWith("#") }
    if (headingIdx > 0) return lines.drop(headingIdx).joinToString("\n").trim()
    return text.trim()
}

/** 取文档正文之前的对话性前言（用于聊天气泡显示）。无明显分界返回空串。 */
internal fun conversationalPreamble(text: String): String {
    val fenceIdx = text.indexOf("```")
    if (fenceIdx > 0) return text.substring(0, fenceIdx).trim()
    val actionIdx = text.indexOf("[[ACTION]]")
    if (actionIdx > 0) return text.substring(0, actionIdx).trim()
    val lines = text.lines()
    val headingIdx = lines.indexOfFirst { it.trimStart().startsWith("#") }
    if (headingIdx > 0) return lines.take(headingIdx).joinToString("\n").trim()
    return ""
}

/** 判断用户本轮是否为"改写/编辑既有文档"意图（需配合 currentDocumentId 使用）。
 *  [requireDocNoun] 为 false 时（已有打开文档）只看动词，不强求"文档/内容"等名词。*/
private fun isDocumentEditRequest(userMessage: String, requireDocNoun: Boolean = true): Boolean {
    val normalized = userMessage.lowercase()
    val editVerb = listOf(
        "改写", "修改", "重写", "补充", "补全", "润色", "续写", "修订", "优化", "完善",
        "增加", "添加", "扩充", "扩写", "扩展", "加入", "加上", "丰富", "整理", "更新",
        "调整", "改进", "精简", "重构", "翻译", "纠错", "校对",
        "rewrite", "revise", "polish", "edit", "improve", "expand", "append", "update",
        "add", "shorten", "condense", "reformat", "format", "fix", "translate"
    ).any { normalized.contains(it) }
    val docNoun = listOf(
        "文档", "笔记", "这篇", "当前", "本文", "内容", "段落", "章节", "全文",
        "document", "note", "this"
    ).any { normalized.contains(it) }
    val isQuestionOrExplanation = listOf(
        "解释", "什么是", "为什么", "介绍一下", "讲解一下", "是什么意思"
    ).any { normalized.contains(it) }
    return editVerb && (!requireDocNoun || docNoun) && !isQuestionOrExplanation
}

/** 文本是否含 Markdown 文档结构特征（标题/列表/代码块/表格）。 */
private fun hasDocumentStructure(text: String): Boolean {
    return text.lineSequence().any { line ->
        val t = line.trimStart()
        t.startsWith("#") ||
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") ||
            (t.firstOrNull()?.isDigit() == true && t.contains(". ")) ||
            t.startsWith("```") ||
            t.startsWith("|")
    }
}

/** 从文本推断文档标题：首行 Markdown 标题去符号；否则首行非空文本；否则默认。 */
private fun implicitDocumentTitle(text: String): String {
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return "AI 生成文档"
    val heading = Regex("^#{1,6}\\s+(.+)").find(firstLine)?.groupValues?.get(1)?.trim()
    return (heading ?: firstLine).take(50).ifBlank { "AI 生成文档" }
}

/** 隐式识别为文档所需的最小文本长度。短于此且无结构特征 → 视为普通回复，不提取。 */
private const val IMPLICIT_DOC_MIN_CHARS = 200
