package com.yumark.app.domain.usecase.ai.agent

import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.UserAction
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.repository.ai.AiAdapterProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val COMPRESS_TIMEOUT_MS = 30_000L

/**
 * Agent 对话压缩器（专项①）：把待压缩的历史回合组摘要为一条注入型消息。
 *
 * **与裁剪的关系**：[AgentContextTrimmer] 解决"不撞爆上下文"（有损裁剪），
 * 本类解决"记得"——多任务长会话里被裁掉的回合保留语义级结论与偏好。
 * 两者互补：压缩成功则注入摘要 + 保留尾部；失败静默回退纯裁剪，绝不阻塞对话。
 *
 * **成本护栏**（设计文档约定）：
 * - 低档参数（temperature=0.2 / maxTokens=1024）与主对话解耦；
 * - 调用方负责"每会话最多 N 次"计数（见 SendAgentMessageUseCase 的压缩记账）；
 * - 30s 超时；任何失败返回 null，由调用方回退。
 */
class ConversationCompressor @Inject constructor(
    private val adapterProvider: AiAdapterProvider
) {
    /**
     * 压缩一组消息为摘要文本；失败/超时/空产出返回 null（调用方回退纯裁剪）。
     */
    suspend fun compress(
        messages: List<ChatMessage>,
        config: com.yumark.app.domain.model.AiConfig
    ): String? = runCatching {
        if (messages.isEmpty()) return null
        val transcript = messages.joinToString("\n\n") { message ->
            "[${message.role}] ${message.content.orEmpty()}"
        }
        val request = buildList {
            add(ChatMessage(role = "user", content = COMPRESS_PROMPT))
            add(ChatMessage(role = "user", content = transcript))
        }
        val summary = withTimeoutOrNull(COMPRESS_TIMEOUT_MS) {
            adapterProvider.chatAdapter(config).sendChatStream(
                request,
                // 低档参数：压缩是后台任务，与主对话的温度/预算解耦（成本护栏）
                AiRequestConfig(
                    model = config.modelName,
                    temperature = LOW_TEMPERATURE,
                    maxTokens = LOW_MAX_TOKENS
                ),
                tools = emptyList()
            )
                // 只收正文事件；Done/截断照常，Error 直接向上抛给 runCatching
                .onCompletion { if (it != null) throw it }
                .lastOrNullContent()
        } ?: return null
        summary.trim().ifBlank { null }
    }.fold(
        onSuccess = { it },
        onFailure = { e ->
            // 压缩失败不影响对话：留一条非致命记录供排查（摘要服务不可见时无从知晓）
            ErrorHandler.report(e, UserAction.COMPRESS_HISTORY)
            null
        }
    )

    /** 只收集 Content 事件的增量拼接（Done/Error 各归其主：Done 截断照常，Error 上抛）。 */
    private suspend fun kotlinx.coroutines.flow.Flow<com.yumark.app.domain.model.StreamEvent>
        .lastOrNullContent(): String? {
        var text: String? = null
        collect { event ->
            if (event is com.yumark.app.domain.model.StreamEvent.Content) {
                text = (text.orEmpty()) + event.text
            }
        }
        return text
    }

    private companion object {
        private const val LOW_TEMPERATURE = 0.2f
        private const val LOW_MAX_TOKENS = 1024

        private val COMPRESS_PROMPT = """
            你是对话摘要器。把下面这段 AI 助手与用户的历史对话压缩为一份给「后续对话」用的
            备忘摘要。只依据给定内容，不得编造；无法确定的一律略过。保留：
            1. 每个已完成任务的结论（做了什么、结果是什么）
            2. 用户表达的持久偏好与约束
            3. 未完成事项与阻塞原因
            4. 关键文档/文件夹的名称与 ID（供工具调用引用）
            直接输出摘要正文（不要任何前后缀、不要重述本指令），控制在 600 字以内。
        """.trimIndent()
    }
}
