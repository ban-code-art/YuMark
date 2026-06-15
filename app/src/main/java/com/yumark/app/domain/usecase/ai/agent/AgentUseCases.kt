package com.yumark.app.domain.usecase.ai.agent

import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
import com.yumark.app.domain.model.StreamEvent
import com.yumark.app.domain.repository.AiConfigRepository
import com.yumark.app.domain.repository.ConversationRepository
import com.yumark.app.domain.usecase.CreateDocumentUseCase
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import com.yumark.app.domain.usecase.SaveDocumentUseCase
import com.yumark.app.data.ai.AiAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

sealed class AgentMessageState {
    object UserMessageSaved : AgentMessageState()
    data class AssistantMessageStarted(val messageId: String) : AgentMessageState()
    data class Streaming(val text: String) : AgentMessageState()
    data class ActionProposed(val messageId: String, val action: AgentAction) : AgentMessageState()
    data class Completed(val fullText: String) : AgentMessageState()
    data class Error(val message: String) : AgentMessageState()
}

/**
 * Agent 对话：在普通流式基础上注入文档操作系统提示，并在完成后解析操作意图。
 */
class SendAgentMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val configRepository: AiConfigRepository,
    private val adapterFactory: AiAdapterFactory
) {
    operator fun invoke(
        conversationId: String,
        userMessage: String,
        currentDocumentId: String?,
        currentDocumentName: String?,
        currentDocumentContent: String?
    ): Flow<AgentMessageState> = flow {
        conversationRepository.addMessage(
            Message(conversationId = conversationId, role = MessageRole.USER, content = userMessage)
        )
        emit(AgentMessageState.UserMessageSaved)

        val config = configRepository.observeConfig().first()
        if (config.apiKey.isBlank() || config.modelName.isBlank()) {
            emit(AgentMessageState.Error("请先在设置中配置 API Key 和模型"))
            return@flow
        }
        val adapter = adapterFactory.createAdapter(config)

        val context = conversationRepository.observeConversation(conversationId).first()
            ?.messages.orEmpty()
            .filter { !it.isStreaming && it.content.isNotBlank() }
            .map { ChatMessage(role = it.role.name.lowercase(), content = it.content) }

        val assistant = Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true
        )
        conversationRepository.addMessage(assistant)
        emit(AgentMessageState.AssistantMessageStarted(assistant.id))

        val full = StringBuilder()
        adapter.sendChatStream(
            context,
            AiRequestConfig(
                model = config.modelName,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                systemPrompt = buildAgentSystemPrompt(currentDocumentName, currentDocumentContent)
            )
        ).collect { event ->
            when (event) {
                is StreamEvent.Content -> {
                    full.append(event.text)
                    conversationRepository.updateMessage(
                        assistant.copy(content = full.toString(), isStreaming = true)
                    )
                    emit(AgentMessageState.Streaming(event.text))
                }
                is StreamEvent.Done -> {
                    val text = event.fullText.ifBlank { full.toString() }
                    val action = parseAgentAction(text, currentDocumentId)
                    val finished = assistant.copy(
                        content = text,
                        isStreaming = false,
                        agentAction = action
                    )
                    conversationRepository.updateMessage(finished)
                    if (action != null) {
                        emit(AgentMessageState.ActionProposed(assistant.id, action))
                    }
                    emit(AgentMessageState.Completed(text))
                }
                is StreamEvent.Error -> {
                    if (full.isBlank()) {
                        conversationRepository.deleteMessage(assistant.id)
                    } else {
                        conversationRepository.updateMessage(
                            assistant.copy(content = full.toString(), isStreaming = false)
                        )
                    }
                    emit(AgentMessageState.Error(event.message))
                }
            }
        }
    }.flowOn(Dispatchers.IO)
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
    suspend operator fun invoke(message: Message, action: AgentAction): Result<String> = runCatching {
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
                saveDocumentUseCase(doc.copy(content = action.content)).getOrThrow()
                targetId
            }
        }
        conversationRepository.updateMessage(
            message.copy(agentAction = action.copy(status = AgentActionStatus.EXECUTED))
        )
        documentId
    }
}

/** 构建 Agent 系统提示，注入当前文档上下文。 */
internal fun buildAgentSystemPrompt(documentName: String?, documentContent: String?): String {
    val docContext = if (documentName != null) {
        """
        当前打开的文档:
        - 文件名: $documentName
        - 内容预览: ${documentContent?.take(200) ?: "(空)"}
        """.trimIndent()
    } else {
        "当前没有打开的文档。"
    }

    return """
        你是一个 Markdown 文档编辑助手。你可以执行以下操作：
        1. CREATE_DOCUMENT - 创建新的 Markdown 文档
        2. EDIT_DOCUMENT - 编辑当前打开的文档

        $docContext

        当用户请求创建或修改文档时，先用一段话说明你的方案，然后在回复末尾严格按以下格式追加操作块（仅在确实需要操作文档时才追加）：

        [[ACTION]]
        type: CREATE_DOCUMENT 或 EDIT_DOCUMENT
        description: 一句话描述你做了什么
        [[CONTENT]]
        在这里写文档的完整 Markdown 内容
        [[/ACTION]]

        注意：
        - 内容部分必须是完整可用的 Markdown，不要省略。
        - 如果只是普通对话、不需要操作文档，则不要输出 [[ACTION]] 块。
        - 一次回复最多包含一个操作块。
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
