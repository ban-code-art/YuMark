package com.yumark.app.data.ai.adapters

import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.data.ai.runConnectionTest
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.ModelInfo
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.StreamEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * OpenAI 官方 + 兼容格式（DeepSeek、Ollama、本地 vLLM 等）。
 * 仅通过 baseUrl 区分；协议同为 /chat/completions 的 SSE。
 */
class OpenAiAdapter(
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
        //  - Convert AiTool list to OpenAI tools format
        //  - Handle tool_calls in delta parsing
        //  - Emit ToolCallDelta events
        val body = buildJsonObject {
            put("model", config.model)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("stream", true)
            putJsonArray("messages") {
                config.systemPrompt?.let {
                    addJsonObject { put("role", "system"); put("content", it) }
                }
                messages.forEach { m ->
                    addJsonObject { put("role", m.role); put("content", m.content) }
                }
            }
        }

        val full = StringBuilder()
        client.preparePost("${baseUrl.trimEnd('/')}/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
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
                if (data == "[DONE]") break
                val delta = runCatching {
                    json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (!delta.isNullOrEmpty()) {
                    full.append(delta)
                    emit(StreamEvent.Content(delta))
                }
            }
            emit(StreamEvent.Done(full.toString()))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchAvailableModels(): List<ModelInfo> {
        val resp = client.get("${baseUrl.trimEnd('/')}/models") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        val arr = runCatching {
            json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]?.jsonArray
        }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .sorted()
            .map { ModelInfo(id = it, name = it) }
    }

    override suspend fun testConnection(model: String): ModelTestResult = runConnectionTest(model)

    override fun close() { /* 共享 client，无私有资源 */ }
}
