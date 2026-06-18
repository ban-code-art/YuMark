package com.yumark.app.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException

class AiErrorMapperTest {

    @Test
    fun `maps common http status codes to friendly messages`() {
        assertThat(AiErrorMapper.mapHttpError(401)).contains("API Key")
        assertThat(AiErrorMapper.mapHttpError(403)).contains("权限")
        assertThat(AiErrorMapper.mapHttpError(404)).contains("Base URL")
        assertThat(AiErrorMapper.mapHttpError(400)).contains("请求格式错误")
        assertThat(AiErrorMapper.mapHttpError(429)).contains("限流")
        assertThat(AiErrorMapper.mapHttpError(500)).contains("暂时不可用")
        assertThat(AiErrorMapper.mapHttpError(502)).contains("暂时不可用")
        assertThat(AiErrorMapper.mapHttpError(503)).contains("暂时不可用")
    }

    @Test
    fun `http error message never leaks response body`() {
        // mapHttpError 不接受 body，根本无外泄途径；且不含 JSON 大括号
        val msg = AiErrorMapper.mapHttpError(500)
        assertThat(msg).doesNotContain("{")
        assertThat(msg).doesNotContain("bodyAsText")
    }

    @Test
    fun `maps socket timeout as timeout not connection failure`() {
        // 顺序敏感：SocketTimeoutException 必须命中“超时”而非“连接失败”
        val msg = AiErrorMapper.mapException(SocketTimeoutException())
        assertThat(msg).contains("超时")
        assertThat(msg).doesNotContain("连接失败")
    }

    @Test
    fun `maps generic io exception as connection failure`() {
        val msg = AiErrorMapper.mapException(IOException("broken pipe"))
        assertThat(msg).contains("网络连接失败")
    }

    @Test
    fun `maps unknown exception without crashing`() {
        val msg = AiErrorMapper.mapException(IllegalStateException("boom"))
        assertThat(msg).contains("未知错误")
        assertThat(msg).contains("boom")
    }

    @Test
    fun `isRetryableStatus true for 429 and 5xx only`() {
        assertThat(AiErrorMapper.isRetryableStatus(429)).isTrue()
        assertThat(AiErrorMapper.isRetryableStatus(500)).isTrue()
        assertThat(AiErrorMapper.isRetryableStatus(502)).isTrue()
        assertThat(AiErrorMapper.isRetryableStatus(503)).isTrue()
        assertThat(AiErrorMapper.isRetryableStatus(504)).isTrue()
        assertThat(AiErrorMapper.isRetryableStatus(401)).isFalse()
        assertThat(AiErrorMapper.isRetryableStatus(400)).isFalse()
        assertThat(AiErrorMapper.isRetryableStatus(404)).isFalse()
        assertThat(AiErrorMapper.isRetryableStatus(200)).isFalse()
    }

    @Test
    fun `isRetryableException true for io and timeout, false for others`() {
        assertThat(AiErrorMapper.isRetryableException(IOException())).isTrue()
        assertThat(AiErrorMapper.isRetryableException(SocketTimeoutException())).isTrue()
        // HttpRequestTimeoutException 是 SocketTimeoutException 子类，同样可重试
        assertThat(AiErrorMapper.isRetryableException(IllegalStateException("nope"))).isFalse()
        assertThat(AiErrorMapper.isRetryableException(RuntimeException())).isFalse()
    }

    @Test
    fun `backoff is exponential and within jitter bounds`() {
        // 期望基数 500/1000/2000，抖动 0..249
        assertThat(AiErrorMapper.backoffMillis(0)).isAtLeast(500L)
        assertThat(AiErrorMapper.backoffMillis(0)).isAtMost(749L)
        assertThat(AiErrorMapper.backoffMillis(1)).isAtLeast(1000L)
        assertThat(AiErrorMapper.backoffMillis(1)).isAtMost(1249L)
        assertThat(AiErrorMapper.backoffMillis(2)).isAtLeast(2000L)
        assertThat(AiErrorMapper.backoffMillis(2)).isAtMost(2249L)
        // 多次采样都应落在合法区间
        repeat(50) { i ->
            val v = AiErrorMapper.backoffMillis(i % 3)
            assertThat(v).isAtLeast(500L)
        }
    }
}
