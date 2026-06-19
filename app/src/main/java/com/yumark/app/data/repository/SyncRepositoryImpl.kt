package com.yumark.app.data.repository

import android.util.Log
import com.yumark.app.core.validation.FileNameValidator
import com.yumark.app.data.local.db.dao.SyncStateDao
import com.yumark.app.data.local.db.entity.SyncStateEntity
import com.yumark.app.data.local.prefs.SyncConfigDataStore
import com.yumark.app.data.remote.webdav.WebDavClient
import com.yumark.app.data.sync.SyncPlanner
import com.yumark.app.data.sync.SyncPlanner.SyncAction
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.SyncOutcome
import com.yumark.app.domain.model.WebDavConfig
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.SyncRepository
import com.yumark.app.domain.usecase.SaveDocumentUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV 同步仓库实现（P1）。
 *
 * 把本地库根级文档正文与远端目录双向对齐：决策交给纯函数 [SyncPlanner]，本类只做网络 IO 与落库，
 * 并复用 [SaveDocumentUseCase] 写入（统一重算字数）。单篇失败计入 failed 不中断整体。
 */
@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val configStore: SyncConfigDataStore,
    private val syncStateDao: SyncStateDao,
    private val webDavClient: WebDavClient,
    private val documentRepository: DocumentRepository,
    private val saveDocument: SaveDocumentUseCase
) : SyncRepository {

    override fun observeConfig(): Flow<WebDavConfig> = configStore.configFlow

    override suspend fun saveConfig(config: WebDavConfig) = configStore.updateConfig(config)

    override fun observeLastSyncedAt(): Flow<Long?> = configStore.lastSyncedAtFlow

    override suspend fun testConnection(config: WebDavConfig): Result<Unit> =
        webDavClient.testConnection(config)

    override suspend fun syncNow(): Result<SyncOutcome> = runCatching {
        val config = configStore.configFlow.first()
        check(config.enabled) { "未启用云端同步" }
        require(config.isValid) { "WebDAV 配置不完整" }

        webDavClient.ensureDir(config).getOrThrow()
        val remotes = webDavClient.list(config).getOrThrow()
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
        // P1：仅同步根级文档正文
        val localDocs = documentRepository.getAllDocuments().getOrThrow()
            .filter { it.folderId == null }
        val docById = localDocs.associateBy { it.id }
        val fileNameByDoc = assignFileNames(localDocs)
        val records = syncStateDao.getAll()

        val plan = SyncPlanner.plan(
            locals = localDocs.map {
                SyncPlanner.LocalDocInfo(it.id, fileNameByDoc.getValue(it.id), hash(it.content))
            },
            remotes = remotes.map { SyncPlanner.RemoteFileInfo(it.name, it.etag) },
            records = records.map {
                SyncPlanner.SyncRecordInfo(it.documentId, it.remotePath, it.remoteEtag, it.localHash)
            }
        )

        var uploaded = 0
        var downloaded = 0
        var conflicts = 0
        var skipped = 0
        var failed = 0
        val now = System.currentTimeMillis()

        for (action in plan) {
            val result = runCatching { execute(config, action, docById, now) }
            result.onSuccess {
                when (action) {
                    is SyncAction.Upload -> uploaded++
                    is SyncAction.DownloadOverwrite -> downloaded++
                    is SyncAction.CreateLocal -> downloaded++
                    is SyncAction.Conflict -> conflicts++
                    is SyncAction.Skip -> skipped++
                }
            }.onFailure { e ->
                failed++
                Log.e(TAG, "同步动作失败：$action", e)
            }
        }

        configStore.setLastSyncedAt(now)
        SyncOutcome(uploaded, downloaded, conflicts, skipped, failed)
    }

    private suspend fun execute(
        config: WebDavConfig,
        action: SyncAction,
        docById: Map<String, Document>,
        now: Long
    ) {
        when (action) {
            is SyncAction.Upload -> {
                val doc = docById.getValue(action.docId)
                val etag = webDavClient.upload(config, action.fileName, doc.content).getOrThrow()
                action.deleteOldPath?.let { old ->
                    // 删旧文件失败不致命（留孤儿，P2 清理）
                    webDavClient.delete(config, old)
                }
                syncStateDao.upsert(stateOf(doc.id, action.fileName, etag, hash(doc.content), now))
            }

            is SyncAction.DownloadOverwrite -> {
                val doc = docById.getValue(action.docId)
                val content = webDavClient.download(config, action.fileName).getOrThrow()
                saveDocument(doc.copy(content = content)).getOrThrow()
                syncStateDao.upsert(stateOf(doc.id, action.fileName, action.remoteEtag, hash(content), now))
            }

            is SyncAction.CreateLocal -> {
                val content = webDavClient.download(config, action.fileName).getOrThrow()
                val created = documentRepository
                    .createDocument(nameFromFileName(action.fileName), null).getOrThrow()
                saveDocument(created.copy(content = content)).getOrThrow()
                syncStateDao.upsert(stateOf(created.id, action.fileName, action.remoteEtag, hash(content), now))
            }

            is SyncAction.Conflict -> {
                val doc = docById.getValue(action.docId)
                // 1) 远端内容存为本地冲突副本（保留远端版本）
                val remoteContent = webDavClient.download(config, action.fileName).getOrThrow()
                val copy = documentRepository
                    .createDocument(conflictName(doc.name, now), null).getOrThrow()
                saveDocument(copy.copy(content = remoteContent)).getOrThrow()
                // 2) 本地内容上行（本地在原文件名上胜出），刷新基线
                val etag = webDavClient.upload(config, action.fileName, doc.content).getOrThrow()
                syncStateDao.upsert(stateOf(doc.id, action.fileName, etag, hash(doc.content), now))
            }

            is SyncAction.Skip -> {
                syncStateDao.upsert(stateOf(action.docId, action.fileName, action.remoteEtag, action.localHash, now))
            }
        }
    }

    private fun stateOf(docId: String, path: String, etag: String?, localHash: String?, now: Long) =
        SyncStateEntity(
            documentId = docId,
            remotePath = path,
            remoteEtag = etag,
            localHash = localHash,
            lastSyncedAt = now
        )

    /** 给每篇文档算唯一远端文件名：sanitize(name).md，重名追加短 id 后缀。 */
    private fun assignFileNames(docs: List<Document>): Map<String, String> {
        val used = HashSet<String>()
        val result = HashMap<String, String>(docs.size)
        for (doc in docs) {
            val base = FileNameValidator.sanitize(doc.name)
            var fileName = "$base.md"
            if (!used.add(fileName.lowercase())) {
                fileName = "$base-${doc.id.take(6)}.md"
                used.add(fileName.lowercase())
            }
            result[doc.id] = fileName
        }
        return result
    }

    private fun nameFromFileName(fileName: String): String =
        fileName.removeSuffix(".md").ifBlank { "未命名" }

    private fun conflictName(baseName: String, now: Long): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
        return "$baseName (冲突 $date)"
    }

    private fun hash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "YuMarkSync"
    }
}
