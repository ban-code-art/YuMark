package com.yumark.app.domain.repository.ai

import com.yumark.app.domain.model.AiConfig

/**
 * AI 适配器的提供边界（依赖倒置：domain 定义契约，data 的 AiAdapterFactory 实现）。
 *
 * 消除此前三处 domain→data 违规（Agent/Chat/Config 用例直接 import
 * `com.yumark.app.data.ai.AiAdapterFactory`）：领域层现在只感知「按配置拿到一个适配器」，
 * provider 分派、共享 HttpClient、适配器缓存全部是实现细节，留在 data 侧。
 */
interface AiAdapterProvider {
    /**
     * 按 [config] 创建聊天适配器。实现负责 provider 分派与单实例复用
     * （切换配置时释放旧实例）。
     */
    fun chatAdapter(config: AiConfig): AiApiAdapter

    /**
     * 创建 embedding 适配器，走 OpenAI 兼容 `/embeddings` 协议。
     * 端点由实现的解析规则决定（复用 chat 配置或独立 embedding 配置）。
     */
    fun embeddingAdapter(config: AiConfig): EmbeddingApiAdapter

    /**
     * 为「获取 embedding 模型列表」创建一次性 OpenAI 形状的聊天适配器
     * （打的是 embedding 端点的 `GET /models`；provider 选 Claude/Gemini 时
     * 按协议形状请求、失败是正确结果——两家不提供 /embeddings）。
     */
    fun ragModelListAdapter(config: AiConfig): AiApiAdapter
}
