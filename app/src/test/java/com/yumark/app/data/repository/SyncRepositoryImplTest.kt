package com.yumark.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.local.db.dao.SyncStateDao
import com.yumark.app.data.local.db.entity.SyncStateEntity
import com.yumark.app.data.local.prefs.SyncConfigDataStore
import com.yumark.app.data.remote.webdav.WebDavClient
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.RemoteEntry
import com.yumark.app.domain.model.WebDavConfig
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.usecase.SaveDocumentUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * 验证 SyncRepositoryImpl 的**执行路径**（不仅是 SyncPlanner 的决策）：
 * 上传写回 sync_state、下载覆盖本地、远端独有拉取、双边冲突生成副本。
 */
class SyncRepositoryImplTest {

    private val config = WebDavConfig(
        enabled = true, baseUrl = "https://host/dav/", username = "u", password = "p", remoteDir = "YuMark"
    )

    // --- 内存假实现 ---

    private class FakeDocRepo : DocumentRepository {
        val docs = LinkedHashMap<String, Document>()
        private var seq = 0
        override suspend fun getAllDocuments(): Result<List<Document>> = Result.success(docs.values.toList())
        override suspend fun createDocument(name: String, folderId: String?): Result<Document> {
            val d = Document.create(id = "new-${seq++}", name = name, folderId = folderId)
            docs[d.id] = d
            return Result.success(d)
        }
        override suspend fun saveDocument(document: Document): Result<Unit> {
            docs[document.id] = document; return Result.success(Unit)
        }
        override suspend fun getDocumentById(id: String): Result<Document> =
            docs[id]?.let { Result.success(it) } ?: Result.failure(NoSuchElementException(id))
        override fun observeDocument(id: String): Flow<Document?> = TODO()
        override fun observeAllDocuments(): Flow<List<Document>> = TODO()
        override suspend fun getAllDocumentMetas(): Result<List<Document>> = TODO()
        override suspend fun getDocumentsByFolder(folderId: String?): Result<List<Document>> = TODO()
        override suspend fun searchDocuments(query: String): Result<List<Document>> = TODO()
        override suspend fun moveDocument(id: String, targetFolderId: String?): Result<Unit> = TODO()
        override suspend fun deleteDocument(id: String): Result<Unit> = TODO()
        override suspend fun toggleFavorite(id: String): Result<Unit> = TODO()
    }

    private class FakeSyncStateDao : SyncStateDao {
        val map = LinkedHashMap<String, SyncStateEntity>()
        override suspend fun upsert(state: SyncStateEntity) { map[state.documentId] = state }
        override suspend fun getByDocument(docId: String) = map[docId]
        override suspend fun getAll() = map.values.toList()
        override suspend fun deleteByDocument(docId: String) { map.remove(docId) }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun doc(id: String, name: String, content: String) =
        Document.create(id, name).copy(content = content)

    private fun newRepo(
        docRepo: FakeDocRepo,
        dao: FakeSyncStateDao,
        web: WebDavClient
    ): SyncRepositoryImpl {
        val configStore = mockk<SyncConfigDataStore>(relaxed = true)
        every { configStore.configFlow } returns flowOf(config)
        return SyncRepositoryImpl(configStore, dao, web, docRepo, SaveDocumentUseCase(docRepo))
    }

    @Test
    fun `first sync uploads local doc and records state`() = runTest {
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "hello") }
        val dao = FakeSyncStateDao()
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns Result.success(emptyList())
        coEvery { web.upload(config, "Note.md", "hello") } returns Result.success("etag1")

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.uploaded).isEqualTo(1)
        assertThat(dao.map["d1"]?.remotePath).isEqualTo("Note.md")
        assertThat(dao.map["d1"]?.remoteEtag).isEqualTo("etag1")
    }

    @Test
    fun `remote-only file is downloaded into a new local doc`() = runTest {
        val docRepo = FakeDocRepo()
        val dao = FakeSyncStateDao()
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns
            Result.success(listOf(RemoteEntry("Remote.md", "e1", null, false)))
        coEvery { web.download(config, "Remote.md") } returns Result.success("remote body")

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.downloaded).isEqualTo(1)
        val created = docRepo.docs.values.single()
        assertThat(created.name).isEqualTo("Remote")
        assertThat(created.content).isEqualTo("remote body")
    }

    @Test
    fun `remote change overwrites local content`() = runTest {
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "old") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity("d1", "Note.md", remoteEtag = "e0", localHash = sha256("old"), lastSyncedAt = 1)
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns
            Result.success(listOf(RemoteEntry("Note.md", "e1", null, false)))
        coEvery { web.download(config, "Note.md") } returns Result.success("new from remote")

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.downloaded).isEqualTo(1)
        assertThat(docRepo.docs["d1"]?.content).isEqualTo("new from remote")
        assertThat(dao.map["d1"]?.remoteEtag).isEqualTo("e1")
    }

    @Test
    fun `conflict keeps local, saves remote as a copy, uploads local`() = runTest {
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "local new") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity("d1", "Note.md", remoteEtag = "e0", localHash = sha256("local old"), lastSyncedAt = 1)
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns
            Result.success(listOf(RemoteEntry("Note.md", "e1", null, false)))
        coEvery { web.download(config, "Note.md") } returns Result.success("remote new")
        coEvery { web.upload(config, "Note.md", "local new") } returns Result.success("e2")

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.conflicts).isEqualTo(1)
        // 原文档保留本地内容
        assertThat(docRepo.docs["d1"]?.content).isEqualTo("local new")
        // 远端版本被存为一个新的冲突副本文档
        val copy = docRepo.docs.values.firstOrNull { it.id != "d1" }
        assertThat(copy).isNotNull()
        assertThat(copy!!.content).isEqualTo("remote new")
        assertThat(copy.name).contains("冲突")
    }
}
