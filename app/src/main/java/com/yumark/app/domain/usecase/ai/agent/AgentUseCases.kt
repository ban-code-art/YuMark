package com.yumark.app.domain.usecase.ai.agent

import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.domain.model.AgentStep
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
import com.yumark.app.domain.usecase.CreateDocumentUseCase
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import com.yumark.app.domain.usecase.SaveDocumentUseCase
import com.yumark.app.domain.usecase.ai.DocumentContextTools
import com.yumark.app.data.ai.AiAdapterFactory
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
    data class ToolStep(val step: com.yumark.app.domain.model.AgentStep) : AgentMessageState()
    data class Notice(val message: String) : AgentMessageState()
    data class Completed(val fullText: String) : AgentMessageState()
    data class Error(val message: String) : AgentMessageState()
}

/**
 * Agent 对话：在普通流式基础上注入文档操作系统提示，并在完成后解析操作意图。
 */
class SendAgentMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val configRepository: AiConfigRepository,
    private val adapterFactory: AiAdapterFactory,
    private val imageProcessor: com.yumark.app.core.image.ImageProcessor,
    private val agentTaskRepository: AgentTaskRepository,
    private val planAgentTask: PlanAgentTaskUseCase,
    private val executeAgentTask: ExecuteAgentTaskUseCase
) {
    operator fun invoke(
        conversationId: String,
        userMessage: String,
        currentDocumentId: String?,
        currentDocumentName: String?,
        currentDocumentContent: String?,
        attachments: List<MessageAttachment> = emptyList()
    ): Flow<AgentMessageState> = flow {
        // 设置状态为 WORKING
        val conversationBeforeTurn = conversationRepository.observeConversation(conversationId).first()
        conversationBeforeTurn?.let { conversation ->
            conversationRepository.updateConversation(
                conversation.copy(status = ConversationStatus.WORKING)
            )
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
        val initialPlan = requestInitialPlan(
            adapter = adapter,
            config = config,
            userMessage = effectiveUserMessage,
            currentDocumentName = currentDocumentName,
            currentDocumentContent = currentDocumentContent
        )
        val now = System.currentTimeMillis()
        val task = AgentTask(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            goal = initialPlan.goal,
            status = AgentTaskStatus.EXECUTING,
            createdAt = now,
            updatedAt = now
        )
        val taskSteps = initialPlan.toTaskSteps(task.id)
        var persistedSteps = taskSteps
        var persistedTask = task.copy(currentStepId = taskSteps.firstOrNull()?.id)
        agentTaskRepository.createTask(persistedTask, taskSteps)

        // 历史（纯文本，排除本轮用户消息——本轮单独构造，可能带图）
        val priorMessages = conversationRepository.observeConversation(conversationId).first()
            ?.messages.orEmpty()
            .filter { it.id != userMsg.id && !it.isStreaming && it.content.isNotBlank() }
            .map { ChatMessage(role = it.role.name.lowercase(), content = it.content) }

        // 本轮用户消息：有附件则构造多模态 contentParts（图片仅本轮进 API，历史图片不重发）
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

        // ReAct 循环：内存态累积 messages，逐轮执行工具直到收敛或达上限。仅最终回答落库。
        val workingMessages = ArrayList(priorMessages).apply { add(currentTurn) }
        val systemPrompt = buildAgentSystemPrompt(currentDocumentName, currentDocumentContent)
        val tools = DocumentContextTools.getAllTools()
        val full = StringBuilder()
        val agentSteps = ArrayList<AgentStep>()
        var lastToolSignature: String? = null

        // 整个 ReAct 循环包 try/finally：异常/取消/未捕获错误时兜底终态化任务与会话，
        // 避免任务卡 EXECUTING、会话卡 WORKING、活跃步骤卡 RUNNING（循环体未重新缩进，Kotlin 不依赖缩进）。
        try {
        for (step in 1..MAX_STEPS) {
            full.clear()
            var pendingCalls: List<ToolCall>? = null
            var errored = false
            // 本轮活跃的规划步骤：写作/工具执行都会推进它的状态。
            val activeTaskStep = persistedSteps.getOrNull((step - 1).coerceAtMost(persistedSteps.lastIndex))
            var stepMarkedRunning = false

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
                        // 首个内容增量到达即把活跃步骤标 RUNNING，让面板实时反映"正在生成"。
                        if (!stepMarkedRunning && activeTaskStep != null) {
                            stepMarkedRunning = true
                            persistedSteps = persistedSteps.replaceStep(activeTaskStep.id) {
                                it.copy(status = AgentTaskStepStatus.RUNNING)
                            }
                            persistedTask = persistedTask.copy(
                                updatedAt = System.currentTimeMillis(),
                                currentStepId = activeTaskStep.id
                            )
                            agentTaskRepository.markStepStatus(activeTaskStep.id, AgentTaskStepStatus.RUNNING)
                            agentTaskRepository.updateTask(persistedTask)
                        }
                        full.append(event.text)
                        conversationRepository.updateMessage(
                            assistant.copy(content = full.toString(), isStreaming = true)
                        )
                        emit(AgentMessageState.Streaming(event.text))
                    }
                    is StreamEvent.ToolCallComplete -> pendingCalls = (pendingCalls ?: emptyList()) + event.calls
                    is StreamEvent.ToolCallDelta -> Unit  // 增量仅供 UI 粗提示，循环依赖 ToolCallComplete
                    is StreamEvent.Done -> Unit           // 本轮流结束
                    is StreamEvent.Error -> {
                        errored = true
                        if (full.isBlank()) conversationRepository.deleteMessage(assistant.id)
                        else conversationRepository.updateMessage(
                            assistant.copy(content = full.toString(), isStreaming = false)
                        )
                        conversationRepository.observeConversation(conversationId).first()?.let {
                            conversationRepository.updateConversation(it.copy(status = ConversationStatus.IDLE))
                        }
                        emit(AgentMessageState.Error(event.message))
                    }
                }
            }
            if (errored) return@flow

            val calls = pendingCalls
            if (calls.isNullOrEmpty()) {
                // 收敛：最终回答（仍兼容第一波 [[ACTION]] + diff 闸门）
                val text = full.toString()
                // 解析写意图，优先级：[[ACTION]] 文本协议 → 弱模型"把全文吐在回复里"的隐式识别。
                // 第三条路（写工具调用 create/edit_document）已在上面循环里拦截转为 proposedAction，不会走到收敛分支。
                val action = parseAgentAction(text, currentDocumentId)
                    ?: extractImplicitWriteAction(text, effectiveUserMessage, currentDocumentId, currentDocumentName)

                // 模型这一轮既无文本也无操作（200 但空流）：常见于端点静默忽略 tools、max_tokens
                // 过小、或推理模型把输出全放进 reasoning_content 而 content 为空。
                // 不要落一条空气泡假装“已完成”，给出可操作提示并把任务标记为受阻。
                if (text.isBlank() && action == null) {
                    val notice = "AI 本轮没有返回任何内容。可能原因：所选模型不支持函数调用、" +
                        "max tokens 过小、或为推理模型（输出在 reasoning 字段）。" +
                        "请重试，或在设置里更换模型 / 调大 max tokens。"
                    conversationRepository.updateMessage(
                        assistant.copy(content = notice, isStreaming = false, steps = agentSteps.toList())
                    )
                    if (activeTaskStep != null) {
                        persistedSteps = persistedSteps.replaceStep(activeTaskStep.id) {
                            it.copy(status = AgentTaskStepStatus.BLOCKED, resultSummary = "模型未返回内容")
                        }
                        agentTaskRepository.markStepStatus(activeTaskStep.id, AgentTaskStepStatus.BLOCKED, "模型未返回内容")
                    }
                    persistedTask = finalizeTaskDirectly(
                        task = persistedTask,
                        status = AgentTaskStatus.BLOCKED,
                        summary = "模型未返回内容",
                        blockingReason = "模型未返回任何内容"
                    )
                    markCompleted(conversationId)
                    emit(AgentMessageState.Notice(notice))
                    emit(AgentMessageState.Completed(notice))
                    return@flow
                }
                // 收敛即完成活跃步骤
                if (activeTaskStep != null) {
                    persistedSteps = persistedSteps.replaceStep(activeTaskStep.id) {
                        it.copy(status = AgentTaskStepStatus.DONE, resultSummary = text.take(80))
                    }
                    agentTaskRepository.markStepStatus(
                        activeTaskStep.id,
                        AgentTaskStepStatus.DONE,
                        text.take(80)
                    )
                }
                // 聊天气泡只显示对话性开场白（说明方案那段），文档正文放进 action.content 由
                // 预览卡承载——避免把"思路/前言"和围栏一起写进文档。无明显前言时给一句简短确认。
                val chatText = if (action != null) {
                    conversationalPreamble(text).ifBlank { action.description.ifBlank { "已生成文档内容，请在下方预览确认。" } }
                } else {
                    text
                }
                conversationRepository.updateMessage(
                    assistant.copy(content = chatText, isStreaming = false, agentAction = action, steps = agentSteps.toList())
                )
                // 直接终态化为 COMPLETED——不再额外跑 completion judge 判定（避免结束后加载与爆红）。
                persistedTask = finalizeTaskDirectly(
                    task = persistedTask,
                    status = AgentTaskStatus.COMPLETED,
                    summary = action?.description ?: text.take(80).ifBlank { "已完成" }
                )
                markCompleted(conversationId)
                if (action != null) emit(AgentMessageState.ActionProposed(assistant.id, action))
                else if (text.contains("[[ACTION]]"))
                    emit(AgentMessageState.Notice("AI 输出的操作块格式有误，未生成可应用的改动"))
                emit(AgentMessageState.Completed(text))
                return@flow
            }

            // 写工具（create/edit_document）= 创建/编辑提议：转为待批准 action 结束循环，复用第一波 diff 闸门
            // 循环检测：完全相同的一组工具调用重复出现 → 停，防失控
            val signature = calls.joinToString("|") { "${it.name}(${it.arguments})" }
            if (signature == lastToolSignature) {
                val text = full.toString().ifBlank { "（已停止：检测到重复的工具调用）" }
                conversationRepository.updateMessage(assistant.copy(content = text, isStreaming = false, steps = agentSteps.toList()))
                persistedTask = finalizeTaskDirectly(
                    task = persistedTask,
                    status = AgentTaskStatus.BLOCKED,
                    summary = text,
                    blockingReason = "检测到重复的工具调用"
                )
                markCompleted(conversationId)
                emit(AgentMessageState.Completed(text))
                return@flow
            }
            lastToolSignature = signature

            // 执行工具并以 role=tool 回填（内存态，不落库）
            workingMessages.add(
                ChatMessage(role = "assistant", content = full.toString().ifBlank { null }, toolCalls = calls)
            )
            for (call in calls) {
                val callingStep = AgentStep.ToolCalling(call.name, summarize(call.arguments))
                agentSteps.add(callingStep)
                emit(AgentMessageState.ToolStep(callingStep))
            }

            if (activeTaskStep == null) {
                val text = "Agent 任务没有可执行步骤，已停止。"
                conversationRepository.updateMessage(
                    assistant.copy(content = text, isStreaming = false, steps = agentSteps.toList())
                )
                persistedTask = finalizeTaskDirectly(
                    task = persistedTask,
                    status = AgentTaskStatus.FAILED,
                    summary = text,
                    blockingReason = text
                )
                markCompleted(conversationId)
                emit(AgentMessageState.Completed(text))
                return@flow
            }

            persistedSteps = persistedSteps.replaceStep(activeTaskStep.id) {
                it.copy(status = AgentTaskStepStatus.RUNNING)
            }
            persistedTask = persistedTask.copy(
                updatedAt = System.currentTimeMillis(),
                currentStepId = activeTaskStep.id
            )

            val execution = executeAgentTask(
                task = persistedTask,
                step = activeTaskStep,
                toolCalls = calls,
                currentDocumentId = currentDocumentId
            ).getOrElse { err ->
                val reason = err.message ?: "工具执行失败"
                agentTaskRepository.markStepStatus(activeTaskStep.id, AgentTaskStepStatus.BLOCKED, reason)
                persistedSteps = persistedSteps.replaceStep(activeTaskStep.id) {
                    it.copy(status = AgentTaskStepStatus.BLOCKED, resultSummary = reason)
                }
                persistedTask = finalizeTaskDirectly(
                    task = persistedTask,
                    status = AgentTaskStatus.BLOCKED,
                    summary = reason,
                    blockingReason = reason
                )
                AgentStepExecutionResult(
                    outcome = AgentStepExecutionOutcome.STEP_BLOCKED,
                    blockingReason = reason
                )
            }

            for (result in execution.toolResults) {
                val truncated = truncateToolResult(result.content)
                workingMessages.add(ChatMessage(role = "tool", content = truncated, toolCallId = result.call.id))
                val doneStep = AgentStep.ToolDone(result.call.name, result.ok, summarize(truncated))
                agentSteps.add(doneStep)
                emit(AgentMessageState.ToolStep(doneStep))
            }

            if (execution.outcome == AgentStepExecutionOutcome.STEP_BLOCKED) {
                val reason = execution.blockingReason ?: "工具执行失败"
                val failedCall = calls.firstOrNull()
                if (failedCall != null && execution.toolResults.none { it.call.id == failedCall.id }) {
                    val doneStep = AgentStep.ToolDone(failedCall.name, false, summarize(reason))
                    agentSteps.add(doneStep)
                    emit(AgentMessageState.ToolStep(doneStep))
                }
                persistedSteps = persistedSteps.replaceStep(activeTaskStep.id) {
                    it.copy(status = AgentTaskStepStatus.BLOCKED, resultSummary = reason)
                }
                persistedTask = persistedTask.copy(
                    status = AgentTaskStatus.BLOCKED,
                    updatedAt = System.currentTimeMillis(),
                    currentStepId = activeTaskStep.id,
                    blockingReason = reason
                )
                val text = full.toString().ifBlank { reason }
                conversationRepository.updateMessage(
                    assistant.copy(content = text, isStreaming = false, steps = agentSteps.toList())
                )
                markCompleted(conversationId)
                emit(AgentMessageState.Completed(text))
                return@flow
            }

            val writeAction = execution.proposedAction
            if (writeAction != null) {
                val doneStep = AgentStep.ToolDone(
                    calls.firstOrNull { it.name == "create_document" || it.name == "edit_document" }?.name
                        ?: "write_document",
                    true,
                    "write proposal ready"
                )
                agentSteps.add(doneStep)
                emit(AgentMessageState.ToolStep(doneStep))
                val text = full.toString().ifBlank { writeAction.description }
                conversationRepository.updateMessage(
                    assistant.copy(content = text, isStreaming = false, agentAction = writeAction, steps = agentSteps.toList())
                )
                persistedSteps = persistedSteps.replaceStep(activeTaskStep.id) {
                    it.copy(
                        status = AgentTaskStepStatus.DONE,
                        resultSummary = "write proposal ready"
                    )
                }
                agentTaskRepository.markStepStatus(
                    activeTaskStep.id,
                    AgentTaskStepStatus.DONE,
                    "write proposal ready"
                )
                // 写操作提议即完成本轮任务——直接终态化 COMPLETED，不再跑 completion judge。
                persistedTask = finalizeTaskDirectly(
                    task = persistedTask,
                    status = AgentTaskStatus.COMPLETED,
                    summary = writeAction.description.ifBlank { "已生成待确认的文档改动" }
                )
                markCompleted(conversationId)
                emit(AgentMessageState.ActionProposed(assistant.id, writeAction))
                emit(AgentMessageState.Completed(text))
                return@flow
            }

            if (execution.outcome == AgentStepExecutionOutcome.STEP_DONE) {
                val resultSummary = execution.toolResults.joinToString {
                    "${it.call.name}: ${summarize(it.content)}"
                }.ifBlank {
                    calls.joinToString { "${it.name}: ${summarize(it.arguments)}" }
                }
                persistedSteps = persistedSteps.replaceStep(activeTaskStep.id) {
                    it.copy(
                        status = AgentTaskStepStatus.DONE,
                        resultSummary = resultSummary
                    )
                }
                val nextStep = persistedSteps.firstOrNull { it.order > activeTaskStep.order }
                persistedTask = persistedTask.copy(
                    updatedAt = System.currentTimeMillis(),
                    currentStepId = nextStep?.id
                )
                agentTaskRepository.updateTask(persistedTask)
            }
        }

        // 达到最大步数仍未收敛：停止并提示
        val text = full.toString().ifBlank { "（已达最大步数 $MAX_STEPS，已停止）" }
        conversationRepository.updateMessage(assistant.copy(content = text, isStreaming = false, steps = agentSteps.toList()))
        persistedTask = finalizeTaskDirectly(
            task = persistedTask,
            status = AgentTaskStatus.FAILED,
            summary = text,
            blockingReason = "已达最大步数 $MAX_STEPS"
        )
        markCompleted(conversationId)
        emit(AgentMessageState.Completed(text))
        } finally {
            // 兜底：若任务仍处 EXECUTING（Error 分支未终态化 / 抛异常 / 协程取消），
            // 强制收束，防任务、会话、活跃步骤卡中间态。NonCancellable 保证取消时清理仍执行。
            if (persistedTask.status == AgentTaskStatus.EXECUTING) {
                withContext(NonCancellable) {
                    persistedTask.currentStepId?.let { stepId ->
                        runCatching { agentTaskRepository.markStepStatus(stepId, AgentTaskStepStatus.BLOCKED) }
                    }
                    persistedTask = finalizeTaskDirectly(
                        task = persistedTask,
                        status = AgentTaskStatus.FAILED,
                        summary = "任务被中断",
                        blockingReason = "任务被中断（取消或异常）"
                    )
                    conversationRepository.observeConversation(conversationId).first()?.let {
                        conversationRepository.updateConversation(it.copy(status = ConversationStatus.IDLE))
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

    private suspend fun requestInitialPlan(
        adapter: com.yumark.app.data.ai.AiApiAdapter,
        config: com.yumark.app.domain.model.AiConfig,
        userMessage: String,
        currentDocumentName: String?,
        currentDocumentContent: String?
    ): AgentPlan {
        val rawPlan = StringBuilder()
        adapter.sendChatStream(
            messages = listOf(ChatMessage(role = "user", content = userMessage)),
            config = AiRequestConfig(
                model = config.modelName,
                temperature = 0f,
                maxTokens = minOf(config.maxTokens, 1200),
                systemPrompt = buildPlannerSystemPrompt(currentDocumentName, currentDocumentContent)
            ),
            tools = emptyList()
        ).collect { event ->
            when (event) {
                is StreamEvent.Content -> rawPlan.append(event.text)
                is StreamEvent.Done -> if (rawPlan.isBlank()) rawPlan.append(event.fullText)
                else -> Unit
            }
        }

        return planAgentTask(rawPlan.toString()).getOrElse {
            fallbackPlan(userMessage)
        }
    }

    private suspend fun finalizeTaskDirectly(
        task: AgentTask,
        status: AgentTaskStatus,
        summary: String,
        blockingReason: String? = null
    ): AgentTask {
        val finalized = task.copy(
            status = status,
            updatedAt = System.currentTimeMillis(),
            currentStepId = null,
            finalSummary = summary,
            blockingReason = blockingReason
        )
        agentTaskRepository.updateTask(finalized)
        return finalized
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
    private val conversationRepository: ConversationRepository
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
                doc.id
            }
            AgentActionType.EDIT_DOCUMENT -> {
                val targetId = action.targetDocumentId
                    ?: error("EDIT_DOCUMENT 缺少 targetDocumentId")
                val doc = loadDocumentUseCase(targetId).getOrThrow()
                // finalContent：用户在 diff 闸门逐 hunk 审阅后合成的内容；为空则回退整篇覆盖
                saveDocumentUseCase(doc.copy(content = finalContent ?: action.content)).getOrThrow()
                targetId
            }
        }
        conversationRepository.updateMessage(
            message.copy(agentAction = action.copy(status = AgentActionStatus.EXECUTED))
        )
        documentId
    }
}

private const val MAX_STEPS = 6

/** 编辑场景把当前文档全文塞进系统提示的字符上限；超过则退回大纲 + read_document。 */
private const val FULL_DOC_CONTEXT_BUDGET = 6000

private fun fallbackPlan(userMessage: String): AgentPlan =
    AgentPlan(
        goal = userMessage,
        successCriteria = listOf("Respond to the user request"),
        steps = listOf(
            AgentPlanStep(
                title = "Respond to user request",
                purpose = "Use the existing agent loop to answer or propose a document change",
                suggestedTools = emptyList(),
                completionCriteria = "The agent produces a final response or action proposal",
                fallback = "Explain why the request cannot be completed"
            )
        )
    )

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

internal fun buildPlannerSystemPrompt(documentName: String?, documentContent: String?): String {
    val docContext = if (documentName != null) {
        val outline = documentContent?.let { com.yumark.app.core.util.documentOutline(it) } ?: "(empty)"
        "Current document: $documentName\n$outline"
    } else {
        "No current document is open."
    }
    return """
        You are the planning stage for YuMark's document agent.
        Return only one JSON object. Do not answer the user directly.
        The JSON schema is:
        {
          "goal": "short user goal",
          "success_criteria": ["criterion"],
          "steps": [
            {
              "title": "short step title",
              "purpose": "why this step is needed",
              "suggested_tools": ["search_in_project", "list_documents", "read_document", "create_document", "edit_document"],
              "completion_criteria": "observable completion rule",
              "fallback": "what to do if this step cannot complete"
            }
          ]
        }
        Use only supported tool names. Keep the plan to 1-4 steps.
        Preserve explicit user approval for any document write.
        For document creation requests, do not require the user to supply save location, title, outline, or depth before planning.
        Infer missing details from the topic and user intent, and use create_document to produce a normal pending write proposal.

        Context:
        $docContext
    """.trimIndent()
}

private inline fun List<com.yumark.app.domain.model.AgentTaskStep>.replaceStep(
    stepId: String,
    transform: (com.yumark.app.domain.model.AgentTaskStep) -> com.yumark.app.domain.model.AgentTaskStep
): List<com.yumark.app.domain.model.AgentTaskStep> =
    map { step -> if (step.id == stepId) transform(step) else step }

/** 工具结果回填前截断，避免单步爆窗（R3）。 */
private fun truncateToolResult(s: String): String {
    val budget = com.yumark.app.core.util.ContextBudget.TOOL_RESULT_CHARS
    return if (s.length <= budget) s else s.take(budget) + "\n…（结果过长已截断）"
}

/** 单行摘要，用于步骤展示。 */
private fun summarize(s: String, max: Int = 60): String {
    val oneLine = s.replace("\n", " ").trim()
    return if (oneLine.length <= max) oneLine else oneLine.take(max) + "…"
}

/** 构建 Agent 系统提示，注入当前文档上下文。 */
internal fun buildAgentSystemPrompt(documentName: String?, documentContent: String?): String {
    val docContext = if (documentName != null) {
        val content = documentContent.orEmpty()
        val detail = if (content.isNotBlank() && content.length <= FULL_DOC_CONTEXT_BUDGET) {
            // 端点可能不支持 read_document（如静默忽略 tools 的兼容端点），编辑时直接给全文，
            // 模型才能基于真实原文输出"更新后的完整文档"，供逐行 diff 审阅。
            "完整内容如下（编辑时基于此输出更新后的全文）：\n$content"
        } else {
            val outline = content.takeIf { it.isNotBlank() }
                ?.let { com.yumark.app.core.util.documentOutline(it) } ?: "(空)"
            "$outline\n（文档较大，仅给出大纲；需要某段完整内容时用 read_document 获取）"
        }
        "当前打开的文档：《$documentName》\n$detail"
    } else {
        "当前没有打开的文档。"
    }

    return """
        # 角色
        你是 YuMark 的文档助手，帮用户检索、整理、改写 Markdown 笔记。

        # 能力边界
        只能操作本应用内的 Markdown 文档；不要杜撰不存在的文档或文档 ID。

        # 工具使用守则
        - 不确定文档是否存在、或不知道其 ID 时，先用 search_in_project / list_documents 查，不要凭空假设。
        - 需要某文档全文时用 read_document；同一文档不要重复读取。
        - 先检索、基于真实内容作答，不要编造文档内容。

        # 创建 / 编辑文档
        关键信息不足时先提问；在这些阻塞信息补齐前不要调用 create_document / edit_document。
        - 用户要求创建文档时，不要因为缺少保存位置、标题、结构或深度而反复追问；这些缺失项由你结合主题自行判断。
        - 如果用户没有说明保存位置，按默认新建文档流程生成待批准内容，不要为了路径阻塞创建。
        - 只有在缺少主题、目标文档、或无法判断用户到底要创建还是编辑时，才先追问。
        确需创建或修改文档且信息充分时，先用一段话说明方案，然后：
        - 调用 create_document / edit_document 工具，把完整内容作为参数提交。
        - 仅当你无法使用上述工具时，才退而在回复末尾追加一个文本操作块兜底：

        [[ACTION]]
        type: CREATE_DOCUMENT 或 EDIT_DOCUMENT
        description: 一句话描述你做了什么
        [[CONTENT]]
        完整可用的 Markdown 内容（不要省略）
        [[/ACTION]]

        两种方式二选一、一次最多一个操作；无论哪种，用户都会先看到改动预览并确认后才生效。

        # 弱工具调用模型的兜底约定
        如果你不确定能否成功发起工具调用（例如所用端点的 function calling 不可靠），
        可以直接输出文档正文作为回复，系统会据此生成一个待确认的创建/编辑提议，
        用户仍会先预览并确认后才落库。此时务必遵守：
        - 开场最多一句话说明你要做什么，不要长篇解释、不要罗列大纲；
        - 把**完整文档正文放进一个 ```markdown 围栏代码块**中，围栏内只放将写入文档的内容；
          不要把开场白、方案说明、"请确认是否保存"等对话内容放进围栏；
        - **仅当用户确实要创建或改写文档时才输出正文，普通问答/解释不要整篇输出。**
        - 编辑现有文档时，输出**更新后的完整文档全文**（保留未改动的部分），不要只给新增/改动片段——
          系统会把它与原文逐行比对，生成可逐块确认的改动。

        # 安全与规范
        - 修改用户已有文档前，用户会看到逐行改动并逐块确认；请确保改动完整、准确。
        - 普通问答 / 总结 / 解释不要输出 [[ACTION]] 块。

        # 当前上下文
        $docContext
    """.trimIndent()
}

/** 从 AI 回复中解析操作意图。返回 null 表示无操作。 */
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
 * 把写工具调用（create_document / edit_document）解析为 [AgentAction]，
 * 复用第一波 diff 闸门审批落库。非写工具或参数畸形返回 null。
 */
internal fun parseWriteToolCall(call: ToolCall, currentDocumentId: String?): AgentAction? {
    val args = runCatching {
        Json.decodeFromString<Map<String, JsonElement>>(call.arguments)
    }.getOrNull() ?: return null
    return when (call.name) {
        "create_document" -> {
            val content = args["content"]?.jsonPrimitive?.contentOrNull ?: return null
            val title = args["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "AI 生成文档"
            AgentAction(
                type = AgentActionType.CREATE_DOCUMENT,
                description = title,
                content = content
            )
        }
        "edit_document" -> {
            val content = args["new_content"]?.jsonPrimitive?.contentOrNull ?: return null
            val docId = args["document_id"]?.jsonPrimitive?.contentOrNull ?: currentDocumentId ?: return null
            AgentAction(
                type = AgentActionType.EDIT_DOCUMENT,
                description = "编辑文档",
                targetDocumentId = docId,
                content = content
            )
        }
        else -> null
    }
}

/**
 * 弱工具调用模型的兜底识别：当模型既未发 `create_document`/`edit_document` 工具调用、
 * 也未按 `[[ACTION]]` 协议输出，而是**直接把完整 Markdown 正文写在回复里**时，
 * 把这段正文识别为一个待批准的 [AgentAction]，复用 diff/批准闸门（不绕过审批、不直接落库）。
 *
 * 仅在满足以下条件时识别，避免把普通问答误判为写操作：
 * - 用户本轮是"创建文档"或"改写文档"意图；
 * - 文本里没有 `[[ACTION]]` 块（已有 [parseAgentAction] 负责那条路）；
 * - 文本达到一定长度或含 Markdown 结构特征（标题/列表），排除"我建议你这样写…"之类的短建议。
 *
 * 返回 null 表示确属普通问答，不当写操作处理。
 */
internal fun extractImplicitWriteAction(
    text: String,
    userMessage: String,
    currentDocumentId: String?,
    currentDocumentName: String?
): AgentAction? {
    if (text.isBlank()) return null
    // 已含 [[ACTION]] 块的由 parseAgentAction 处理，这里不重复识别
    if (text.contains("[[ACTION]]")) return null

    val createIntent = isDocumentCreationRequest(userMessage)
    // 已有打开文档时，编辑意图无需再强求出现"文档/这篇"等名词——上下文已明确指向当前文档。
    val editIntent = currentDocumentId != null && isDocumentEditRequest(userMessage, requireDocNoun = false)
    if (!createIntent && !editIntent) return null

    // 剥离对话前言、解开 ```markdown 围栏，得到纯文档正文
    val body = extractDocumentBody(text)
    if (body.isBlank()) return null

    // 文本需像"文档正文"而非短回复：长度达标 或 含 Markdown 结构
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
        // 创建：标题取首行 Markdown 标题或文本首行，否则用默认
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
 * 1) 优先取 ```markdown / ```md / ``` 围栏内部（模型常把正文包在围栏里）；
 * 2) 否则从第一行 Markdown 标题开始，丢弃前面的对话前言；
 * 3) 都不命中则原样返回。
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

/**
 * 取文档正文之前的对话性前言（用于聊天气泡显示），与 [extractDocumentBody] 对应：
 * 围栏前 / [[ACTION]] 前 / 首个标题前的文本。无明显分界返回空串。
 */
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
        "rewrite", "revise", "polish", "edit", "improve", "expand", "append", "update"
    ).any { normalized.contains(it) }
    val docNoun = listOf(
        "文档", "笔记", "这篇", "当前", "本文", "内容", "段落", "章节", "全文",
        "document", "note", "this"
    ).any { normalized.contains(it) }
    // 负向条件：明显是"解释/说明/提问"类请求时不当编辑处理，避免把长解释误识别为整篇覆写提议。
    // 注意：不再用句尾问号作为否决——"帮我优化一下好吗？"仍是编辑请求。
    val isQuestionOrExplanation = listOf(
        "解释", "什么是", "为什么", "介绍一下", "讲解一下", "是什么意思"
    ).any { normalized.contains(it) }
    return editVerb && (!requireDocNoun || docNoun) && !isQuestionOrExplanation
}

/** 文本是否含 Markdown 文档结构特征（标题/列表/代码块/表格）。 */
private fun hasDocumentStructure(text: String): Boolean {
    return text.lineSequence().any { line ->
        val t = line.trimStart()
        t.startsWith("#") ||                      // 标题
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") ||  // 无序列表
            (t.firstOrNull()?.isDigit() == true && t.contains(". ")) ||        // 有序列表
            t.startsWith("```") ||                // 代码块
            t.startsWith("|")                     // 表格
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
