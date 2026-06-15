package com.yumark.app.domain.repository

import com.yumark.app.domain.model.AiConfig
import kotlinx.coroutines.flow.Flow

interface AiConfigRepository {
    fun observeConfig(): Flow<AiConfig>
    suspend fun updateConfig(config: AiConfig)
    suspend fun clearApiKey()
}
