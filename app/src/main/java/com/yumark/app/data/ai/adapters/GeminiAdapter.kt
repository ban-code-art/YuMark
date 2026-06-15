package com.yumark.app.data.ai.adapters

import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.data.ai.runConnectionTest
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.ModelInfo
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.StreamEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Google Generative Language API。
 * 差异点：key 走 query 参数、contents/parts 结构、role assistant→model、systemInstruction。
 * 使用 alt=sse 统一为 SSE 流。
 */
class GeminiAdapter(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: HttpClient
) : AiApiAdapter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun sendChatStream(
        messages: List<ChatMessage>,
        config: AiRequestConfig
    ): Flow<StreamEvent> = flow {
        val url = "${baseUrl.trimEnd('/')}/models/${config.model}:streamGenerateContent?alt=sse&key=$apiKey"
        val body = buildJsonObject {
            config.systemPrompt?.let { sp ->
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", sp) } }
                }
            }
            putJsonArray("contents") {
                messages.filter { it.role != "system" }.forEach { m ->
                    addJsonObject {
                        put("role", if (m.role == "assistant") "model" else "user")
                        putJsonArray("parts") { addJsonObject { put("text", m.content) } }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", config.temperature)
                put("maxOutputTokens", config.maxTokens)
            }
        }

        val full = StringBuilder()
        client.preparePost(url) {
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
                val text = runCatching {
                    json.parseToJsonElement(data).jsonObject["candidates"]?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("text")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (!text.isNullOrEmpty()) {
                    full.append(text)
                    emit(StreamEvent.Content(text))
                }
            }
            emit(StreamEvent.Done(full.toString()))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchAvailableModels(): List<ModelInfo> {
        val resp = client.get("${baseUrl.trimEnd('/')}/models?key=$apiKey")
        val arr = runCatching {
            json.parseToJsonElement(resp.bodyAsText()).jsonObject["models"]?.jsonArray
        }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el.jsonObject
            val methods = obj["supportedGenerationMethods"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            if (!methods.contains("generateContent")) return@mapNotNull null
            // name 形如 "models/gemini-1.5-pro"，取末段作为 id
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val id = name.removePrefix("models/")
            ModelInfo(id = id, name = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: id)
        }
    }

    override suspend fun testConnection(model: String): ModelTestResult = runConnectionTest(model)

    override fun close() { /* 共享 client，无私有资源 */ }
}
