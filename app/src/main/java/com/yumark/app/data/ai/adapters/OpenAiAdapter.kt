package com.yumark.app.data.ai.adapters

import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.data.ai.HttpResponseException
import com.yumark.app.data.ai.OpenAiToolCallAccumulator
import com.yumark.app.data.ai.runConnectionTest
import com.yumark.app.data.ai.toJsonElement
import com.yumark.app.data.ai.withRetryAndEmissionGuard
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.AiTool
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
        tools: List<AiTool>
    ): Flow<StreamEvent> = flow {
        val full = StringBuilder()
        val toolAcc = OpenAiToolCallAccumulator()

        // 一次完整请求：构建 body(可选 tools) → 请求 → 解析 SSE → emit。失败抛异常交由守护处理。
        suspend fun runOnce(emit: suspend (StreamEvent) -> Unit, includeTools: Boolean) {
            full.clear()
            val body = buildBody(messages, config, if (includeTools) tools else emptyList())
            client.preparePost("${baseUrl.trimEnd('/')}/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                setBody(body.toString())
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw HttpResponseException(response.status.value, response.bodyAsText())
                }
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank() || !line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val choice = runCatching {
                        json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray
                            ?.firstOrNull()?.jsonObject
                    }.getOrNull() ?: continue
                    val delta = choice["delta"]?.jsonObject ?: continue

                    delta["content"]?.jsonPrimitive?.contentOrNull?.let {
                        if (it.isNotEmpty()) {
                            full.append(it)
                            emit(StreamEvent.Content(it))
                        }
                    }
                    delta["tool_calls"]?.jsonArray?.forEach { tcEl ->
                        val tc = tcEl.jsonObject
                        val index = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val id = tc["id"]?.jsonPrimitive?.contentOrNull
                        val fn = tc["function"]?.jsonObject
                        val name = fn?.get("name")?.jsonPrimitive?.contentOrNull
                        val argsChunk = fn?.get("arguments")?.jsonPrimitive?.contentOrNull
                        toolAcc.accept(index, id, name, argsChunk)
                    }
                }
                if (!toolAcc.isEmpty()) {
                    emit(StreamEvent.ToolCallComplete(toolAcc.build()))
                }
                emit(StreamEvent.Done(full.toString()))
            }
        }

        // 保守降级：带 tools 的请求若首字节前被拒（4xx，常见于模型不支持 function calling），
        // 自动去掉 tools 重试一次，让对话仍可用。
        withRetryAndEmissionGuard(
            flowEmit = { e -> emit(e) },
            fallback = if (tools.isNotEmpty()) {
                { trackedEmit -> runOnce(trackedEmit, includeTools = false) }
            } else null
        ) { trackedEmit ->
            runOnce(trackedEmit, includeTools = tools.isNotEmpty())
        }
    }.flowOn(Dispatchers.IO)

    /** 构建 /chat/completions 请求体。[tools] 为空则不带 tools 字段（降级用）。 */
    private fun buildBody(
        messages: List<ChatMessage>,
        config: AiRequestConfig,
        tools: List<AiTool>
    ): JsonObject = buildJsonObject {
        put("model", config.model)
        put("temperature", config.temperature)
        put("max_tokens", config.maxTokens)
        put("stream", true)
        putJsonArray("messages") {
            config.systemPrompt?.let {
                addJsonObject { put("role", "system"); put("content", it) }
            }
            messages.forEach { m ->
                addJsonObject {
                    put("role", m.role)
                    if (m.contentParts.isNotEmpty()) {
                        put("content", openAiContentParts(m.contentParts))
                    } else {
                        put("content", m.content)
                    }
                    if (!m.toolCalls.isNullOrEmpty()) {
                        putJsonArray("tool_calls") {
                            m.toolCalls.forEach { tc ->
                                addJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments)
                                    }
                                }
                            }
                        }
                    }
                    m.toolCallId?.let { put("tool_call_id", it) }
                }
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters.toJsonElement())
                        }
                    }
                }
            }
        }
    }

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
