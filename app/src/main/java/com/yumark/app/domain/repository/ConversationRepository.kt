package com.yumark.app.domain.repository

import com.yumark.app.domain.model.Conversation
import com.yumark.app.domain.model.ConversationType
import com.yumark.app.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun observeAllConversations(): Flow<List<Conversation>>
    fun observeConversation(id: String): Flow<Conversation?>
    suspend fun createConversation(title: String, type: ConversationType): Conversation
    suspend fun updateConversation(conversation: Conversation)
    suspend fun deleteConversation(id: String)
    suspend fun addMessage(message: Message)
    suspend fun updateMessage(message: Message)
    suspend fun deleteMessage(messageId: String)
}
