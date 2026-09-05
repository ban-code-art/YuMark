package com.yumark.app.data.ai.adapters

import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.data.ai.ContentBlockedException
import com.yumark.app.data.ai.HttpResponseException
import com.yumark.app.data.ai.StreamInterruptedException
import com.yumark.app.data.ai.inlineErrorStatus
import com.yumark.app.data.ai.isBlockedStopReason
import com.yumark.app.data.ai.isTruncatedStopReason
import com.yumark.app.data.ai.presentableBlockReason
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
            // 是否被 max_tokens 截断；Claude 把它放在 message_delta 事件里，
            // 从前这个事件类型在下面的 when 里没有分支，于是截断信号从来没到过上层。
            var truncated = false
            // 服务端是否给过「说完了」的信号：message_stop 哨兵，或 message_delta 里的 stop_reason。
            // 两者都没见过就读到 EOF，说明连接是被掐断的（见下面的 StreamInterruptedException）。
            var sawStopSignal = false
            // 内容被策略拦下：Claude 只有 stop_reason == "refusal" 这一个信号
            //（整段请求被安全系统拦下时走的是 error 事件，上面那条 HttpResponseException 已经接住）。
            // 原因词可能没什么信息量，故「拦没拦」不能靠 reason 判空。
            var blocked = false
            var blockedReason: String? = null
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
                        // 终止原因只在这个事件里：{"type":"message_delta","delta":{"stop_reason":"max_tokens"},…}
                        "message_delta" -> {
                            val delta = obj["delta"]?.jsonObject ?: continue
                            delta["stop_reason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
                                // 任何取值都算「说完了」：end_turn / tool_use / max_tokens 都是
                                // 服务端给出的终止判决。之后再分两类：max_tokens 一族是截断
                                //（调大上限），refusal 是被拦（改写措辞）——补救办法相反，分开记。
                                sawStopSignal = true
                                if (isTruncatedStopReason(reason)) truncated = true
                                if (isBlockedStopReason(reason)) {
                                    blocked = true
                                    blockedReason = presentableBlockReason(reason)
                                }
                            }
                        }
                        // 200 之后在流里内联报错，过载/限流最常见。抛出去复用非 2xx 的那条路
                        // （首字节前自动退避重试 + 友好文案）；从前这个事件类型没有分支，
                        // 被 when 无声吃掉后服务端关连接，一次过载就报成了「回复完成」。
                        "error" -> throw HttpResponseException(inlineErrorStatus(obj) ?: 500, data)
                        "message_stop" -> {
                            sawStopSignal = true
                            val completed = toolCalls.completeMessage()
                            // 被拦且一个字都没产出：单独成句（判断与下面 EOF 那条一致）。
                            // 这里必须再判一次 —— stop_reason 走的是 message_delta，紧接着的
                            // message_stop 自己 emit Done 就 return@execute，永远走不到 EOF 那段。
                            if (blocked && full.isEmpty() && completed.isEmpty()) {
                                throw ContentBlockedException(blockedReason)
                            }
                            if (completed.isNotEmpty()) {
                                flowEmit(StreamEvent.ToolCallComplete(completed))
                            }
                            flowEmit(StreamEvent.Done(full.toString(), truncated = truncated))
                            return@execute
                        }
                    }
                }
                val completed = toolCalls.completeMessage()
                // 被内容策略拦下、且一个字都没产出：单独成一句话。落到上层的空响应文案上
                // 就把话说反了 —— 那句让人换模型 / 调大 max tokens，这里该做的是改写措辞。
                // 有正文/有工具调用则不抛：已经流到屏幕上的部分比一句错误更值钱。
                if (blocked && full.isEmpty() && completed.isEmpty()) {
                    throw ContentBlockedException(blockedReason)
                }
                // 读到 EOF 却一个终止信号都没见过 —— 连接被掐断了，不是「回复完成」。
                // 只在确实产出过内容时才抛：什么都没产出时交给上层的空响应处理，
                // 那条路的文案更准（被拦的那一类已在上面单独成句）。
                if (!sawStopSignal && (full.isNotEmpty() || completed.isNotEmpty())) {
                    throw StreamInterruptedException()
                }
                if (completed.isNotEmpty()) {
                    flowEmit(StreamEvent.ToolCallComplete(completed))
                }
                flowEmit(StreamEvent.Done(full.toString(), truncated = truncated))
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
        val visible = messages.filter { it.role != "system" }
        var i = 0
        while (i < visible.size) {
            val message = visible[i]
            if (message.role == "tool") {
                // 一轮 assistant 可以带多个 tool_use，Claude 要求它们的 tool_result 全部装进
                // 紧随其后的**同一条** user 消息里。一个结果发一条 user 消息会得到连续多条
                // user：官方端点会把它们并成一轮，但本项目允许用户填自定义 baseUrl，
                // 兼容 Claude 协议的第三方中转常常不并轮，直接回
                // `messages: roles must alternate between "user" and "assistant"`。
                // 这里主动折叠，请求形状就与官方文档示例一致。
                val run = visible.subList(i, visible.size).takeWhile { it.role == "tool" }
                addJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        run.forEach { toolMessage -> claudeMessageContent(toolMessage).forEach { add(it) } }
                    })
                }
                i += run.size
            } else {
                addJsonObject {
                    put("role", message.role)
                    put("content", claudeMessageContent(message))
                }
                i++
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

/**
 * 把一条标准化消息转成 Claude 的 `content` 数组。
 *
 * 「有工具调用时也带上正文」是刻意的：Claude 的 content 是数组，`text` 与 `tool_use`
 * 本来就能共存，而模型在同一轮里常常先写一段说明再调工具（AgentUseCases 回填的
 * `ChatMessage(role = "assistant", content = full, toolCalls = calls)` 就同时握着两样）。
 * 从前这里只发 `tool_use`，那段说明在下一轮上下文里凭空消失——模型看不见自己刚才的推理，
 * 于是把同一个工具再调一遍，或者把已经解释过的事重新解释一次。OpenAI 侧（`buildBody`）
 * 一直是两样都发的，这里对齐它。
 *
 * 空正文必须跳过，不能发空 text 块：Claude 会以 `invalid_request_error` 打回整个请求，
 * 而 `content == null` 正是「这一轮只有工具调用」的正常取值。
 */
internal fun claudeMessageContent(message: ChatMessage): JsonArray = when {
    !message.toolCalls.isNullOrEmpty() -> buildJsonArray {
        message.content?.takeIf { it.isNotBlank() }?.let { text ->
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
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
