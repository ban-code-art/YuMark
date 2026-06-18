package com.yumark.app.data.ai.adapters

import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.data.ai.HttpResponseException
import com.yumark.app.data.ai.runConnectionTest
import com.yumark.app.data.ai.toJsonElement
import com.yumark.app.data.ai.withRetryAndEmissionGuard
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.AiTool
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.ModelInfo
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.StreamEvent
import com.yumark.app.domain.model.ToolCall
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class GeminiAdapter(
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
        val url = "${baseUrl.trimEnd('/')}/models/${config.model}:streamGenerateContent?alt=sse&key=$apiKey"
        val body = buildGeminiBody(messages, config, tools)
        val full = StringBuilder()

        withRetryAndEmissionGuard(flowEmit = { e -> emit(e) }) { flowEmit ->
            client.preparePost(url) {
                contentType(ContentType.Application.Json)
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
                    val candidate = runCatching {
                        json.parseToJsonElement(data).jsonObject["candidates"]?.jsonArray
                            ?.firstOrNull()?.jsonObject
                    }.getOrNull() ?: continue

                    val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
                        val toolCalls = ArrayList<ToolCall>()
                        parts.forEachIndexed { index, part ->
                        val obj = part.jsonObject
                        obj["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                            if (text.isNotEmpty()) {
                                full.append(text)
                                flowEmit(StreamEvent.Content(text))
                            }
                        }
                        obj["functionCall"]?.jsonObject?.let { call ->
                            val name = call["name"]?.jsonPrimitive?.contentOrNull ?: return@let
                            val id = call["id"]?.jsonPrimitive?.contentOrNull
                            val args = call["args"]?.toString() ?: "{}"
                            toolCalls += ToolCall(
                                id = id ?: "$name#$index",
                                name = name,
                                arguments = args
                            )
                        }
                    }
                    if (toolCalls.isNotEmpty()) {
                        flowEmit(StreamEvent.ToolCallComplete(toolCalls))
                    }
                }
                flowEmit(StreamEvent.Done(full.toString()))
            }
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
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val id = name.removePrefix("models/")
            ModelInfo(id = id, name = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: id)
        }
    }

    override suspend fun testConnection(model: String): ModelTestResult = runConnectionTest(model)

    override fun close() {}
}

internal fun buildGeminiBody(
    messages: List<ChatMessage>,
    config: AiRequestConfig,
    tools: List<AiTool>
): JsonObject = buildJsonObject {
    config.systemPrompt?.let { sp ->
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", sp) } }
        }
    }
    putJsonArray("contents") {
        messages.filter { it.role != "system" }.forEach { message ->
            addJsonObject {
                put("role", when (message.role) {
                    "assistant" -> "model"
                    "tool" -> "user"
                    else -> message.role
                })
                put("parts", geminiMessageParts(message))
            }
        }
    }
    putJsonObject("generationConfig") {
        put("temperature", config.temperature)
        put("maxOutputTokens", config.maxTokens)
    }
    if (tools.isNotEmpty()) {
        putJsonArray("tools") {
            addJsonObject {
                putJsonArray("functionDeclarations") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters.toJsonElement())
                        }
                    }
                }
            }
        }
    }
}

internal fun geminiMessageParts(message: ChatMessage) = when {
    !message.toolCalls.isNullOrEmpty() -> buildJsonArray {
        message.toolCalls.forEach { call ->
            addJsonObject {
                putJsonObject("functionCall") {
                    put("id", call.id)
                    put("name", call.name)
                    put("args", runCatching { Json.parseToJsonElement(call.arguments) }.getOrElse { buildJsonObject {} })
                }
            }
        }
    }
    message.role == "tool" -> buildJsonArray {
        addJsonObject {
            putJsonObject("functionResponse") {
                val toolCallId = message.toolCallId
                put("id", toolCallId)
                put("name", toolCallId?.substringBefore('#') ?: "tool")
                putJsonObject("response") {
                    put("content", message.content)
                }
            }
        }
    }
    message.contentParts.isNotEmpty() -> geminiContentParts(message.contentParts)
    else -> buildJsonArray {
        addJsonObject { put("text", message.content) }
    }
}
