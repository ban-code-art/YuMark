package com.yumark.app.data.ai

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.AiErrorMapper
import com.yumark.app.core.util.ErrorMessages
import com.yumark.app.core.util.UiMessage
import com.yumark.app.domain.model.StreamEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class StreamRetryTest {

    @Test
    fun `retries retryable http status then succeeds`() = runTest {
        var calls = 0
        val events = mutableListOf<StreamEvent>()
        val result = withRetryAndEmissionGuard(flowEmit = { events += it }) { emit ->
            calls++
            if (calls < 3) throw HttpResponseException(503, "busy")
            emit(StreamEvent.Content("hi"))
            emit(StreamEvent.Done("hi"))
            "ok"
        }
        assertThat(calls).isEqualTo(3)
        assertThat(result).isEqualTo("ok")
        assertThat(events).containsExactly(
            StreamEvent.Content("hi"), StreamEvent.Done("hi")
        ).inOrder()
    }

    @Test
    fun `emits friendly error after exhausting retries on http status`() = runTest {
        val events = mutableListOf<StreamEvent>()
        val result = withRetryAndEmissionGuard<String>(flowEmit = { events += it }) { _ ->
            throw HttpResponseException(503, "busy")
        }
        assertThat(result).isNull()
        assertThat(events).hasSize(1)
        val err = events.first() as StreamEvent.Error
        // 5xx 的文案带状态码实参，由 AiErrorMapper.mapHttpError 产出
        assertThat(err.message).isEqualTo(UiMessage.of(R.string.ai_error_http_server, 503))
    }

    @Test
    fun `non-retryable http status emits error immediately without retry`() = runTest {
        var calls = 0
        val events = mutableListOf<StreamEvent>()
        withRetryAndEmissionGuard<String>(flowEmit = { events += it }) { _ ->
            calls++
            throw HttpResponseException(401, "nope")
        }
        assertThat(calls).isEqualTo(1)
        // 401 是「API Key 无效或已过期」那条，映射表里没有实参
        assertThat((events.first() as StreamEvent.Error).message)
            .isEqualTo(UiMessage.Res(R.string.ai_error_http_401))
    }

    @Test
    fun `retries io exception then succeeds`() = runTest {
        var calls = 0
        withRetryAndEmissionGuard(flowEmit = { }) { emit ->
            calls++
            if (calls < 2) throw IOException("reset")
            emit(StreamEvent.Done("ok"))
        }
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `does not retry after content already emitted`() = runTest {
        var calls = 0
        val events = mutableListOf<StreamEvent>()
        withRetryAndEmissionGuard<String>(flowEmit = { events += it }) { emit ->
            calls++
            emit(StreamEvent.Content("partial"))
            throw IOException("mid-stream drop")
        }
        assertThat(calls).isEqualTo(1) // mid-stream 失败不重试，避免内容重复
        assertThat(events).hasSize(2)
        assertThat(events[0]).isEqualTo(StreamEvent.Content("partial"))
        val err = events[1] as StreamEvent.Error
        // mid-stream 掉线走 mapException 的 IOException 分支，与 ErrorHandler 共用同一句
        assertThat(err.message).isEqualTo(ErrorMessages.NETWORK)
    }

    @Test
    fun `cancellation exception propagates and is not swallowed`() = runTest {
        // 关键：CancellationException 不能被 mapException 吞成“发生未知错误”的 Error 事件
        var caught: Throwable? = null
        val events = mutableListOf<StreamEvent>()
        try {
            withRetryAndEmissionGuard<String>(flowEmit = { events += it }) { _ ->
                throw CancellationException("user-cancelled")
            }
        } catch (e: Throwable) {
            caught = e
        }
        assertThat(caught).isInstanceOf(CancellationException::class.java)
        assertThat(events).isEmpty() // 没有 emit 任何 Error
    }

    @Test
    fun `falls back when primary fails with 4xx before first byte`() = runTest {
        var primaryCalls = 0
        var fallbackCalls = 0
        val events = mutableListOf<StreamEvent>()
        val result = withRetryAndEmissionGuard<String>(
            flowEmit = { events += it },
            fallback = { emit ->
                fallbackCalls++
                emit(StreamEvent.Content("降级回复"))
                emit(StreamEvent.Done("降级回复"))
                "fb"
            }
        ) { _ ->
            primaryCalls++
            throw HttpResponseException(400, "tools not supported")
        }
        assertThat(primaryCalls).isEqualTo(1)
        assertThat(fallbackCalls).isEqualTo(1)
        assertThat(result).isEqualTo("fb")
        assertThat(events.filterIsInstance<StreamEvent.Content>().map { it.text }).contains("降级回复")
        assertThat(events.filterIsInstance<StreamEvent.Error>()).isEmpty()
    }

    @Test
    fun `does not fall back on 5xx but retries the primary`() = runTest {
        var primaryCalls = 0
        var fallbackCalls = 0
        withRetryAndEmissionGuard<String>(
            flowEmit = { },
            fallback = { fallbackCalls++; "fb" }
        ) { _ ->
            primaryCalls++
            throw HttpResponseException(503, "busy")
        }
        assertThat(primaryCalls).isEqualTo(3)  // 5xx 走常规重试到耗尽
        assertThat(fallbackCalls).isEqualTo(0) // 不走降级
    }

    @Test
    fun `does not fall back after content already emitted`() = runTest {
        var fallbackCalls = 0
        val events = mutableListOf<StreamEvent>()
        withRetryAndEmissionGuard<String>(
            flowEmit = { events += it },
            fallback = { fallbackCalls++; "fb" }
        ) { emit ->
            emit(StreamEvent.Content("partial"))
            throw HttpResponseException(400, "boom")
        }
        assertThat(fallbackCalls).isEqualTo(0)  // 已 emit 内容，不降级
        assertThat(events.last()).isInstanceOf(StreamEvent.Error::class.java)
    }

    // ---- 内联 SSE 错误识别（inlineErrorStatus）----
    // 三家都会在 HTTP 200 的流里塞错误对象。识别不出来的后果不是「报错文案不好看」，
    // 而是整条流被当成正常收尾：有正文时是半截，没正文时是「AI 没有返回任何内容」。

    private fun status(raw: String): Int? =
        inlineErrorStatus(Json.parseToJsonElement(raw).jsonObject)

    @Test
    fun `null error field is not treated as an inline error`() {
        // 部分中转（vLLM / LiteLLM 一类）每个正常 chunk 都带 "error": null，
        // 认成错误会让整条流的第一行就报错。
        assertThat(status("""{"error":null,"choices":[{"delta":{"content":"hi"}}]}""")).isNull()
    }

    @Test
    fun `ordinary chunk without error field is not an inline error`() {
        assertThat(status("""{"choices":[{"delta":{"content":"hi"},"finish_reason":null}]}""")).isNull()
        assertThat(status("""{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}""")).isNull()
    }

    @Test
    fun `gemini numeric code is used directly`() {
        assertThat(status("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota"}}"""))
            .isEqualTo(429)
        assertThat(status("""{"error":{"code":503}}""")).isEqualTo(503)
    }

    @Test
    fun `google grpc status name maps to http status`() {
        assertThat(status("""{"error":{"status":"RESOURCE_EXHAUSTED"}}""")).isEqualTo(429)
        assertThat(status("""{"error":{"status":"UNAUTHENTICATED"}}""")).isEqualTo(401)
        assertThat(status("""{"error":{"status":"UNAVAILABLE"}}""")).isEqualTo(503)
    }

    @Test
    fun `anthropic error type maps to http status`() {
        // 529 是 Anthropic 的过载专用码，必须落在可重试一侧
        assertThat(status("""{"type":"error","error":{"type":"overloaded_error"}}""")).isEqualTo(529)
        assertThat(status("""{"type":"error","error":{"type":"rate_limit_error"}}""")).isEqualTo(429)
        assertThat(status("""{"type":"error","error":{"type":"authentication_error"}}""")).isEqualTo(401)
    }

    @Test
    fun `openai string code maps to http status`() {
        assertThat(status("""{"error":{"message":"slow down","code":"rate_limit_exceeded"}}"""))
            .isEqualTo(429)
        assertThat(status("""{"error":{"message":"bad key","code":"invalid_api_key"}}"""))
            .isEqualTo(401)
        assertThat(status("""{"error":{"message":"too long","code":"context_length_exceeded"}}"""))
            .isEqualTo(400)
    }

    @Test
    fun `out of range numeric code falls through to the name table`() {
        // 有的中转把 code 写成 0 或自家的内部错误号，落不到 HTTP 区间就该继续看分类名
        assertThat(status("""{"error":{"code":0,"status":"UNAUTHENTICATED"}}""")).isEqualTo(401)
        assertThat(status("""{"error":{"code":100001,"type":"overloaded_error"}}""")).isEqualTo(529)
    }

    @Test
    fun `unclassifiable error still reports a server error`() {
        // 认不出分类也必须当错误：宁可报「服务端异常」，也不能让它冒充正常收尾
        assertThat(status("""{"error":"boom"}""")).isEqualTo(500)
        assertThat(status("""{"error":{}}""")).isEqualTo(500)
        assertThat(status("""{"error":{"type":"something_new"}}""")).isEqualTo(500)
        assertThat(status("""{"error":{"message":"no classification at all"}}""")).isEqualTo(500)
    }

    @Test
    fun `mapped inline statuses land on the intended retry side`() {
        // inlineErrorStatus 折成状态码的全部意义：让现成的退避重试判定直接生效
        assertThat(AiErrorMapper.isRetryableStatus(429)).isTrue()
        assertThat(AiErrorMapper.isRetryableStatus(500)).isTrue()
        assertThat(AiErrorMapper.isRetryableStatus(503)).isTrue()
        assertThat(AiErrorMapper.isRetryableStatus(529)).isTrue()
        assertThat(AiErrorMapper.isRetryableStatus(401)).isFalse()
        assertThat(AiErrorMapper.isRetryableStatus(400)).isFalse()
    }

    // ---- 流被掐断（StreamInterruptedException）----

    @Test
    fun `interrupted stream before first token is retried like any dropped connection`() = runTest {
        var calls = 0
        val events = mutableListOf<StreamEvent>()
        val result = withRetryAndEmissionGuard(flowEmit = { events += it }) { emit ->
            calls++
            if (calls < 3) throw StreamInterruptedException()
            emit(StreamEvent.Content("完整回复"))
            emit(StreamEvent.Done("完整回复"))
            "ok"
        }
        // 继承 IOException 就是为了走这条现成的退避重试通路
        assertThat(calls).isEqualTo(3)
        assertThat(result).isEqualTo("ok")
        assertThat(events.filterIsInstance<StreamEvent.Error>()).isEmpty()
    }

    @Test
    fun `interrupted stream after content reports network failure not completion`() = runTest {
        var calls = 0
        val events = mutableListOf<StreamEvent>()
        withRetryAndEmissionGuard<String>(flowEmit = { events += it }) { emit ->
            calls++
            emit(StreamEvent.Content("半截"))
            throw StreamInterruptedException()
        }
        assertThat(calls).isEqualTo(1) // 已 emit 内容，不重试（否则内容重复）
        // 关键：最后一个事件是 Error 而不是 Done —— Done 的语义是「这段正文是完整的」，
        // 半截正文走到 Agent 的隐式写入/划词改写那两处会整篇覆盖用户文档。
        assertThat(events.filterIsInstance<StreamEvent.Done>()).isEmpty()
        assertThat((events.last() as StreamEvent.Error).message).isEqualTo(ErrorMessages.NETWORK)
    }

    @Test
    fun `interrupted stream retries the primary instead of falling back`() = runTest {
        // 降级那条路只认 HttpResponseException 的 4xx（模型不支持 tools）。掉线是另一回事：
        // 去掉 tools 也一样会掉，该做的是原样重试。两条路不能互相抢。
        var fallbackCalls = 0
        var primaryCalls = 0
        withRetryAndEmissionGuard<String>(
            flowEmit = { },
            fallback = { fallbackCalls++; "fb" }
        ) { _ ->
            primaryCalls++
            throw StreamInterruptedException()
        }
        assertThat(primaryCalls).isEqualTo(3)
        assertThat(fallbackCalls).isEqualTo(0)
    }

    // ---- 内容被策略拦下（ContentBlockedException）----
    // 与掐断刚好相反的一类：掐断是瞬时故障，值得重试；被拦是确定性结论，重发只是把
    // 同一个答案等三遍。文案也相反——那边说「网络连接失败」，这边该说「改写措辞」。

    @Test
    fun `content blocked is not retried`() = runTest {
        var calls = 0
        withRetryAndEmissionGuard<String>(flowEmit = { }) { _ ->
            calls++
            throw ContentBlockedException("SAFETY")
        }
        // 继承裸 Exception（而不是 IOException）+ 带 UserFacingMessage 标记，两处都让
        // isRetryableException 说不。重试三次拿回来的是同一个 SAFETY。
        assertThat(calls).isEqualTo(1)
        assertThat(AiErrorMapper.isRetryableException(ContentBlockedException("SAFETY"))).isFalse()
        assertThat(AiErrorMapper.isRetryableException(ContentBlockedException())).isFalse()
    }

    @Test
    fun `content blocked surfaces its own copy not the unknown-error one`() = runTest {
        val events = mutableListOf<StreamEvent>()
        withRetryAndEmissionGuard<String>(flowEmit = { events += it }) { _ ->
            throw ContentBlockedException("SAFETY")
        }
        val err = events.single() as StreamEvent.Error
        // 原因原样带进括号里：它是用户唯一能拿去搜服务商文档的线索
        assertThat(err.message)
            .isEqualTo(UiMessage.of(R.string.ai_error_content_blocked_reason, "SAFETY"))
    }

    @Test
    fun `content blocked without a reason uses the parenthesis-free copy`() = runTest {
        val events = mutableListOf<StreamEvent>()
        withRetryAndEmissionGuard<String>(flowEmit = { events += it }) { _ ->
            // Gemini 的 OTHER / BLOCK_REASON_UNSPECIFIED 过 presentableBlockReason 后就是 null
            throw ContentBlockedException(null)
        }
        assertThat((events.single() as StreamEvent.Error).message)
            .isEqualTo(UiMessage.Res(R.string.ai_error_content_blocked))
    }

    @Test
    fun `content blocked does not trigger the no-tools fallback`() = runTest {
        // 降级只认 HttpResponseException 的 4xx（模型不支持 tools）。去掉 tools 再问一遍
        // 一样会被拦，白搭一次请求。
        var fallbackCalls = 0
        var primaryCalls = 0
        withRetryAndEmissionGuard<String>(
            flowEmit = { },
            fallback = { fallbackCalls++; "fb" }
        ) { _ ->
            primaryCalls++
            throw ContentBlockedException("content_filter")
        }
        assertThat(primaryCalls).isEqualTo(1)
        assertThat(fallbackCalls).isEqualTo(0)
    }
}
