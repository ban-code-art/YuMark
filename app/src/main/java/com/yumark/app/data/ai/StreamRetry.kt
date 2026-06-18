package com.yumark.app.data.ai

import com.yumark.app.core.util.AiErrorMapper
import com.yumark.app.domain.model.StreamEvent
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/** 适配器内部信号：HTTP 非 2xx。body 仅用于诊断，不进用户消息。 */
internal class HttpResponseException(val status: Int, val body: String) : Exception()

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
