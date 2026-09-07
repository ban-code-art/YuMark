package com.yumark.app.domain.repository.ai

import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.AiTool
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.ModelInfo
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.StreamEvent
import kotlinx.coroutines.flow.Flow

/**
 * AI 提供商统一适配器接口（协议契约，从 data/ai 上移）。
 * 上层（UseCase）只依赖此接口，不感知具体 API 协议差异；实现位于 data 层各 Provider 适配器。
 */
interface AiApiAdapter {
    /** 测试连接，记录首 token 延迟、总耗时、流式是否可用 */
    suspend fun testConnection(model: String): ModelTestResult

    /** 拉取可用模型列表（部分 Provider 返回预置列表） */
    suspend fun fetchAvailableModels(): List<ModelInfo>

    /** 流式对话，支持工具调用 */
    fun sendChatStream(
        messages: List<ChatMessage>,
        config: AiRequestConfig,
        tools: List<AiTool> = emptyList()
    ): Flow<StreamEvent>

    /** 释放适配器私有资源（不关闭共享 HttpClient） */
    fun close()
}
