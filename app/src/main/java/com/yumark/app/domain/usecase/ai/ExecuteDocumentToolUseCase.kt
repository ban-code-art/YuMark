package com.yumark.app.domain.usecase.ai

import com.yumark.app.data.local.file.FileManager
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.repository.DocumentRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 执行AI文档工具调用
 */
@Singleton
class ExecuteDocumentToolUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val fileManager: FileManager
) {
    suspend operator fun invoke(toolCall: ToolCall): Result<String> = runCatching {
        val args = Json.decodeFromString<Map<String, JsonElement>>(toolCall.arguments)

        when (toolCall.name) {
            "read_document" -> executeReadDocument(args)
            "list_documents" -> executeListDocuments(args)
            "search_in_project" -> executeSearchInProject(args)
            else -> throw IllegalArgumentException("未知工具: ${toolCall.name}")
        }
    }

    private suspend fun executeReadDocument(args: Map<String, JsonElement>): String {
        val docId = args["document_id"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数: document_id")

        val doc = documentRepository.getDocumentById(docId).getOrElse {
            throw IllegalArgumentException("文档不存在: $docId")
        }

        val content = fileManager.loadDocumentContent(doc.id).getOrElse {
            throw IllegalArgumentException("无法读取文档内容: $docId")
        }

        return """
            【文档名称】${doc.name}
            【文档路径】${doc.folderId ?: "根目录"}
            【文档内容】
            $content
        """.trimIndent()
    }

    private suspend fun executeListDocuments(args: Map<String, JsonElement>): String {
        val folderId = args["folder_id"]?.jsonPrimitive?.content

        val docs = if (folderId != null) {
            documentRepository.getDocumentsByFolder(folderId).getOrElse {
                throw IllegalArgumentException("无法获取文件夹文档: $folderId")
            }
        } else {
            documentRepository.getAllDocuments().getOrElse {
                throw IllegalArgumentException("无法获取所有文档")
            }
        }

        return if (docs.isEmpty()) {
            "项目中暂无文档。"
        } else {
            "项目文档列表（共${docs.size}个）：\n" + docs.joinToString("\n") { doc ->
                "- 【${doc.name}】ID: ${doc.id}, 路径: ${doc.folderId ?: "根目录"}"
            }
        }
    }

    private suspend fun executeSearchInProject(args: Map<String, JsonElement>): String {
        val query = args["query"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数: query")
        val maxResults = args["max_results"]?.jsonPrimitive?.intOrNull ?: 5

        val allDocs = documentRepository.getAllDocuments().getOrElse {
            throw IllegalArgumentException("无法获取所有文档")
        }
        val results = mutableListOf<Pair<com.yumark.app.domain.model.Document, List<Pair<Int, String>>>>()

        for (doc in allDocs) {
            val content = fileManager.loadDocumentContent(doc.id).getOrElse { "" }
            val lines = content.lines()
            val matches = lines.withIndex()
                .filter { it.value.contains(query, ignoreCase = true) }
                .take(maxResults)
                .map { it.index to it.value }
                .toList()

            if (matches.isNotEmpty()) {
                results.add(doc to matches)
            }
        }

        return if (results.isEmpty()) {
            "未找到包含\"$query\"的文档。"
        } else {
            "搜索结果（关键词：\"$query\"）：\n\n" + results.joinToString("\n\n") { (doc, matches) ->
                "【${doc.name}】\n" + matches.joinToString("\n") { (index, line) ->
                    "  第${index + 1}行: ${line.trim()}"
                }
            }
        }
    }
}
