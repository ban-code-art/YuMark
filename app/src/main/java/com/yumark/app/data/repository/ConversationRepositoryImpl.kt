package com.yumark.app.data.repository

import com.yumark.app.data.local.db.dao.ConversationDao
import com.yumark.app.data.local.db.dao.MessageDao
import com.yumark.app.data.mapper.toDomain
import com.yumark.app.data.mapper.toEntity
import com.yumark.app.domain.model.Conversation
import com.yumark.app.domain.model.ConversationType
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : ConversationRepository {

    override fun observeAllConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { entities ->
            entities.map { it.toDomain() }  // 列表不加载消息正文，按需在详情中加载
        }

    override fun observeConversation(id: String): Flow<Conversation?> =
        conversationDao.observeById(id).combine(
            messageDao.observeByConversation(id)
        ) { conversation, messages ->
            conversation?.toDomain(messages.map { it.toDomain() })
        }

    override suspend fun createConversation(title: String, type: ConversationType): Conversation {
        val conversation = Conversation(title = title, type = type)
        conversationDao.insert(conversation.toEntity())
        return conversation
    }

    override suspend fun updateConversation(conversation: Conversation) {
        conversationDao.update(
            conversation.toEntity().copy(updatedAt = System.currentTimeMillis())
        )
    }

    override suspend fun deleteConversation(id: String) = conversationDao.delete(id)

    override suspend fun addMessage(message: Message) = messageDao.insert(message.toEntity())

    override suspend fun updateMessage(message: Message) = messageDao.update(message.toEntity())

    override suspend fun deleteMessage(messageId: String) = messageDao.delete(messageId)
}
