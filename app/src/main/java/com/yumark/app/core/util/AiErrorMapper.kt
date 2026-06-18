package com.yumark.app.core.util

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * AI 请求错误的友好化映射与重试判定。
 *
 * - 把 HTTP 状态码 / 网络异常映射为面向用户的中文提示，**不外泄响应体原文**
 *   （旧实现 `HTTP ${status}: ${bodyAsText()}` 会把 provider 内部错误 JSON / 鉴权细节直接丢给用户）。
 * - 提供可重试判定与指数退避时长，供适配器层重试使用。
 *
 * 注：刻意返回硬编码中文，与项目现有“AI 文案全硬编码在 Compose”风格一致，
 * 不引入 strings.xml 线程化；未来若做 i18n 可统一迁移。
 */
object AiErrorMapper {

    /** HTTP 状态码 → 面向用户的友好提示（不含响应体原文）。 */
    fun mapHttpError(status: Int): String = when (status) {
        401 -> "API Key 无效或已过期，请在设置中检查"
        403 -> "无访问权限，请检查 API Key 与账户权限"
        404 -> "接口地址不存在，请检查 Base URL"
        400 -> "请求格式错误，模型或参数可能有误"
        429 -> "请求过于频繁，已被限流，请稍后重试"
        in 500..599 -> "AI 服务暂时不可用（HTTP $status），请稍后重试"
        else -> "请求失败（HTTP $status）"
    }

    /**
     * 异常 → 面向用户的友好提示。
     *
     * [SocketTimeoutException] 含 Ktor 的 `HttpRequestTimeoutException`
     * （其继承链：HttpRequestTimeoutException → SocketTimeoutException → IOException）。
     * 顺序敏感：SocketTimeoutException 必须在 IOException 之前匹配，否则超时会被归为“连接失败”。
     */
    fun mapException(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "网络请求超时，请检查网络后重试"
        is IOException -> "网络连接失败，请检查网络"
        else -> "发生未知错误：${e.message ?: e::class.simpleName ?: "未知异常"}"
    }

    /** HTTP 状态码是否值得重试（5xx 服务端错误 / 429 限流）。 */
    fun isRetryableStatus(status: Int): Boolean =
        status == 429 || status in 500..599

    /**
     * 异常是否值得重试（网络层瞬时故障）。CancellationException 调用前应已重抛，不会到达此处。
     * [SocketTimeoutException] / Ktor `HttpRequestTimeoutException` 均为 [IOException] 子类，已被覆盖。
     */
    fun isRetryableException(e: Throwable): Boolean = e is IOException

    /**
     * 指数退避时长（毫秒）：base * 2^attempt + 抖动。
     * attempt 从 0 起 → 期望 {500, 1000, 2000} + 0..jitter 抖动。
     */
    fun backoffMillis(attempt: Int, base: Long = 500L, jitter: Long = 250L): Long {
        val shift = attempt.coerceAtLeast(0).coerceAtMost(30)
        val exp = base shl shift
        val j = if (jitter > 0) Random.nextLong(0, jitter) else 0L
        return exp + j
    }
}
