package com.yumark.app.data.local.db.dao

import androidx.room.*
import com.yumark.app.data.local.db.entity.DocumentEntity
import com.yumark.app.data.local.db.entity.DocumentVersionEntity
import com.yumark.app.data.local.db.entity.FolderEntity
import com.yumark.app.data.local.db.entity.ImageEntity
import com.yumark.app.data.local.db.entity.SyncStateEntity
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

    @Query("SELECT * FROM documents WHERE folder_id = :folderId ORDER BY updated_at DESC")
    suspend fun getByFolder(folderId: String?): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: DocumentEntity)

    @Update
    suspend fun update(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE documents SET folder_id = :folderId, updated_at = :updatedAt WHERE id = :id")
    suspend fun moveToFolder(id: String, folderId: String?, updatedAt: Long)

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

    @Query("SELECT * FROM folders WHERE parent_id = :parentId ORDER BY `order` ASC, name ASC")
    suspend fun getByParent(parentId: String?): List<FolderEntity>

    // 同父文件夹下最大 order，用于新建/移动时取 MAX(order)+1，避免并发创建时 size 竞态导致 order 重复
    @Query("SELECT COALESCE(MAX(`order`), -1) FROM folders WHERE parent_id IS :parentId")
    suspend fun maxOrder(parentId: String?): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity)

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)
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
