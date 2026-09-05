package com.yumark.app.data.ai

import com.yumark.app.core.util.AppError
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.AiTool
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.ModelInfo
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.StreamEvent
import kotlinx.coroutines.flow.Flow

/**
 * AI 提供商统一适配器接口。
 * 上层（UseCase）只依赖此接口，不感知具体 API 协议差异。
 */
interface AiApiAdapter {
    /** 测试连接，记录首 token 延迟、总耗时、流式是否可用 */
    suspend fun testConnection(model: String): ModelTestResult

    /** 拉取可用模型列表（部分 Provider 返回预置列表） */
    suspend fun fetchAvailableModels(): List<ModelInfo>

    /** 流式对话，支持工具调用 */
    fun sendChatStream(
        messages: List<ChatMessage>,
        config: AiRequestConfig,
        tools: List<AiTool> = emptyList()  // 新增：可用工具列表
    ): Flow<StreamEvent>

    /** 释放适配器私有资源（不关闭共享 HttpClient） */
    fun close()
}

/**
 * 通用连接测试：发一条极短的流式请求，测量首 token 延迟与总耗时。
 * 各适配器的 [AiApiAdapter.testConnection] 直接委托此函数。
 */
internal suspend fun AiApiAdapter.runConnectionTest(model: String): ModelTestResult {
    val start = System.currentTimeMillis()
    var firstTokenLatency = -1L
    var gotContent = false
    return try {
        sendChatStream(
            listOf(ChatMessage(role = "user", content = "Hi")),
            AiRequestConfig(model = model, maxTokens = 16)
        ).collect { event ->
            when (event) {
                is StreamEvent.Content -> {
                    if (firstTokenLatency < 0) firstTokenLatency = System.currentTimeMillis() - start
                    gotContent = true
                }
                is StreamEvent.ToolCallDelta -> Unit  // 工具调用在连接测试中忽略
                is StreamEvent.ToolCallComplete -> Unit
                is StreamEvent.Done -> Unit
                // 用 AppError.Friendly 而不是 IllegalStateException 携带这句话：event.message
                // 已经是 AiErrorMapper 产出的中文文案，下面 catch 里靠这个标记原样透出。
                is StreamEvent.Error -> throw AppError.Friendly(event.message)
            }
        }
        ModelTestResult(
            success = true,
            responseTime = System.currentTimeMillis() - start,
            firstTokenLatency = if (firstTokenLatency < 0) 0 else firstTokenLatency,
            streamingWorks = gotContent
        )
    } catch (e: Exception) {
        ModelTestResult(
            success = false,
            responseTime = System.currentTimeMillis() - start,
            firstTokenLatency = 0,
            streamingWorks = false,
            // 不读 e.message：连接测试失败最常见的原因是地址/网络，而 Ktor 的异常消息会把
            // 完整请求 URL（Gemini 的密钥就在 ?key= 里）一起带出来，这个字段是直接显示在界面上的。
            errorMessage = ErrorHandler.message(e)
        )
    }
}
