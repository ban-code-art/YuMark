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

        return formatDocumentForTool(doc.name, doc.folderId, content)
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

        val tokens = SearchRanker.tokenize(query)
        if (tokens.isEmpty()) return "搜索词为空。"

        val allDocs = documentRepository.getAllDocuments().getOrElse {
            throw IllegalArgumentException("无法获取所有文档")
        }

        data class Ranked(
            val doc: com.yumark.app.domain.model.Document,
            val score: Int,
            val snippets: List<Pair<Int, String>>
        )

        val ranked = allDocs.mapNotNull { doc ->
            val content = fileManager.loadDocumentContent(doc.id).getOrElse { "" }
            val s = SearchRanker.score(doc.name, content, tokens)
            if (s <= 0) null
            else Ranked(doc, s, SearchRanker.snippets(content, tokens, maxResults))
        }.sortedByDescending { it.score }.take(10)

        return if (ranked.isEmpty()) {
            "未找到与\"$query\"相关的文档。"
        } else {
            "搜索结果（关键词：\"$query\"，按相关度排序）：\n\n" + ranked.joinToString("\n\n") { r ->
                "【${r.doc.name}】(相关度 ${r.score})\n" + r.snippets.joinToString("\n") { (index, line) ->
                    "  第${index + 1}行: ${line.trim()}"
                }
            }
        }
    }
}

/**
 * `read_document` 交给模型的返回体：三行表头 + **原封不动**的正文。
 *
 * 不要改回 `"""…" + "$content…""".trimIndent()` 那种写法。`trimIndent()` 作用在**插值之后**的
 * 整串上，取的是所有非空行的最小公共缩进，于是正文的形状反过来决定模板会被怎么裁：
 * - 正文多行且有任意一行顶格（`# 标题` 开头的文档就是）→ 最小缩进 0，`trimIndent()` 一个字符
 *   都不裁，模板那几行的 12 个空格全部留下，正文**第一行**还额外顶着这 12 个空格。
 *   在 Markdown 里 4 个以上前导空格就是缩进代码块，模型看到的第一行不再是标题。
 * - 正文每行都缩进（整篇是缩进代码块、或深层嵌套列表）→ 最小缩进落在正文自己身上，
 *   `trimIndent()` 把这份公共缩进从**正文**上剥掉，代码块直接不再是代码块。
 *
 * 两种情况都是同一个后果：模型读到的正文与磁盘上的不是同一份。而模型正是照这份正文提
 * `edit_document` 的 `old_string`——原文错一个空格，外科式编辑就永远定位不到，用户看到的是
 * 反复「未命中」。
 */
internal fun formatDocumentForTool(name: String, folderId: String?, content: String): String =
    buildString {
        append("【文档名称】").append(name).append('\n')
        append("【文档路径】").append(folderId ?: "根目录").append('\n')
        append("【文档内容】\n")
        append(content)
    }
