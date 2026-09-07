package com.yumark.app.domain.repository.ai

/**
 * Embedding 适配器接口（协议契约，从 data/ai/adapters 上移）。
 *
 * 实现位于 data 层（OpenAI 兼容 `/embeddings`）；domain 与 presentation 只依赖本接口。
 */
interface EmbeddingApiAdapter {
    /**
     * 对 [input] 批量生成向量。返回顺序与输入一致（实现必须显式按响应 index 对齐，兼容乱序端点）。
     * 单次请求过大时由调用方分批；本方法不做内部分批。
     */
    suspend fun embed(input: List<String>, model: String): List<FloatArray>
}
