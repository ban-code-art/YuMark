package com.yumark.app.data.local.db.dao

import androidx.room.*
import com.yumark.app.data.local.db.entity.DocumentEntity
import com.yumark.app.data.local.db.entity.DocumentVersionEntity
import com.yumark.app.data.local.db.entity.FolderEntity
import com.yumark.app.data.local.db.entity.ImageEntity
import com.yumark.app.data.local.db.entity.SyncStateEntity
import com.yumark.app.data.local.db.entity.SyncTombstoneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    // 列表类查询一律 `deleted_at IS NULL`：回收站里的文档不出现在任何库视图、搜索与
    // 同步清单里（同步侧靠这一点让远端文件成为「孤儿」而不是被复活，见 SyncPlanner）。
    // getById / observeById 刻意**不过滤**：展开态双窗格下文档在列表侧被移入回收站时，
    // 右侧编辑器还开着，过滤会让 observeDocument 突然发 null、自动保存链路掉线；
    // 回收站文档没有任何 UI 入口能被再次打开，不过滤没有暴露面。

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeById(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    suspend fun getAll(): List<DocumentEntity>

    /**
     * 同一文件夹下的**活跃**文档；`folderId` 传 null（根目录）时也查得到。
     *
     * 关键在 `IS` 而不是 `=`：SQL 里 `folder_id = NULL` 恒为 NULL，永远匹配不到任何行，
     * 所以「= 版本 + 可空参数」这个组合在根目录上永远返回空表——而根目录恰好是新建文档的默认落点。
     * 曾经并存的 `getByFolder`（用 `=`）已删除：留着它只会让下一个调用方随手挑中错的那个。
     * 对非空 folderId，`IS` 与 `=` 等价且同样能走 `folder_id` 索引，不存在只用一个方法的代价。
     */
    @Query("SELECT * FROM documents WHERE folder_id IS :folderId AND deleted_at IS NULL ORDER BY updated_at DESC")
    suspend fun getByFolderIncludingRoot(folderId: String?): List<DocumentEntity>

    /**
     * 批量按 id 取**活跃**文档元数据（全文搜索命中后回捞用）。
     * 返回顺序由 SQLite 决定，与传入 ids 的顺序无关，需要保序请在调用侧重排。
     */
    @Query("SELECT * FROM documents WHERE id IN (:ids) AND deleted_at IS NULL")
    suspend fun getByIds(ids: List<String>): List<DocumentEntity>

    /**
     * 插入一篇新文档。**刻意不带 `onConflict = REPLACE`**（即默认的 ABORT）。
     *
     * 从 schema 12 起 `documents(folder_id, name)` 上有唯一索引（见 [DocumentEntity]），
     * 而 `INSERT OR REPLACE` 的语义是「先删掉冲突行再插」——冲突行一被删，`images`、
     * `document_versions`、`sync_state` 上的 CASCADE 会把那篇文档的图片、历史版本和同步态
     * 一起带走。本该兜住并发重名的最后一道闸，反而成了静默的数据丢失。ABORT 则是抛
     * SQLiteConstraintException，由上层当失败上报。
     *
     * 对正常流程零影响：唯一的调用方 `DocumentRepositoryImpl.createDocument` 每次都用新
     * UUID，主键永不冲突；名字冲突本来就该失败。
     */
    @Insert
    suspend fun insert(document: DocumentEntity)

    // 这里刻意**没有**整行的 `@Update`。`documents` 一行上每个可变字段都有专属写入路径
    // （[rename] / [moveToFolder] / [toggleFavorite] / [updateContentMeta]；`created_at`
    // 只在插入时写一次）。留一个整行 update 就是留一个后门：自动保存每隔几秒带着 ViewModel
    // 内存里那份可能已经过期的快照跑一次，一次整行覆盖就把用户刚在文件列表里改好的名字 /
    // 归属 / 收藏静默回滚掉，且界面上没有任何提示。理由详见 [updateContentMeta]。

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 删一篇文档前，把它的远端路径抄进 `sync_tombstones`。
     *
     * `INSERT … SELECT` 而不是「先查出来再插」：一条语句、零往返，而且没有同步记录时
     * SELECT 什么都不产出、自然什么都不插——「只给同步过的文档立碑」这条规则不用写 if。
     *
     * 必须在删文档行**之前**执行：`sync_state` 挂着指向 `documents` 的 CASCADE 外键，
     * 文档行一删，那条记录连远端路径一起消失，之后再想立碑就无从查起。
     */
    @Query(
        "INSERT OR REPLACE INTO sync_tombstones (document_id, remote_path, deleted_at) " +
            "SELECT document_id, remote_path, :deletedAt FROM sync_state WHERE document_id = :id"
    )
    suspend fun recordTombstone(id: String, deletedAt: Long)

    /**
     * 删一篇文档并留下墓碑，两件事在同一个事务里。（schema 14 起这是**彻底删除**专用的路径：
     * UI 的「删除」先走 [trashById] 进回收站，只有回收站里的彻底删除与同步的远端删除传播
     * 会走到这里。）
     *
     * 为什么必须同一个事务：只删不立碑，下次同步会把远端那个文件当成「另一台设备新建的」
     * 拉回来，删掉的文档就地复活；只立碑不删，下次同步反过来把远端文件删了，
     * 而本地文档还在——两种中间态都是实打实的数据错误，所以要么都成要么都不成。
     */
    @Transaction
    suspend fun deleteWithTombstone(id: String, deletedAt: Long) {
        recordTombstone(id, deletedAt)
        deleteById(id)
    }

    // ===== 回收站（schema 14 起）=====
    //
    // 软删除的全部写入路径都收在这几条里。要点：
    // - **不立墓碑、不动 sync_state**。远端文件必须原样留到「彻底删除」那一刻，恢复才有意义；
    //   同步侧靠「软删除文档不在同步清单里 + sync_state 记录仍在」把它登记为孤儿文件，
    //   既不会被当成「远端独有」拉回来，也不会被删（见 SyncPlanner.plan）。
    // - **name 改写成 id、原名挪进 original_name**，给 (folder_id, name) 唯一索引腾出名字槽位，
    //   回收站里躺着一篇「笔记」时用户仍能新建「笔记」。改回名字是恢复路径的事（[restoreById]）。
    // - `deleted_at IS NULL` 守卫让重复移入是幂等空操作（列表多选删除与同步路径并发时）。

    @Query(
        "UPDATE documents SET deleted_at = :deletedAt, original_name = name, name = id " +
            "WHERE id = :id AND deleted_at IS NULL"
    )
    suspend fun trashById(id: String, deletedAt: Long)

    /** 批量版 [trashById]，供文件夹子树整体进回收站用。守卫语义相同。 */
    @Query(
        "UPDATE documents SET deleted_at = :deletedAt, original_name = name, name = id " +
            "WHERE id IN (:ids) AND deleted_at IS NULL"
    )
    suspend fun trashByIds(ids: List<String>, deletedAt: Long)

    /**
     * 只把行恢复成活跃态；改名（原名被占时挑新名字）由仓库在调用本条**之前**改好
     * [original_name]，本条原样落座。SQLite 的 UPDATE 各赋值项都取**行更新前**的值，
     * 所以 `name = original_name` 与 `original_name = NULL` 同一条语句里先后无所谓。
     */
    @Query(
        "UPDATE documents SET name = original_name, original_name = NULL, deleted_at = NULL " +
            "WHERE id = :id AND deleted_at IS NOT NULL"
    )
    suspend fun restoreById(id: String)

    /** 恢复前先把 original_name 改成不冲突的名字（由仓库算好传入）。 */
    @Query("UPDATE documents SET original_name = :name WHERE id = :id")
    suspend fun updateOriginalName(id: String, name: String)

    @Query("SELECT * FROM documents WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    suspend fun getTrashed(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getTrashedById(id: String): DocumentEntity?

    /** 早于 [deletedBefore] 移入回收站的文档 id，供到期自动清理逐篇走彻底删除。 */
    @Query("SELECT id FROM documents WHERE deleted_at IS NOT NULL AND deleted_at < :deletedBefore")
    suspend fun getExpiredTrashIds(deletedBefore: Long): List<String>

    /** 回收站实时计数（文件列表的入口角标用），回收站为空时恒为 0。 */
    @Query("SELECT COUNT(*) FROM documents WHERE deleted_at IS NOT NULL")
    fun observeTrashCount(): Flow<Int>

    @Query("UPDATE documents SET folder_id = :folderId, updated_at = :updatedAt WHERE id = :id")
    suspend fun moveToFolder(id: String, folderId: String?, updatedAt: Long)

    /**
     * 只改名，不碰正文文件、不碰 `word_count`/`character_count`。
     *
     * 存在的意义是「改名不该经过正文」：走 [update] 就得先有一个完整的 `DocumentEntity`，
     * 而调用方为了凑出它会去读盘取正文，编辑器里未保存的修改就此被旧正文顶掉。
     * 与 [moveToFolder] 同一个形状——单字段 UPDATE 顺带刷 `updated_at`。
     */
    @Query("UPDATE documents SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long)

    /**
     * 保存正文之后回写这一行「正文自己的那几个字段」：时间戳 + 两个计数。
     *
     * 刻意不是整行 [update]：`documents` 这一行上的 `name` / `folder_id` / `is_favorite`
     * 各有专属写入路径（[rename] / [moveToFolder] / [toggleFavorite]），而自动保存每隔几秒
     * 就带着 ViewModel 内存里的那份快照跑一次。整行覆盖等于让自动保存拿一份可能已经过期的
     * 元数据去盖库里的现值：展开态双窗格下（左列表 + 右编辑器，见 AppShell），在列表里改名 /
     * 移动 / 收藏，几秒后就会被右边的自动保存静默回滚，界面上没有任何提示。
     *
     * `created_at` 同理——它本该只在插入时写一次。
     */
    @Query(
        "UPDATE documents SET updated_at = :updatedAt, word_count = :wordCount, " +
            "character_count = :characterCount WHERE id = :id"
    )
    suspend fun updateContentMeta(id: String, updatedAt: Long, wordCount: Int, characterCount: Int)

    @Query("UPDATE documents SET is_favorite = NOT is_favorite WHERE id = :id")
    suspend fun toggleFavorite(id: String)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: String): FolderEntity?

    @Query("SELECT * FROM folders ORDER BY `order` ASC, name ASC")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders ORDER BY `order` ASC, name ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    /**
     * 同一父级下的子文件夹；`parentId` 传 null（根级）时也查得到。理由同
     * [DocumentDao.getByFolderIncludingRoot]：`parent_id = NULL` 恒不匹配。
     *
     * 这条尤其要紧——[com.yumark.app.data.repository.FolderRepositoryImpl] 的整棵树是从
     * `buildFolderTree(null, …)` 递归下来的，用 `=` 版本时**整棵文件夹树恒为空**，
     * 根目录的文档计数也恒为 0，而这两件事都不会报错。
     */
    @Query("SELECT * FROM folders WHERE parent_id IS :parentId ORDER BY `order` ASC, name ASC")
    suspend fun getByParentIncludingRoot(parentId: String?): List<FolderEntity>

    // 同父文件夹下最大 order，用于新建/移动时取 MAX(order)+1，避免并发创建时 size 竞态导致 order 重复
    @Query("SELECT COALESCE(MAX(`order`), -1) FROM folders WHERE parent_id IS :parentId")
    suspend fun maxOrder(parentId: String?): Int

    /**
     * 插入一个新文件夹。**刻意不带 `onConflict = REPLACE`**，理由同 [DocumentDao.insert]：
     * schema 12 起 `folders(parent_id, name)` 上有唯一索引，而 REPLACE 会先删冲突行，
     * `folders.parent_id` 的 CASCADE 会顺带删掉它整棵子树的文件夹，`documents.folder_id`
     * 的 SET_NULL 又会把里面的文档倒进根目录——一次同名创建就能拆掉用户的一整棵目录。
     *
     * 两个调用方都不受影响：`createFolder` 用新 UUID；`ensureImportLibraryFolder` 用固定 id
     * 但先 `getById` 判存在，且它的 `parent_id` 是 NULL，那条唯一索引对 NULL 组本来不生效。
     */
    @Insert
    suspend fun insert(folder: FolderEntity)

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)

    // ===== 级联删除子树 =====
    // 下面这几条查的是 documents / document_search 而不是 folders，看着不该放在
    // FolderDao 里。但 Room 的 @Transaction 默认方法只能调用**同一个接口**上的方法，
    // 而「删一棵子树」必须整体成功或整体回滚（否则会留下文件夹没了、文档还挂在上面的中间态），
    // 所以参与这一次事务的语句必须聚在一起。事务的入口是 [trashSubtree]。

    /** 子树内所有文档 id（含已在回收站的，[trashDocumentsByIds] 的守卫会把后者跳过）。
     *  `IN` 天然排除 folder_id 为 NULL 的根级文档，不会误伤根目录。 */
    @Query("SELECT id FROM documents WHERE folder_id IN (:folderIds)")
    suspend fun documentIdsInFolders(folderIds: List<String>): List<String>

    @Query(
        "UPDATE documents SET deleted_at = :deletedAt, original_name = name, name = id " +
            "WHERE id IN (:ids) AND deleted_at IS NULL"
    )
    suspend fun trashDocumentsByIds(ids: List<String>, deletedAt: Long)

    /**
     * 清理这批文档的全文索引行。
     *
     * `document_search` 是 FTS4 虚拟表，既不能建外键也不能建索引，所以它既不会被
     * `documents` 的删除级联带走（这正是旧实现留下幽灵命中的原因），也没法按 doc_id 走索引。
     * 用 `IN` 一次删掉一整批：全表扫描的次数从「每篇文档一次」降到「每批一次」。
     */
    @Query("DELETE FROM document_search WHERE doc_id IN (:ids)")
    suspend fun deleteSearchIndexOf(ids: List<String>)

    @Query("DELETE FROM folders WHERE id IN (:ids)")
    suspend fun deleteFoldersByIds(ids: List<String>)

    /**
     * 在一个事务里把整棵子树送进回收站：文档软删除 → 清全文索引 → 删文件夹结构。
     *
     * 与 schema 13 及之前的 `deleteSubtree`（硬删 + 立墓碑 + 删图片行）相比，这里的每一步
     * 都服务于「子树可恢复」：
     * - 文档走软删除（[trashDocumentsByIds]），**不立墓碑、不删 images 行、不删正文文件**——
     *   恢复要能拿回全部内容；图片文件与磁盘清理由回收站的「彻底删除」负责。
     * - 全文索引行必须清：回收站里的文档不该再被搜到；恢复时仓库会按正文重建索引。
     * - 文件夹结构是硬删（文件夹不进回收站）：先删文档再删文件夹的顺序不能反，反过来
     *   `documents.folder_id` 上的外键（SET_NULL）会把还没处理的活跃文档冲进根目录；
     *   软删除的文档被 SET_NULL 倒进根目录反而是期望行为——原文件夹没了，恢复后落在根目录。
     *
     * 分批是为了 SQLite 的绑定变量上限（旧版 Android 上是 999 个）：id 列表按 400 切段，
     * 段与段仍在同一个事务里，所以「整体成功或整体回滚」不受影响。
     */
    @Transaction
    suspend fun trashSubtree(folderIds: List<String>, documentIds: List<String>, deletedAt: Long) {
        documentIds.chunked(400).forEach { chunk ->
            trashDocumentsByIds(chunk, deletedAt)
            deleteSearchIndexOf(chunk)
        }
        folderIds.chunked(400).forEach { chunk -> deleteFoldersByIds(chunk) }
    }
}

/**
 * 「本地删过这篇文档」的墓碑（见 [SyncTombstoneEntity]）。
 *
 * 写入不在这里：立碑必须与删文档行同处一个事务，所以插入语句放在
 * [DocumentDao.recordTombstone] / [FolderDao.recordTombstonesFor]。这个 DAO 只负责
 * 同步流程侧的读取与销毁。
 */
@Dao
interface SyncTombstoneDao {
    @Query("SELECT * FROM sync_tombstones")
    suspend fun getAll(): List<SyncTombstoneEntity>

    @Query("DELETE FROM sync_tombstones WHERE document_id = :docId")
    suspend fun deleteByDocument(docId: String)

    /**
     * 文档「复活」时撤掉墓碑。
     *
     * 场景：远端删除被同步到本地（删本地文档）之后，用户又从历史版本或另一台设备把同名
     * 文档拿回来。此时上一步留下的墓碑还在，下一轮同步会拿它去删刚刚传上去的远端文件。
     * 同步流程执行删远端成功后也走这个方法清场。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tombstone: SyncTombstoneEntity)
}

@Dao
interface ImageDao {
    @Query("SELECT * FROM images WHERE id = :id")
    suspend fun getById(id: String): ImageEntity?

    /** 按落盘文件名反查（文件名是应用生成的 uuid.ext，全局唯一）；媒体同步拉取侧用。 */
    @Query("SELECT * FROM images WHERE file_name = :fileName LIMIT 1")
    suspend fun getByFileName(fileName: String): ImageEntity?

    @Query("SELECT * FROM images WHERE document_id = :documentId")
    suspend fun getByDocument(documentId: String): List<ImageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: ImageEntity)

    @Query("DELETE FROM images WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM images WHERE document_id = :documentId")
    suspend fun deleteByDocument(documentId: String)

    @Query("""
        SELECT images.* FROM images
        LEFT JOIN documents ON images.document_id = documents.id
        WHERE documents.id IS NULL
    """)
    suspend fun getOrphanedImages(): List<ImageEntity>
}

@Dao
interface DocumentVersionDao {
    @Insert
    suspend fun insert(version: DocumentVersionEntity)

    @Query("SELECT * FROM document_versions WHERE document_id = :docId ORDER BY created_at DESC")
    fun observeByDocument(docId: String): Flow<List<DocumentVersionEntity>>

    @Query("SELECT * FROM document_versions WHERE id = :id")
    suspend fun getById(id: String): DocumentVersionEntity?

    @Query("SELECT * FROM document_versions WHERE document_id = :docId ORDER BY created_at DESC LIMIT 1")
    suspend fun latest(docId: String): DocumentVersionEntity?

    @Query("SELECT COUNT(*) FROM document_versions WHERE document_id = :docId")
    suspend fun count(docId: String): Int

    /** 删除超出保留数量的最旧版本（保留最近 [keep] 个）。 */
    @Query(
        """
        DELETE FROM document_versions WHERE document_id = :docId AND id NOT IN (
            SELECT id FROM document_versions WHERE document_id = :docId ORDER BY created_at DESC LIMIT :keep
        )
        """
    )
    suspend fun pruneOldest(docId: String, keep: Int)

    @Query("DELETE FROM document_versions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE document_id = :docId")
    suspend fun getByDocument(docId: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state")
    suspend fun getAll(): List<SyncStateEntity>

    @Query("DELETE FROM sync_state WHERE document_id = :docId")
    suspend fun deleteByDocument(docId: String)
}
