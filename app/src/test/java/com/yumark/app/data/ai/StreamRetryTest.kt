package com.yumark.app.data.ai

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.StreamEvent
import kotlinx.coroutines.test.runTest
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
        assertThat(err.message).contains("暂时不可用")
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
        assertThat((events.first() as StreamEvent.Error).message).contains("API Key")
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
        assertThat(err.message).contains("网络连接失败")
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
}
