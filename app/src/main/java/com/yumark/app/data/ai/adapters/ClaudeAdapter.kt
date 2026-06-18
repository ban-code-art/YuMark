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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class ClaudeAdapter(
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
        val body = buildClaudeBody(messages, config, tools)

        withRetryAndEmissionGuard(flowEmit = { e -> emit(e) }) { flowEmit ->
            // 每次请求（含重试）新建累积态，避免上一轮未发完就失败时残留 tool_use 块。
            val full = StringBuilder()
            val toolCalls = ClaudeToolCallAccumulator()
            client.preparePost("${baseUrl.trimEnd('/')}/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
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
                    val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "content_block_start" -> toolCalls.onBlockStart(obj)
                        "content_block_delta" -> {
                            val delta = obj["delta"]?.jsonObject ?: continue
                            delta["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                                if (text.isNotEmpty()) {
                                    full.append(text)
                                    flowEmit(StreamEvent.Content(text))
                                }
                            }
                            toolCalls.onBlockDelta(obj)
                        }
                        "content_block_stop" -> Unit
                        "message_stop" -> {
                            val completed = toolCalls.completeMessage()
                            if (completed.isNotEmpty()) {
                                flowEmit(StreamEvent.ToolCallComplete(completed))
                            }
                            flowEmit(StreamEvent.Done(full.toString()))
                            return@execute
                        }
                    }
                }
                val completed = toolCalls.completeMessage()
                if (completed.isNotEmpty()) {
                    flowEmit(StreamEvent.ToolCallComplete(completed))
                }
                flowEmit(StreamEvent.Done(full.toString()))
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchAvailableModels(): List<ModelInfo> = listOf(
        ModelInfo("claude-opus-4-8", "Claude Opus 4.8"),
        ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6"),
        ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5"),
        ModelInfo("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet"),
        ModelInfo("claude-3-5-haiku-latest", "Claude 3.5 Haiku")
    )

    override suspend fun testConnection(model: String): ModelTestResult = runConnectionTest(model)

    override fun close() {}
}

internal fun buildClaudeBody(
    messages: List<ChatMessage>,
    config: AiRequestConfig,
    tools: List<AiTool>
): JsonObject = buildJsonObject {
    put("model", config.model)
    put("max_tokens", config.maxTokens)
    put("temperature", config.temperature)
    put("stream", true)
    config.systemPrompt?.let { put("system", it) }
    putJsonArray("messages") {
        messages.filter { it.role != "system" }.forEach { message ->
            addJsonObject {
                put("role", if (message.role == "tool") "user" else message.role)
                put("content", claudeMessageContent(message))
            }
        }
    }
    if (tools.isNotEmpty()) {
        putJsonArray("tools") {
            tools.forEach { tool ->
                addJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", tool.parameters.toJsonElement())
                }
            }
        }
    }
}

internal fun claudeMessageContent(message: ChatMessage): JsonArray = when {
    !message.toolCalls.isNullOrEmpty() -> buildJsonArray {
        message.toolCalls.forEach { call ->
            addJsonObject {
                put("type", "tool_use")
                put("id", call.id)
                put("name", call.name)
                put("input", runCatching { Json.parseToJsonElement(call.arguments) }.getOrElse { buildJsonObject {} })
            }
        }
    }
    message.role == "tool" -> buildJsonArray {
        addJsonObject {
            put("type", "tool_result")
            put("tool_use_id", message.toolCallId)
            put("content", message.content)
        }
    }
    message.contentParts.isNotEmpty() -> claudeContentParts(message.contentParts)
    else -> buildJsonArray {
        addJsonObject {
            put("type", "text")
            put("text", message.content)
        }
    }
}

private fun normalizeClaudeToolCall(call: ToolCall): ToolCall =
    call.copy(arguments = runCatching { Json.parseToJsonElement(call.arguments).toString() }.getOrElse { call.arguments })

internal class ClaudeToolCallAccumulator {
    private data class PendingToolCall(
        val id: String,
        val name: String,
        val initialInput: String?,
        val partialJson: StringBuilder = StringBuilder()
    )

    private val callsByIndex = linkedMapOf<Int, PendingToolCall>()

    fun onBlockStart(event: JsonObject) {
        val block = event["content_block"]?.jsonObject ?: return
        if (block["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return
        val index = event["index"]?.jsonPrimitive?.intOrNull ?: return
        val id = block["id"]?.jsonPrimitive?.contentOrNull ?: return
        val name = block["name"]?.jsonPrimitive?.contentOrNull ?: return
        val input = block["input"]?.toString()?.takeUnless { it == "{}" }
        callsByIndex[index] = PendingToolCall(id = id, name = name, initialInput = input)
    }

    fun onBlockDelta(event: JsonObject) {
        val delta = event["delta"]?.jsonObject ?: return
        val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: return
        val index = event["index"]?.jsonPrimitive?.intOrNull ?: return
        val current = callsByIndex[index] ?: return
        current.partialJson.append(partial)
    }

    fun completeMessage(): List<ToolCall> {
        if (callsByIndex.isEmpty()) return emptyList()
        val completed = callsByIndex.toSortedMap().values.map { pending ->
            val rawArgs = pending.partialJson.toString().ifEmpty { pending.initialInput ?: "{}" }
            normalizeClaudeToolCall(
                ToolCall(id = pending.id, name = pending.name, arguments = rawArgs)
            )
        }
        callsByIndex.clear()
        return completed
    }
}
