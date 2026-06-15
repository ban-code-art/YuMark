package com.yumark.app.data.repository

import com.yumark.app.data.local.prefs.AiConfigDataStore
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.repository.AiConfigRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiConfigRepositoryImpl @Inject constructor(
    private val dataStore: AiConfigDataStore
) : AiConfigRepository {

    override fun observeConfig(): Flow<AiConfig> = dataStore.configFlow

    override suspend fun updateConfig(config: AiConfig) = dataStore.updateConfig(config)

    override suspend fun clearApiKey() = dataStore.clearApiKey()
}
