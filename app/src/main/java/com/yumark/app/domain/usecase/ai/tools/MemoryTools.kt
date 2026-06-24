package com.yumark.app.domain.usecase.ai.tools

import com.yumark.app.domain.model.AiTool

/** 记忆系统工具集（Phase 3）。 */
object MemoryTools {

    val SAVE_MEMORY = AiTool(
        name = "save_memory",
        description = "把一条用户明确要求记住的信息存入长期记忆（偏好、项目、学习、画像、指令）。" +
            "用户说\"记住/记下来/保存记忆\"时调用。相似已有记忆会自动合并更新，无需你判断重复。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "content" to mapOf(
                    "type" to "string",
                    "description" to "要记住的信息内容（简明、可复用）"
                ),
                "category" to mapOf(
                    "type" to "string",
                    "description" to "记忆分类",
                    "enum" to listOf("preference", "project", "learning", "profile", "instruction")
                )
            ),
            "required" to listOf("content", "category")
        )
    )

    val SEARCH_MEMORY = AiTool(
        name = "search_memory",
        description = "在长期记忆中检索与查询相关的条目。用户询问\"我记得什么/之前说过/我的偏好\"时调用，" +
            "避免凭空回答用户已有的事实。按分类优先级与相关度排序返回。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "检索关键词或问题"
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

    val LIST_MEMORIES = AiTool(
        name = "list_memories",
        description = "列出长期记忆库中的条目（分页），用于概览已记住的信息。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "limit" to mapOf("type" to "integer", "description" to "返回数量，默认20", "default" to 20),
                "offset" to mapOf("type" to "integer", "description" to "偏移量，默认0", "default" to 0)
            )
        )
    )

    val all: List<AiTool> = listOf(SAVE_MEMORY, SEARCH_MEMORY, LIST_MEMORIES)
}
