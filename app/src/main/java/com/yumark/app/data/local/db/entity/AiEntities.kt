package com.yumark.app.data.local.db.entity

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
    val timestamp: Long
)
