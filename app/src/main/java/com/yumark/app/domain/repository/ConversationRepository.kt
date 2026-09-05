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

    /**
     * 把仍停在 WORKING 的对话复位成 IDLE，返回受影响行数。仅供启动期调用。
     *
     * COMPLETED 不动：那是 Agent 正常收尾写下的终态，不是残留。
     */
    suspend fun resetInterruptedRuns(): Int
}
