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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    private val executeAgentTask: ExecuteAgentTaskUseCase,
    private val evaluateTaskCompletion: EvaluateTaskCompletionUseCase
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

        for (step in 1..MAX_STEPS) {
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
                    is StreamEvent.ToolCallComplete -> pendingCalls = event.calls
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
            val activeTaskStep = persistedSteps.getOrNull((step - 1).coerceAtMost(persistedSteps.lastIndex))
            if (calls.isNullOrEmpty()) {
                // 收敛：最终回答（仍兼容第一波 [[ACTION]] + diff 闸门）
                val text = full.toString()
                val action = parseAgentAction(text, currentDocumentId)
                conversationRepository.updateMessage(
                    assistant.copy(content = text, isStreaming = false, agentAction = action, steps = agentSteps.toList())
                )
                persistedTask = finalizeTaskWithJudge(
                    adapter = adapter,
                    config = config,
                    task = persistedTask,
                    steps = persistedSteps,
                    finalText = text,
                    action = action,
                    agentSteps = agentSteps
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
                val nextStep = persistedSteps.firstOrNull { it.order > activeTaskStep.order }
                persistedTask = persistedTask.copy(
                    updatedAt = System.currentTimeMillis(),
                    currentStepId = nextStep?.id
                )
                agentTaskRepository.updateTask(persistedTask)
                persistedTask = finalizeTaskWithJudge(
                    adapter = adapter,
                    config = config,
                    task = persistedTask,
                    steps = persistedSteps,
                    finalText = text,
                    action = writeAction,
                    agentSteps = agentSteps
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

    private suspend fun finalizeTaskWithJudge(
        adapter: com.yumark.app.data.ai.AiApiAdapter,
        config: com.yumark.app.domain.model.AiConfig,
        task: AgentTask,
        steps: List<com.yumark.app.domain.model.AgentTaskStep>,
        finalText: String,
        action: AgentAction?,
        agentSteps: List<AgentStep>
    ): AgentTask {
        val rawDecision = StringBuilder()
        adapter.sendChatStream(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = buildCompletionJudgeInput(
                        task = task,
                        steps = steps,
                        finalText = finalText,
                        action = action,
                        agentSteps = agentSteps
                    )
                )
            ),
            config = AiRequestConfig(
                model = config.modelName,
                temperature = 0f,
                maxTokens = minOf(config.maxTokens, 800),
                systemPrompt = buildCompletionSystemPrompt()
            ),
            tools = emptyList()
        ).collect { event ->
            when (event) {
                is StreamEvent.Content -> rawDecision.append(event.text)
                is StreamEvent.Done -> if (rawDecision.isBlank()) rawDecision.append(event.fullText)
                else -> Unit
            }
        }

        val decision = evaluateTaskCompletion(rawDecision.toString()).getOrElse {
            TaskCompletionDecision(
                outcome = TaskCompletionOutcome.COMPLETED,
                summary = finalText.ifBlank { "Agent response completed" }
            )
        }
        val status = when (decision.outcome) {
            TaskCompletionOutcome.COMPLETED -> AgentTaskStatus.COMPLETED
            TaskCompletionOutcome.BLOCKED -> AgentTaskStatus.BLOCKED
            TaskCompletionOutcome.FAILED -> AgentTaskStatus.FAILED
        }
        return finalizeTaskDirectly(
            task = task,
            status = status,
            summary = decision.summary,
            blockingReason = decision.blockingReason
        )
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

internal fun buildCompletionSystemPrompt(): String =
    """
        You are the completion judge for YuMark's document agent.
        Decide whether the task is complete based on the task goal, success criteria, observed steps, final response, and pending action proposal.
        Return only one JSON object with this schema:
        {
          "outcome": "COMPLETED" | "BLOCKED" | "FAILED",
          "summary": "short factual summary",
          "blocking_reason": "reason when BLOCKED or FAILED, otherwise omit"
        }
        Use COMPLETED when the agent answered the user or produced a write proposal that awaits normal user approval.
        Use BLOCKED when more user input or missing project content prevents completion.
        Use FAILED when the runtime stopped because of repeated failures or exhausted limits.
    """.trimIndent()

private fun buildCompletionJudgeInput(
    task: AgentTask,
    steps: List<com.yumark.app.domain.model.AgentTaskStep>,
    finalText: String,
    action: AgentAction?,
    agentSteps: List<AgentStep>
): String {
    val plannedSteps = steps.joinToString("\n") { step ->
        "- [${step.status}] ${step.title}: ${step.completionCriteria}" +
            (step.resultSummary?.let { " result=$it" } ?: "")
    }
    val observedSteps = agentSteps.joinToString("\n") { "- $it" }.ifBlank { "- no tool steps" }
    val actionSummary = action?.let {
        "${it.type}: ${it.description.ifBlank { "pending document write" }}"
    } ?: "none"
    return """
        Task goal:
        ${task.goal}

        Planned success criteria and step state:
        $plannedSteps

        Observed execution:
        $observedSteps

        Pending action proposal:
        $actionSummary

        Final agent response:
        ${finalText.ifBlank { "(blank)" }}
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
        val outline = documentContent?.let { com.yumark.app.core.util.documentOutline(it) } ?: "(空)"
        "当前打开的文档：《$documentName》\n$outline\n（需要完整内容时用 read_document 获取）"
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
