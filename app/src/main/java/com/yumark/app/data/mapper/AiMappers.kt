package com.yumark.app.data.mapper

import com.yumark.app.data.local.db.entity.ConversationEntity
import com.yumark.app.data.local.db.entity.MessageEntity
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.Conversation
import com.yumark.app.domain.model.ConversationStatus
import com.yumark.app.domain.model.ConversationType
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val aiJson = Json { ignoreUnknownKeys = true }

fun ConversationEntity.toDomain(messages: List<Message> = emptyList()): Conversation =
    Conversation(
        id = id,
        title = title,
        type = ConversationType.valueOf(type),
        createdAt = createdAt,
        updatedAt = updatedAt,
        messages = messages,
        relatedDocumentId = relatedDocumentId,
        relatedDocumentName = relatedDocumentName,
        status = runCatching { ConversationStatus.valueOf(status) }.getOrElse { ConversationStatus.IDLE }
    )

fun Conversation.toEntity(): ConversationEntity =
    ConversationEntity(
        id = id,
        title = title,
        type = type.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        relatedDocumentId = relatedDocumentId,
        relatedDocumentName = relatedDocumentName,
        status = status.name
    )

fun MessageEntity.toDomain(): Message =
    Message(
        id = id,
        conversationId = conversationId,
        role = MessageRole.valueOf(role),
        content = content,
        agentAction = agentActionJson?.let {
            runCatching { aiJson.decodeFromString<AgentAction>(it) }.getOrNull()
        },
        timestamp = timestamp
    )

fun Message.toEntity(): MessageEntity =
    MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role.name,
        content = content,
        agentActionJson = agentAction?.let { aiJson.encodeToString(it) },
        timestamp = timestamp
    )
