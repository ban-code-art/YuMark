package com.yumark.app.domain.usecase.ai.tools

import com.yumark.app.domain.model.AiTool

/** 知识库（RAG）工具集（Phase 4）。 */
object KnowledgeTools {

    val SEARCH_KNOWLEDGE = AiTool(
        name = "search_knowledge",
        description = "在本地知识库（已索引的全部文档）中做语义检索，返回与查询最相关的文档片段。" +
            "用户问及文档/笔记/资料的核心观点、提到什么、讲了什么，或需跨文档归纳时调用。" +
            "比 search_in_project 更适合语义相关问题（同义不同字也能命中）。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "检索问题或关键词"
                ),
                "top_k" to mapOf(
                    "type" to "integer",
                    "description" to "返回结果数，默认5",
                    "default" to 5
                )
            ),
            "required" to listOf("query")
        )
    )

    val KNOWLEDGE_STATS = AiTool(
        name = "knowledge_stats",
        description = "返回本地知识库的索引统计：已索引文档数、分块总数。用于判断知识库是否已建立、覆盖范围。",
        parameters = mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any>()
        )
    )

    val all: List<AiTool> = listOf(SEARCH_KNOWLEDGE, KNOWLEDGE_STATS)
}
