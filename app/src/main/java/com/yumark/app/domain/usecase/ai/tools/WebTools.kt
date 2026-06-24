package com.yumark.app.domain.usecase.ai.tools

import com.yumark.app.domain.model.AiTool

/** 网络搜索工具集（Phase 2）。 */
object WebTools {

    val WEB_SEARCH = AiTool(
        name = "web_search",
        description = "联网搜索网络上的最新信息、新闻、文档或事实。当问题需要超出你训练数据时效的外部信息、" +
            "或需要确认最新动态时使用。返回若干来源的标题、摘要与链接。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "搜索关键词或短语"
                ),
                "max_results" to mapOf(
                    "type" to "integer",
                    "description" to "返回结果数，默认5",
                    "default" to 5
                )
            ),
            "required" to listOf("query")
        )
    )

    val all: List<AiTool> = listOf(WEB_SEARCH)
}
