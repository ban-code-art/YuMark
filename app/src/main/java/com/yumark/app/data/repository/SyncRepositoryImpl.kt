package com.yumark.app.data.repository

import android.util.Log
import com.yumark.app.R
import com.yumark.app.core.export.UniqueFileNames
import com.yumark.app.core.text.ContentHash
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.FriendlyValidationException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.data.local.db.dao.SyncStateDao
import com.yumark.app.data.local.db.dao.SyncTombstoneDao
import com.yumark.app.data.local.db.entity.SyncStateEntity
import com.yumark.app.data.local.file.FileManager
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
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * WebDAV 同步仓库实现（P1）。
 *
 * 把本地库根级文档正文与远端目录双向对齐：决策交给纯函数 [SyncPlanner]，本类只做网络 IO 与落库，
 * 并复用 [SaveDocumentUseCase] 写入（统一重算字数）。单篇失败计入 failed 不中断整体。
 */
@Singleton
class SyncRepositoryImpl @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val configStore: SyncConfigDataStore,
    private val syncStateDao: SyncStateDao,
    private val syncTombstoneDao: SyncTombstoneDao,
    private val webDavClient: WebDavClient,
    private val documentRepository: DocumentRepository,
    private val saveDocument: SaveDocumentUseCase,
    private val fileManager: FileManager,
    private val mediaSync: com.yumark.app.data.sync.MediaSync
) : SyncRepository {

    override fun observeConfig(): Flow<WebDavConfig> = configStore.configFlow

    override suspend fun saveConfig(config: WebDavConfig) {
        configStore.updateConfig(config)
        // 配置一变就重排后台任务：开 = 注册周期同步（KEEP，不重置已有排期），
        // 关 = 注销。配置导入路径改了 enabled 的场景不经过这里，下次启动收编。
        com.yumark.app.data.sync.SyncWorkScheduler.updateSchedule(appContext, config.enabled)
    }

    override fun observeLastSyncedAt(): Flow<Long?> = configStore.lastSyncedAtFlow

    override suspend fun testConnection(config: WebDavConfig): Result<Unit> =
        webDavClient.testConnection(config)

    /**
     * 同步全程互斥。三个触发点（设置页手动、WorkManager 周期任务、启动静默同步）互不知情，
     * 撞在一起就是两轮 planner 同时读同一份 sync_state、同时 PUT 同一批文件——
     * 幂等性救得了数据，救不了流量与乱序的基线回写。排队等前一轮跑完即可。
     */
    private val syncMutex = kotlinx.coroutines.sync.Mutex()

    override suspend fun syncNow(): Result<SyncOutcome> = runCatching {
        syncMutex.withLock { syncNowLocked() }
    }.onFailure { if (it is CancellationException) throw it }

    /** [syncNow] 的实体，调用方已持 [syncMutex]。返回值即本轮结果计数。 */
    private suspend fun syncNowLocked(): SyncOutcome {
        val config = configStore.configFlow.first()
        // 这两句会直接出现在同步设置页的结果栏里。check/require 抛的是裸
        // IllegalState/IllegalArgument，会被 ErrorHandler 归类成「出现未知问题」——
        // 而「WebDAV 配置不完整」恰恰是用户自己能修的那一类。
        if (!config.enabled) throw FriendlyValidationException(
            UiMessage.Res(R.string.sync_error_disabled)
        )
        if (!config.isValid) throw FriendlyValidationException(
            UiMessage.Res(R.string.sync_error_config_incomplete)
        )

        webDavClient.ensureDir(config).getOrThrow()
        val remotes = webDavClient.list(config).getOrThrow()
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
        // P1：仅同步根级文档正文
        val localDocs = documentRepository.getAllDocuments().getOrThrow()
            .filter { it.folderId == null }
        val docById = localDocs.associateBy { it.id }
        // 消毒与去重统一交给 UniqueFileNames（那份有单测钉住：同名分组里每个成员都带自己的 id
        // 后缀因而与查询顺序无关、判重按小写、加后缀后总长仍不超 200、按码点截断不留半个 emoji）；
        // 「沿用远端已有的大小写」那一步在 [adoptRemoteCasing]，为什么要沿用、以及什么时候
        // 必须放弃沿用，都写在它的注释里。
        val fileNameByDoc = adoptRemoteCasing(
            UniqueFileNames.assign(localDocs.map { UniqueFileNames.Entry(it.id, it.name) }),
            remotes.map { it.name }
        )
        val records = syncStateDao.getAll()
        // 本地删除的痕迹：文档行早就没了，只有墓碑还记着它当初的远端路径。
        // 不读这张表的话，那些远端文件会被当成「远端独有」重新拉回来（复活）。
        val tombstones = syncTombstoneDao.getAll()
        // 条件上传要用**服务器真给的强** ETag。两种东西都进不来：代用标记（remoteVersionTag 造的
        // `mtime:…`）与弱验证器，拿去当 If-Match 都是必然 412，而 412 刻意不退化，于是那台服务器上
        // 「远端已存在的文档」上传永久失败。筛选与理由都在 [ifMatchEtagOf]，所以这里与下面比较用的
        // 版本标记分开存。
        val ifMatchByName = remotes.associate { it.name to ifMatchEtagOf(it.etag, it.etagWeak) }
        // 这台服务器的 PROPFIND 到底给不给 getetag。
        //
        // 「远端变了没」是拿 `sync_state.remote_etag` 与列目录算出的 [remoteVersionTag] 比字符串
        // （见 SyncPlanner 的 `remoteChanged`），而 PUT 的响应头是**另一条观测通道**：只有
        // PROPFIND 也给 ETag 时两者才同源、才能直接比。给不给因服务器而异，所以只能实测。
        val listExposesEtags = remotes.any { !it.etag.isNullOrBlank() }

        val plan = SyncPlanner.plan(
            locals = localDocs.map {
                SyncPlanner.LocalDocInfo(it.id, fileNameByDoc.getValue(it.id), hash(it.content))
            },
            remotes = remotes.map {
                SyncPlanner.RemoteFileInfo(it.name, remoteVersionTag(it.etag, it.lastModifiedMs))
            },
            records = records.map {
                SyncPlanner.SyncRecordInfo(it.documentId, it.remotePath, it.remoteEtag, it.localHash)
            },
            tombstones = tombstones.map {
                SyncPlanner.TombstoneInfo(it.documentId, it.remotePath)
            }
        )

        var uploaded = 0
        var downloaded = 0
        var deleted = 0
        var conflicts = 0
        var skipped = 0
        var failed = 0
        val now = System.currentTimeMillis()
        // 基线不能直接采信 PUT 响应的那些文档：循环结束后统一补一次，见 [refreshMissingBaselines]。
        // 两种情况都进这个集合 —— PUT 压根没回 ETag，以及 PUT 回了但这台服务器的 PROPFIND
        // 不给 getetag（跨通道的标记不能比，理由见 [listExposesEtags] 与 [execute]）。
        val pendingBaseline = mutableSetOf<String>()

        for (action in plan) {
            val result = runCatching {
                execute(config, action, docById, ifMatchByName, listExposesEtags, now, pendingBaseline)
            }
            result.onSuccess {
                when (action) {
                    is SyncAction.Upload -> uploaded++
                    is SyncAction.DownloadOverwrite -> downloaded++
                    is SyncAction.CreateLocal -> downloaded++
                    is SyncAction.Conflict -> conflicts++
                    is SyncAction.Skip -> skipped++
                    is SyncAction.DeleteRemote -> deleted++
                    is SyncAction.DeleteLocal -> deleted++
                    // 只清一行墓碑，没有任何文档发生变化——不该计进任何一个计数
                    is SyncAction.DropTombstone -> Unit
                }
            }.onFailure { e ->
                // 取消不是失败。`runCatching` 连 CancellationException 一起收，若在这里被记成
                // failed++ 并继续下一篇，「用户退出同步页」就变成了一次「同步失败 N 篇」，
                // 而且循环还会带着已取消的作用域接着发请求——必须原样抛回去。
                if (e is CancellationException) throw e
                failed++
                // 只记动作类型 + 脱敏摘要：`$action` 会把文档标题和远端文件名整条打进 logcat，
                // 异常本体里则是 Ktor 的完整请求 URL，而 WebDAV 的 URL 可能内嵌账号密码。
                Log.e(TAG, "同步动作失败：${action.javaClass.simpleName} / ${ErrorHandler.safeDetail(e)}")
            }
        }

        if (pendingBaseline.isNotEmpty()) refreshMissingBaselines(config, pendingBaseline, now)
        // 媒体通道（_media/ 图片）在文档动作全部落地后补跑：推送本地缺的、
        // 拉取本轮新落地文档引用的图在 execute 里已逐篇做过。整体 best-effort——
        // 图片拉不到只是暂时裂图，绝不能把一次内容已经同步成功的轮次报成失败。
        runCatching { mediaSync.pushLocalImages(config, webDavClient) }
            .onSuccess { if (it.uploaded > 0) Log.i(TAG, "媒体同步：上传 ${it.uploaded} 张") }
            .onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "媒体同步失败：${ErrorHandler.safeDetail(e)}")
            }
        configStore.setLastSyncedAt(now)
        return SyncOutcome(uploaded, downloaded, deleted, conflicts, skipped, failed)
    }

    /**
     * 给「基线不能采信 PUT 响应」的那些文档补上远端版本基线：整轮结束后**一次** PROPFIND，
     * 按文件名回填。哪些文档进这个集合、为什么，见 [canTrustPutEtag]。
     *
     * 为什么非补不可：[SyncPlanner] 判「远端变了没」只比 `sync_state.remote_etag` 与列目录读到的
     * 标记是否相等，而基线为 null 时那个判断恒为假（见 SyncPlanner 里 `remoteChanged` 的写法）。
     * 于是这篇文档从此**永远检测不出远端改动**：另一台设备怎么改都看不见，本地一有修改就直接
     * 覆盖上去，界面上一句提示都没有。而 PUT 不回 ETag 在自建 nginx dav / 部分 NAS 上是常态——
     * 上行成功的那一刻就把自己的下行能力关掉了，这是整条同步链上最隐蔽的一处。
     *
     * 为什么放在循环外做一次：每篇补一次就是每篇多一个往返。列目录本来就是同步的第一步，
     * 再来一次拿到的是「本轮所有 PUT 都落地之后」的快照，一次就够。
     *
     * 三处刻意的设计：
     * - 失败只记日志不改结果计数：补基线是锦上添花，补不上也只是回到「基线为 null」，
     *   不该把一次内容已经成功上传的同步报成失败；
     * - 只认还留在库里的那一行（`getByDocument`），并按行里的 `remotePath` 去查标记——
     *   动作执行时那行刚写过，路径一定是最新的；
     * - 只改 `remoteEtag` / `lastSyncedAt`，`localHash` 原样保留：那是本地内容的指纹，
     *   与远端标记无关，被顺手覆盖就会把「本地已改动」擦掉。
     *
     * 已知窗口：我们 PUT 完到这次 PROPFIND 之间，另一台设备若也 PUT 了同一个文件，
     * 读回的标记描述的是**对方**那一版，而本地哈希对应我们这一版 → 下轮判成 Skip，对方的改动
     * 要等它下一次改动才会被发现。任何「写后读」方案都有这个窗口，秒级，不值得为它加锁。
     */
    private suspend fun refreshMissingBaselines(config: WebDavConfig, docIds: Set<String>, now: Long) {
        val entries = webDavClient.list(config).getOrElse { e ->
            Log.w(TAG, "补远端基线时列目录失败：${ErrorHandler.safeDetail(e)}")
            return
        }
        val tagByName = entries
            .filter { !it.isDirectory }
            .associate { it.name to remoteVersionTag(it.etag, it.lastModifiedMs) }
        for (docId in docIds) {
            val state = syncStateDao.getByDocument(docId) ?: continue
            val tag = tagByName[state.remotePath] ?: continue
            syncStateDao.upsert(state.copy(remoteEtag = tag, lastSyncedAt = now))
        }
    }

    /**
     * 文档从远端落地（新建/覆盖/冲突副本）后按正文引用补齐图片。整段 best-effort：
     * [MediaSync.pullImagesFor] 内部已逐张容错，这里再兜一层是为了保证
     * 「图片通道的任何意外都不影响文档同步的结果计数」。
     */
    private suspend fun pullImagesQuietly(config: WebDavConfig, documentId: String, content: String) {
        runCatching { mediaSync.pullImagesFor(config, webDavClient, documentId, content) }
            .onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "按引用拉取图片失败：${ErrorHandler.safeDetail(e)}")
            }
    }

    private suspend fun execute(
        config: WebDavConfig,
        action: SyncAction,
        docById: Map<String, Document>,
        ifMatchEtags: Map<String, String?>,
        listExposesEtags: Boolean,
        now: Long,
        pendingBaseline: MutableSet<String>
    ) {
        when (action) {
            is SyncAction.Upload -> {
                val doc = docById.getValue(action.docId)
                // 远端已有同名文件、且服务器给的是强 ETag → 带 If-Match：从 PROPFIND 读到 etag 到 PUT
                // 发出之间，另一台设备可能刚改了同一篇，无条件 PUT 会把对方的修改静默冲掉。远端没有
                // 这个文件（新建/改名）或那台服务器只给弱验证器时 map 里是 null，退化成无条件 PUT，
                // 正是想要的。
                val etag = webDavClient
                    .upload(config, action.fileName, doc.content, ifMatchEtags[action.fileName])
                    .getOrThrow()
                deleteOldRemote(config, action.deleteOldPath)
                if (!canTrustPutEtag(etag, listExposesEtags)) pendingBaseline += doc.id
                syncStateDao.upsert(stateOf(doc.id, action.fileName, etag, hash(doc.content), now))
            }

            is SyncAction.DownloadOverwrite -> {
                val doc = docById.getValue(action.docId)
                val content = webDavClient.download(config, action.fileName).getOrThrow()
                saveDocument(doc.copy(content = content)).getOrThrow()
                pullImagesQuietly(config, doc.id, content)
                syncStateDao.upsert(stateOf(doc.id, action.fileName, action.remoteEtag, hash(content), now))
            }

            is SyncAction.CreateLocal -> {
                val content = webDavClient.download(config, action.fileName).getOrThrow()
                val created = documentRepository
                    .createDocument(nameFromFileName(action.fileName), null).getOrThrow()
                saveDocument(created.copy(content = content)).getOrThrow()
                pullImagesQuietly(config, created.id, content)
                syncStateDao.upsert(stateOf(created.id, action.fileName, action.remoteEtag, hash(content), now))
            }

            is SyncAction.Conflict -> {
                val doc = docById.getValue(action.docId)
                // 1) 远端内容存为本地冲突副本（保留远端版本）。
                // 这一步失败就整个动作放弃，**两步的顺序不能调**：下面第 2 步会用本地内容盖掉
                // 远端，而远端那一版此刻只存在于服务器上，副本没建成就先上传等于把它抹掉。
                val remoteContent = webDavClient.download(config, action.fileName).getOrThrow()
                val copy = createConflictCopy(doc.name, now)
                saveDocument(copy.copy(content = remoteContent)).getOrThrow()
                // 冲突副本是真实落地的文档：它正文里引用的图同样要补齐，
                // 否则用户打开副本看到的是一篇裂图的「远端版本」
                pullImagesQuietly(config, copy.id, remoteContent)
                // 2) 本地内容上行（本地在原文件名上胜出），刷新基线。
                // 这一步**故意**不带 If-Match：远端版本已经在上一步存成本地副本了，没有丢数据的风险，
                // 而列目录时读到的 etag 到这里已经隔了一次下载，带上只会平添一次 412 失败。
                val etag = webDavClient.upload(config, action.fileName, doc.content).getOrThrow()
                // 这次冲突同时是一次改名时（新名字被另一份远端文件占着），旧路径也要清掉
                deleteOldRemote(config, action.deleteOldPath)
                if (!canTrustPutEtag(etag, listExposesEtags)) pendingBaseline += doc.id
                syncStateDao.upsert(stateOf(doc.id, action.fileName, etag, hash(doc.content), now))
            }

            is SyncAction.Skip -> {
                syncStateDao.upsert(stateOf(action.docId, action.fileName, action.remoteEtag, action.localHash, now))
            }

            is SyncAction.DeleteRemote -> {
                // 顺序不能反：先删远端、成功了才清墓碑。反过来的话删远端一失败，
                // 墓碑已经没了，那个文件从此没人认领——下次同步被当成「远端独有」拉回本地，
                // 删掉的文档就地复活，而用户看到的只是一次「失败 1 篇」。
                webDavClient.delete(config, action.fileName).getOrThrow()
                syncTombstoneDao.deleteByDocument(action.docId)
            }

            is SyncAction.DeleteLocal -> {
                val doc = docById.getValue(action.docId)
                // 救援先于一切：sync_trash 里那份带原名的拷贝是「删除确实发生过」的可浏览证据
                // （设置页有入口）。schema 14 起本地删除落在回收站里不再销毁内容，救援从
                // 「最后一份拷贝的抢救」降级为「证据副本」，但保留它——回收站可能被用户
                // 清空，两份有界留存互为兜底。救援失败整个动作判失败（getOrThrow），
                // 下轮重判仍是 DeleteLocal、重试幂等，绝不「救不了也照删」。
                fileManager.rescueBeforeRemoteDelete(doc.id, doc.name).getOrThrow()
                // 进回收站而不是彻底删除：远端删除的传播终点是「本地也删掉」，但另一台设备
                // 上的删除动作未必是本机用户的本意——软删除保留一条应用内恢复路径，与
                // SyncPlanner「宁可复活一篇，也不误删一篇」的删除原则同向。回收站里躺够
                // 保留期或被显式清空时才走彻底删除，那时才立墓碑/清文件。
                documentRepository.moveToTrash(action.docId).getOrThrow()
                // 清一块可能存在的旧墓碑：这次删除源自远端，远端文件已经不在了，
                // 任何残留墓碑都只会让下一轮多发一次注定 404 的 DELETE。
                syncTombstoneDao.deleteByDocument(action.docId)
            }

            is SyncAction.DropTombstone -> {
                syncTombstoneDao.deleteByDocument(action.docId)
            }
        }
    }

    /**
     * 改名后清理旧远端文件。
     *
     * 失败不致命（留孤儿，P2 清理），但必须留痕：那份孤儿文件内容陈旧，
     * 下次同步会被当成「远端独有」拉回来，变成一篇重复文档。
     */
    private suspend fun deleteOldRemote(config: WebDavConfig, oldPath: String?) {
        val old = oldPath ?: return
        webDavClient.delete(config, old).onFailure { e ->
            Log.w(TAG, "改名后清理旧远端文件失败：${ErrorHandler.safeDetail(e)}")
        }
    }

    /**
     * 这次 PUT 响应里的 ETag 能不能直接当远端基线用。不能则该文档记进 `pendingBaseline`，
     * 由 [refreshMissingBaselines] 整轮结束后用**一次** PROPFIND 补齐。
     *
     * 两种不能：
     * - PUT 压根没回 ETag（自建 nginx dav / 部分 NAS 上是常态）：基线为 null 会让 SyncPlanner 的
     *   `remoteChanged` 恒为假，这篇文档从此永远检测不出远端改动；
     * - PUT 回了 ETag，但这台服务器的 PROPFIND 不给 `getetag`：基线里躺着一个真 ETag，而下一轮
     *   比较侧算出来的是 `mtime:…`（[remoteVersionTag] 的退路），两个字符串必然不等 →
     *   判成「远端变了」。本地没改就白下载一遍（自愈，但每次上传后都要来一次）；本地恰好也改过，
     *   就走 [SyncAction.Conflict]——**凭空多出一份「冲突副本」文档**，而两边内容其实一模一样。
     *
     * 反过来两个通道都给 ETag 时刻意留用 PUT 那个：它描述的正是我们刚写上去的那一版，
     * 而 [refreshMissingBaselines] 的补写有个「写后读」窗口（见那边的注释），能少走就少走。
     */
    private fun canTrustPutEtag(etag: String?, listExposesEtags: Boolean) =
        etag != null && listExposesEtags

    private fun stateOf(docId: String, path: String, etag: String?, localHash: String?, now: Long) =
        SyncStateEntity(
            documentId = docId,
            remotePath = path,
            remoteEtag = etag,
            localHash = localHash,
            lastSyncedAt = now
        )

    /**
     * 远端文件名 → 本地文档标题。
     *
     * 后缀**按大小写不敏感**剥：`list()` 认 `.MD`，若这里只剥小写的 `.md`，`NOTE.MD` 会创建出一篇
     * 标题为 `NOTE.MD` 的文档，下次同步按标题算出的文件名是 `NOTE.MD.md`——远端多一份，
     * 原来的 `NOTE.MD` 又被当成「远端独有」再拉一遍，每次同步都多一篇重复文档。
     */
    private fun nameFromFileName(fileName: String): String {
        val stem = if (fileName.endsWith(".md", ignoreCase = true)) fileName.dropLast(3) else fileName
        return stem.ifBlank { "未命名" }
    }

    /**
     * 建一篇承载远端版本的冲突副本，名字撞了就换一个再试。
     *
     * 为什么必须能重试：`createDocument` 先过 `requireNoDocumentNameConflict`，同名就抛
     * [FriendlyValidationException]。而冲突副本落在根目录，根目录**没有**唯一索引兜底
     * （`folder_id` 为 NULL，SQLite 不认为两个 NULL 相等，见 Entities.kt 里 `documents` 索引上
     * 的注释），所以那条检查就是唯一的闸门，也是唯一挡得住副本创建的原因——换个不重名的名字
     * 就能过去。
     *
     * 从前的名字只到「天」（`yyyy-MM-dd`），于是同一篇文档在同一天里的第二次冲突必然与第一次的
     * 副本同名：`createDocument` 抛异常 → 整个 Conflict 动作被 `.getOrThrow()` 打断 → failed++，
     * 而紧随其后的「本地内容上行」压根没跑。结果是这一整天里每次同步都在重复同一次失败：
     * 远端始终停在对方那一版，本地的修改一次都传不上去，界面上只有一个「失败 1 篇」。
     *
     * 现在时间戳精确到秒，正常情况一次就成；再撞（同一秒内，或用户手里本就有一篇叫这个名字的
     * 文档）就在尾部加序号，最多试 [CONFLICT_NAME_ATTEMPTS] 次。
     *
     * 只对 [FriendlyValidationException] 重试：磁盘写失败、库被锁这类真错误必须原样抛出去，
     * 拿它们去重试只是把一次失败重复几遍。[CancellationException] 要**先**判——
     * `FriendlyValidationException` 是 `IllegalArgumentException` 的子类，两个判断的顺序稍有
     * 差池就会把「协程已取消」当成重名，一路重试到试满。
     */
    private suspend fun createConflictCopy(baseName: String, now: Long): Document {
        var lastFailure: Throwable? = null
        for (attempt in 0 until CONFLICT_NAME_ATTEMPTS) {
            val result = documentRepository.createDocument(conflictCopyName(baseName, now, attempt), null)
            val error = result.exceptionOrNull() ?: return result.getOrThrow()
            if (error is CancellationException) throw error
            if (error !is FriendlyValidationException) throw error
            lastFailure = error
        }
        // 试满都撞名：把最后一次失败原样抛出去，由 syncNow 记 failed++ 并留一条脱敏日志。
        // 刻意**不**退化成「跳过副本、直接上传本地」——那会用本地内容盖掉远端版本，
        // 而远端那一版此刻在任何地方都没有留存，是真丢数据。
        // `?:` 那一支不可达（循环至少跑一轮，每一轮都要么返回、要么抛、要么给 lastFailure 赋值），
        // 只是为了让类型系统满意。
        throw lastFailure ?: IllegalStateException("conflict copy name exhausted")
    }

    // 算法搬到 core/text：Agent 的编辑提议也要按同一套指纹判断「原文有没有被改过」，
    // 两边各留一份 SHA-256 只会在某天分叉。
    private fun hash(content: String): String = ContentHash.of(content)

    companion object {
        private const val TAG = "YuMarkSync"

        /**
         * 冲突副本名字最多试几次。
         *
         * 秒级时间戳已经让第一次就成为常态，这几次是给「同一秒内的第二次冲突」和「用户手里
         * 本来就有一篇同名文档」兜底。取小值是刻意的：每次失败都是一次实打实的库查询，
         * 而试到第 8 次还撞名说明撞的不是时间戳，加大次数也救不回来。
         */
        private const val CONFLICT_NAME_ATTEMPTS = 8
    }
}

/**
 * 冲突副本的文档名：`原名 (冲突 2026-09-03 14-30-15)`，[attempt] 大于 0 时尾部再加序号。
 *
 * 时间戳到**秒**而不是到天：到天的话同一篇文档一天里的第二次冲突必然与第一次的副本同名，
 * 而根目录同名会被 `requireNoDocumentNameConflict` 拒掉（详见
 * [SyncRepositoryImpl] 里 `createConflictCopy` 的注释）。
 *
 * 用 `HH-mm-ss` 而不是 `HH:mm:ss`：这个名字会经 [UniqueFileNames] 变成远端文件名，
 * 冒号在 Windows 和多数 WebDAV 服务端上都是非法字符。`FileNameValidator.sanitize` 确实会替掉它，
 * 但那样文档标题与远端文件名就长得不一样，排查时对不上账。
 *
 * `Locale.ROOT` 而不是 `getDefault()`：默认区域可能带非公历日历或非 ASCII 数字
 * （`fa-IR` 会把年份写成 ۱۴۰۵），生成的名字既不可读也不可排序。这个字符串是机器痕迹，
 * 不是给用户看的日期展示。
 *
 * 纯函数（[SimpleDateFormat] 与 [Date] 都在 JDK 里，不碰 android.jar），可直接单测。
 */
internal fun conflictCopyName(baseName: String, now: Long, attempt: Int = 0): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(Date(now))
    // 序号从 2 起：人读「副本 (2)」是第二份，读「副本 (1)」会以为还有个第 0 份
    val suffix = if (attempt <= 0) "" else " ${attempt + 1}"
    return "$baseName (冲突 $stamp$suffix)"
}

/**
 * 远端「版本标记」：优先用真 ETag，服务器不给就用 Last-Modified 造一个 `mtime:<毫秒>`。
 *
 * [SyncPlanner] 判「远端变了没」只比这个字符串是否与基线相等，而 ETag 是可选响应头，
 * 自建 nginx dav、部分 NAS 压根不给。在那些服务器上远端改动**永远检测不出来**，
 * 本地会拿旧内容一遍遍覆盖远端，且界面上一句提示都没有。Last-Modified 是 PROPFIND 的标准属性，
 * [com.yumark.app.domain.model.RemoteEntry] 早就解析好了，只是之前没人用。
 *
 * 它只作为不透明字符串参与相等比较，落在 `sync_state.remote_etag`（本就是可空 TEXT，不动表结构）。
 * 两个已知局限：HTTP-date 只到秒，同一秒内的连续改动分辨不出；ETag 与 Last-Modified 都没有时，
 * 仍然退化成「只比本地内容哈希」。
 *
 * 纯函数，可单测（见 SyncRepositoryImplTest 末尾几条用例）。
 */
internal fun remoteVersionTag(etag: String?, lastModifiedMs: Long?): String? =
    etag?.takeIf { it.isNotBlank() } ?: lastModifiedMs?.let { "mtime:$it" }

/**
 * 这个远端 ETag 能不能拿去当 `If-Match`。不能就返回 null，让那一次 PUT 退化成无条件上传。
 *
 * 两种不能：
 * - **空**（服务器压根不给 `getetag`，或给了个 `""`）：没有条件可言；
 * - **弱验证器**（`W/"…"`，[weak] 为真）：RFC 9110 §13.1.1 规定 `If-Match` 用强比较，§8.8.3.2 又规定
 *   强比较要求两个标签都不是弱的——弱标签在强比较下连自己都不等于自己。
 *
 * 弱那一条是这里的要害。[com.yumark.app.data.remote.webdav.WebDavEtags] 会把 `W/"abc"` 剥成 `abc`，
 * 光看剥完的字符串是认不出弱强的；不筛就等于把一个冒充强标签的弱验证器发出去，服务器只能回 412，
 * 而 412 **刻意不退化**（`WebDavClient.upload` 的退化名单只有 400/501，注释里写着「412 绝不退化，
 * 那正是要拦的情况」）。后果是：那台服务器上凡是远端已经存在的文档，上传**每一轮都失败**，
 * 界面上只有一句「失败 N 篇」，而真正的原因（服务器的 ETag 是弱的）在任何地方都看不到。
 * 这不是小众配置——nginx 及各类反向代理一开 gzip 就会把强 ETag 改写成弱的。
 *
 * 代价是弱验证器的服务器上失去「防覆盖对方修改」这道闸门（退化成无条件 PUT）。这不是取舍，
 * 是协议本身给的唯一出路：弱标签在 `If-Match` 下无论怎么发都不可能匹配成功。而丢掉的那道闸门本就
 * 只在「两台设备在同一次同步的几百毫秒窗口内改同一篇」时起作用，比「上传永久失败」轻得多；
 * 真正的双改检测仍由 [SyncPlanner] 按内容哈希 + 版本标记做，走 [SyncAction.Conflict]，那条路不受影响。
 *
 * 纯函数，可直接单测。
 */
internal fun ifMatchEtagOf(etag: String?, weak: Boolean): String? =
    etag?.takeIf { it.isNotBlank() && !weak }

/**
 * 把 [UniqueFileNames.assign] 算出的文件名改写成**远端已经在用的那个大小写拼法**。
 *
 * 为什么要沿用：远端已存在 `note.md`，而本地把标题改成了 `Note` —— 在大小写不敏感的服务端
 * （Windows 后端、多数商业网盘的 WebDAV 网关）上两者是同一个文件，而 [SyncPlanner] 会按
 * 「基线路径 ≠ 本轮文件名」判成一次改名，走「PUT 新名 + DELETE 旧名」：两个名字指向同一份
 * 东西，于是 DELETE 把刚 PUT 进去的内容删掉，一次同步之后远端什么都不剩。
 * 换拼写不改小写形态，所以这一步不会破坏 assign 保证的「小写唯一」。
 *
 * 为什么只沿用**独苗**（`size == 1`）：同一小写形态下摆着两个及以上远端文件，本身就证明这台
 * 服务器对大小写是敏感的（自建 nginx dav、ext4 上的 Nextcloud），`Note.md` 与 `note.md` 是两篇
 * 不同的文档。从前这里是 `associate`——后者胜，沿用到哪一个取决于 PROPFIND 的返回顺序，而那个
 * 顺序 WebDAV 规范并不保证。基线里记着 `Note.md`、这一轮却算出 `note.md`，[SyncPlanner] 照样
 * 判成改名，于是「本地内容 PUT 到 note.md + DELETE Note.md」：自己那份远端文件被删掉，另一篇
 * 毫不相干的文档被本地内容顶掉（它的正文只剩一份冲突副本），下一轮顺序一变再反向来一遍。
 *
 * 认不准就不认：留着 assign 给的名字。它要么精确命中远端同名文件（大小写敏感的服务器上这才是
 * 对的那一个），要么在远端新建一个，两条路都不会碰到别人的文件。
 *
 * 纯函数，[assigned] 的键序原样保留（`LinkedHashMap` 语义），可直接单测。
 */
internal fun adoptRemoteCasing(
    assigned: Map<String, String>,
    remoteNames: List<String>
): Map<String, String> {
    val unambiguous = remoteNames
        .groupBy { it.lowercase() }
        .filterValues { it.size == 1 }
        .mapValues { (_, group) -> group.single() }
    return assigned.mapValues { (_, fileName) -> unambiguous[fileName.lowercase()] ?: fileName }
}
