package com.yumark.app.data.ai

import com.yumark.app.R
import com.yumark.app.core.util.AiErrorMapper
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserFacingMessage
import com.yumark.app.domain.model.StreamEvent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** 适配器内部信号：HTTP 非 2xx。body 仅用于诊断，不进用户消息。 */
internal class HttpResponseException(val status: Int, val body: String) : Exception()

/**
 * 流在没给出任何「说完了」信号的前提下断了：EOF 之前既没有终止哨兵
 *（`[DONE]` / `message_stop`），也没有任何 finish_reason / stop_reason / finishReason。
 *
 * 必须抛异常而不是照常 emit [StreamEvent.Done]：Done 的语义是「这段正文是完整的」，
 * 而 Agent 的隐式写入会拿它整篇覆盖用户已有文档（EDIT 分支是整篇替换、隐式提案没有
 * baseContentHash 可拦），划词改写也会拿它替换选中的正文 —— 半截正文走到那两处就是数据丢失。
 *
 * 继承 [IOException] 是为了直接复用两条现成通路：[withRetryAndEmissionGuard] 认它可重试
 *（首字节前断线自动退避重试），[AiErrorMapper.mapException] 把它说成「网络连接失败」——
 * 这也确实是它的成因，比「回复被输出上限截断」准确。
 */
internal class StreamInterruptedException : IOException()

/**
 * 内容被服务商的策略拦下，这一轮因此**一个字都没有**。
 *
 * 三家的说法见 [isBlockedStopReason]。不认这个信号的代价不是少一句提示，而是话说反了：
 * 没有正文的响应会落到上层的 `ai_error_empty_response`，那句文案让用户去「换模型 / 调大
 * max tokens」，而真正该做的是改写措辞 —— 照它做一遍，用户会先怀疑自己配置错了，
 * 把 max tokens 调到顶，再换一个模型，然后撞上同一堵墙。
 *
 * 两个刻意的设计：
 *  * 继承**裸** [Exception] 而不是 [IOException]：被拦是确定性结果，重发三次得到同一个答案，
 *    只会把「AI 没响应」的等待时间乘以三（[AiErrorMapper.isRetryableException] 按 IOException 判定）；
 *  * 带 [UserFacingMessage] 标记：文案在抛出点就已经知道，靠 [AiErrorMapper.mapException]
 *    原样透出，不再被套上一层「发生未知错误：…」。
 *
 * 抛出时机由适配器保证：**只在 `full` 为空时抛**。已经流出去的半截正文比一句准确的错误更值钱
 * ——被拦的往往只是回复的后半段，前半段该留在屏幕上（也已经 emit 过 [StreamEvent.Content]，
 * [withRetryAndEmissionGuard] 那时也不会再重试）。
 *
 * @param reason 服务商给的原因，已过 [presentableBlockReason] 净化；为 null 时用不带括号的通用文案。
 */
internal class ContentBlockedException(reason: String? = null) :
    Exception("content blocked: ${reason ?: "unspecified"}"), UserFacingMessage {

    override val uiMessage: UiMessage = if (reason == null) {
        UiMessage.Res(R.string.ai_error_content_blocked)
    } else {
        UiMessage.of(R.string.ai_error_content_blocked_reason, reason)
    }
}

private const val MAX_ATTEMPTS = 3

/**
 * 包裹一段流式请求，提供“指数退避重试 + 已 emit 内容则不重试”守护。
 *
 * - [block] 内抛 [HttpResponseException] 或可重试异常时，若尚未 emit 任何 [StreamEvent.Content]
 *   （首字节前失败），按指数退避重试，最多 [MAX_ATTEMPTS] 次。
 * - 已 emit Content 后的 mid-stream 失败**不重试**（否则会重复输出内容），直接 emit 友好错误。
 * - [CancellationException] 永远向上传播（协程取消不应被吞，否则按返回会变“发生未知错误”且阻塞取消）。
 * - 重试耗尽或不可重试时 emit [StreamEvent.Error]（消息经 [AiErrorMapper] 友好化），返回 null。
 *
 * 抽成独立函数以便用 fake block 单测，无需 Ktor MockEngine。
 *
 * @param flowEmit 适配器 flow 的 emit（透传到 FlowCollector）。
 * @param block 一次完整的请求+流式读取；其 [emit] 参数是已被本函数追踪的 emit，
 *              block 正常返回表示成功完成。
 */
internal suspend fun <T> withRetryAndEmissionGuard(
    flowEmit: suspend (StreamEvent) -> Unit,
    fallback: (suspend (emit: suspend (StreamEvent) -> Unit) -> T)? = null,
    block: suspend (emit: suspend (StreamEvent) -> Unit) -> T
): T? {
    var emittedAny = false
    val trackedEmit: suspend (StreamEvent) -> Unit = { e ->
        if (e is StreamEvent.Content) emittedAny = true
        flowEmit(e)
    }

    var attempt = 0
    while (true) {
        try {
            return block(trackedEmit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: HttpResponseException) {
            val canRetry = !emittedAny && attempt < MAX_ATTEMPTS - 1 &&
                AiErrorMapper.isRetryableStatus(e.status)
            if (canRetry) {
                delay(AiErrorMapper.backoffMillis(attempt))
                attempt++
                continue
            }
            // 保守降级：首字节前 4xx（最可能是请求被拒，如模型不支持 tools）且提供了 fallback
            //（去掉 tools 的请求）→ 用 fallback 重试一次，让对话仍可用。
            if (!emittedAny && fallback != null && e.status in 400..499) {
                return runFallback(fallback, trackedEmit, flowEmit)
            }
            flowEmit(StreamEvent.Error(AiErrorMapper.mapHttpError(e.status)))
            return null
        } catch (e: Throwable) {
            val canRetry = !emittedAny && attempt < MAX_ATTEMPTS - 1 &&
                AiErrorMapper.isRetryableException(e)
            if (canRetry) {
                delay(AiErrorMapper.backoffMillis(attempt))
                attempt++
                continue
            }
            flowEmit(StreamEvent.Error(AiErrorMapper.mapException(e)))
            return null
        }
    }
}

/**
 * 降级备用请求：只尝试一次，不再二次降级。失败则 emit 友好错误并返回 null。
 * [CancellationException] 仍向上传播。
 */
private suspend fun <T> runFallback(
    fallback: suspend (emit: suspend (StreamEvent) -> Unit) -> T,
    trackedEmit: suspend (StreamEvent) -> Unit,
    flowEmit: suspend (StreamEvent) -> Unit
): T? = try {
    fallback(trackedEmit)
} catch (ce: CancellationException) {
    throw ce
} catch (e: HttpResponseException) {
    flowEmit(StreamEvent.Error(AiErrorMapper.mapHttpError(e.status)))
    null
} catch (e: Throwable) {
    flowEmit(StreamEvent.Error(AiErrorMapper.mapException(e)))
    null
}

/**
 * 认出 SSE 流里的**内联错误**：HTTP 状态码是 200，错误对象却在流中某一行 data 里。
 *
 * 三家都有这种发法 —— Anthropic 的 `{"type":"error","error":{"type":"overloaded_error",…}}`、
 * OpenAI 兼容端点的 `{"error":{"message":…,"code":"rate_limit_exceeded"}}`、
 * Gemini 的 `{"error":{"code":429,"status":"RESOURCE_EXHAUSTED",…}}`。
 * 从前三个适配器都读不出来：那一行既不是 content，也解析不出 choices/candidates，
 * 被 `?: continue` 跳过，服务端随后关闭连接 → 走到 EOF 之后的 Done。
 * 于是一次限流/过载被报成「回复完成」：有正文时是半截，没正文时是「AI 没有返回任何内容」，
 * 把用户引去换模型，而真正该做的是等一会儿重试。
 *
 * 统一折成 [HttpResponseException] 后就能复用已有机制：首字节前的 429/5xx 自动退避重试，
 * 其余 emit [AiErrorMapper.mapHttpError] 的友好文案。
 *
 * @return 映射出的 HTTP 状态码；这一行不是错误载荷时返回 null（调用方照常继续读流）。
 */
internal fun inlineErrorStatus(obj: JsonObject, defaultStatus: Int = 500): Int? {
    val raw = obj["error"]
    // `"error": null` 是部分中转（vLLM / LiteLLM 一类）每个正常 chunk 都会带的字段，
    // 不排掉它会把整条流的每一行都当成错误。
    if (raw == null || raw is JsonNull) return null
    // 有的中转把 error 写成一句字符串。仍然是错误，只是没有可用的分类信息。
    val err = raw as? JsonObject ?: return defaultStatus
    // Gemini 直接给数字 code；顺带容错「字符串形式的数字」。
    (err["code"] as? JsonPrimitive)?.intOrNull?.let { if (it in 100..599) return it }
    for (key in listOf("status", "type", "code")) {
        (err[key] as? JsonPrimitive)?.contentOrNull?.let { name ->
            errorNameToStatus(name)?.let { return it }
        }
    }
    return defaultStatus
}

/**
 * 错误分类名 → HTTP 状态码。三家的取值空间互不重叠，合在一张表里即可：
 * Anthropic 的 `error.type`、Google 的 `error.status`（gRPC 名）、OpenAI 兼容端点的 `error.code`。
 *
 * 映射到状态码而不是各自造文案，是为了让 [AiErrorMapper.isRetryableStatus] 直接判定可重试性：
 * 过载/限流（529/429）值得自动退避重试，鉴权错（401）重试一万次也一样。
 */
private fun errorNameToStatus(name: String): Int? = when (name.trim().lowercase()) {
    // Anthropic error.type
    "invalid_request_error" -> 400
    "authentication_error" -> 401
    "permission_error" -> 403
    "not_found_error" -> 404
    "request_too_large" -> 413
    "rate_limit_error" -> 429
    "api_error" -> 500
    "overloaded_error" -> 529
    // Google error.status
    "invalid_argument", "failed_precondition", "out_of_range" -> 400
    "unauthenticated" -> 401
    "permission_denied" -> 403
    "not_found" -> 404
    "already_exists", "aborted" -> 409
    "resource_exhausted" -> 429
    "cancelled" -> 499
    "internal", "unknown", "data_loss" -> 500
    "unimplemented" -> 501
    "unavailable" -> 503
    "deadline_exceeded" -> 504
    // OpenAI 兼容端点的 error.code / error.type
    "invalid_api_key", "invalid_authentication" -> 401
    "insufficient_quota", "quota_exceeded", "rate_limit_exceeded" -> 429
    "context_length_exceeded", "invalid_request" -> 400
    "server_error", "engine_overloaded" -> 500
    else -> null
}
