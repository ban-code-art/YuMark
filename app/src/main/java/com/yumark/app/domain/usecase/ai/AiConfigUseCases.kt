package com.yumark.app.domain.usecase.ai

import com.yumark.app.R
import com.yumark.app.core.util.FriendlyValidationException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.ModelInfo
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.ragBaseUrlResolved
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

/**
 * 拉取 **embedding 端点** 的模型列表，并写回配置的 [AiConfig.ragAvailableModels]。
 *
 * 与 [FetchAvailableModelsUseCase] 分开而不是加个开关参数：两者打的是不同的地址、用不同的密钥、
 * 写不同的字段，唯一相同的只是「按一下按钮拉一次 `/models`」。合并会得到一个每处都要 if 的函数。
 *
 * 地址为空时**在发请求之前**拦下来。空串的两种来路要分开报：复用模式下解析为空说明上方的
 * chat Base URL 没填（OpenAI 兼容端点没有默认地址），该去填上面那个框；单独配置模式下
 * 则是 embedding 那个框没填。放它走下去只会得到一条 Ktor 的相对 URL 异常。
 */
class FetchRagModelsUseCase @Inject constructor(
    private val factory: AiAdapterFactory,
    private val repository: AiConfigRepository
) {
    suspend operator fun invoke(config: AiConfig): Result<List<ModelInfo>> = runCatching {
        if (config.ragBaseUrlResolved.isBlank()) {
            val reason =
                if (config.ragUseMainEndpoint) R.string.ai_config_base_url_required
                else R.string.ai_config_rag_base_url_required
            throw FriendlyValidationException(UiMessage.of(reason))
        }
        val models = factory.createRagModelListAdapter(config).fetchAvailableModels()
        repository.updateConfig(config.copy(ragAvailableModels = models.map { it.id }))
        models
    }
}
