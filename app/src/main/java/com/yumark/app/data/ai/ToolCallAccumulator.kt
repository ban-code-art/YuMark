package com.yumark.app.data.ai

import com.yumark.app.domain.model.ToolCall

/**
 * OpenAI 流式工具调用累积器：`tool_calls` 按 `index` 跨 SSE 分片增量拼接，
 * 完成后产出完整 [ToolCall] 列表。纯逻辑、不依赖 JSON 库，便于单测。
 *
 * 用法：适配器从每个 SSE delta 的 `tool_calls[]` 提取 index/id/name/arguments 片段喂 [accept]，
 * 在 `finish_reason == "tool_calls"` 时调 [build]。
 */
class OpenAiToolCallAccumulator {

    private class Builder {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()
    }

    // LinkedHashMap 保插入顺序：build 输出顺序与工具调用出现顺序一致
    private val byIndex = linkedMapOf<Int, Builder>()

    fun accept(index: Int, id: String?, name: String?, argumentsChunk: String?) {
        val b = byIndex.getOrPut(index) { Builder() }
        if (id != null) b.id = id
        if (name != null) b.name = name
        if (argumentsChunk != null) b.args.append(argumentsChunk)
    }

    fun isEmpty(): Boolean = byIndex.isEmpty()

    fun build(): List<ToolCall> = byIndex.values.map {
        ToolCall(id = it.id ?: "", name = it.name ?: "", arguments = it.args.toString())
    }
}
