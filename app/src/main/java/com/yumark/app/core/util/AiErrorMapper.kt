package com.yumark.app.core.util

import com.yumark.app.R
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * AI 请求错误的友好化映射与重试判定。
 *
 * - 把 HTTP 状态码 / 网络异常映射为面向用户的提示，**不外泄响应体原文**
 *   （旧实现 `HTTP ${status}: ${bodyAsText()}` 会把 provider 内部错误 JSON / 鉴权细节直接丢给用户）。
 * - 提供可重试判定与指数退避时长，供适配器层重试使用。
 *
 * 出口是 [UiMessage] 而不是 String：本对象在 `core`，拿不到 Context，
 * 状态码这类实参随资源 id 一起带走，到界面层再解析（见 `presentation.common.resolve`）。
 */
object AiErrorMapper {

    /** HTTP 状态码 → 面向用户的友好提示（不含响应体原文）。 */
    fun mapHttpError(status: Int): UiMessage = when (status) {
        401 -> UiMessage.Res(R.string.ai_error_http_401)
        403 -> UiMessage.Res(R.string.ai_error_http_403)
        404 -> UiMessage.Res(R.string.ai_error_http_404)
        400 -> UiMessage.Res(R.string.ai_error_http_400)
        429 -> UiMessage.Res(R.string.ai_error_http_429)
        in 500..599 -> UiMessage.of(R.string.ai_error_http_server, status)
        else -> UiMessage.of(R.string.ai_error_http_other, status)
    }

    /**
     * 异常 → 面向用户的友好提示。
     *
     * [UserFacingMessage] 优先，且必须在类型分支**之前**：带这个标记的异常在抛出那一刻就
     * 知道该跟用户说什么，再按类型套一层泛化文案等于把话说反。两个现成的例子都会踩中：
     * `FriendlyIOException`（WebDAV 401「账号或密码不正确」）**同时**是 [IOException]，
     * 按类型分流必然被说成「网络连接失败」；`ContentBlockedException`（内容被策略拦下）
     * 不是 IOException，会落到 else 分支变成「发生未知错误：ContentBlockedException」。
     * 优先级与 [ErrorHandler.classify] 一致（uiMessage → message），同一个异常在编辑器与
     * AI 面板里必须说同一句话。
     *
     * [SocketTimeoutException] 含 Ktor 的 `HttpRequestTimeoutException`
     * （其继承链：HttpRequestTimeoutException → SocketTimeoutException → IOException）。
     * 顺序敏感：SocketTimeoutException 必须在 IOException 之前匹配，否则超时会被归为“连接失败”。
     *
     * 这里的 [IOException] 按**网络**解释，与 [ErrorHandler] 把裸 IOException 按存储解释相反：
     * 差别来自调用域（此处只服务 AI 流式请求），不是分类分歧；文案取自同一处 [ErrorMessages]，
     * 保证同一种故障在编辑器与 AI 面板里说的是同一句话。
     */
    fun mapException(e: Throwable): UiMessage {
        if (e is UserFacingMessage) {
            e.uiMessage?.let { return it }
            // 标记的契约是「这个异常的 message 也是给用户看的」，故 uiMessage 缺席时退回 message
            // （文案在抛出点由运行期数据拼成、还没资源化的那类）。仍为空才继续按类型分流。
            e.message?.takeIf { it.isNotBlank() }?.let { return UiMessage.Raw(it) }
        }
        return when (e) {
            is SocketTimeoutException -> ErrorMessages.TIMEOUT
            is IOException -> ErrorMessages.NETWORK
            // 这个分支刻意保留一点原文：AI 面板是配置密集的界面，自定义 Base URL / 模型名写错时
            // provider 抛的是 `SerializationException: Unexpected JSON token at offset 42` 这类信息，
            // 换成一句「出现未知问题」用户根本无从下手。原文先经 safeDetail 脱敏、压平、截断。
            else -> UiMessage.of(R.string.ai_error_unknown_detail, ErrorHandler.safeDetail(e))
        }
    }

    /** HTTP 状态码是否值得重试（5xx 服务端错误 / 429 限流）。 */
    fun isRetryableStatus(status: Int): Boolean =
        status == 429 || status in 500..599

    /**
     * 异常是否值得重试（网络层瞬时故障）。CancellationException 调用前应已重抛，不会到达此处。
     * [SocketTimeoutException] / Ktor `HttpRequestTimeoutException` 均为 [IOException] 子类，已被覆盖。
     *
     * [UserFacingMessage] 一律不重试：带这个标记意味着「已经知道该跟用户说什么」，也就意味着
     * 结论已经定了 —— WebDAV 的 401、被内容策略拦下的请求，重发三次得到的是同一个答案，
     * 只把失败提示的到达时间乘以三。这条判断从前只写在 `WebDavClient.retrying` 里
     *（`e !is FriendlyIOException && …`），同一套策略抄两份，AI 流那一侧就漏掉了。
     * 反例是刻意的：`StreamInterruptedException` 是**不带**标记的裸 [IOException]，
     * 它代表连接被掐断，正是最该自动退避重试的那一类。
     */
    fun isRetryableException(e: Throwable): Boolean = e !is UserFacingMessage && e is IOException

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
