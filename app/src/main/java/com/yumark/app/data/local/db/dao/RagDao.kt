package com.yumark.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yumark.app.data.local.db.entity.ChunkEntity
import com.yumark.app.data.local.db.entity.EmbeddingEntity
import com.yumark.app.data.local.db.entity.EmbeddingJobEntity

@Dao
interface RagDao {
    // ---- chunks ----
    @Query("SELECT * FROM rag_chunks")
    suspend fun getAllChunks(): List<ChunkEntity>

    @Query("SELECT * FROM rag_chunks WHERE document_id = :documentId")
    suspend fun getChunksByDocument(documentId: String): List<ChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<ChunkEntity>)

    @Query("DELETE FROM rag_chunks WHERE document_id = :documentId")
    suspend fun deleteChunksByDocument(documentId: String)   // embeddings 经外键级联删除

    // ---- embeddings ----
    @Query("SELECT * FROM rag_embeddings")
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>

    @Query("SELECT * FROM rag_embeddings WHERE chunk_id IN (:chunkIds)")
    suspend fun getEmbeddings(chunkIds: List<String>): List<EmbeddingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>)

    // ---- jobs ----
    @Query("SELECT * FROM rag_embedding_jobs WHERE document_id = :documentId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestJob(documentId: String): EmbeddingJobEntity?

    @Query("SELECT * FROM rag_embedding_jobs WHERE status = 'pending' ORDER BY created_at ASC")
    suspend fun getPendingJobs(): List<EmbeddingJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: EmbeddingJobEntity)

    @Query("UPDATE rag_embedding_jobs SET status = :status, error = :error, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateJobStatus(id: String, status: String, error: String?, updatedAt: Long)

    @Query("DELETE FROM rag_embedding_jobs WHERE document_id = :documentId")
    suspend fun deleteJobsByDocument(documentId: String)

    // ---- stats ----
    @Query("SELECT COUNT(*) FROM rag_chunks")
    suspend fun chunkCount(): Int

    @Query("SELECT COUNT(DISTINCT document_id) FROM rag_chunks")
    suspend fun documentCount(): Int
}
