package com.yumark.app.domain.usecase.ai

import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.ModelInfo
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.repository.AiConfigRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** 观察 AI 配置 */
class GetAiConfigUseCase @Inject constructor(
    private val repository: AiConfigRepository
) {
    operator fun invoke(): Flow<AiConfig> = repository.observeConfig()
}

/** 更新 AI 配置 */
class UpdateAiConfigUseCase @Inject constructor(
    private val repository: AiConfigRepository
) {
    suspend operator fun invoke(config: AiConfig) = repository.updateConfig(config)
}

/** 测试连接与性能 */
class TestAiConnectionUseCase @Inject constructor(
    private val factory: AiAdapterFactory
) {
    suspend operator fun invoke(config: AiConfig): ModelTestResult =
        factory.createAdapter(config).testConnection(config.modelName)
}

/** 拉取可用模型列表，并写回配置的 availableModels */
class FetchAvailableModelsUseCase @Inject constructor(
    private val factory: AiAdapterFactory,
    private val repository: AiConfigRepository
) {
    suspend operator fun invoke(config: AiConfig): Result<List<ModelInfo>> = runCatching {
        val models = factory.createAdapter(config).fetchAvailableModels()
        repository.updateConfig(config.copy(availableModels = models.map { it.id }))
        models
    }
}
