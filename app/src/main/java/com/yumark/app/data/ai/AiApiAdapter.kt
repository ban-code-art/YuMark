package com.yumark.app.data.ai

import com.yumark.app.core.util.AppError
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.StreamEvent
import kotlinx.coroutines.flow.Flow

/**
 * 兼容别名：接口本体已上移到 domain（依赖倒置，见 domain/repository/ai/AiApiAdapter.kt）。
 * 保留此别名使 data 层既有引用零改动；新代码一律 import domain 路径。
 */
typealias AiApiAdapter = com.yumark.app.domain.repository.ai.AiApiAdapter

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
