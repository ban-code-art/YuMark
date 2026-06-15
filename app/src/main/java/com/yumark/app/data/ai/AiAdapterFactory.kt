package com.yumark.app.data.ai

import com.yumark.app.data.ai.adapters.ClaudeAdapter
import com.yumark.app.data.ai.adapters.GeminiAdapter
import com.yumark.app.data.ai.adapters.OpenAiAdapter
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiProvider
import com.yumark.app.domain.model.defaultBaseUrl
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按 [AiConfig.provider] 创建对应适配器。所有适配器共享注入的 [HttpClient]，
 * 切换时只更换适配器逻辑，不重建（也不关闭）客户端。
 */
@Singleton
class AiAdapterFactory @Inject constructor(
    private val client: HttpClient
) {
    private var current: AiApiAdapter? = null

    fun createAdapter(config: AiConfig): AiApiAdapter {
        current?.close()
        val baseUrl = config.baseUrl.ifBlank { config.provider.defaultBaseUrl }
        val adapter = when (config.provider) {
            AiProvider.OPENAI, AiProvider.OPENAI_COMPATIBLE ->
                OpenAiAdapter(baseUrl, config.apiKey, client)
            AiProvider.CLAUDE -> ClaudeAdapter(baseUrl, config.apiKey, client)
            AiProvider.GEMINI -> GeminiAdapter(baseUrl, config.apiKey, client)
        }
        current = adapter
        return adapter
    }
}
