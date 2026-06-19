package com.yumark.app.di

import com.yumark.app.data.repository.AiConfigRepositoryImpl
import com.yumark.app.data.repository.AgentTaskRepositoryImpl
import com.yumark.app.data.repository.ConversationRepositoryImpl
import com.yumark.app.data.repository.DocumentRepositoryImpl
import com.yumark.app.data.repository.DocumentVersionRepositoryImpl
import com.yumark.app.data.repository.FolderRepositoryImpl
import com.yumark.app.data.repository.ImageRepositoryImpl
import com.yumark.app.data.repository.SettingsRepositoryImpl
import com.yumark.app.data.repository.SyncRepositoryImpl
import com.yumark.app.data.repository.WorkspaceRepositoryImpl
import com.yumark.app.domain.repository.AiConfigRepository
import com.yumark.app.domain.repository.AgentTaskRepository
import com.yumark.app.domain.repository.ConversationRepository
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.DocumentVersionRepository
import com.yumark.app.domain.repository.FolderRepository
import com.yumark.app.domain.repository.ImageRepository
import com.yumark.app.domain.repository.SettingsRepository
import com.yumark.app.domain.repository.SyncRepository
import com.yumark.app.domain.repository.WorkspaceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindDocumentRepository(impl: DocumentRepositoryImpl): DocumentRepository

    @Binds @Singleton
    abstract fun bindDocumentVersionRepository(impl: DocumentVersionRepositoryImpl): DocumentVersionRepository

    @Binds @Singleton
    abstract fun bindFolderRepository(impl: FolderRepositoryImpl): FolderRepository

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindImageRepository(impl: ImageRepositoryImpl): ImageRepository

    @Binds @Singleton
    abstract fun bindWorkspaceRepository(impl: WorkspaceRepositoryImpl): WorkspaceRepository

    @Binds @Singleton
    abstract fun bindAiConfigRepository(impl: AiConfigRepositoryImpl): AiConfigRepository

    @Binds @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds @Singleton
    abstract fun bindAgentTaskRepository(impl: AgentTaskRepositoryImpl): AgentTaskRepository

    @Binds @Singleton
    abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository
}
