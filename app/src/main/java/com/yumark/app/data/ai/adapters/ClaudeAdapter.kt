package com.yumark.app.data.ai.adapters

import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.data.ai.runConnectionTest
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.ModelInfo
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.StreamEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Anthropic Claude Messages API。
 * 差异点：x-api-key 鉴权、anthropic-version 头、system 为顶层字段、max_tokens 必填、SSE 事件类型多样。
 */
class ClaudeAdapter(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: HttpClient
) : AiApiAdapter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun sendChatStream(
        messages: List<ChatMessage>,
        config: AiRequestConfig,
        tools: List<com.yumark.app.domain.model.AiTool>
    ): Flow<StreamEvent> = flow {
        // TODO: Implement tool calling support
        //  - Convert AiTool list to Claude tools format
        //  - Handle tool_use events in SSE stream
        //  - Emit ToolCallDelta events
        val body = buildJsonObject {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            put("temperature", config.temperature)
            put("stream", true)
            config.systemPrompt?.let { put("system", it) }
            putJsonArray("messages") {
                messages.filter { it.role != "system" }.forEach { m ->
                    addJsonObject { put("role", m.role); put("content", m.content) }
                }
            }
        }

        val full = StringBuilder()
        client.preparePost("${baseUrl.trimEnd('/')}/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                emit(StreamEvent.Error("HTTP ${response.status.value}: ${response.bodyAsText()}"))
                return@execute
            }
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (line.isBlank() || !line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "content_block_delta" -> {
                        val text = obj["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                        if (!text.isNullOrEmpty()) {
                            full.append(text)
                            emit(StreamEvent.Content(text))
                        }
                    }
                    "message_stop" -> {
                        emit(StreamEvent.Done(full.toString()))
                        return@execute
                    }
                }
            }
            emit(StreamEvent.Done(full.toString()))
        }
    }.flowOn(Dispatchers.IO)

    // Anthropic 无公开模型列表端点，返回预置列表（ID 可能过期，UI 应允许手动输入）。
    override suspend fun fetchAvailableModels(): List<ModelInfo> = listOf(
        ModelInfo("claude-opus-4-8", "Claude Opus 4.8"),
        ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6"),
        ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5"),
        ModelInfo("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet"),
        ModelInfo("claude-3-5-haiku-latest", "Claude 3.5 Haiku")
    )

    override suspend fun testConnection(model: String): ModelTestResult = runConnectionTest(model)

    override fun close() { /* 共享 client，无私有资源 */ }
}
