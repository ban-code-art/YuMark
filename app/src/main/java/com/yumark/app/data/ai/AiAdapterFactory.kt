package com.yumark.app.data.ai

import com.yumark.app.data.ai.adapters.ClaudeAdapter
import com.yumark.app.data.ai.adapters.EmbeddingAdapter
import com.yumark.app.data.ai.adapters.GeminiAdapter
import com.yumark.app.data.ai.adapters.OpenAiAdapter
import com.yumark.app.data.ai.adapters.OpenAiEmbeddingAdapter
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiProvider
import com.yumark.app.domain.model.defaultBaseUrl
import com.yumark.app.domain.model.ragApiKeyResolved
import com.yumark.app.domain.model.ragBaseUrlResolved
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
) : com.yumark.app.domain.repository.ai.AiAdapterProvider {
    private var current: AiApiAdapter? = null

    override fun chatAdapter(config: AiConfig): AiApiAdapter = createAdapter(config)

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

    /**
     * 创建 embedding 适配器，走 OpenAI 兼容 `/embeddings`。
     *
     * 端点由 [ragBaseUrlResolved] / [ragApiKeyResolved] 决定（复用 chat 那一组，还是用户单独配的
     * 一组），解析规则只有那一处实现，这里不再自己拼一遍。
     *
     * 不影响 [current] chat 适配器缓存——embedding 与 chat 独立、无状态可管。
     */
    fun createEmbeddingAdapter(config: AiConfig): EmbeddingAdapter =
        OpenAiEmbeddingAdapter(config.ragBaseUrlResolved, config.ragApiKeyResolved, client)

    override fun embeddingAdapter(config: AiConfig): EmbeddingAdapter =
        createEmbeddingAdapter(config)

    override fun ragModelListAdapter(config: AiConfig): AiApiAdapter =
        createRagModelListAdapter(config)

    /**
     * 为「获取 embedding 模型列表」按钮创建一个一次性适配器。
     *
     * 固定用 [OpenAiAdapter] 而不看 [AiConfig.provider]：这条路要打的是 embedding 端点，而
     * embedding 只有 OpenAI 那套协议（`GET /models` + `Authorization: Bearer`）。用户把
     * provider 选成 Claude/Gemini 时这里照样按 OpenAI 形状请求，失败是**正确结果**——
     * 那两家不提供 `/embeddings`，列出一堆 embedding 路径根本用不了的模型才是误导。
     *
     * 刻意不写进 [current]：那个字段缓存的是 chat 适配器，覆盖它会对正在用的会话调用
     * `close()`。也不必缓存——[OpenAiAdapter.close] 本身是空的（共享 client，无私有资源）。
     */
    fun createRagModelListAdapter(config: AiConfig): AiApiAdapter =
        OpenAiAdapter(config.ragBaseUrlResolved, config.ragApiKeyResolved, client)
}
