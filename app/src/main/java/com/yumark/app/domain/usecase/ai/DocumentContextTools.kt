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

    /**
     * 获取所有可用工具
     */
    fun getAllTools(): List<AiTool> = listOf(
        READ_DOCUMENT,
        LIST_DOCUMENTS,
        SEARCH_IN_PROJECT
    )
}
