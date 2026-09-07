package com.yumark.app.data.ai

import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.repository.ai.AgentToolService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆 + 知识检索的聚合端口实现：Agent 构造只想要一个「记忆与知识」依赖，
 * 实际背后是两个服务——按工具名分发，失败的语义与单服务完全一致。
 */
@Singleton
class MemoryKnowledgeDispatcher @Inject constructor(
    private val memoryService: com.yumark.app.data.ai.memory.MemoryService,
    private val ragPipeline: com.yumark.app.data.ai.rag.RagPipeline
) : AgentToolService {

    override suspend fun execute(toolCall: ToolCall): Result<String> =
        if (toolCall.name in MEMORY_TOOLS) memoryService.execute(toolCall)
        else ragPipeline.execute(toolCall)

    private companion object {
        val MEMORY_TOOLS = setOf("save_memory", "search_memory", "list_memories")
    }
}
