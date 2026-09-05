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
    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeById(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY updated_at DESC")
    suspend fun getAll(): List<DocumentEntity>

    /**
     * 同一文件夹下的文档；`folderId` 传 null（根目录）时也查得到。
     *
     * 关键在 `IS` 而不是 `=`：SQL 里 `folder_id = NULL` 恒为 NULL，永远匹配不到任何行，
     * 所以「= 版本 + 可空参数」这个组合在根目录上永远返回空表——而根目录恰好是新建文档的默认落点。
     * 曾经并存的 `getByFolder`（用 `=`）已删除：留着它只会让下一个调用方随手挑中错的那个。
     * 对非空 folderId，`IS` 与 `=` 等价且同样能走 `folder_id` 索引，不存在只用一个方法的代价。
     */
    @Query("SELECT * FROM documents WHERE folder_id IS :folderId ORDER BY updated_at DESC")
    suspend fun getByFolderIncludingRoot(folderId: String?): List<DocumentEntity>

    /**
     * 批量按 id 取元数据（全文搜索命中后回捞用）。
     * 返回顺序由 SQLite 决定，与传入 ids 的顺序无关，需要保序请在调用侧重排。
     */
    @Query("SELECT * FROM documents WHERE id IN (:ids)")
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
     * 删一篇文档并留下墓碑，两件事在同一个事务里。
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
    // 下面这几条查的是 documents / images / document_search 而不是 folders，看着不该放在
    // FolderDao 里。但 Room 的 @Transaction 默认方法只能调用**同一个接口**上的方法，
    // 而「删一棵子树」必须整体成功或整体回滚（否则会留下文件夹没了、文档还挂在上面的中间态），
    // 所以参与这一次事务的语句必须聚在一起。事务的入口是 [deleteSubtree]。

    /** 子树内所有文档 id。`IN` 天然排除 folder_id 为 NULL 的根级文档，不会误删根目录。 */
    @Query("SELECT id FROM documents WHERE folder_id IN (:folderIds)")
    suspend fun documentIdsInFolders(folderIds: List<String>): List<String>

    /** 这批文档引用的图片文件名（images 行会被 CASCADE 带走，磁盘文件得靠这个名单去删）。 */
    @Query("SELECT file_name FROM images WHERE document_id IN (:documentIds)")
    suspend fun imageFileNamesOf(documentIds: List<String>): List<String>

    @Query("DELETE FROM documents WHERE id IN (:ids)")
    suspend fun deleteDocumentsByIds(ids: List<String>)

    /**
     * 清理这批文档的全文索引行。
     *
     * `document_search` 是 FTS4 虚拟表，既不能建外键也不能建索引，所以它既不会被
     * `documents` 的删除级联带走（这正是旧实现留下幽灵命中的原因），也没法按 doc_id 走索引。
     * 用 `IN` 一次删掉一整批：全表扫描的次数从「每篇文档一次」降到「每批一次」。
     */
    @Query("DELETE FROM document_search WHERE doc_id IN (:ids)")
    suspend fun deleteSearchIndexOf(ids: List<String>)

    /** images 行本会被 documents 的 CASCADE 带走；显式删一次，外键被关掉时也不留孤儿行。 */
    @Query("DELETE FROM images WHERE document_id IN (:ids)")
    suspend fun deleteImagesOf(ids: List<String>)

    @Query("DELETE FROM folders WHERE id IN (:ids)")
    suspend fun deleteFoldersByIds(ids: List<String>)

    /**
     * 给这批即将被删的文档批量立墓碑（同 [DocumentDao.recordTombstone]，只是按 id 列表）。
     *
     * 同样必须在 [deleteDocumentsByIds] 之前跑：`sync_state` 会被文档行的 CASCADE 带走。
     */
    @Query(
        "INSERT OR REPLACE INTO sync_tombstones (document_id, remote_path, deleted_at) " +
            "SELECT document_id, remote_path, :deletedAt FROM sync_state WHERE document_id IN (:ids)"
    )
    suspend fun recordTombstonesFor(ids: List<String>, deletedAt: Long)

    /**
     * 在一个事务里删掉整棵子树的库记录：立墓碑 → 索引行 → 图片行 → 文档行 → 文件夹行。
     *
     * 顺序不能反过来。先删 `folders` 的话，`documents.folder_id` 上的外键是 SET_NULL，
     * 剩下的文档会瞬间变成根目录文档；这个事务万一后面某条语句失败回滚倒是没事，
     * 可提交成功的路径上就等于把整棵子树的文档倒进了根目录。
     *
     * 墓碑排在最前面同理：它的数据源 `sync_state` 会被文档行的 CASCADE 带走，
     * 删完再立就查不到远端路径了，删除也就传不到远端——那些文件会在下次同步被当成
     * 「远端独有」整棵子树地拉回来。
     *
     * 分批是为了 SQLite 的绑定变量上限（旧版 Android 上是 999 个）：id 列表按 400 切段，
     * 段与段仍在同一个事务里，所以「整体成功或整体回滚」不受影响。
     */
    @Transaction
    suspend fun deleteSubtree(folderIds: List<String>, documentIds: List<String>, deletedAt: Long) {
        documentIds.chunked(400).forEach { chunk ->
            recordTombstonesFor(chunk, deletedAt)
            deleteSearchIndexOf(chunk)
            deleteImagesOf(chunk)
            deleteDocumentsByIds(chunk)
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
