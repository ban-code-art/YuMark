package com.yumark.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val type: String,          // ConversationType.name
    val createdAt: Long,
    val updatedAt: Long,
    val relatedDocumentId: String? = null,     // 关联的文档 ID
    val relatedDocumentName: String? = null,   // 关联的文档名称
    val status: String = "IDLE"                // ConversationStatus.name
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,              // MessageRole.name
    val content: String,
    val agentActionJson: String?,  // JSON 序列化的 AgentAction
    val timestamp: Long,
    val stepsJson: String? = null,        // JSON 序列化的 List<AgentStep>（P3.1）
    val attachmentsJson: String? = null   // 预留给 attachment Phase 2（D2：合并到同一迁移版本）
)

/**
 * 长期记忆（移植自 guanmo memoryService）。与 RAG 知识库完全分开——
 * 独立表，无 chunk、无 document 关联。Phase 3 用词法相似度检索，Phase 4 embedding 基建就绪后可增强。
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val category: String,        // MemoryCategory.name
    val source: String,          // user_explicit / auto_extracted
    val locked: Boolean = false,
    val status: String = "active",   // active / candidate
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
