package com.yumark.app.data.repository

import android.util.Log
import com.yumark.app.R
import com.yumark.app.core.util.FriendlyValidationException
import com.yumark.app.core.util.PathSafety
import com.yumark.app.core.util.UiMessage
import com.yumark.app.data.ai.rag.RagPipeline
import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.db.dao.FolderDao
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.data.mapper.FolderMapper
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.FolderTree
import com.yumark.app.domain.repository.FolderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class FolderRepositoryImpl @Inject constructor(
    private val folderDao: FolderDao,
    private val documentDao: DocumentDao,
    private val fileManager: FileManager,
    private val mapper: FolderMapper,
    // data 层内部依赖（无环）：子树进回收站时把文档从 RAG 知识索引里摘掉
    private val ragPipeline: RagPipeline
) : FolderRepository {

    override suspend fun getFolderById(id: String): Result<Folder> = runCatching {
        val entity = folderDao.getById(id) ?: folderNotFound()
        mapper.toDomain(entity)
    }

    override suspend fun getAllFolders(): Result<List<Folder>> = runCatching {
        folderDao.getAll().map { mapper.toDomain(it) }
    }

    override suspend fun getFoldersByParent(parentId: String?): Result<List<Folder>> = runCatching {
        folderDao.getByParentIncludingRoot(parentId).map { mapper.toDomain(it) }
    }

    override suspend fun getFolderTree(): Result<FolderTree> = runCatching {
        buildFolderTree(null, depth = 0, visited = mutableSetOf())
    }

    override fun observeFolders(): Flow<List<Folder>> {
        return folderDao.observeAll().map { entities ->
            entities.map { mapper.toDomain(it) }
        }
    }

    override suspend fun createFolder(name: String, parentId: String?): Result<Folder> = runCatching {
        requireNoFolderNameConflict(name, parentId, excludeId = null)
        val id = UUID.randomUUID().toString()
        // order 取 MAX(order)+1，避免并发创建同父文件夹时 size 竞态导致 order 重复
        val order = folderDao.maxOrder(parentId) + 1
        val folder = Folder.create(id, name, parentId, order)
        folderDao.insert(mapper.toEntity(folder))
        folder
    }

    override suspend fun ensureImportLibraryFolder(): Result<Folder> = runCatching {
        // 导入库根用固定 ID（不走 UUID），存在则复用、不存在则惰性创建
        val existing = folderDao.getById(FolderRepository.IMPORT_LIBRARY_FOLDER_ID)
        if (existing != null) {
            mapper.toDomain(existing)
        } else {
            val folder = Folder(
                id = FolderRepository.IMPORT_LIBRARY_FOLDER_ID,
                name = FolderRepository.IMPORT_LIBRARY_FOLDER_NAME,
                parentId = null,
                createdAt = kotlinx.datetime.Clock.System.now(),
                order = 0
            )
            folderDao.insert(mapper.toEntity(folder))
            folder
        }
    }

    override suspend fun renameFolder(id: String, newName: String): Result<Unit> = runCatching {
        val entity = folderDao.getById(id) ?: folderNotFound()
        requireNoFolderNameConflict(newName, entity.parentId, excludeId = id)
        // 改名前先算出旧镜像目录（依赖旧名称链）
        val oldMirror = importMirrorDir(id)
        folderDao.update(entity.copy(name = newName))
        // 导入库子树：同步搬走 import_assets 镜像目录，否则该子树下文档的图片全部失效
        relocateImportMirror(oldMirror, id)
    }

    override suspend fun deleteFolder(id: String, deleteContents: Boolean): Result<Unit> = runCatching {
        if (!deleteContents) {
            // 不删除内容时，检查是否为空。
            // 这两条刻意保留裸 IllegalStateException、不配资源文案：全仓库两个调用点
            // （FileListScreen.kt:716、EditorViewModel.kt:778）都传 deleteContents = true，
            // 这条分支只是接口契约的守卫。真跑到这儿说明有新调用方用错了参数——那是编程错误，
            // 归到 Unknown 去占一格崩溃日志正是想要的行为，而为不可达分支加两个字符串键是纯浪费。
            // （getByFolderIncludingRoot 只返回活跃文档——躺在回收站里的旧文档不算数，
            // 它们的恢复落点在删除文件夹后统一是根目录。）
            val documents = documentDao.getByFolderIncludingRoot(id)
            if (documents.isNotEmpty()) throw IllegalStateException("Folder is not empty")
            val subfolders = folderDao.getByParentIncludingRoot(id)
            if (subfolders.isNotEmpty()) throw IllegalStateException("Folder has subfolders")

            // 删除前先算镜像目录（删完 Room 记录就找不到名称链了）；空文件夹没有内容，
            // 镜像目录一并清掉不丢任何东西
            val mirror = importMirrorDir(id)
            folderDao.deleteById(id)
            deleteImportMirror(mirror)
        } else {
            // 子树整体进回收站（schema 14 起）：文档可恢复，镜像目录与图片文件原样保留，
            // 彻底删除时再逐篇清理（见 DocumentRepositoryImpl.purgeDocument）
            deleteFolderSubtree(id)
        }
    }

    /**
     * 文件夹在 import_assets 镜像中的目录；不在导入库子树内返回 null。
     * 路径段消毒规则与导入复制时一致（[FileManager.sanitizeImportSegment]）。
     */
    private suspend fun importMirrorDir(folderId: String): File? {
        var cur = folderId
        val names = ArrayDeque<String>()
        var guard = 0
        while (cur != FolderRepository.IMPORT_LIBRARY_FOLDER_ID) {
            if (++guard > MAX_FOLDER_DEPTH) return null
            val entity = folderDao.getById(cur) ?: return null
            names.addFirst(entity.name)
            cur = entity.parentId ?: return null
        }
        return names.fold(fileManager.getImportAssetsDir()) { parent, segment ->
            File(parent, FileManager.sanitizeImportSegment(segment))
        }
    }

    /**
     * 把 import_assets 镜像目录从 [oldMirror] 搬到 [folderId] 当前名称链对应的位置。
     *
     * 改名和移动都要调，且必须在 Room 记录已经更新**之后**——新位置是照着新名称链算的。
     *
     * 选「搬目录」而不是「改写正文里的相对路径」：图片引用的 base 就是文件夹名称链
     * （见 EditorViewModel 的导入库图片解析），把目录搬到新链上，整棵子树的引用一次就全对了，
     * 子孙文件夹的镜像本来就躺在这个目录里面。改写正文得逐篇读写用户数据，
     * 正则出一次错就是把文档改坏，代价完全不对等。
     *
     * 失败只记日志：库里的改名/移动已经生效，为一次搬目录失败把它回滚，用户看到的是
     * 「重命名失败」却又找不到哪里不对；而图片失效是可逆的（改回原名即可）。
     */
    private suspend fun relocateImportMirror(oldMirror: File?, folderId: String) {
        val assetsRoot = fileManager.getImportAssetsDir()
        // oldMirror 等于镜像根说明动的是导入库根本身：镜像根不跟着改名，它是导入时的固定落点
        if (oldMirror == null || oldMirror == assetsRoot || !oldMirror.isDirectory) return
        val newMirror = importMirrorDir(folderId) ?: return
        if (newMirror == oldMirror || newMirror == assetsRoot) return
        try {
            PathSafety.requireInside(oldMirror, assetsRoot, label = "Import mirror")
            PathSafety.requireInside(newMirror, assetsRoot, label = "Import mirror")
            // 目标已存在：不覆盖别人的镜像，宁可让这一棵子树的图片暂时失效
            if (newMirror.exists()) return
            newMirror.parentFile?.mkdirs()
            if (oldMirror.renameTo(newMirror)) return
            // 同分区内 renameTo 正常都会成功；失败时退回复制+删除，宁可多占一次磁盘也别丢图
            if (oldMirror.copyRecursively(newMirror, overwrite = false)) oldMirror.deleteRecursively()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "迁移导入库镜像目录失败：${oldMirror.path}", e)
        }
    }

    /** 清理 [mirror] 镜像目录；失败只记日志，理由同 [deleteFilesOf]。 */
    private fun deleteImportMirror(mirror: File?) {
        if (mirror == null) return
        val assetsRoot = fileManager.getImportAssetsDir()
        runCatching {
            PathSafety.requireInside(mirror, assetsRoot, label = "Import mirror")
            if (mirror == assetsRoot) {
                // 删的是导入库根：清空镜像内容但保留目录本身
                mirror.listFiles()?.forEach { it.deleteRecursively() }
            } else {
                mirror.deleteRecursively()
            }
        }.onFailure { e -> Log.w(TAG, "清理导入库镜像目录失败：${mirror.path}", e) }
    }

    /**
     * 把 [folderId] 子树整体送进回收站（schema 14 起）：先算出子树集合，再在一个事务里
     * 软删文档 + 清全文索引 + 删文件夹结构，最后把文档从 RAG 知识索引里摘掉。
     *
     * 与旧实现（硬删 + 立墓碑 + 删磁盘）相比，刻意**不做**的三件事，全部服务于「可恢复」：
     * - 不立同步墓碑：远端文件留到回收站里逐篇「彻底删除」时再删（见
     *   [DocumentRepositoryImpl.purgeDocument]，届时 sync_state 仍在，立碑不受影响）。
     * - 不删图片行/图片文件/正文文件，也不清 import_assets 镜像：恢复要能拿回全部内容。
     * - 磁盘文件的清理整体后移到彻底删除——这里不碰任何文件。
     *
     * 顺序要点不变：集合必须先算完。边查边删的话，父文件夹一没，`documents.folder_id` 上
     * 的外键（SET_NULL）会把还没处理到的文档冲进根目录。软删除的文档被 SET_NULL 倒进根目录
     * 反而是期望行为——原文件夹没了，恢复后落在根目录。
     */
    private suspend fun deleteFolderSubtree(folderId: String) {
        // 一次取全表再在内存里走链：比逐层 getByParent 少很多次往返，也绕开了
        // getByParent 在 parentId 为 null 时恒返回空表的 NULL 陷阱
        val parentById = folderDao.getAll().associate { it.id to it.parentId }
        val folderIds = folderSubtreeIds(folderId, parentById)
        val documentIds = folderIds.chunked(SQL_BIND_CHUNK)
            .flatMap { chunk -> folderDao.documentIdsInFolders(chunk) }

        folderDao.trashSubtree(folderIds, documentIds, System.currentTimeMillis())
        // RAG 索引摘除放事务外、逐篇 best-effort：失败的文档留在知识索引里，
        // 恢复或彻底删除时都会再走一遍同一个入口，有自愈路径
        documentIds.forEach { docId ->
            runCatching { ragPipeline.removeFromIndex(docId) }
                .onFailure { e -> Log.w(TAG, "回收站移除文档知识索引失败：${docId}", e) }
        }
    }

    override suspend fun moveFolder(id: String, targetParentId: String?): Result<Unit> = runCatching {
        val entity = folderDao.getById(id) ?: folderNotFound()
        // 防环:不能移动到自身,也不能移动到自己的子孙(否则子树脱离根、构建树时死循环)
        if (targetParentId == id) throw FriendlyValidationException(
            UiMessage.Res(R.string.folder_error_move_into_self)
        )
        if (targetParentId != null && isDescendant(targetParentId, ancestorId = id)) {
            throw FriendlyValidationException(
                UiMessage.Res(R.string.folder_error_move_into_descendant)
            )
        }
        // 目标位置已有同名文件夹就先拦住：两棵子树会算出同一个镜像目录，
        // 下面的镜像迁移也就无处可搬（会看到目标已存在而放弃）
        requireNoFolderNameConflict(entity.name, targetParentId, excludeId = id)
        // 移动前先算旧镜像目录（依赖旧名称链）
        val oldMirror = importMirrorDir(id)
        // 追加到目标文件夹末尾,避免与目标内既有 order 冲突
        val newOrder = folderDao.maxOrder(targetParentId) + 1
        folderDao.update(entity.copy(parentId = targetParentId, order = newOrder))
        // 跨导入库边界移动会改变名称链：镜像目录必须跟着搬，否则整棵子树的相对图片引用失效。
        // 移出导入库时 importMirrorDir 返回 null，此时旧镜像原地留着——那些图片已经没有
        // 名称链能指到它们了，清理留给「孤儿资源清理」，这里绝不能删（用户还可能移回来）。
        relocateImportMirror(oldMirror, id)
    }

    /**
     * 同一父级下不许出现同名文件夹。
     *
     * 挡住的不只是「列表里两行长得一样」：导入库镜像目录是按**文件夹名**逐段拼出来的
     * （见 [importMirrorDir]），两个同名兄弟会指向同一个镜像目录，图片直接互相覆盖。
     *
     * 抛 [FriendlyValidationException] 而不是普通异常：它带 [UiMessage] 且被 ErrorHandler
     * 认作用户可见错误，文案会原样进 Snackbar，而不是被兜底成「出现未知问题，请重试」。
     */
    private suspend fun requireNoFolderNameConflict(
        name: String,
        parentId: String?,
        excludeId: String?
    ) {
        val siblings = folderDao.getByParentIncludingRoot(parentId).map { NamedEntry(it.id, it.name) }
        val conflict = findNameConflict(siblings, name, excludeId) ?: return
        throw FriendlyValidationException(
            UiMessage.of(R.string.folder_error_duplicate_name, conflict.name)
        )
    }

    /**
     * 库里查不到这一行。与 `DocumentRepositoryImpl.documentNotFound()` 同一套理由：
     *
     * 从前三处都是 `throw Exception("Folder not found: $id")`，代价有两笔——
     *  - `ErrorHandler.classify` 把裸 `Exception` 归到 `Unknown`，用户看到「出现未知问题，
     *    请重试」，而真正发生的事是「这个文件夹已经被删掉了」，说不清就会一直重试；
     *  - `Unknown` 的 `worthRecording` 为 true，每一次并发删除都吃掉一格崩溃日志配额
     *    （总共 20 格）。平板双栏下一栏删文件夹、另一栏对同一行点改名/移动是很平常的竞态。
     *
     * 返回 [Nothing] 以便直接写在 `?:` 右边。刻意不带 id：文案是给用户看的，
     * 而这条失败按设计不进日志，UUID 没有去处。
     */
    private fun folderNotFound(): Nothing =
        throw FriendlyValidationException(UiMessage.Res(R.string.folder_error_not_found))

    /** folderId 是否是 ancestorId 的后代(沿 parentId 链上溯,带深度/循环 guard)。 */
    private suspend fun isDescendant(folderId: String, ancestorId: String): Boolean {
        var current: String? = folderId
        val visited = mutableSetOf<String>()
        var depth = 0
        while (current != null) {
            if (current == ancestorId) return true
            if (current in visited || depth > MAX_FOLDER_DEPTH) return false
            visited.add(current)
            current = folderDao.getById(current)?.parentId
            depth++
        }
        return false
    }

    /**
     * 递归构建文件夹树
     * @param parentId 父文件夹 ID
     * @param depth 当前深度
     * @param visited 已访问的文件夹 ID（循环检测）
     */
    private suspend fun buildFolderTree(
        parentId: String?,
        depth: Int,
        visited: MutableSet<String>
    ): FolderTree {
        // 深度限制检查
        if (depth > MAX_FOLDER_DEPTH) {
            throw IllegalStateException("Folder tree depth exceeds limit: $MAX_FOLDER_DEPTH")
        }

        // 循环引用检查
        if (parentId != null && parentId in visited) {
            throw IllegalStateException("Circular folder reference detected: $parentId")
        }

        val folder = parentId?.let { folderDao.getById(it) }?.let { mapper.toDomain(it) }
        // 必须走 IncludingRoot 版本：这个递归的入口就是 parentId = null（见 [getFolderTree]），
        // 用 `parent_id = NULL` 的那版时根级子文件夹一个都查不到，整棵树恒为空。
        val children = folderDao.getByParentIncludingRoot(parentId)

        // 将当前文件夹添加到已访问集合
        if (parentId != null) {
            visited.add(parentId)
        }

        val childTrees = children.map { child ->
            buildFolderTree(child.id, depth + 1, visited)
        }

        val documentCount = documentDao.getByFolderIncludingRoot(parentId).size
        return FolderTree(folder, childTrees, documentCount)
    }

    companion object {
        private const val MAX_FOLDER_DEPTH = 100  // 最大文件夹层级
        private const val TAG = "FolderRepository"

        /**
         * 单条 `IN (:ids)` 里最多塞多少个 id。
         *
         * SQLite 的绑定变量上限在旧版 Android 上是 999，超了直接抛
         * `too many SQL variables`——删一个装了上千篇文档的文件夹正好会撞上。
         * 取 400 是留足余量，反正多切几段的代价只是多几次语句执行。
         */
        private const val SQL_BIND_CHUNK = 400
    }
}

/**
 * 一条「有 id 有名字」的条目，重名判定只需要这两样。
 *
 * 刻意不用 Room 实体：判定逻辑因此能在 JVM 单测里直接跑，不必拖上整个数据库。
 */
internal data class NamedEntry(val id: String, val name: String)

/**
 * 在同级条目 [siblings] 里找出与 [name] 重名的那一个，没有则返回 null。
 *
 * 两个细节是刻意的：
 * - [excludeId] 用来在改名时排掉自己，否则任何一次「名字没改」的保存都会被自己挡下来；
 * - 比较前 trim，但**不**忽略大小写。忽略大小写会让导入侧的「同名子文件夹复用」判定和这里
 *   打起来：源目录同时有 `Notes/` 和 `notes/` 时，复用找不到、创建又被拦，整次导入直接失败。
 *   只差大小写的两个条目留给导出侧去消重。
 *
 * 导入侧（`ImportFolderUseCase.resolveFolderPath`）复用的就是本函数，不再自己写一遍比较——
 * 它从前是裸 `it.name == segment`，少了 trim 这一步，于是「Notes 」这样的段落正好撞上上面
 * 描述的死局。规则只留一份，两侧才不会再分家。
 *
 * 返回的是**已存在**的那一条，好让提示语回显库里真实的名字，而不是用户刚敲的那个。
 */
internal fun findNameConflict(
    siblings: List<NamedEntry>,
    name: String,
    excludeId: String? = null
): NamedEntry? {
    val target = name.trim()
    return siblings.firstOrNull { it.id != excludeId && it.name.trim() == target }
}

/**
 * 从 [rootId] 出发，按 parentId 关系列出整棵子树的文件夹 id（含 [rootId] 本身，广度优先）。
 *
 * [parentById] 是「id → parentId」的全量快照（一次 `getAll()` 就够），因此这里是纯内存计算：
 * 删除前先把待删集合算完，才能在删库之后还知道该去磁盘上删哪些文件。
 *
 * 已访问集合同时兼作环检测：库里真出现 A→B→A 时只会各访问一次然后停下，
 * 不会像沿链递归那样栈溢出。[rootId] 不在 [parentById] 里（已被并发删掉）时只返回它自己。
 */
internal fun folderSubtreeIds(rootId: String, parentById: Map<String, String?>): List<String> {
    val childrenByParent = parentById.entries.groupBy({ it.value }, { it.key })
    val result = mutableListOf<String>()
    val seen = mutableSetOf(rootId)
    val queue = ArrayDeque<String>()
    queue.addLast(rootId)
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        result += id
        childrenByParent[id]?.forEach { child -> if (seen.add(child)) queue.addLast(child) }
    }
    return result
}
