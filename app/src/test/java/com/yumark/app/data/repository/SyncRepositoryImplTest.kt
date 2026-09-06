package com.yumark.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.yumark.app.core.util.FriendlyValidationException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.data.local.db.dao.SyncStateDao
import com.yumark.app.data.local.db.dao.SyncTombstoneDao
import com.yumark.app.data.local.db.entity.SyncStateEntity
import com.yumark.app.data.local.db.entity.SyncTombstoneEntity
import com.yumark.app.data.local.prefs.SyncConfigDataStore
import com.yumark.app.data.remote.webdav.WebDavClient
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.RemoteEntry
import com.yumark.app.domain.model.WebDavConfig
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.usecase.SaveDocumentUseCase
import io.mockk.coEvery
import io.mockk.coVerify
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

        /** 每次 [createDocument] 试过的名字，按调用顺序——用来钉住冲突副本的改名策略。 */
        val createAttempts = mutableListOf<String>()

        /**
         * 让接下来 N 次 [createDocument] 以「重名」失败。
         *
         * 模拟的是根目录已经存在同名文档：那里没有唯一索引兜底（`folder_id` 为 NULL），
         * 唯一的闸门是 `requireNoDocumentNameConflict`，抛的正是 [FriendlyValidationException]。
         * 用计数器而不是按名字判重，是为了不依赖 `System.currentTimeMillis()`——
         * 副本名字里的秒级时间戳在测试里没法预先算出来。
         */
        var rejectCreates = 0
        private var seq = 0
        override suspend fun getAllDocuments(): Result<List<Document>> = Result.success(docs.values.toList())
        override suspend fun createDocument(name: String, folderId: String?): Result<Document> {
            createAttempts += name
            if (rejectCreates > 0) {
                rejectCreates--
                return Result.failure(FriendlyValidationException(UiMessage.Raw(name)))
            }
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
        override suspend fun renameDocument(id: String, newName: String): Result<Unit> = TODO()

        /**
         * 真实实现（[DocumentRepositoryImpl.moveToTrash]）把文档软删进回收站：
         * 库行、正文、历史版本都在，只是从活跃清单里消失——同步侧关心的就是
         * 「这篇文档不再出现在 locals 里」。[trashedIds] 用来钉住「删本地」这个动作
         * 确实是经仓库走的，而不是只改了 sync_state。
         */
        val trashedIds = mutableListOf<String>()
        override suspend fun moveToTrash(id: String): Result<Unit> {
            trashedIds += id
            docs.remove(id)
            return Result.success(Unit)
        }
        override suspend fun restoreFromTrash(id: String): Result<Unit> = TODO()
        override fun observeTrashCount(): Flow<Int> = flowOf(0)
        override suspend fun isTrashed(id: String): Boolean = false
        override suspend fun purgeDocument(id: String): Result<Unit> = TODO()
        override suspend fun getTrashedDocuments(): Result<List<com.yumark.app.domain.model.TrashedDocument>> = TODO()
        override suspend fun emptyTrash(): Result<Unit> = TODO()
        override suspend fun purgeTrashExpired(nowMs: Long, retentionMs: Long): Result<Int> = TODO()
        override suspend fun toggleFavorite(id: String): Result<Unit> = TODO()
    }

    private class FakeSyncTombstoneDao : SyncTombstoneDao {
        val map = LinkedHashMap<String, SyncTombstoneEntity>()
        override suspend fun getAll() = map.values.toList()
        override suspend fun deleteByDocument(docId: String) { map.remove(docId) }
        override suspend fun upsert(tombstone: SyncTombstoneEntity) { map[tombstone.documentId] = tombstone }

        /** 立一块碑：`document_id` / `remote_path` 就是墓碑表的全部信息，时间戳与判定无关。 */
        fun put(docId: String, remotePath: String) {
            map[docId] = SyncTombstoneEntity(docId, remotePath, deletedAt = 1)
        }
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
        web: WebDavClient,
        tombs: FakeSyncTombstoneDao = FakeSyncTombstoneDao(),
        fileManager: com.yumark.app.data.local.file.FileManager = fakeFileManager()
    ): SyncRepositoryImpl {
        val configStore = mockk<SyncConfigDataStore>(relaxed = true)
        every { configStore.configFlow } returns flowOf(config)
        // appContext 只被 saveConfig 的后台任务重排用到；本类只测 syncNow，给个空壳即可
        val appContext = mockk<android.content.Context>(relaxed = true)
        // 媒体通道在 syncNow 尾部与 execute 的三个落点被 best-effort 调用；文档同步测试里
        // 全部短路成空操作（pull 返回全零计数，push 不会碰网络——直接抛错就会被
        // runCatching 吞掉暴露不了，所以给合法空实现）
        val mediaSync = mockk<com.yumark.app.data.sync.MediaSync>(relaxed = true)
        coEvery { mediaSync.pushLocalImages(any(), any()) } returns
            com.yumark.app.data.sync.MediaSync.PushStats(0, 0)
        coEvery { mediaSync.pullImagesFor(any(), any(), any(), any()) } returns
            com.yumark.app.data.sync.MediaSync.PullStats(0, 0, 0)
        return SyncRepositoryImpl(appContext, configStore, dao, tombs, web, docRepo, SaveDocumentUseCase(docRepo), fileManager, mediaSync)
    }

    /**
     * 只桩救援路径的 FileManager 替身：[rescued] 记下「哪篇正文在删除前被救了」，
     * 其余方法一律抛——这个 fake 的职责就是钉住 DeleteLocal 的救援契约，
     * 别的调用出现说明测试本身写歪了。
     */
    private fun fakeFileManager(
        rescueFail: Boolean = false
    ): com.yumark.app.data.local.file.FileManager {
        val rescued = mutableListOf<Pair<String, String>>()
        val fm = mockk<com.yumark.app.data.local.file.FileManager>()
        if (rescueFail) {
            coEvery { fm.rescueBeforeRemoteDelete(any(), any()) } returns
                Result.failure(java.io.IOException("disk full"))
        } else {
            coEvery { fm.rescueBeforeRemoteDelete(any(), any()) } coAnswers {
                rescued += Pair(firstArg(), secondArg())
                Result.success(Unit)
            }
        }
        rescueLog[fm] = rescued
        return fm
    }

    /** 每个 fake FileManager 的救援记录。testScope 内共享，按 mock 实例取。 */
    private val rescueLog = java.util.IdentityHashMap<com.yumark.app.data.local.file.FileManager, MutableList<Pair<String, String>>>()

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

    @Test
    fun `上传带上 If-Match，避免静默冲掉另一台设备刚写的内容`() = runTest {
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "local new") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Note.md", remoteEtag = "e1", localHash = sha256("local old"), lastSyncedAt = 1
            )
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        // 远端 etag 与基线相同 → 只有本地变了 → Upload（不是 Conflict）
        coEvery { web.list(config) } returns
            Result.success(listOf(RemoteEntry("Note.md", "e1", null, false)))
        // 只桩「第四个参数是 e1」这一种调用。mockk 未 relaxed：真发出无条件 PUT 就匹配不上而失败
        coEvery { web.upload(config, "Note.md", "local new", "e1") } returns Result.success("e2")

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.uploaded).isEqualTo(1)
        assertThat(outcome.failed).isEqualTo(0)
        assertThat(dao.map["d1"]?.remoteEtag).isEqualTo("e2")
    }

    @Test
    fun `不给 ETag 的服务器靠 mtime 代用标记也能判出双边冲突`() = runTest {
        // 自建 nginx dav / 部分 NAS 不回 ETag：只比 etag 的话远端改动永远检测不出来，
        // 本地会拿旧内容一遍遍覆盖远端，界面上一句提示都没有
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "local new") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Note.md", remoteEtag = "mtime:1000", localHash = sha256("local old"), lastSyncedAt = 1
            )
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns
            Result.success(listOf(RemoteEntry("Note.md", null, 2000L, false)))
        coEvery { web.download(config, "Note.md") } returns Result.success("remote new")
        // 冲突分支的上行故意不带 If-Match（远端版本已存成本地副本，不会丢数据）
        coEvery { web.upload(config, "Note.md", "local new") } returns Result.success(null)

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.conflicts).isEqualTo(1)
        assertThat(docRepo.docs.values.firstOrNull { it.id != "d1" }?.content).isEqualTo("remote new")
    }

    @Test
    fun `mtime 代用标记绝不能当 If-Match 发出去`() = runTest {
        // 代用标记不是服务器给的 ETag，拿去做条件请求必然 412，会把「不给 ETag 的服务器」的上传彻底打死
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "local new") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Note.md", remoteEtag = "mtime:1000", localHash = sha256("local old"), lastSyncedAt = 1
            )
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        // mtime 与基线相同 → 远端没变 → 单边 Upload
        coEvery { web.list(config) } returns
            Result.success(listOf(RemoteEntry("Note.md", null, 1000L, false)))
        // 只桩无条件 PUT：真把 mtime:1000 当 If-Match 发出去就匹配不上，测试失败
        coEvery { web.upload(config, "Note.md", "local new") } returns Result.success(null)

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.uploaded).isEqualTo(1)
        assertThat(outcome.failed).isEqualTo(0)
    }

    @Test
    fun `remoteVersionTag 优先真 ETag，其次 mtime，都没有就 null`() {
        assertThat(remoteVersionTag("e1", 2000L)).isEqualTo("e1")
        // 空白 etag 等于没给：有服务器回 `ETag: ""`，直接用会让所有文件的标记都相等
        assertThat(remoteVersionTag("", 2000L)).isEqualTo("mtime:2000")
        assertThat(remoteVersionTag("  ", 2000L)).isEqualTo("mtime:2000")
        assertThat(remoteVersionTag(null, 2000L)).isEqualTo("mtime:2000")
        // 两者都没有：退化成「只比本地内容哈希」，不能凭空造一个每次都不同的标记（会天天判冲突）
        assertThat(remoteVersionTag(null, null)).isNull()
        assertThat(remoteVersionTag("", null)).isNull()
    }

    // ===== 沿用远端大小写 =====

    @Test
    fun `远端已有的大小写拼法被沿用，避免改名把自己刚上传的内容删掉`() {
        // 本地标题改成 Note、远端只有 note.md：在大小写不敏感的服务端上两者是同一个文件，
        // 不沿用就会走「PUT Note.md + DELETE note.md」，DELETE 删掉的正是刚写进去的那份。
        val result = adoptRemoteCasing(mapOf("d1" to "Note.md"), listOf("note.md"))
        assertThat(result).isEqualTo(mapOf("d1" to "note.md"))
    }

    @Test
    fun `同一小写形态有多个远端文件时放弃沿用，不去猜是哪一个`() {
        // 两个文件同时在，本身就证明这台服务器大小写敏感 —— 它们是两篇不同的文档。
        // 旧实现是 associate（后者胜），沿用到哪个取决于 PROPFIND 的返回顺序，
        // 而摇到哪个就 PUT 到哪个：另一篇的正文被顶掉，自己那份远端文件被 DELETE 掉。
        val remotes = listOf("Note.md", "note.md")
        assertThat(adoptRemoteCasing(mapOf("d1" to "Note.md"), remotes))
            .isEqualTo(mapOf("d1" to "Note.md"))
        // 返回顺序反过来也必须给同一个答案，这才叫「与顺序无关」
        assertThat(adoptRemoteCasing(mapOf("d1" to "Note.md"), remotes.reversed()))
            .isEqualTo(mapOf("d1" to "Note.md"))
        // 本地算出的是小写那一版时同理：照样保持原样，精确命中远端的 note.md
        assertThat(adoptRemoteCasing(mapOf("d1" to "note.md"), remotes))
            .isEqualTo(mapOf("d1" to "note.md"))
    }

    @Test
    fun `远端没有对应文件时原样保留，且不动其他文档`() {
        val result = adoptRemoteCasing(
            mapOf("d1" to "Note.md", "d2" to "Other.md"),
            listOf("note.md", "third.md")
        )
        assertThat(result).isEqualTo(mapOf("d1" to "note.md", "d2" to "Other.md"))
    }

    @Test
    fun `大小写敏感服务器上的同名歧义不会让同步动到任何一个远端文件`() = runTest {
        // 端到端版本：基线记着 Note.md，远端同时摆着 Note.md 与 note.md（note.md 排在后面，
        // 正是旧实现的 associate 会挑中的那个）。旧实现下这一轮会被判成改名：
        // 本地内容 PUT 到 note.md（顶掉一篇毫不相干的文档）+ DELETE Note.md（自己那份没了）。
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "body") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Note.md", remoteEtag = "e1", localHash = sha256("body"), lastSyncedAt = 1
            )
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns Result.success(
            listOf(
                RemoteEntry("Note.md", "e1", null, false),
                RemoteEntry("note.md", "eX", null, false)
            )
        )
        // 陌生的 note.md 只该被当成「远端独有」拉成一篇新文档
        coEvery { web.download(config, "note.md") } returns Result.success("someone else")

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        // d1 两边都没变 → Skip；note.md → CreateLocal
        assertThat(outcome.skipped).isEqualTo(1)
        assertThat(outcome.downloaded).isEqualTo(1)
        assertThat(outcome.deleted).isEqualTo(0)
        assertThat(outcome.conflicts).isEqualTo(0)
        assertThat(outcome.failed).isEqualTo(0)
        // 一次上传、一次删除都不该有：两个 mock 都没桩，真调到就直接失败
        coVerify(exactly = 0) { web.upload(config, any(), any(), any()) }
        coVerify(exactly = 0) { web.delete(config, any()) }
        // d1 的基线仍指着自己那个文件，没被改写到 note.md 上
        assertThat(dao.map.getValue("d1").remotePath).isEqualTo("Note.md")
        // 本地内容没被谁顶掉
        assertThat(docRepo.docs.getValue("d1").content).isEqualTo("body")
    }

    // ===== 弱 ETag 不能当 If-Match =====

    @Test
    fun `强 ETag 才拿去当 If-Match，弱验证器与空值都退化成无条件 PUT`() {
        // 弱那一条是要害：RFC 9110 §13.1.1 的 If-Match 用强比较，§8.8.3.2 又要求强比较两边都不是弱的
        // ——弱标签连自己都不等于自己。发出去只能换回 412，而 412 刻意不退化（WebDavClient.upload 的
        // 退化名单只有 400/501），于是那台服务器上「远端已存在的文档」上传永久失败。
        assertThat(ifMatchEtagOf("abc", weak = false)).isEqualTo("abc")
        assertThat(ifMatchEtagOf("abc", weak = true)).isNull()
        assertThat(ifMatchEtagOf(null, weak = false)).isNull()
        // 空串是非空引用，不筛掉就会发出 `If-Match: ""`
        assertThat(ifMatchEtagOf("", weak = false)).isNull()
        assertThat(ifMatchEtagOf("   ", weak = false)).isNull()
    }

    @Test
    fun `服务器给弱 ETag 时上传不带 If-Match，否则每一轮都 412`() = runTest {
        // nginx 及各类反代一开 gzip 就把强 ETag 改写成弱的，所以这是常见配置而非边角。
        // 规范化会把 `W/"weak-1"` 剥成 `weak-1`，光看字符串认不出弱强 —— 靠的是 etagWeak 这一位。
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "local new") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Note.md", remoteEtag = "weak-1", localHash = sha256("local old"), lastSyncedAt = 1
            )
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        // 远端标记与基线相同 → 只有本地变了 → Upload（不是 Conflict）
        coEvery { web.list(config) } returns Result.success(
            listOf(RemoteEntry("Note.md", "weak-1", null, false, etagWeak = true))
        )
        // 只桩「第四个参数是 null」这一种调用。mockk 未 relaxed：真把 weak-1 当 If-Match 发出去
        // 就匹配不上，用例直接失败 —— 这正是要守的回归
        coEvery { web.upload(config, "Note.md", "local new") } returns Result.success("weak-2")

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.uploaded).isEqualTo(1)
        assertThat(outcome.failed).isEqualTo(0)
        coVerify(exactly = 1) { web.upload(config, "Note.md", "local new", null) }
        // 弱标记照样能当版本基线用（只参与相等比较，不参与强比较）
        assertThat(dao.map["d1"]?.remoteEtag).isEqualTo("weak-2")
    }

    // ===== 冲突副本命名 =====

    @Test
    fun `冲突副本名带到秒的时间戳，同一天的两次冲突不会撞名`() {
        // 到天的旧实现下这两个名字完全相同，于是第二次冲突的 createDocument 必然被重名拒掉，
        // 整个 Conflict 动作中断，本地修改一整天都传不上去
        val first = conflictCopyName("Note", 1_756_800_000_000L)
        val second = conflictCopyName("Note", 1_756_800_000_000L + 1_000L)
        assertThat(first).isNotEqualTo(second)
        assertThat(first).startsWith("Note (冲突 ")
        assertThat(first).endsWith(")")
    }

    @Test
    fun `冲突副本名不含冒号等文件名非法字符`() {
        // 这个名字会经 UniqueFileNames 变成远端文件名：冒号在 Windows 和多数 WebDAV 服务端非法
        val name = conflictCopyName("Note", 1_756_800_000_000L)
        assertThat(name).doesNotContain(":")
        assertThat(name).doesNotContain("/")
        assertThat(name).doesNotContain("\\")
    }

    @Test
    fun `attempt 大于 0 时追加序号，且序号从 2 起`() {
        val base = conflictCopyName("Note", 1_756_800_000_000L, attempt = 0)
        val retry1 = conflictCopyName("Note", 1_756_800_000_000L, attempt = 1)
        val retry2 = conflictCopyName("Note", 1_756_800_000_000L, attempt = 2)
        assertThat(retry1).isEqualTo(base.dropLast(1) + " 2)")
        assertThat(retry2).isEqualTo(base.dropLast(1) + " 3)")
        // 三个名字互不相同才谈得上「换个名字再试」
        assertThat(setOf(base, retry1, retry2)).hasSize(3)
    }

    @Test
    fun `副本名被占用时换名重试，本地内容照样上传`() = runTest {
        // 这是原缺陷的核心后果：副本创建失败 → getOrThrow 打断整个 Conflict 动作 →
        // 紧随其后的「本地内容上行」压根不跑。远端停在对方那一版，本地修改一次都传不上去。
        val docRepo = FakeDocRepo().apply {
            docs["d1"] = doc("d1", "Note", "local new")
            rejectCreates = 1 // 第一个副本名已被占用
        }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Note.md", remoteEtag = "e0", localHash = sha256("local old"), lastSyncedAt = 1
            )
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns
            Result.success(listOf(RemoteEntry("Note.md", "e1", null, false)))
        coEvery { web.download(config, "Note.md") } returns Result.success("remote new")
        coEvery { web.upload(config, "Note.md", "local new") } returns Result.success("e2")

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.conflicts).isEqualTo(1)
        assertThat(outcome.failed).isEqualTo(0)
        // 试了两个名字：第一个被拒，第二个带序号
        assertThat(docRepo.createAttempts).hasSize(2)
        assertThat(docRepo.createAttempts[1]).isEqualTo(docRepo.createAttempts[0].dropLast(1) + " 2)")
        // 远端版本落进了副本，基线也刷成了上传后的新 etag
        assertThat(docRepo.docs.values.firstOrNull { it.id != "d1" }?.content).isEqualTo("remote new")
        assertThat(dao.map["d1"]?.remoteEtag).isEqualTo("e2")
    }

    @Test
    fun `副本名一直撞到试满时整个动作失败，绝不先上传盖掉远端`() = runTest {
        // 上传若在副本创建失败后照跑，远端那一版就被本地内容抹掉了，而它此刻还没有任何本地留存
        val docRepo = FakeDocRepo().apply {
            docs["d1"] = doc("d1", "Note", "local new")
            rejectCreates = 99
        }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Note.md", remoteEtag = "e0", localHash = sha256("local old"), lastSyncedAt = 1
            )
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns
            Result.success(listOf(RemoteEntry("Note.md", "e1", null, false)))
        coEvery { web.download(config, "Note.md") } returns Result.success("remote new")
        // 刻意不桩 upload：真发出上传就匹配不上任何桩，测试失败

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.failed).isEqualTo(1)
        assertThat(outcome.conflicts).isEqualTo(0)
        // 重试有上限，不会拿着同一个失败无限打库
        assertThat(docRepo.createAttempts.size).isAtMost(16)
        assertThat(docRepo.createAttempts.size).isAtLeast(2)
        // 基线未被推进：下次同步还会重新判一次冲突，而不是误以为已经对齐
        assertThat(dao.map["d1"]?.remoteEtag).isEqualTo("e0")
    }

    // ===== PUT 不回 ETag 时的基线补写 =====

    @Test
    fun `PUT 没回 ETag 时整轮结束后补一次远端基线`() = runTest {
        // 基线为 null 会让 SyncPlanner 的 remoteChanged 恒为假 → 这篇文档从此**永远**检测不出
        // 远端改动，本地一有修改就直接覆盖上去，界面上一句提示都没有。而 PUT 不回 ETag 在
        // 自建 nginx dav / 部分 NAS 上是常态：上行成功的那一刻就把自己的下行能力关掉了。
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "local new") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Note.md", remoteEtag = "mtime:1000", localHash = sha256("local old"), lastSyncedAt = 1
            )
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        // 两次列目录：第一次是同步开始时的快照（mtime 与基线相同 → 单边 Upload），
        // 第二次是补基线时读到的「PUT 落地之后」的快照
        coEvery { web.list(config) } returnsMany listOf(
            Result.success(listOf(RemoteEntry("Note.md", null, 1000L, false))),
            Result.success(listOf(RemoteEntry("Note.md", null, 2000L, false)))
        )
        coEvery { web.upload(config, "Note.md", "local new") } returns Result.success(null)

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.uploaded).isEqualTo(1)
        assertThat(outcome.failed).isEqualTo(0)
        // 基线被补成了 PUT 之后的远端标记
        assertThat(dao.map["d1"]?.remoteEtag).isEqualTo("mtime:2000")
        // localHash 是本地内容的指纹，与远端标记无关：补基线时被顺手覆盖就会把「本地已改动」擦掉
        assertThat(dao.map["d1"]?.localHash).isEqualTo(sha256("local new"))
    }

    @Test
    fun `补基线时列目录失败不算同步失败`() = runTest {
        // 补基线是锦上添花：补不上也只是回到「基线为 null」，不该把一次内容已经成功上传的
        // 同步报成失败——用户看到「失败 1 篇」会重试，而重试解决不了服务器不给 ETag
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "hello") }
        val dao = FakeSyncStateDao()
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returnsMany listOf(
            Result.success(emptyList()),
            Result.failure(java.io.IOException("boom"))
        )
        coEvery { web.upload(config, "Note.md", "hello") } returns Result.success(null)

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.uploaded).isEqualTo(1)
        assertThat(outcome.failed).isEqualTo(0)
        // 基线仍是 null（下次同步会重新尝试），但那一行本身写进去了
        assertThat(dao.map["d1"]?.remotePath).isEqualTo("Note.md")
        assertThat(dao.map["d1"]?.remoteEtag).isNull()
    }

    @Test
    fun `PUT 回了 ETag 但列目录不给 getetag 时基线仍按列目录的标记写`() = runTest {
        // 两条观测通道各说各话的服务器：PUT 响应带 ETag，PROPFIND 却不给 getetag。
        // 采信 PUT 那个的话，基线里躺着真 ETag，而下一轮比较侧算出来的是 `mtime:…`——
        // 两个字符串必然不等 → 判「远端变了」。本地没改只是白下载一遍（每次上传后都来一次），
        // 本地恰好也改过就走 Conflict：凭空多出一份「冲突副本」文档，而两边内容一模一样。
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "local new") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Note.md", remoteEtag = "mtime:1000", localHash = sha256("local old"), lastSyncedAt = 1
            )
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returnsMany listOf(
            Result.success(listOf(RemoteEntry("Note.md", null, 1000L, false))),
            Result.success(listOf(RemoteEntry("Note.md", null, 2000L, false)))
        )
        coEvery { web.upload(config, "Note.md", "local new") } returns Result.success("\"deadbeef\"")

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.uploaded).isEqualTo(1)
        assertThat(outcome.failed).isEqualTo(0)
        // 关键：不是 PUT 回来的 "deadbeef"，而是与比较侧同源的那个标记
        assertThat(dao.map["d1"]?.remoteEtag).isEqualTo("mtime:2000")
        assertThat(dao.map["d1"]?.localHash).isEqualTo(sha256("local new"))
    }

    @Test
    fun `两条通道都给 ETag 时留用 PUT 那个，不再多列一次目录`() = runTest {
        // 反向守护：别把上面那条修成「一律回头再 PROPFIND 一次」。PUT 回来的 ETag 描述的正是
        // 我们刚写上去的那一版，而补基线那次读有个「写后读」窗口（另一台设备在我们 PUT 之后、
        // 这次 PROPFIND 之前也 PUT，读回的标记描述的是对方那一版，却配着我们的本地哈希 →
        // 下轮判 Skip，对方的改动被漏掉）。能少读一次就少读一次。
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "local new") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Note.md", remoteEtag = "v1", localHash = sha256("local old"), lastSyncedAt = 1
            )
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns
            Result.success(listOf(RemoteEntry("Note.md", "v1", 1000L, false)))
        coEvery { web.upload(config, "Note.md", "local new", "v1") } returns Result.success("v2")

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.uploaded).isEqualTo(1)
        assertThat(dao.map["d1"]?.remoteEtag).isEqualTo("v2")
        coVerify(exactly = 1) { web.list(config) }
    }

    // ===== 改名 =====

    @Test
    fun `改名后上传新名并删掉旧远端文件，不会多出一篇重复文档`() = runTest {
        // 旧路径若不删，下次同步会把它当成「远端独有」拉回来，变成一篇内容陈旧的重复文档
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "New", "body") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Old.md", remoteEtag = "e0", localHash = sha256("body"), lastSyncedAt = 1
            )
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns
            Result.success(listOf(RemoteEntry("Old.md", "e0", null, false)))
        // 新名字在远端不存在 → 无条件 PUT（没有 etag 可用作 If-Match）
        coEvery { web.upload(config, "New.md", "body") } returns Result.success("e1")
        coEvery { web.delete(config, "Old.md") } returns Result.success(Unit)

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.uploaded).isEqualTo(1)
        assertThat(outcome.failed).isEqualTo(0)
        coVerify { web.delete(config, "Old.md") }
        assertThat(dao.map["d1"]?.remotePath).isEqualTo("New.md")
        assertThat(dao.map["d1"]?.remoteEtag).isEqualTo("e1")
        // 旧路径没被当成远端独有拉成新文档
        assertThat(docRepo.docs).hasSize(1)
    }

    @Test
    fun `改名撞上远端已有的同名文件时先存副本再上传，并清掉旧路径`() = runTest {
        // 新名字被**另一份**远端文件占着（多半是另一台设备建的、本地还没拉下来）。
        // 直接上传会把它静默冲掉；旧实现更糟——判成 DownloadOverwrite，
        // 拿那份毫不相干的内容盖掉本地正文，旁边再多出一篇由旧路径拉出来的重复文档。
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "New", "local body") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Old.md", remoteEtag = "e0", localHash = sha256("local body"), lastSyncedAt = 1
            )
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns Result.success(
            listOf(RemoteEntry("Old.md", "e0", null, false), RemoteEntry("New.md", "e9", null, false))
        )
        coEvery { web.download(config, "New.md") } returns Result.success("stranger body")
        coEvery { web.upload(config, "New.md", "local body") } returns Result.success("e2")
        coEvery { web.delete(config, "Old.md") } returns Result.success(Unit)

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.conflicts).isEqualTo(1)
        assertThat(outcome.failed).isEqualTo(0)
        // 本地正文没被陌生内容覆盖
        assertThat(docRepo.docs["d1"]?.content).isEqualTo("local body")
        // 陌生那一版存成了冲突副本，没有凭空消失
        val copy = docRepo.docs.values.single { it.id != "d1" }
        assertThat(copy.content).isEqualTo("stranger body")
        assertThat(copy.name).contains("冲突")
        // 恰好两篇：原文档 + 副本，旧路径没有再拉出第三篇
        assertThat(docRepo.docs).hasSize(2)
        coVerify { web.delete(config, "Old.md") }
        assertThat(dao.map["d1"]?.remotePath).isEqualTo("New.md")
        assertThat(dao.map["d1"]?.remoteEtag).isEqualTo("e2")
    }

    // ===== 删除双向传播 =====

    @Test
    fun `本地删除推到远端：删远端文件并清掉墓碑`() = runTest {
        // 墓碑是「本地删过这篇」的唯一痕迹（文档行与 sync_state 行都已经没了）。
        // 不走这条路的话，远端那个文件下轮会被当成「远端独有」拉回来，删掉的文档就地复活。
        val docRepo = FakeDocRepo()
        val dao = FakeSyncStateDao()
        val tombs = FakeSyncTombstoneDao().apply { put("d1", "Gone.md") }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns
            Result.success(listOf(RemoteEntry("Gone.md", "e0", null, false)))
        coEvery { web.delete(config, "Gone.md") } returns Result.success(Unit)

        val outcome = newRepo(docRepo, dao, web, tombs).syncNow().getOrThrow()

        assertThat(outcome.deleted).isEqualTo(1)
        assertThat(outcome.failed).isEqualTo(0)
        coVerify { web.delete(config, "Gone.md") }
        // 碑已清：下轮不会再发一次注定 404 的 DELETE
        assertThat(tombs.map).isEmpty()
        // 关键否定断言：那个远端文件没有被拉成一篇新文档
        assertThat(docRepo.docs).isEmpty()
    }

    @Test
    fun `删远端失败时墓碑必须留着`() = runTest {
        // 先清碑再删远端的话，DELETE 一失败那个文件就没人认领了——下轮被当成「远端独有」
        // 拉回本地，删掉的文档就地复活，而用户看到的只是一次「失败 1 篇」。
        val docRepo = FakeDocRepo()
        val dao = FakeSyncStateDao()
        val tombs = FakeSyncTombstoneDao().apply { put("d1", "Gone.md") }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns
            Result.success(listOf(RemoteEntry("Gone.md", "e0", null, false)))
        coEvery { web.delete(config, "Gone.md") } returns Result.failure(RuntimeException("boom"))

        val outcome = newRepo(docRepo, dao, web, tombs).syncNow().getOrThrow()

        assertThat(outcome.failed).isEqualTo(1)
        assertThat(outcome.deleted).isEqualTo(0)
        assertThat(tombs.map.keys).containsExactly("d1")
    }
    @Test
    fun `远端删除下行：本地内容与基线一致时经仓库删掉本地文档`() = runTest {
        // 走 documentRepository 而不是直接删库行：正文文件、图片文件、全文索引行都得一起清
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "body") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Note.md", remoteEtag = "e0", localHash = sha256("body"), lastSyncedAt = 1
            )
        }
        val tombs = FakeSyncTombstoneDao()
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns Result.success(emptyList())
        val fm = fakeFileManager()
        val outcome = newRepo(docRepo, dao, web, tombs, fm).syncNow().getOrThrow()

        assertThat(outcome.deleted).isEqualTo(1)
        assertThat(outcome.failed).isEqualTo(0)
        assertThat(docRepo.trashedIds).containsExactly("d1")
        assertThat(docRepo.docs).isEmpty()
        // 救援先于删除：CASCADE 吞掉 document_versions 之前，最后一份正文已落 sync_trash
        assertThat(rescueLog[fm]).containsExactly(Pair("d1", "Note"))
        // 那次删除会顺手立一块碑（它分不清删除来自用户还是同步）；远端文件早就没了，必须清掉，
        // 否则下轮多发一次注定 404 的 DELETE
        assertThat(tombs.map).isEmpty()
        // 非 relaxed 的 web mock 就是断言本身：这条路径一次网络请求都不该发（除了 ensureDir/list）
    }

    @Test
    fun `救援写失败时整个删除动作失败，绝不照删`() = runTest {
        // 救不了却照删 = 那篇正文在世界任何地方都不再存在（远端已没了、库行/版本史被 CASCADE
        // 带走）。动作判失败后下轮重判仍是 DeleteLocal（远端文件还是不在），重试幂等。
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "body") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Note.md", remoteEtag = "e0", localHash = sha256("body"), lastSyncedAt = 1
            )
        }
        val tombs = FakeSyncTombstoneDao()
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns Result.success(emptyList())

        val outcome = newRepo(docRepo, dao, web, tombs, fakeFileManager(rescueFail = true))
            .syncNow().getOrThrow()

        assertThat(outcome.failed).isEqualTo(1)
        assertThat(outcome.deleted).isEqualTo(0)
        // 关键否定断言：文档行、版本史、墓碑全都在
        assertThat(docRepo.trashedIds).isEmpty()
        assertThat(docRepo.docs).containsKey("d1")
    }

    @Test
    fun `远端消失但本地有未同步改动时复活上传，绝不删本地`() = runTest {
        val docRepo = FakeDocRepo().apply { docs["d1"] = doc("d1", "Note", "new body") }
        val dao = FakeSyncStateDao().apply {
            map["d1"] = SyncStateEntity(
                "d1", "Note.md", remoteEtag = "e0", localHash = sha256("old body"), lastSyncedAt = 1
            )
        }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns Result.success(emptyList())
        coEvery { web.upload(config, "Note.md", "new body") } returns Result.success("e1")

        val outcome = newRepo(docRepo, dao, web).syncNow().getOrThrow()

        assertThat(outcome.uploaded).isEqualTo(1)
        assertThat(outcome.deleted).isEqualTo(0)
        assertThat(docRepo.trashedIds).isEmpty()
        assertThat(docRepo.docs["d1"]?.content).isEqualTo("new body")
    }
    @Test
    fun `远端本来就没有那个文件时只清墓碑，一次网络请求都不发`() = runTest {
        // 对方也删了、或者当初压根没传成。留着碑就是一颗延时炸弹：每轮重判一次，
        // 而某天远端出现同名文件就会被它删掉。
        val docRepo = FakeDocRepo()
        val dao = FakeSyncStateDao()
        val tombs = FakeSyncTombstoneDao().apply { put("d1", "Gone.md") }
        // 刻意不 relaxed、也不给 delete 打桩：真发出 DELETE 就会因「no answer found」直接失败
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns Result.success(emptyList())

        val outcome = newRepo(docRepo, dao, web, tombs).syncNow().getOrThrow()

        // 只清了一行墓碑，没有任何文档发生变化 → 不该计进任何一个计数
        assertThat(outcome.deleted).isEqualTo(0)
        assertThat(outcome.failed).isEqualTo(0)
        assertThat(outcome.uploaded).isEqualTo(0)
        assertThat(outcome.downloaded).isEqualTo(0)
        assertThat(tombs.map).isEmpty()
    }

    @Test
    fun `墓碑的远端路径已归另一篇活着的文档时绝不删远端`() = runTest {
        // 典型场景：删掉「随笔」之后又新建了一篇「随笔」。那个远端文件名已经是新文档的了，
        // 按墓碑删掉等于把用户刚写的东西删了。web mock 不给 delete 打桩即是断言。
        val docRepo = FakeDocRepo().apply { docs["d2"] = doc("d2", "随笔", "fresh") }
        val dao = FakeSyncStateDao()
        val tombs = FakeSyncTombstoneDao().apply { put("d1", "随笔.md") }
        val web = mockk<WebDavClient>()
        coEvery { web.ensureDir(config) } returns Result.success(Unit)
        coEvery { web.list(config) } returns
            Result.success(listOf(RemoteEntry("随笔.md", "e0", null, false)))
        // 新文档没有基线、远端已有同名文件 → 按首次同步撞名走冲突
        coEvery { web.download(config, "随笔.md") } returns Result.success("remote side")
        coEvery { web.upload(config, "随笔.md", "fresh") } returns Result.success("e1")

        val outcome = newRepo(docRepo, dao, web, tombs).syncNow().getOrThrow()

        assertThat(outcome.conflicts).isEqualTo(1)
        assertThat(outcome.failed).isEqualTo(0)
        assertThat(tombs.map).isEmpty()
        assertThat(docRepo.docs["d2"]?.content).isEqualTo("fresh")
    }
}
