package com.yumark.app.data.ai.adapters

import com.yumark.app.core.util.AiErrorMapper
import com.yumark.app.core.util.FriendlyIOException
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
import io.ktor.client.request.get
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
        // API key 走请求头而非 URL 查询串，避免被代理/CDN/访问日志捕获。
        val url = "${baseUrl.trimEnd('/')}/models/${config.model}:streamGenerateContent?alt=sse"
        val body = buildGeminiBody(messages, config, tools)
        val full = StringBuilder()
        // 是否被 maxOutputTokens 截断（candidates[].finishReason == "MAX_TOKENS"）；
        // 从前 finishReason 整个没读，截断和正常写完在上层看起来一模一样。
        var truncated = false

        withRetryAndEmissionGuard(flowEmit = { e -> emit(e) }) { flowEmit ->
            // 完整性信号：Gemini 没有 [DONE] 一类哨兵行，finishReason 是唯一的「说完了」判据。
            // 声明在 block 内 → 每次重试天然复位。
            var sawFinishReason = false
            // 内容被策略拦下的信号，两处都可能给：promptFeedback.blockReason（整个请求被拦，
            // 连 candidates 都没有）与 candidates[].finishReason（写到一半被拦）。
            // 原因可能为空（OTHER / BLOCK_REASON_UNSPECIFIED），故「拦没拦」不能靠 reason 判空。
            var blocked = false
            var blockedReason: String? = null
            client.preparePost(url) {
                header("x-goog-api-key", apiKey)
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
                    val root = runCatching { json.parseToJsonElement(data).jsonObject }
                        .getOrNull() ?: continue
                    // 200 之后在流里内联报错：{"error":{"code":429,"status":"RESOURCE_EXHAUSTED",…}}。
                    // 必须在读 candidates 之前判 —— 错误载荷里没有 candidates，从前被下面那个
                    // `?: continue` 跳过，服务端随即关连接 → 一次限流被报成「AI 没有返回任何内容」。
                    inlineErrorStatus(root)?.let { throw HttpResponseException(it, data) }
                    // 整个请求被内容策略拦下：Gemini 写在 promptFeedback.blockReason 上，并且
                    // **不给** candidates —— 同样必须在下面那个 `?: continue` 之前判，否则这一行
                    // 被跳过，服务端随即关连接，用户看到的是「AI 没有返回任何内容，请调大
                    // max tokens / 换模型」，而这里真正该做的是改写措辞。
                    runCatching {
                        root["promptFeedback"]?.jsonObject
                            ?.get("blockReason")?.jsonPrimitive?.contentOrNull
                    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { reason ->
                        blocked = true
                        blockedReason = presentableBlockReason(reason)
                    }
                    val candidate = runCatching {
                        root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    }.getOrNull() ?: continue
                    // finishReason 挂在候选上，通常和最后一批 parts 同一个 chunk 到达
                    candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
                        // 任何取值都算「说完了」：STOP / SAFETY / MAX_TOKENS 都是服务端的终止判决。
                        // 之后再分两类：MAX_TOKENS 一族是截断（调大上限），SAFETY 一族是被拦
                        //（改写措辞）——补救办法相反，所以必须分开记。
                        sawFinishReason = true
                        if (isTruncatedStopReason(reason)) truncated = true
                        if (isBlockedStopReason(reason)) {
                            blocked = true
                            blockedReason = presentableBlockReason(reason)
                        }
                    }

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
                // 被内容策略拦下、且一个字都没产出：单独成一句话。落到上层的空响应文案上
                // 就把话说反了——那句让人换模型 / 调大 max tokens，这里该做的是改写措辞。
                // 有正文则不抛：被拦的往往只是后半段，已经流到屏幕上的前半段比一句错误更值钱
                //（那时 withRetryAndEmissionGuard 也早就不会再重试了）。
                if (blocked && full.isEmpty()) {
                    throw ContentBlockedException(blockedReason)
                }
                // 一个 finishReason 都没见过就读到 EOF —— 连接被掐断，不是正常收尾。
                // 只挡已经产出过正文的情况：真正的空响应走上层那句空响应文案，比说成网络故障准确
                //（被拦的那一类已在上面单独成句）；已经发出去的工具调用也不回收，
                // 它是从单个 chunk 里整块解析出来的，本身完整。
                if (!sawFinishReason && full.isNotEmpty()) {
                    throw StreamInterruptedException()
                }
                flowEmit(StreamEvent.Done(full.toString(), truncated = truncated))
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchAvailableModels(): List<ModelInfo> {
        val resp = client.get("${baseUrl.trimEnd('/')}/models") {
            header("x-goog-api-key", apiKey)
        }
        // 同 OpenAiAdapter：401/403 的错误体里没有 "models" 字段，不先判状态码就会静默返回空列表。
        if (!resp.status.isSuccess()) {
            throw FriendlyIOException(AiErrorMapper.mapHttpError(resp.status.value))
        }
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

/**
 * 把一条标准化消息转成 Gemini 的 `parts` 数组。
 *
 * 有工具调用时也带上正文，理由与 [claudeMessageContent] 相同：Gemini 的 parts 允许
 * `text` 与 `functionCall` 同时出现，而只发 functionCall 会让模型在下一轮丢掉自己
 * 刚写的那段说明。空正文跳过——一个空 text part 没有意义，部分版本还会直接报错。
 */
internal fun geminiMessageParts(message: ChatMessage) = when {
    !message.toolCalls.isNullOrEmpty() -> buildJsonArray {
        message.content?.takeIf { it.isNotBlank() }?.let { text ->
            addJsonObject { put("text", text) }
        }
        message.toolCalls.forEach { call ->
            addJsonObject {
                putJsonObject("functionCall") {
                    // 只回传 Gemini 真的下发过的 id。适配器在流里没拿到 id 时会拼一个
                    // `name#index` 占位（见 sendChatStream），那是本地用来配对的，
                    // 原样发回去等于凭空造一个 Gemini 不认识的 id。
                    if (!isSyntheticGeminiToolCallId(call.id, call.name)) put("id", call.id)
                    put("name", call.name)
                    put("args", runCatching { Json.parseToJsonElement(call.arguments) }.getOrElse { buildJsonObject {} })
                }
            }
        }
    }
    message.role == "tool" -> buildJsonArray {
        addJsonObject {
            putJsonObject("functionResponse") {
                // name 必须等于 functionDeclarations 里声明的函数名。从前这里写
                // `toolCallId.substringBefore('#')`，只有在 id 是上面那个 `name#index`
                // 占位时才凑巧对；模型做并行调用时 Gemini 会真的返回 id，反解就把整串
                // id 当成了函数名，请求直接被拒。现在优先用调用方带过来的 toolName，
                // 反解只作为老数据的兜底。
                val name = message.toolName
                    ?: message.toolCallId?.substringBefore('#')?.takeIf { it.isNotBlank() }
                    ?: "tool"
                val toolCallId = message.toolCallId
                if (toolCallId != null && !isSyntheticGeminiToolCallId(toolCallId, name)) {
                    put("id", toolCallId)
                }
                put("name", name)
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

/** id 是否是本适配器自己拼的 `name#index` 占位——那种 id 不能回传给 Gemini。 */
private fun isSyntheticGeminiToolCallId(id: String, name: String): Boolean =
    name.isNotEmpty() && Regex("^${Regex.escape(name)}#\\d+$").matches(id)
