package com.yumark.app.domain.model

import com.yumark.app.core.util.UiMessage

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

    /**
     * 流式结束，附带完整文本。
     *
     * [truncated] 表示这段文本是被输出上限**截断**的（服务端 stop reason 为
     * OpenAI `length` / Claude `max_tokens` / Gemini `MAX_TOKENS`，归一化见
     * [com.yumark.app.data.ai.isTruncatedStopReason]），而不是模型自己写完了。
     *
     * 这一位必须传到上层：Agent 的隐式写入路径会把「看起来像文档」的正文整篇覆盖到
     * 目标文档上（AgentUseCases 的 EDIT_DOCUMENT 分支），而一段被砍掉后半截的正文
     * 同样满足那个判定。截断不上报的话，用户看到的是一次「AI 帮我改好了」，
     * 实际是文档后半部分被静默删掉——历史版本能救回来，但没人知道该去救。
     *
     * 默认 false：适配器之外的构造点（连接测试等）无需关心。
     */
    data class Done(val fullText: String, val truncated: Boolean = false) : StreamEvent()

    /**
     * 发生错误。
     *
     * [message] 是 [UiMessage] 而不是 String：产出侧在 `data/ai`（[com.yumark.app.core.util.AiErrorMapper]），
     * 那里拿不到 Context，解析要等到界面层。这条文案**只走界面**，不进 Room——
     * 会话历史存的是累积的正文，见 AgentUseCases / SendChatMessageUseCase 的 Done 分支。
     */
    data class Error(val message: UiMessage) : StreamEvent()
}

/** 连接测试结果（含性能指标，帮助用户诊断） */
data class ModelTestResult(
    val success: Boolean,
    val responseTime: Long,        // 总耗时（ms）
    val firstTokenLatency: Long,   // 首 token 延迟（ms）
    val streamingWorks: Boolean,
    val errorMessage: UiMessage? = null
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
    /**
     * `role == "tool"` 时，这条结果对应哪个工具的名字。
     *
     * OpenAI / Claude 只按 id 配对，用不到它；**Gemini 的 `functionResponse` 必须带 `name`**，
     * 而且必须等于 functionDeclarations 里声明的名字。从前 GeminiAdapter 只能从 id 里反解
     * （`toolCallId.substringBefore('#')`），那只在「Gemini 没返回 id、适配器自己拼了
     * `name#index`」时凑巧成立；新模型做并行调用时会真的下发 id，反解就把整串 id 当成了
     * 函数名。构造 tool 消息的地方本来就握着 `ToolCall.name`，直接带过来即可。
     */
    val toolName: String? = null,
    val contentParts: List<MessageContent> = emptyList()  // 非空即走多模态分支（content 退居纯文本兼容路径）
)

/** 单次请求的参数 */
data class AiRequestConfig(
    val model: String,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val systemPrompt: String? = null
)
