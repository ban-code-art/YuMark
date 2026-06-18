package com.yumark.app.domain.usecase.ai

import com.yumark.app.domain.model.AiTool

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
        description = "用新内容整体替换某文档。仅在用户确需修改文档时调用；" +
            "调用后用户会逐块审阅 diff 并确认，无需你再追加 [[ACTION]] 文本块。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "document_id" to mapOf(
                    "type" to "string",
                    "description" to "目标文档 ID；省略则默认当前打开的文档"
                ),
                "new_content" to mapOf(
                    "type" to "string",
                    "description" to "替换后的完整 Markdown 正文（不要省略）"
                )
            ),
            "required" to listOf("new_content")
        )
    )

    /**
     * 获取所有可用工具
     */
    fun getAllTools(): List<AiTool> = listOf(
        READ_DOCUMENT,
        LIST_DOCUMENTS,
        SEARCH_IN_PROJECT,
        CREATE_DOCUMENT,
        EDIT_DOCUMENT
    )
}
