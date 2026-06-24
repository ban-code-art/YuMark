package com.yumark.app.domain.usecase.ai

import com.yumark.app.domain.model.AiTool
import com.yumark.app.domain.usecase.ai.tools.KnowledgeTools
import com.yumark.app.domain.usecase.ai.tools.MemoryTools
import com.yumark.app.domain.usecase.ai.tools.WebTools

/**
 * AI文档上下文工具定义
 * 提供三个工具：读取文档、列出文档、搜索项目
 */
object DocumentContextTools {

    val READ_DOCUMENT = AiTool(
        name = "read_document",
        description = "读取指定文档的完整内容。用于分析、引用或理解项目中的具体文档。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "document_id" to mapOf(
                    "type" to "string",
                    "description" to "文档ID（可通过list_documents获取）"
                )
            ),
            "required" to listOf("document_id")
        )
    )

    val LIST_DOCUMENTS = AiTool(
        name = "list_documents",
        description = "列出项目中所有文档的基本信息（ID、名称、路径、文件夹）。用于了解项目结构。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "folder_id" to mapOf(
                    "type" to "string",
                    "description" to "可选，限定到指定文件夹内的文档"
                )
            )
        )
    )

    val SEARCH_IN_PROJECT = AiTool(
        name = "search_in_project",
        description = "在项目所有文档中搜索关键词，返回匹配的文档片段和位置。用于查找相关内容。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "搜索关键词或短语"
                ),
                "max_results" to mapOf(
                    "type" to "integer",
                    "description" to "每个文档最多返回的匹配结果数，默认5",
                    "default" to 5
                )
            ),
            "required" to listOf("query")
        )
    )

    val CREATE_DOCUMENT = AiTool(
        name = "create_document",
        description = "创建一篇新的 Markdown 文档。仅在用户确实需要新建文档时调用；" +
            "调用后会向用户展示待创建内容并请其确认，无需你再追加 [[ACTION]] 文本块。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "title" to mapOf(
                    "type" to "string",
                    "description" to "文档标题；留空则自动命名"
                ),
                "content" to mapOf(
                    "type" to "string",
                    "description" to "完整可用的 Markdown 正文（不要省略）"
                )
            ),
            "required" to listOf("content")
        )
    )

    val EDIT_DOCUMENT = AiTool(
        name = "edit_document",
        description = "对某文档做**外科式局部编辑**：用一组 old_string→new_string 精确替换片段，" +
            "而不是整篇重写。改动越小越好；不要重复原文未改动的大段内容。" +
            "调用后用户会逐块审阅 diff 并确认。修改前建议先用 read_document 获取确切原文。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "document_id" to mapOf(
                    "type" to "string",
                    "description" to "目标文档 ID；省略则默认当前打开的文档"
                ),
                "edits" to mapOf(
                    "type" to "array",
                    "description" to "按顺序应用的编辑列表",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "old_string" to mapOf(
                                "type" to "string",
                                "description" to "要被替换的原文片段，需与文档完全一致且能唯一定位（含足够上下文）"
                            ),
                            "new_string" to mapOf(
                                "type" to "string",
                                "description" to "替换后的文本"
                            ),
                            "replace_all" to mapOf(
                                "type" to "boolean",
                                "description" to "是否替换所有命中（默认 false，要求唯一命中）",
                                "default" to false
                            )
                        ),
                        "required" to listOf("old_string", "new_string")
                    )
                )
            ),
            "required" to listOf("edits")
        )
    )

    val UPDATE_PLAN = AiTool(
        name = "update_plan",
        description = "维护当前任务的待办计划（todo）。多步任务时用它列出步骤并随进展更新状态；" +
            "简单一步问答可不调用。每次调用都会整体替换计划，请提交完整步骤列表。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "steps" to mapOf(
                    "type" to "array",
                    "description" to "完整的步骤列表",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "title" to mapOf(
                                "type" to "string",
                                "description" to "步骤标题（简短、动宾结构）"
                            ),
                            "status" to mapOf(
                                "type" to "string",
                                "description" to "步骤状态",
                                "enum" to listOf("pending", "in_progress", "done", "blocked")
                            )
                        ),
                        "required" to listOf("title", "status")
                    )
                )
            ),
            "required" to listOf("steps")
        )
    )

    /**
     * 获取所有可用工具（聚合文档工具 + 网络搜索工具 + 记忆工具）。
     * 后续 Phase 的知识库工具按同样方式并入此处聚合。
     */
    fun getAllTools(): List<AiTool> = listOf(
        READ_DOCUMENT,
        LIST_DOCUMENTS,
        SEARCH_IN_PROJECT,
        CREATE_DOCUMENT,
        EDIT_DOCUMENT,
        UPDATE_PLAN
    ) + WebTools.all + MemoryTools.all + KnowledgeTools.all
}
