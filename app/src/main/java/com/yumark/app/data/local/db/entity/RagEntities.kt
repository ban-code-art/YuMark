package com.yumark.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RAG 知识库分块（Phase 4，移植自 guanmo vectorStore/chunker）。
 *
 * 一篇文档切为多个语义分块，每块一条。删除文档时级联清除其分块与向量。
 * [titlePath] 以 JSON 字符串存储标题层级（如 `["项目", "架构"]`），检索时反序列化用于关键词加权。
 */
@Entity(
    tableName = "rag_chunks",
    indices = [Index(value = ["document_id"])],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChunkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    val content: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "title_path") val titlePath: String,   // JSON: List<String>
    val heading: String?,
    @ColumnInfo(name = "source_type") val sourceType: String, // markdown / text
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(name = "start_line") val startLine: Int,
    @ColumnInfo(name = "end_line") val endLine: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * 分块向量。embedding 以 JSON 字符串存储（`[f1,f2,...]`），启动时反序列化进内存做余弦线性扫描。
 * 删除分块时级联清除。
 */
@Entity(
    tableName = "rag_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = ChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunk_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class EmbeddingEntity(
    @PrimaryKey @ColumnInfo(name = "chunk_id") val chunkId: String,
    val embedding: String,        // JSON: FloatArray
    val model: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * 索引任务队列。文档保存后入队一条 pending；后台协程消费并置 done/failed。
 * [contentHash] 为本次索引对应的文档正文哈希——若与最近一条 done 任务相同则跳过，保证幂等。
 */
@Entity(
    tableName = "rag_embedding_jobs",
    indices = [Index(value = ["document_id"]), Index(value = ["status"])],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class EmbeddingJobEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    val status: String,            // pending / running / done / failed
    @ColumnInfo(name = "content_hash") val contentHash: String?,
    val error: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
