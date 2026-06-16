package com.yumark.app.domain.usecase.ai.chat

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
    data class Error(val message: String) : ChatMessageState()
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
            emit(ChatMessageState.Error("请先在设置中配置 API Key 和模型"))
            return@flow
        }
        val adapter = adapterFactory.createAdapter(config)

        // 3. 构建上下文（含刚保存的用户消息，排除流式占位消息）
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
                is StreamEvent.Done -> {
                    val text = event.fullText.ifBlank { full.toString() }
                    conversationRepository.updateMessage(
                        assistant.copy(content = text, isStreaming = false)
                    )
                    emit(ChatMessageState.Completed(text))
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
