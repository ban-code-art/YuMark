package com.yumark.app.di

import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.data.ai.MemoryKnowledgeDispatcher
import com.yumark.app.data.ai.memory.MemoryService
import com.yumark.app.data.ai.rag.RagPipeline
import com.yumark.app.data.ai.web.WebSearchService
import com.yumark.app.domain.repository.ai.AgentToolService
import com.yumark.app.domain.repository.ai.AiAdapterProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier

/**
 * AI 适配器与外围工具的接口绑定（依赖倒置的 DI 半边）：
 * domain 定义契约（AiAdapterProvider / AgentToolService），data 实现并在本模块接线。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiAdapterModule {

    @Binds
    abstract fun bindAdapterProvider(impl: AiAdapterFactory): AiAdapterProvider

    @Binds
    abstract fun bindImportFilePort(
        impl: com.yumark.app.data.local.file.FileManager
    ): com.yumark.app.domain.repository.ImportFilePort

    companion object {
        /** Agent 构造里的「联网搜索」端口：直连 WebSearchService。 */
        @Provides
        @WebSearchPort
        fun provideWebSearchPort(impl: WebSearchService): AgentToolService = impl

        /** Agent 构造里的「记忆+知识检索」端口：聚合分发器（memory 走 Memory、知识走 RAG）。 */
        @Provides
        @MemoryKnowledgePort
        fun provideMemoryKnowledgePort(impl: MemoryKnowledgeDispatcher): AgentToolService = impl

    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WebSearchPort

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MemoryKnowledgePort
