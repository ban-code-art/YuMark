package com.yumark.app.domain.model

/** 流式响应事件 */
sealed class StreamEvent {
    /** 一个内容增量块 */
    data class Content(val text: String) : StreamEvent()

    /** 流式结束，附带完整文本 */
    data class Done(val fullText: String) : StreamEvent()

    /** 发生错误 */
    data class Error(val message: String) : StreamEvent()
}

/** 连接测试结果（含性能指标，帮助用户诊断） */
data class ModelTestResult(
    val success: Boolean,
    val responseTime: Long,        // 总耗时（ms）
    val firstTokenLatency: Long,   // 首 token 延迟（ms）
    val streamingWorks: Boolean,
    val errorMessage: String? = null
)

/** 模型信息 */
data class ModelInfo(
    val id: String,
    val name: String,
    val contextWindow: Int? = null,
    val description: String? = null
)

/** 发送给适配器的标准化消息 */
data class ChatMessage(
    val role: String,  // "user" | "assistant" | "system"
    val content: String
)

/** 单次请求的参数 */
data class AiRequestConfig(
    val model: String,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val systemPrompt: String? = null
)
