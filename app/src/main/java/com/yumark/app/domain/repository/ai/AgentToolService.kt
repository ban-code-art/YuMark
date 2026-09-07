package com.yumark.app.domain.repository.ai

import com.yumark.app.domain.model.ToolCall

/**
 * Agent 外围能力的执行边界：联网搜索、记忆、知识检索（RAG）。
 *
 * 三个 data 服务（WebSearchService / MemoryService / RagPipeline）都实现了
 * 「按 ToolCall 执行并返回 Result<String>」的统一形态——domain 用本接口聚合它们，
 * Agent 循环的构造参数从三个具体服务收拢为一个端口（同时消除对 data 的直接依赖）。
 */
interface AgentToolService {
    /** 执行一个外围工具调用；结果文本回喂模型，失败由实现折为 ERROR 消息的语义由调用方处理。 */
    suspend fun execute(toolCall: ToolCall): Result<String>
}
