package com.yumark.app.domain.usecase.ai.chat

import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
import com.yumark.app.domain.model.StreamEvent
import com.yumark.app.domain.repository.AiConfigRepository
import com.yumark.app.domain.repository.ConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

sealed class ChatMessageState {
    object UserMessageSaved : ChatMessageState()
    data class AssistantMessageStarted(val messageId: String) : ChatMessageState()
    data class Streaming(val text: String) : ChatMessageState()
    data class Completed(val fullText: String) : ChatMessageState()
    /**
     * 一条与正文并行的提示，不代表失败。
     *
     * 目前唯一的来源是「回复被输出上限截断」：那段半截正文照样要留在气泡里
     * （用户可能只想要前半段），但必须让人知道它为什么停在句子中间，
     * 否则用户只会以为模型答得莫名其妙。Agent 侧早就有同名通道
     * （`AgentMessageState.Notice`），聊天侧从前没有，于是截断在这条路上完全静默。
     */
    data class Notice(val message: UiMessage) : ChatMessageState()
    /** [UiMessage] 而不是 String：`domain` 拿不到 Context，解析在界面层。 */
    data class Error(val message: UiMessage) : ChatMessageState()
}

/**
 * 普通聊天：保存用户消息 → 构建上下文 → 流式请求 → 实时更新 AI 消息。
 */
class SendChatMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val configRepository: AiConfigRepository,
    private val adapterFactory: AiAdapterFactory
) {
    operator fun invoke(
        conversationId: String,
        userMessage: String
    ): Flow<ChatMessageState> = flow {
        // 1. 保存用户消息
        conversationRepository.addMessage(
            Message(conversationId = conversationId, role = MessageRole.USER, content = userMessage)
        )
        emit(ChatMessageState.UserMessageSaved)

        // 2. 配置与适配器
        val config = configRepository.observeConfig().first()
        if (config.apiKey.isBlank() || config.modelName.isBlank()) {
            emit(ChatMessageState.Error(UiMessage.Res(R.string.ai_error_not_configured)))
            return@flow
        }
        val adapter = adapterFactory.createAdapter(config)

        // 3. 构建上下文（含刚保存的用户消息，排除流式占位消息）
        // 真正把占位消息挡掉的是 `content.isNotBlank()`：`isStreaming` 没有落库（messages 表无此列），
        // 从库里读回来永远是 false。删掉正文那一条判断，空占位就会被发给模型。
        val context = conversationRepository.observeConversation(conversationId).first()
            ?.messages.orEmpty()
            .filter { !it.isStreaming && it.content.isNotBlank() }
            .map { ChatMessage(role = it.role.name.lowercase(), content = it.content) }

        // 4. 创建 AI 占位消息
        val assistant = Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true
        )
        conversationRepository.addMessage(assistant)
        emit(ChatMessageState.AssistantMessageStarted(assistant.id))

        // 5. 流式响应
        val full = StringBuilder()
        adapter.sendChatStream(
            context,
            AiRequestConfig(
                model = config.modelName,
                temperature = config.temperature,
                maxTokens = config.maxTokens
            )
        ).collect { event ->
            when (event) {
                is StreamEvent.Content -> {
                    full.append(event.text)
                    conversationRepository.updateMessage(
                        assistant.copy(content = full.toString(), isStreaming = true)
                    )
                    emit(ChatMessageState.Streaming(event.text))
                }
                is StreamEvent.ToolCallDelta -> Unit  // Chat模式暂不使用工具调用
                is StreamEvent.ToolCallComplete -> Unit
                is StreamEvent.Done -> {
                    val text = event.fullText.ifBlank { full.toString() }
                    // 空正文的 Done 是真实结果，不是异常：适配层把「重试若干次仍是空补全」也收敛成
                    // Done("")（OpenAiAdapter.runAttempt 末尾；Claude/Gemini 连空重试都没有）。
                    // 照常落库的话用户得到一个永久空气泡，既没有正文也没有任何错误提示，
                    // 只能自己猜是不是模型选错了 —— 按错误处理，并且把占位气泡删掉。
                    if (text.isBlank()) {
                        conversationRepository.deleteMessage(assistant.id)
                        emit(ChatMessageState.Error(UiMessage.Res(R.string.ai_error_empty_response)))
                    } else {
                        conversationRepository.updateMessage(
                            assistant.copy(content = text, isStreaming = false)
                        )
                        // 截断提示在 Completed 之前发：Completed 会把 isStreaming 复位，
                        // 界面据此收起「停止」按钮，提示排在后面容易被当成下一轮的事。
                        if (event.truncated) {
                            emit(ChatMessageState.Notice(UiMessage.Res(R.string.ai_notice_response_truncated)))
                        }
                        emit(ChatMessageState.Completed(text))
                    }
                }
                is StreamEvent.Error -> {
                    // 占位消息为空则删除，避免留下空气泡
                    if (full.isBlank()) {
                        conversationRepository.deleteMessage(assistant.id)
                    } else {
                        conversationRepository.updateMessage(
                            assistant.copy(content = full.toString(), isStreaming = false)
                        )
                    }
                    emit(ChatMessageState.Error(event.message))
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
