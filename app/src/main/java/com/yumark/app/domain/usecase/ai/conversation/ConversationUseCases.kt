package com.yumark.app.domain.usecase.ai.conversation

import com.yumark.app.domain.model.Conversation
import com.yumark.app.domain.model.ConversationType
import com.yumark.app.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetAllConversationsUseCase @Inject constructor(
    private val repository: ConversationRepository
) {
    operator fun invoke(): Flow<List<Conversation>> = repository.observeAllConversations()
}

class GetConversationUseCase @Inject constructor(
    private val repository: ConversationRepository
) {
    operator fun invoke(id: String): Flow<Conversation?> = repository.observeConversation(id)
}

class CreateConversationUseCase @Inject constructor(
    private val repository: ConversationRepository
) {
    suspend operator fun invoke(title: String, type: ConversationType): Conversation =
        repository.createConversation(title, type)
}

class DeleteConversationUseCase @Inject constructor(
    private val repository: ConversationRepository
) {
    suspend operator fun invoke(id: String) = repository.deleteConversation(id)
}

class UpdateConversationTitleUseCase @Inject constructor(
    private val repository: ConversationRepository
) {
    suspend operator fun invoke(conversation: Conversation, title: String) =
        repository.updateConversation(conversation.copy(title = title))
}
