package com.yumark.app.data.repository

import com.yumark.app.R
import com.yumark.app.core.search.FtsQueryBuilder
import com.yumark.app.core.search.FtsTextNormalizer
import com.yumark.app.core.util.FriendlyValidationException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.db.dao.DocumentSearchDao
import com.yumark.app.data.local.db.dao.ImageDao
import com.yumark.app.data.local.db.entity.DocumentSearchEntity
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.data.mapper.DocumentMapper
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.repository.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val fileManager: FileManager,
    private val mapper: DocumentMapper,
    private val searchDao: DocumentSearchDao,
    private val imageDao: ImageDao
) : DocumentRepository {

    /** 惰性回填只做一次；成功后置位，失败保持 false 以便下次搜索重试 */
    private var searchIndexReady = false
    private val searchIndexMutex = Mutex()

    override suspend fun getDocumentById(id: String): Result<Document> = runCatching {
        val entity = documentDao.getById(id) ?: documentNotFound()
        // 正确传播文件读取错误，而不是使用 getOrDefault 吞掉异常
        val content = fileManager.loadDocumentContent(id).getOrElse { error ->
            when (error) {
                is java.io.FileNotFoundException -> "" // 新文档，空内容是合理的
                else -> throw error // 其他错误需要传播给调用者
            }
        }
        mapper.toDomain(entity, content)
    }

    override fun observeDocument(id: String): Flow<Document?> {
        return documentDao.observeById(id).map { entity ->
            entity?.let {
                // 正确处理文件读取错误
                val content = fileManager.loadDocumentContent(id).getOrElse { error ->
                    when (error) {
                        is java.io.FileNotFoundException -> "" // 新文档，空内容是合理的
                        else -> throw error // 传播其他错误
                    }
                }
                mapper.toDomain(it, content)
            }
        }
    }

    override fun observeAllDocuments(): Flow<List<Document>> {
        return documentDao.observeAll().map { entities ->
            entities.map { entity ->
                // 性能优化：列表视图不需要加载完整内容
                mapper.toDomain(entity, "")
            }
        }
    }

    override suspend fun getAllDocuments(): Result<List<Document>> = runCatching {
        documentDao.getAll().map { entity ->
            val content = fileManager.loadDocumentContent(entity.id).getOrElse { error ->
                when (error) {
                    is java.io.FileNotFoundException -> ""
                    else -> throw error
                }
            }
            mapper.toDomain(entity, content)
        }
    }

    override suspend fun getAllDocumentMetas(): Result<List<Document>> = runCatching {
        documentDao.getAll().map { entity -> mapper.toDomain(entity, "") }
    }

    override suspend fun getDocumentsByFolder(folderId: String?): Result<List<Document>> = runCatching {
        documentDao.getByFolderIncludingRoot(folderId).map { entity ->
            val content = fileManager.loadDocumentContent(entity.id).getOrElse { error ->
                when (error) {
                    is java.io.FileNotFoundException -> ""
                    else -> throw error
                }
            }
            mapper.toDomain(entity, content)
        }
    }

    /**
     * 全文搜索：优先走 FTS4 索引，拿到命中 id 后只回捞这批文档的正文。
     *
     * 与旧实现（读全部文档正文再内存过滤）的关键差别是 IO 量：磁盘读取从
     * 「与文档总数成正比」降到「与命中数成正比」。
     *
     * 三条退回旧路径的分支，都不算错误：
     * - [FtsQueryBuilder.build] 返回 null：查询里没有可索引的 token（纯标点、纯 emoji）；
     * - MATCH 抛异常：索引表缺失、损坏或表达式被拒；
     * - 命中数为 0：FTS 只能整 token 匹配，用户打了半个英文单词（`kot`）时必然为空，
     *   而旧的子串搜索能命中，退回去才不算功能回退。
     */
    override suspend fun searchDocuments(query: String): Result<List<Document>> = runCatching {
        // 切到 IO 调度器：回填与读正文都是密集磁盘 IO，不应在调用线程（可能是 UI）执行
        withContext(Dispatchers.IO) {
            ensureSearchIndex()
            val match = FtsQueryBuilder.build(query)
            val hits = if (match == null) emptyList() else matchDocIds(match)
            if (hits.isEmpty()) legacySearch(query) else loadDocuments(hits)
        }
    }

    /** 执行 MATCH；任何失败都当作「索引不可用」返回空列表，由调用方退回旧路径 */
    private suspend fun matchDocIds(match: String): List<String> =
        try {
            searchDao.searchDocIds(match, SEARCH_HIT_LIMIT)
        } catch (e: CancellationException) {
            // 必须排在 Exception 之前：CancellationException 是 IllegalStateException 的子类，
            // 被下面吞掉就等于把「协程已取消」翻译成「索引坏了」，还破坏了取消传播。
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    /** 按 FTS 命中顺序回捞文档（含正文）；索引里残留的已删除文档会被自然跳过 */
    private suspend fun loadDocuments(ids: List<String>): List<Document> {
        val byId = documentDao.getByIds(ids).associateBy { it.id }
        return ids.mapNotNull { id ->
            val entity = byId[id] ?: return@mapNotNull null
            mapper.toDomain(entity, loadContentOrEmpty(id))
        }
    }

    /**
     * 旧的内存子串搜索，保留作为降级路径。
     *
     * 语义与 FTS 短语查询并不等价：这里是严格子串匹配，FTS 那边是 token 相邻匹配
     * （`foo_bar` 会命中正文里的 `foo bar`）。两者都由 UI 侧按匹配次数重排，差异只体现在召回集。
     */
    private suspend fun legacySearch(query: String): List<Document> =
        documentDao.getAll()
            .map { entity -> mapper.toDomain(entity, loadContentOrEmpty(entity.id)) }
            .filter { doc ->
                doc.name.contains(query, ignoreCase = true) ||
                    doc.content.contains(query, ignoreCase = true)
            }

    /** 读正文；文件缺失（新文档/被外部删除）视为空正文，其余错误照旧向上抛 */
    private suspend fun loadContentOrEmpty(id: String): String =
        fileManager.loadDocumentContent(id).getOrElse { error ->
            when (error) {
                is java.io.FileNotFoundException -> ""
                else -> throw error
            }
        }

    // ===== 全文索引维护 =====

    /**
     * 首次搜索时的一次性回填。
     *
     * 迁移 10 → 11 只建了空表，历史文档全都不在索引里；而正文在文件系统，SQL 迁移拿不到，
     * 只能在这里按文档逐个读文件补齐。回填失败不抛出：本次搜索会因命中为空自动退回旧路径。
     */
    private suspend fun ensureSearchIndex() {
        if (searchIndexReady) return
        searchIndexMutex.withLock {
            // 双检：并发的两次搜索里，后进来的那个不该重复扫一遍全库
            if (searchIndexReady) return
            try {
                withContext(Dispatchers.IO) {
                    if (searchDao.count() == 0) {
                        documentDao.getAll().forEach { entity ->
                            searchDao.upsert(
                                searchEntry(entity.id, entity.name, loadContentOrEmpty(entity.id))
                            )
                        }
                    }
                }
                searchIndexReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 不置 searchIndexReady：下次搜索再试
            }
        }
    }

    /**
     * 索引写入是 best-effort：失败只让这篇文档暂时搜不到，绝不能让保存/删除本身失败。
     *
     * 这里刻意不打 android.util.Log —— 本类在 JVM 单测里被直接实例化，
     * android.jar 的桩方法会抛 `RuntimeException("Stub!")`，一行日志就能让测试红。
     */
    private suspend fun indexBestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 忽略：下一次保存或惰性回填会自愈
        }
    }

    /** 写入索引的文本一律先规范化（CJK 逐字空格化），与查询侧共用同一个函数 */
    private fun searchEntry(id: String, name: String, content: String) = DocumentSearchEntity(
        rowId = null,
        docId = id,
        name = FtsTextNormalizer.normalize(name),
        content = FtsTextNormalizer.normalize(content)
    )

    override suspend fun createDocument(name: String, folderId: String?): Result<Document> = runCatching {
        requireNoDocumentNameConflict(name, folderId, excludeId = null)
        val id = UUID.randomUUID().toString()
        val document = Document.create(id, name, folderId)
        // 先落盘、再插库，与 [saveDocument] 同一个顺序。
        // 反过来的话（旧实现就是），磁盘写失败被忽略，库里却已经多了一行：列表上出现一篇
        // 打得开、点进去空白、保存时又会覆盖别处的幽灵文档。宁可创建失败，也不要幽灵行。
        fileManager.saveDocumentContent(id, "").getOrThrow()
        try {
            documentDao.insert(mapper.toEntity(document))
        } catch (e: CancellationException) {
            // 取消时**不**回收文件：insert 可能已经执行完（Room 的 SQL 不随协程取消而回滚），
            // 那时删了文件就变成「有行没正文」。留一个空文件只是垃圾，比反过来安全。
            throw e
        } catch (e: Exception) {
            // 插库失败：把刚写出的空文件收回去，否则 documents/ 里留一个没有主人的 .md
            fileManager.deleteDocumentFile(id)
            throw e
        }
        // 新文档正文为空，先把标题喂进索引，用户马上按标题搜也能命中
        indexBestEffort { searchDao.upsert(searchEntry(id, document.name, document.content)) }
        document
    }

    override suspend fun saveDocument(document: Document): Result<Unit> = runCatching {
        // 原子性保存：先写文件，成功后再更新数据库
        // 这样可以避免数据库和文件系统不一致的情况
        fileManager.saveDocumentContent(document.id, document.content).getOrThrow()

        // 文件写入成功后才回写库——但**只回写正文自己的那几个字段**（时间戳 + 两个计数），
        // 不是整行覆盖。名字、归属、收藏各有专属路径（renameDocument / moveDocument /
        // toggleFavorite），而这条路径是自动保存每隔几秒就跑一次的：拿 ViewModel 内存里
        // 那份可能已经过期的元数据整行盖库，就成了「在左窗格改完名，几秒后被右窗格的自动
        // 保存静默改回去」。见 [DocumentDao.updateContentMeta]。
        //
        // 也因此这里不再需要改名查重：这条路径已经改不动名字了。
        documentDao.updateContentMeta(
            id = document.id,
            updatedAt = System.currentTimeMillis(),
            wordCount = document.wordCount,
            characterCount = document.characterCount
        )

        // 索引里的标题取库里的现值而不是入参：理由同上，入参的名字可能是过期快照。
        // 读不到行（并发删除）时索引也没必要留，交给 deleteDocument 那条路清理。
        val storedName = documentDao.getById(document.id)?.name
        if (storedName != null) {
            indexBestEffort {
                searchDao.upsert(searchEntry(document.id, storedName, document.content))
            }
        }
    }

    /**
     * 改名：单字段 UPDATE，正文文件一个字节都不读也不写。
     *
     * 旧实现在两个 ViewModel 里各写了一遍「`getDocumentById` 读盘 → `copy(name=…)` →
     * `saveDocument` 整篇回写」。它有两个后果，第二个是真正致命的：
     *  1. 白读白写一遍正文文件；
     *  2. **编辑器里未保存的修改被磁盘上的旧正文顶掉**——`getDocumentById` 读的是磁盘，
     *     而磁盘落后于编辑框；回写之后 ViewModel 又把这份旧正文塞回 `_document`，
     *     用户正在写的段落就此消失，且全程没有任何错误提示。
     *
     * 同名时直接返回成功：既不查重（库里那一行本来就叫这个名字，唯一索引保证没有同名兄弟），
     * 也不刷 `updated_at`——「点了改名但没改字」不该让文档跳到列表最前面。
     */
    override suspend fun renameDocument(id: String, newName: String): Result<Unit> = runCatching {
        val current = documentDao.getById(id) ?: documentNotFound()
        if (current.name == newName) return@runCatching
        requireNoDocumentNameConflict(newName, current.folderId, excludeId = id)
        documentDao.rename(id, newName, System.currentTimeMillis())
        // 索引里的标题跟着改。正文从磁盘读（`loadContentOrEmpty`，读不到就空串）：
        // 索引本来就只反映已落盘的内容，与编辑器内存里的脏内容无关，
        // 下一次保存会带着新正文再 upsert 一遍。
        indexBestEffort { searchDao.upsert(searchEntry(id, newName, loadContentOrEmpty(id))) }
    }

    /**
     * 同一文件夹下不许出现同名文档。
     *
     * 两篇同名文档在列表里根本分不出哪个是哪个，而导出与 WebDAV 同步都拿名字当文件名：
     * 同步侧靠 `assignFileNames` 追加短 id 后缀来兜，导出侧没有这层兜底，直接互相覆盖。
     *
     * 抛 [FriendlyValidationException]：它带 [UiMessage] 且被 ErrorHandler 认作用户可见错误，
     * 文案会原样进 Snackbar，而不是被兜底成「出现未知问题，请重试」。
     */
    private suspend fun requireNoDocumentNameConflict(
        name: String,
        folderId: String?,
        excludeId: String?
    ) {
        val siblings = documentDao.getByFolderIncludingRoot(folderId).map { NamedEntry(it.id, it.name) }
        val conflict = findNameConflict(siblings, name, excludeId) ?: return
        throw FriendlyValidationException(
            UiMessage.of(R.string.document_error_duplicate_name, conflict.name)
        )
    }

    /**
     * 「库里查不到这一行」的唯一抛出点（[getDocumentById] / [renameDocument] / [moveDocument]）。
     *
     * 从前三处都抛裸 `Exception("Document not found: $id")`，代价有两笔：
     *  - `ErrorHandler.classify` 把裸 `Exception` 归到 `Unknown`，用户看到的是「出现未知问题，
     *    请重试」。而真正发生的事是「这篇文档已经被删掉了」——说不清就会一直重试；
     *  - `Unknown` 的 `worthRecording` 为 true，于是每一次并发删除都吃掉一格崩溃日志配额
     *    （总共只有 20 格）。展开态双窗格下（左列表 + 右编辑器，见 `AppShell`）在一侧删、
     *    在另一侧点改名/移动是很平常的竞态，不该把真崩溃挤出去。
     *
     * 换成 [FriendlyValidationException] 两笔都解决：文案原样透出，且归到
     * `AppError.Friendly` 后不再记账。返回 [Nothing] 以便直接写在 `?:` 右边。
     * 刻意不带 id：文案是给用户看的，而这条失败按设计不进日志，UUID 没有去处。
     */
    private fun documentNotFound(): Nothing =
        throw FriendlyValidationException(UiMessage.Res(R.string.document_error_not_found))

    /**
     * 删掉一篇文档：库行、全文索引行、图片文件、正文文件。
     *
     * 三段的顺序就是这个函数的全部要点：
     * - **图片名单必须在删库之前取**。`images` 行挂着 `documents` 的外键（CASCADE），
     *   `deleteById` 一执行它们就没了，删完再查只会得到空表——磁盘上那些 JPEG/PNG 于是
     *   永远没人引用得到，连 `getOrphanedImages` 的孤儿清理也扫不到（它靠 images 行反查）。
     *   旧实现就漏在这里：删一篇带图文档，图片文件全部留在 images/ 目录里白占空间。
     * - **图片文件删在正文文件之前**。下面那句 `deleteDocumentFile(...).getOrThrow()` 会抛，
     *   放在它后面的话，一次正文删除失败就把图片清理整段跳过。
     * - **图片删除是 best-effort**（失败只在 [FileManager.deleteImageFiles] 里记日志）。
     *   库行已经删了，为几 KB 图片把整次删除判成失败，用户看到「删除失败」而文档确实已经
     *   不在列表里，比留下垃圾文件坏得多。
     *
     * 正文文件那句仍然保留 `getOrThrow()`，是刻意不改：正文删不掉意味着下一篇复用同一 id
     * 的文档会被 `readOrRecover` 捞出上一篇的内容，那是数据错乱而非垃圾，必须上报。
     *
     * 删库那一步走 [DocumentDao.deleteWithTombstone] 而不是裸 `deleteById`：同步过的文档
     * 要在同一个事务里留下墓碑，否则远端那个文件下次同步会被当成「另一台设备新建的」
     * 拉回来，删掉的文档就地复活（详见 [com.yumark.app.data.local.db.entity.SyncTombstoneEntity]）。
     */
    override suspend fun deleteDocument(id: String): Result<Unit> = runCatching {
        val imageFileNames = imageDao.getByDocument(id).map { it.fileName }
        documentDao.deleteWithTombstone(id, System.currentTimeMillis())
        // 紧跟数据库删除，避免删文件失败时把索引条目留成幽灵命中
        indexBestEffort { searchDao.deleteByDocId(id) }
        fileManager.deleteImageFiles(imageFileNames)
        fileManager.deleteDocumentFile(id).getOrThrow()
    }

    /**
     * 把文档移到另一个文件夹。
     *
     * 移动前必须查重：目标文件夹里已经有同名文档时，移过去就是两篇同名——而导出直接拿名字
     * 当文件名，两篇会互相覆盖。新建和改名两条路径早就在查（[requireNoDocumentNameConflict]），
     * 移动这条以前是漏的，等于给「不许同名」开了一个后门。
     *
     * `excludeId = id` 是必要的：并发下同级列表里可能已经读到自己那一行（比如重复点了两次
     * 移动），不排掉的话第二次移动会被自己挡下来。
     */
    override suspend fun moveDocument(id: String, targetFolderId: String?): Result<Unit> = runCatching {
        val current = documentDao.getById(id) ?: documentNotFound()
        requireNoDocumentNameConflict(current.name, targetFolderId, excludeId = id)
        // 仅改归属(folder_id),正文文件不动;顺带刷新 updated_at
        documentDao.moveToFolder(id, targetFolderId, System.currentTimeMillis())
    }

    override suspend fun toggleFavorite(id: String): Result<Unit> = runCatching {
        documentDao.toggleFavorite(id)
    }

    private companion object {
        /**
         * 单次搜索最多回捞的文档数。
         *
         * FTS 的返回顺序是 rowid 序（入索引顺序），不是相关度序，所以这个 LIMIT 是
         * 「截断」而非「取前 N 相关」。取 200 是因为搜索面板本身只展示前几十条，
         * 而每条命中都要额外读一次正文文件——不封顶的话一个高频词就能把整库正文读一遍，
         * 正好抵消掉换 FTS 想省的那部分 IO。
         */
        const val SEARCH_HIT_LIMIT = 200
    }
}
