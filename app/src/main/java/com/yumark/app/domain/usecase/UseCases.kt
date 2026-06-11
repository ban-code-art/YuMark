package com.yumark.app.domain.usecase

import com.yumark.app.domain.model.Document
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.FolderRepository
import com.yumark.app.domain.repository.SettingsRepository
import com.yumark.app.domain.model.SearchResult
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import java.util.regex.Pattern
import javax.inject.Inject

class LoadDocumentUseCase @Inject constructor(
    private val repo: DocumentRepository
) {
    suspend operator fun invoke(id: String): Result<Document> = repo.getDocumentById(id)
    fun observe(id: String): Flow<Document?> = repo.observeDocument(id)
}

class SaveDocumentUseCase @Inject constructor(
    private val repo: DocumentRepository
) {
    suspend operator fun invoke(document: Document): Result<Unit> {
        if (document.name.isBlank()) return Result.failure(
            IllegalArgumentException("Document name cannot be empty")
        )
        val updated = document.copy(
            updatedAt = Clock.System.now(),
            wordCount = calculateWordCount(document.content),
            characterCount = document.content.length
        )
        return repo.saveDocument(updated)
    }

    private fun calculateWordCount(content: String): Int {
        // 移除 Markdown 语法符号
        val plainText = content
            .replace(Regex("```[\\s\\S]*?```"), "")        // 移除代码块
            .replace(Regex("`[^`]+`"), "")                 // 移除行内代码
            .replace(Regex("!?\\[([^]]+)]\\([^)]+\\)"), "$1")  // 保留链接文本
            .replace(Regex("[#*_~`]"), "")                 // 移除 Markdown 符号
            .trim()

        if (plainText.isEmpty()) return 0

        // 统计中文字符
        val chineseChars = plainText.count { it in '一'..'鿿' }

        // 统计英文单词（移除中文字符后再分词）
        val englishText = plainText.replace(Regex("[一-鿿]"), " ")
        val englishWords = englishText
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .size

        return chineseChars + englishWords
    }
}

class CreateDocumentUseCase @Inject constructor(
    private val repo: DocumentRepository
) {
    suspend operator fun invoke(name: String, folderId: String? = null): Result<Document> {
        if (name.isBlank()) return Result.failure(IllegalArgumentException("Name cannot be empty"))
        return repo.createDocument(name, folderId)
    }
}

class DeleteDocumentUseCase @Inject constructor(
    private val repo: DocumentRepository
) {
    suspend operator fun invoke(id: String): Result<Unit> = repo.deleteDocument(id)
}

class LoadSettingsUseCase @Inject constructor(
    private val repo: SettingsRepository
) {
    fun observe(): Flow<UserSettings> = repo.observeSettings()
    suspend operator fun invoke(): UserSettings = repo.getSettings()
}

class SearchDocumentsUseCase @Inject constructor(
    private val repo: DocumentRepository
) {
    suspend operator fun invoke(query: String): Result<List<SearchResult>> {
        if (query.isBlank()) return Result.success(emptyList())
        return repo.searchDocuments(query).map { docs ->
            docs.map { doc ->
                SearchResult(
                    document = doc,
                    matchCount = countMatches(doc.content, query),
                    snippets = extractSnippets(doc.content, query)
                )
            }.sortedByDescending { it.matchCount }
        }
    }

    private fun countMatches(content: String, query: String): Int =
        Regex(Pattern.quote(query), RegexOption.IGNORE_CASE).findAll(content).count()

    private fun extractSnippets(content: String, query: String): List<String> {
        val pattern = Regex(Pattern.quote(query), RegexOption.IGNORE_CASE)
        return pattern.findAll(content).take(MAX_SNIPPETS).map { m ->
            val s = maxOf(0, m.range.first - SNIPPET_LENGTH)
            val e = minOf(content.length, m.range.last + SNIPPET_LENGTH + 1)
            "...${content.substring(s, e)}..."
        }.toList()
    }

    companion object {
        private const val SNIPPET_LENGTH = 50
        private const val MAX_SNIPPETS = 3
    }
}

class ManageFoldersUseCase @Inject constructor(
    private val folderRepo: FolderRepository,
    private val docRepo: DocumentRepository
) {
    suspend fun createFolder(name: String, parentId: String?): Result<Folder> {
        if (name.isBlank()) return Result.failure(IllegalArgumentException("Name cannot be empty"))
        return folderRepo.createFolder(name, parentId)
    }

    suspend fun renameFolder(id: String, newName: String): Result<Unit> {
        if (newName.isBlank()) return Result.failure(IllegalArgumentException("Name cannot be empty"))
        return folderRepo.renameFolder(id, newName)
    }

    suspend fun deleteFolder(id: String, deleteContents: Boolean): Result<Unit> =
        folderRepo.deleteFolder(id, deleteContents)

    suspend fun moveFolder(id: String, targetParentId: String?): Result<Unit> {
        if (targetParentId != null && isDescendant(targetParentId, id))
            return Result.failure(IllegalArgumentException("Cannot move to descendant"))
        return folderRepo.moveFolder(id, targetParentId)
    }

    /**
     * 检查 folderId 是否是 ancestorId 的后代
     * 添加深度限制防止无限循环（数据库循环引用时）
     */
    private suspend fun isDescendant(folderId: String, ancestorId: String): Boolean {
        var current: String? = folderId
        val visited = mutableSetOf<String>()  // 循环检测
        var depth = 0

        while (current != null) {
            // 检测到循环引用
            if (current in visited) {
                throw IllegalStateException("Circular folder reference detected: $current")
            }

            // 超过最大深度限制
            if (depth > MAX_FOLDER_DEPTH) {
                throw IllegalStateException("Folder hierarchy too deep (max: $MAX_FOLDER_DEPTH)")
            }

            if (current == ancestorId) return true

            visited.add(current)
            current = folderRepo.getFolderById(current).getOrNull()?.parentId
            depth++
        }
        return false
    }

    companion object {
        private const val MAX_FOLDER_DEPTH = 100  // 最大文件夹层级
    }
}
