package com.yumark.app.domain.usecase.ai

import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.FolderRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val DEFAULT_READ_CHARS = 6000
private const val MAX_FOLDER_DEPTH = 10

/**
 * 大纲模式返回体：每条标题带它在正文中的**字符偏移**，模型可以拿偏移直接当
 * `offset` 参数精准跳读目标段落——没有偏移的分页只能盲猜，等于没有分页。
 * 偏移单位与 `offset` 参数一致（UTF-16 char），`content.substring(offset, …)` 即从标题行开始。
 */
internal fun outlineWithOffsets(content: String): String {
    val sb = StringBuilder()
    var offset = 0
    for (line in content.lineSequence()) {
        if (line.trimStart().startsWith("#")) {
            sb.append(offset).append(": ").append(line.trim()).append('\n')
        }
        offset += line.length + 1   // +1 为换行符（末行多计 1 不影响用作起点）
    }
    return sb.toString().trimEnd()
}

/**
 * 把 [start]（含）与 [endExclusive]（不含）吸附到 UTF-16 代码点边界。
 * substring 按 char 索引，截断点落在代理对中间（emoji 占 2 char）会产生孤代理——
 * 模型读到乱码，并据此提出永远失配的 edit old_string。
 */
internal fun snapToCodePointBoundary(content: String, start: Int, endExclusive: Int): Pair<Int, Int> {
    fun isLowSurrogateAt(index: Int) =
        index in content.indices && Character.isLowSurrogate(content[index])
    var s = start
    if (isLowSurrogateAt(s)) s--            // 起点落在低代理：退到高代理
    var e = endExclusive
    if (e < content.length && isLowSurrogateAt(e)) e--   // 终点落在低代理：退回完整代理对
    return s to e
}

/**
 * 文件夹 ID → 「a/b」名称链。悬空引用或超深（防环）回退为原始 ID，不给模型悬空引用。
 * 独立成纯函数：read_document 的「所在文件夹」与 list_documents 的结构节共用同一口径。
 */
private fun folderLabelOf(
    folderId: String?,
    byId: Map<String, com.yumark.app.domain.model.Folder>
): String {
    // 悬空引用/超深（防环）一律回退原始 ID；根目录与正常链都走同一条收集路径。
    var cursor: String? = folderId
    var guard = 0
    val names = ArrayDeque<String>()
    var dangling = false
    while (cursor != null && guard < MAX_FOLDER_DEPTH) {
        val folder = byId[cursor]
        if (folder == null) {
            dangling = true
            break
        }
        names.addFirst(folder.name)
        cursor = folder.parentId
        guard++
    }
    return when {
        dangling || cursor != null -> folderId ?: "根目录"
        names.isEmpty() -> "根目录"
        else -> names.joinToString("/")
    }
}

/**
 * 执行 AI 文档工具调用：read_document（大纲/分页）、list_documents（含文件夹结构）、
 * search_in_project（相关度排序）。
 *
 * 大文档导航设计：`read_document` 默认只回前 [DEFAULT_READ_CHARS] 字符并附分页指引，
 * 模型可用 `mode=outline` 廉价地看结构、用 `offset/length` 分页读目标段落——
 * 否则大文档的尾部对模型永远不可见（旧的静默截断把后半篇吞掉且不告诉模型）。
 */
@Singleton
class ExecuteDocumentToolUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val folderRepository: FolderRepository
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

        // getDocumentById 已随元数据读取正文，无需再经 FileManager 二次 IO
        val content = doc.content
        val header = "【文档名称】${doc.name}\n【所在文件夹】${resolveFolderLabel(doc.folderId)}\n"

        val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "full"
        if (mode == "outline") {
            return header +
                "【大纲（每行格式：起始字符偏移: 标题；全文共 ${content.length} 字符）】\n" +
                outlineWithOffsets(content) + "\n" +
                "（用 mode=full + offset=<标题偏移> 读取该段原文）"
        }

        val rawOffset = args["offset"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
        val length = args["length"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: DEFAULT_READ_CHARS
        // 吸附到代码点边界：截断点落在 emoji 等代理对中间会产生孤代理乱码
        val (safeOffset, rawEnd) = snapToCodePointBoundary(
            content, rawOffset.coerceAtMost(content.length), (rawOffset + length).coerceAtMost(content.length)
        )
        val window = content.substring(safeOffset, rawEnd)
        val end = safeOffset + window.length

        val paging = if (end < content.length || safeOffset > 0) {
            "【正文片段：第 ${safeOffset + 1}–$end 字符，共 ${content.length} 字符】\n" +
                (if (end < content.length) "（后续内容用 offset=$end 继续读取）\n" else "") +
                (if (safeOffset > 0) "（前文从 offset=0 开始）\n" else "")
        } else {
            ""
        }
        return header + paging + window
    }

    private suspend fun executeListDocuments(args: Map<String, JsonElement>): String {
        val folderId = args["folder_id"]?.jsonPrimitive?.contentOrNull

        val docs = if (folderId != null) {
            documentRepository.getDocumentsByFolder(folderId).getOrElse {
                throw IllegalArgumentException("无法获取文件夹文档: $folderId")
            }
        } else {
            documentRepository.getAllDocuments().getOrElse {
                throw IllegalArgumentException("无法获取所有文档")
            }
        }

        // 文件夹结构与名称解析：只给 UUID 等于让模型对库结构一无所知
        val folders = folderRepository.getAllFolders().getOrElse { emptyList() }
        val byId = folders.associateBy { it.id }
        fun folderPath(id: String?): String = folderLabelOf(id, byId)

        val folderSection = if (folders.isEmpty()) {
            "文件夹：无\n\n"
        } else {
            "文件夹结构：\n" + folders.joinToString("\n") { folder ->
                "- 【${folder.name}】ID: ${folder.id}, 路径: ${folderPath(folder.id)}"
            } + "\n\n"
        }

        return if (docs.isEmpty() && folders.isEmpty()) {
            "项目中暂无文档和文件夹。"
        } else {
            folderSection + "项目文档列表（共${docs.size}个）：\n" + docs.joinToString("\n") { doc ->
                "- 【${doc.name}】ID: ${doc.id}, 所在文件夹: ${folderPath(doc.folderId)}"
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
            val content = doc.content
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

    /** 文件夹 ID → 「a/b」名称链；找不到或超深回退为原始 ID（不给模型悬空引用）。 */
    private suspend fun resolveFolderLabel(folderId: String?): String {
        val byId = folderRepository.getAllFolders()
            .getOrElse { emptyList() }
            .associateBy { it.id }
        return folderLabelOf(folderId, byId)
    }
}
