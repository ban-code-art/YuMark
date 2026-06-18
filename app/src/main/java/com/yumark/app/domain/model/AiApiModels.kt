package com.yumark.app.domain.model

/** 流式响应事件 */
sealed class StreamEvent {
    /** 一个内容增量块 */
    data class Content(val text: String) : StreamEvent()

    /** 工具调用增量（仅供 UI 粗提示；循环逻辑依赖 [ToolCallComplete]） */
    data class ToolCallDelta(
        val callId: String,
        val name: String?,
        val argumentsDelta: String?
    ) : StreamEvent()

    /** 本轮工具调用已拼装完成（可能并行多个）；ReAct 循环据此触发执行 */
    data class ToolCallComplete(val calls: List<ToolCall>) : StreamEvent()

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
    val role: String,                    // "user", "assistant", "tool", "system"
    val content: String?,
    val toolCalls: List<ToolCall>? = null,  // assistant发起的工具调用
    val toolCallId: String? = null,         // tool角色响应关联的调用ID
    val contentParts: List<MessageContent> = emptyList()  // 非空即走多模态分支（content 退居纯文本兼容路径）
)

/** 单次请求的参数 */
data class AiRequestConfig(
    val model: String,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val systemPrompt: String? = null
)
