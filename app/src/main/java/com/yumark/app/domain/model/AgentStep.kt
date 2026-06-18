package com.yumark.app.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Agent 单个执行步骤。第二波为内存态；第三波 P3.1 起随消息持久化（[Message.steps]）。
 * 用于展示"调了哪个工具、结果如何"。
 */
@Serializable
sealed interface AgentStep {
    /** 正在调用工具。[argsSummary] 为参数摘要（截断）。 */
    @Serializable
    @SerialName("tool_calling")
    data class ToolCalling(val tool: String, val argsSummary: String) : AgentStep

    /** 工具调用完成。[ok] 标记是否成功，[summary] 为结果摘要（截断）。 */
    @Serializable
    @SerialName("tool_done")
    data class ToolDone(val tool: String, val ok: Boolean, val summary: String) : AgentStep
}
