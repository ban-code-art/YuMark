package com.yumark.app.domain.usecase

import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.TrashedDocument
import com.yumark.app.domain.model.withFreshCounts
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.FolderRepository
import com.yumark.app.domain.repository.SettingsRepository
import com.yumark.app.domain.model.SearchResult
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow
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
        // 计数算法在 core/text，编辑器落历史版本快照时要用同一套：见 [WordCount]
        return repo.saveDocument(document.withFreshCounts())
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

/** 「删除文档」= 移入回收站（schema 14 起）。彻底删除只能从回收站页显式发起。 */
class DeleteDocumentUseCase @Inject constructor(
    private val repo: DocumentRepository
) {
    suspend operator fun invoke(id: String): Result<Unit> = repo.moveToTrash(id)
}

class RestoreFromTrashUseCase @Inject constructor(
    private val repo: DocumentRepository
) {
    suspend operator fun invoke(id: String): Result<Unit> = repo.restoreFromTrash(id)
}

class PurgeDocumentUseCase @Inject constructor(
    private val repo: DocumentRepository
) {
    suspend operator fun invoke(id: String): Result<Unit> = repo.purgeDocument(id)
}

class EmptyTrashUseCase @Inject constructor(
    private val repo: DocumentRepository
) {
    suspend operator fun invoke(): Result<Unit> = repo.emptyTrash()
}

class LoadTrashedDocumentsUseCase @Inject constructor(
    private val repo: DocumentRepository
) {
    suspend operator fun invoke(): Result<List<TrashedDocument>> = repo.getTrashedDocuments()
}

/**
 * 回收站到期自动清理（默认保留 30 天，见 [TRASH_RETENTION_MS]）。
 *
 * 只由「回收站页打开」与「文件列表加载」两条路径顺手触发：查的是同一张表上一个
 * 带时间下界的 SELECT，多数时候返回空，不值得为它引入后台任务调度。
 */
class PurgeExpiredTrashUseCase @Inject constructor(
    private val repo: DocumentRepository
) {
    suspend operator fun invoke(nowMs: Long = System.currentTimeMillis()): Result<Int> =
        repo.purgeTrashExpired(nowMs, TRASH_RETENTION_MS)

    companion object {
        /** 回收站保留期：移入 30 天后自动彻底删除。 */
        const val TRASH_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
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
