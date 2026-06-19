package com.yumark.app.domain.usecase.ai.agent

import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.AgentStep
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.domain.model.AiRequestConfig
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
    data class Notice(val message: String) : AgentMessageState()
    data class Completed(val fullText: String) : AgentMessageState()
    data class Error(val message: String) : AgentMessageState()
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
    private val buildWriteProposal: BuildWriteProposalUseCase
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
            emit(AgentMessageState.Error("请先在设置中配置 API Key 和模型"))
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
        val systemPrompt = buildAgentSystemPrompt(currentDocumentName, currentDocumentContent)
        val tools = DocumentContextTools.getAllTools()
        val full = StringBuilder()
        val agentSteps = ArrayList<AgentStep>()
        var lastToolSignature: String? = null

        // 复用本会话既有任务（若有）；否则任务由 update_plan 懒创建。
        var taskId: String? = agentTaskRepository.getTaskByConversationId(conversationId)?.task?.id
        var taskFinalized = false

        suspend fun finalizeTask(status: AgentTaskStatus, summary: String, blockingReason: String? = null) {
            taskFinalized = true
            val id = taskId ?: return
            val existing = agentTaskRepository.getTaskByConversationId(conversationId)?.task ?: return
            if (existing.id != id) return
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

        try {
            for (turn in 1..MAX_TURNS) {
                full.clear()
                var pendingCalls: List<ToolCall>? = null
                var errored = false

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
                        is StreamEvent.Done -> Unit
                        is StreamEvent.Error -> {
                            errored = true
                            if (full.isBlank()) conversationRepository.deleteMessage(assistant.id)
                            else conversationRepository.updateMessage(
                                assistant.copy(content = full.toString(), isStreaming = false)
                            )
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
                        ?: extractImplicitWriteAction(text, effectiveUserMessage, currentDocumentId, currentDocumentName)

                    if (text.isBlank() && action == null) {
                        val notice = "AI 本轮没有返回任何内容。可能原因：所选模型不支持函数调用、" +
                            "max tokens 过小、或为推理模型（输出在 reasoning 字段）。" +
                            "请重试，或在设置里更换模型 / 调大 max tokens。"
                        conversationRepository.updateMessage(
                            assistant.copy(content = notice, isStreaming = false, steps = agentSteps.toList())
                        )
                        finalizeTask(AgentTaskStatus.BLOCKED, "模型未返回内容", "模型未返回任何内容")
                        markCompleted(conversationId)
                        emit(AgentMessageState.Notice(notice))
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
                    finalizeTask(AgentTaskStatus.COMPLETED, action?.description ?: text.take(80).ifBlank { "已完成" })
                    markCompleted(conversationId)
                    if (action != null) emit(AgentMessageState.ActionProposed(assistant.id, action))
                    else if (text.contains("[[ACTION]]"))
                        emit(AgentMessageState.Notice("AI 输出的操作块格式有误，未生成可应用的改动"))
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
                    finalizeTask(AgentTaskStatus.BLOCKED, text, "检测到重复的工具调用")
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
                                    val msg = e.message ?: "写操作失败"
                                    workingMessages.add(ChatMessage(role = "tool", content = "ERROR: $msg", toolCallId = call.id))
                                    val done = AgentStep.ToolDone(call.name, false, summarize(msg))
                                    agentSteps.add(done)
                                    emit(AgentMessageState.ToolStep(done))
                                }
                            )
                        }
                        "update_plan" -> {
                            val result = runCatching { applyPlan(conversationId, effectiveUserMessage, call) }
                            result.onSuccess { taskId = it }
                            val summary = result.fold({ "已更新计划" }, { "计划更新失败：${it.message}" })
                            workingMessages.add(ChatMessage(role = "tool", content = summary, toolCallId = call.id))
                            val done = AgentStep.ToolDone(call.name, result.isSuccess, summary)
                            agentSteps.add(done)
                            emit(AgentMessageState.ToolStep(done))
                        }
                        else -> {
                            // 只读工具：read_document / list_documents / search_in_project
                            executeDocumentTool(call).fold(
                                onSuccess = { content ->
                                    val truncated = truncateToolResult(content)
                                    workingMessages.add(ChatMessage(role = "tool", content = truncated, toolCallId = call.id))
                                    val done = AgentStep.ToolDone(call.name, true, summarize(truncated))
                                    agentSteps.add(done)
                                    emit(AgentMessageState.ToolStep(done))
                                },
                                onFailure = { e ->
                                    val msg = e.message ?: "工具执行失败"
                                    workingMessages.add(ChatMessage(role = "tool", content = "ERROR: $msg", toolCallId = call.id))
                                    val done = AgentStep.ToolDone(call.name, false, summarize(msg))
                                    agentSteps.add(done)
                                    emit(AgentMessageState.ToolStep(done))
                                }
                            )
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
                    finalizeTask(AgentTaskStatus.COMPLETED, act.description.ifBlank { "已生成待确认的文档改动" })
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
            finalizeTask(AgentTaskStatus.FAILED, text, "已达最大步数 $MAX_TURNS")
            markCompleted(conversationId)
            emit(AgentMessageState.Completed(text))
        } finally {
            // 兜底：取消/异常导致未正常终态化时，收束任务与会话，防中间态残留。
            if (!taskFinalized) {
                withContext(NonCancellable) {
                    taskId?.let { id ->
                        agentTaskRepository.getTaskByConversationId(conversationId)?.task?.let { t ->
                            if (t.id == id && (t.status == AgentTaskStatus.EXECUTING ||
                                    t.status == AgentTaskStatus.PLANNING || t.status == AgentTaskStatus.REPLANNING)) {
                                agentTaskRepository.updateTask(
                                    t.copy(
                                        status = AgentTaskStatus.FAILED,
                                        updatedAt = System.currentTimeMillis(),
                                        currentStepId = null,
                                        blockingReason = "任务被中断（取消或异常）"
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
            agentTaskRepository.updateTask(existing.copy(status = derived, updatedAt = now))
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
            AgentActionType.CREATE_DOCUMENT -> {
                val title = action.description.take(50).ifBlank { "AI 生成文档" }
                val doc = createDocumentUseCase(title).getOrThrow()
                saveDocumentUseCase(doc.copy(content = action.content)).getOrThrow()
                snapshotVersion(doc.id)  // 新建文档：AI 生成内容落首个历史版本
                doc.id
            }
            AgentActionType.EDIT_DOCUMENT -> {
                val targetId = action.targetDocumentId
                    ?: error("EDIT_DOCUMENT 缺少 targetDocumentId")
                val doc = loadDocumentUseCase(targetId).getOrThrow()
                // 覆盖前先把改动前内容入历史，保证可回退到 Agent 修改之前
                snapshotVersion(targetId, doc.content, doc.wordCount)
                // finalContent：用户在 diff 闸门逐 hunk 审阅后合成的内容；为空则回退整篇覆盖
                saveDocumentUseCase(doc.copy(content = finalContent ?: action.content)).getOrThrow()
                snapshotVersion(targetId)  // 改动后内容入历史，与手动保存路径一致
                targetId
            }
        }
        conversationRepository.updateMessage(
            message.copy(agentAction = action.copy(status = AgentActionStatus.EXECUTED))
        )
        documentId
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

/** 单行摘要，用于步骤展示。 */
private fun summarize(s: String, max: Int = 60): String {
    val oneLine = s.replace("\n", " ").trim()
    return if (oneLine.length <= max) oneLine else oneLine.take(max) + "…"
}

/** 工具结果回填前截断，避免单步爆窗。 */
private fun truncateToolResult(s: String): String {
    val budget = com.yumark.app.core.util.ContextBudget.TOOL_RESULT_CHARS
    return if (s.length <= budget) s else s.take(budget) + "\n…（结果过长已截断）"
}

/** 构建 Agent 系统提示：工具优先、外科式编辑、模型驱动 todo，注入当前文档上下文。 */
internal fun buildAgentSystemPrompt(documentName: String?, documentContent: String?): String {
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

    return """
        # 角色
        你是 YuMark 的文档助手，帮用户检索、整理、创作与改写 Markdown 笔记。

        # 工作方式（工具优先）
        你可以调用工具来完成任务，并基于工具结果继续推理，直到给出最终答复：
        - read_document：读取某文档完整内容（需要确切原文时用；同一文档不要重复读）。
        - list_documents / search_in_project：不确定文档是否存在或不知其 ID 时，先查再做，不要凭空假设。
        - create_document：创建新文档（提交完整 Markdown 正文）。
        - edit_document：对既有文档做**外科式局部编辑**（见下）。
        - update_plan：多步任务时维护 todo 计划（见下）。
        普通问答 / 解释：直接用文字回答，不要调用写工具，也不要整篇输出文档。

        # 外科式编辑（重要）
        修改既有文档时**只改需要改的部分**，用 edit_document 提交一组 old_string→new_string：
        - old_string 必须与文档**完全一致**且能**唯一定位**（带足够上下文），不确定就先 read_document。
        - 不要把整篇文档塞进 new_content（已无此参数），不要重复未改动的大段内容。
        - 同一片段多处出现时，补充上下文使其唯一，或设 replace_all=true。
        - 若编辑未命中/不唯一，你会收到错误说明——据此修正 old_string 后重试。
        - new_string 必须是**改好后的真实文档正文**，不要写"改进要点/修改说明"这类对改动的描述。

        # 计划（update_plan）
        任务需要多步时，先用 update_plan 列出步骤（pending/in_progress/done/blocked），
        并在推进时更新各步状态；单步小任务可不调用。

        # 审批
        任何创建/编辑都会先以预览或逐行 diff 呈现给用户，由用户确认后才真正写入。
        因此放心提出改动；但务必保证内容完整、准确。

        # 弱端点兜底
        若所用端点不支持函数调用（你无法发起工具调用），可改为：开场一句话说明，
        然后把**完整文档正文放进一个 ```markdown 围栏代码块**中（围栏外只放那句说明）；
        编辑时在围栏内输出**更新后的完整文档全文**。仅在确实要创建/改写文档时才这样做。
        围栏内必须是文档的真实正文，**不能是"改了哪些地方"的说明或要点清单**。

        # 当前上下文
        $docContext
    """.trimIndent()
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

    val type = AgentActionType.valueOf(typeStr)
    return AgentAction(
        type = type,
        description = description,
        targetDocumentId = if (type == AgentActionType.EDIT_DOCUMENT) currentDocumentId else null,
        content = content
    )
}

/**
 * 降级识别（弱端点）：模型未发写工具、未按 [[ACTION]] 协议，而是直接把完整 Markdown 正文
 * 写在回复里时，把正文识别为待批准 [AgentAction]（复用 diff/审批门，不绕过审批）。
 */
internal fun extractImplicitWriteAction(
    text: String,
    userMessage: String,
    currentDocumentId: String?,
    currentDocumentName: String?
): AgentAction? {
    if (text.isBlank()) return null
    if (text.contains("[[ACTION]]")) return null

    val createIntent = isDocumentCreationRequest(userMessage)
    // 已有打开文档时，编辑意图无需再强求出现"文档/这篇"等名词——上下文已明确指向当前文档。
    val editIntent = currentDocumentId != null && isDocumentEditRequest(userMessage, requireDocNoun = false)
    if (!createIntent && !editIntent) return null

    // 剥离对话前言、解开 ```markdown 围栏，得到纯文档正文
    val body = extractDocumentBody(text)
    if (body.isBlank()) return null

    val looksLikeDocument = body.length >= IMPLICIT_DOC_MIN_CHARS || hasDocumentStructure(body)
    if (!looksLikeDocument) return null

    return if (editIntent && currentDocumentId != null) {
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
 */
internal fun extractDocumentBody(text: String): String {
    val fence = Regex("```(?:markdown|md)?[ \\t]*\\r?\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        .find(text)
    if (fence != null) {
        val inner = fence.groupValues[1].trim()
        if (inner.isNotBlank()) return inner
    }
    val lines = text.lines()
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
