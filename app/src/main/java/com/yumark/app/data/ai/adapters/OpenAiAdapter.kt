package com.yumark.app.data.ai.adapters

import com.yumark.app.core.util.AiErrorMapper
import com.yumark.app.core.util.FriendlyIOException
import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.data.ai.ContentBlockedException
import com.yumark.app.data.ai.HttpResponseException
import com.yumark.app.data.ai.OpenAiToolCallAccumulator
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
// 空补全间歇性出现（实测该类多智能体/聚合端点对任务类 prompt 常返回 200-空），且空补全失败很快
// （~0.3s）而真实回复是慢速流式（~10s+），故重试成本低、可多试几次显著提升成功率。
private const val EMPTY_RETRY_MAX = 5
private const val EMPTY_RETRY_DELAY_MS = 400L

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
        // 推理模型（DeepSeek-R1 / o 系列等）把输出放进 reasoning_content/reasoning，content 可能为空。
        // 单独累积；若最终 content 为空而 reasoning 非空，则把 reasoning 作为答复兜底，避免“空响应”。
        val reasoning = StringBuilder()
        // 本次流是否被 max_tokens 截断（finish_reason == "length"）。从前这个字段被整个忽略，
        // 于是一段砍掉后半截的正文能一路走到 Agent 的隐式写入，把目标文档整篇覆盖成半成品。
        var truncated = false

        // 一次完整请求：构建 body(可选 tools) → 请求 → 解析 SSE → emit Content/ToolCallComplete。
        // 返回本次是否“产出了有效内容”（有正文 或 有工具调用）。不在此 emit Done——交由 runAttempt。
        suspend fun streamOnce(emit: suspend (StreamEvent) -> Unit, includeTools: Boolean): Boolean {
            full.clear()
            reasoning.clear()
            // 跟 full 一起复位：空补全重试会重跑本函数，上一次的截断标记不能带到下一次
            truncated = false
            // 完整性信号：[DONE] 哨兵与 finish_reason，任一出现即视为服务端正常收尾。
            // 局部变量，随本函数每次重跑天然复位。
            var sawDone = false
            var sawFinishReason = false
            // 内容被策略拦下（finish_reason == "content_filter"）。同为局部变量：这一轮的判决
            // 不能带到下一轮空补全重试上。
            var blocked = false
            var blockedReason: String? = null
            // 每次请求新建累积器，避免主路径/兜底重试时残留上一轮的 tool_calls delta
            // 导致向模型回填陈旧或重复的工具调用。
            val toolAcc = OpenAiToolCallAccumulator()
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
                    if (data == "[DONE]") { sawDone = true; break }
                    val root = runCatching { json.parseToJsonElement(data).jsonObject }
                        .getOrNull() ?: continue
                    // 200 之后在流里内联报错（上游限流/过载最常见）。必须在读 choices 之前判：
                    // 错误载荷里根本没有 choices，从前会被下面那个 `?: continue` 跳过，
                    // 服务端随即关连接 → 报成「回复完成」或「AI 没有返回任何内容」。
                    inlineErrorStatus(root)?.let { throw HttpResponseException(it, data) }
                    val choice = runCatching {
                        root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    }.getOrNull() ?: continue
                    // finish_reason 落在最后一个 chunk 上，而部分实现的那一个 chunk 里没有 delta
                    // （只有 finish_reason），所以必须在下面 delta 的 `?: continue` **之前**读，
                    // 否则整个截断信号会被那一行悄悄跳过。
                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
                        sawFinishReason = true
                        if (isTruncatedStopReason(it)) truncated = true
                        // content_filter 与 length 分开记：一个要改写措辞，一个要调大上限
                        if (isBlockedStopReason(it)) {
                            blocked = true
                            blockedReason = presentableBlockReason(it)
                        }
                    }
                    val delta = choice["delta"]?.jsonObject ?: continue

                    delta["content"]?.jsonPrimitive?.contentOrNull?.let {
                        if (it.isNotEmpty()) {
                            full.append(it)
                            emit(StreamEvent.Content(it))
                        }
                    }
                    // 推理增量：累积但不实时上屏（避免把思维链当正文）；仅在 content 为空时兜底使用。
                    (delta["reasoning_content"] ?: delta["reasoning"])
                        ?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) reasoning.append(it) }
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
                // 被内容策略拦下、且这一轮什么都没产出：当场抛，不让空补全重试的阶梯继续跑。
                // 那条阶梯是为「间歇性空补全」设计的（同一 prompt 重试几次就有了），而被拦是
                // 确定性结果：EMPTY_RETRY_MAX 次带 tools 再 EMPTY_RETRY_MAX 次不带 tools，
                // 十次请求、三秒多的等待，换回来的是同一个 content_filter，最后还落到一句
                // 「请调大 max tokens」上。判空条件与本函数的返回值一致，再加上 reasoning
                // ——那三样任一非空都说明有东西可以交给用户，此时不抛。
                if (blocked && full.isBlank() && toolAcc.isEmpty() && reasoning.isBlank()) {
                    throw ContentBlockedException(blockedReason)
                }
                // 既没读到 [DONE]，也没见过任何 finish_reason —— 这条流是被掐断的，不是正常收尾。
                // 只认哨兵会误伤：不少中转从不发 [DONE]，但会照常给出 finish_reason。
                // 只挡「已经产出过内容」的情况：真正的空补全交给下面的空补全重试，那条路文案更准。
                if (!sawDone && !sawFinishReason && (full.isNotBlank() || !toolAcc.isEmpty())) {
                    throw StreamInterruptedException()
                }
                if (!toolAcc.isEmpty()) {
                    emit(StreamEvent.ToolCallComplete(toolAcc.build()))
                }
            }
            return full.isNotBlank() || !toolAcc.isEmpty()
        }

        // 空补全重试：部分聚合代理/多智能体模型会间歇性地返回「200 + 只有 role、无 content/工具」
        // 的空补全（实测同一 prompt 重试几次后能出内容）。空补全期间未 emit 任何内容，重试安全
        // （不会重复输出）。带 tools 多次仍空，则去掉 tools 再试若干次（兼容不支持 function calling 的端点）。
        suspend fun runAttempt(emit: suspend (StreamEvent) -> Unit, includeTools: Boolean) {
            suspend fun attemptPhase(useTools: Boolean): Boolean {
                repeat(EMPTY_RETRY_MAX) { i ->
                    if (streamOnce(emit, useTools) || reasoning.isNotBlank()) return true
                    if (i < EMPTY_RETRY_MAX - 1) kotlinx.coroutines.delay(EMPTY_RETRY_DELAY_MS)
                }
                return false
            }

            var produced = attemptPhase(includeTools)
            if (!produced && includeTools) {
                produced = attemptPhase(false)
            }
            // content 为空但有推理内容（推理模型把答案放在 reasoning 字段）→ 用推理内容兜底为答复。
            if (full.isBlank() && reasoning.isNotBlank()) {
                val text = reasoning.toString()
                full.append(text)
                emit(StreamEvent.Content(text))
            }
            emit(StreamEvent.Done(full.toString(), truncated = truncated))
        }

        // 保守降级：带 tools 的请求若首字节前被拒（4xx，常见于模型不支持 function calling），
        // 自动去掉 tools 重试一次，让对话仍可用。
        withRetryAndEmissionGuard(
            flowEmit = { e -> emit(e) },
            fallback = if (tools.isNotEmpty()) {
                { trackedEmit -> runAttempt(trackedEmit, includeTools = false) }
            } else null
        ) { trackedEmit ->
            runAttempt(trackedEmit, includeTools = tools.isNotEmpty())
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
        // 必须先看状态码：401 的错误体里当然没有 "data" 字段，直接往下走会得到空列表，
        // 界面报「获取到 0 个模型」——用户以为账号没有模型权限，真正的问题却是 Key 写错了。
        if (!resp.status.isSuccess()) {
            throw FriendlyIOException(AiErrorMapper.mapHttpError(resp.status.value))
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
