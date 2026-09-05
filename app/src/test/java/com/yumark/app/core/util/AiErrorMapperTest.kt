package com.yumark.app.core.util

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 断言的是**映射关系**（状态码 → 哪条资源 + 哪些实参），不是文案字样。
 *
 * 出口从 String 改成 [UiMessage] 之后，`contains("API Key")` 这种写法有两个问题：
 * 一是编译不过（[UiMessage] 上没有 contains），二是即使能过，在 `values-en` 语区也必然假失败。
 * 资源 id 是编译期常量，跨语区稳定，正是该钉住的东西。
 */
class AiErrorMapperTest {

    @Test
    fun `maps common http status codes to their own resources`() {
        assertThat(AiErrorMapper.mapHttpError(401)).isEqualTo(UiMessage.Res(R.string.ai_error_http_401))
        assertThat(AiErrorMapper.mapHttpError(403)).isEqualTo(UiMessage.Res(R.string.ai_error_http_403))
        assertThat(AiErrorMapper.mapHttpError(404)).isEqualTo(UiMessage.Res(R.string.ai_error_http_404))
        assertThat(AiErrorMapper.mapHttpError(400)).isEqualTo(UiMessage.Res(R.string.ai_error_http_400))
        assertThat(AiErrorMapper.mapHttpError(429)).isEqualTo(UiMessage.Res(R.string.ai_error_http_429))
        // 5xx 共用一条带 %1${'$'}d 的文案：状态码作为实参带走，而不是拼进句子
        assertThat(AiErrorMapper.mapHttpError(500))
            .isEqualTo(UiMessage.of(R.string.ai_error_http_server, 500))
        assertThat(AiErrorMapper.mapHttpError(502))
            .isEqualTo(UiMessage.of(R.string.ai_error_http_server, 502))
        assertThat(AiErrorMapper.mapHttpError(503))
            .isEqualTo(UiMessage.of(R.string.ai_error_http_server, 503))
    }

    @Test
    fun `unmapped status codes fall back to the generic resource`() {
        // 418 既不在具名分支里也不在 5xx 段，应落到 ai_error_http_other
        assertThat(AiErrorMapper.mapHttpError(418))
            .isEqualTo(UiMessage.of(R.string.ai_error_http_other, 418))
    }

    @Test
    fun `http error message never leaks response body`() {
        // mapHttpError 只收状态码，没有 body 可泄；结构上再钉一道：实参只允许 Int。
        // 字符串实参是唯一能把响应体原文带上界面的通道。
        val msg = AiErrorMapper.mapHttpError(500)
        assertThat(msg).isInstanceOf(UiMessage.Res::class.java)
        assertThat((msg as UiMessage.Res).args.filterIsInstance<String>()).isEmpty()
    }

    @Test
    fun `maps socket timeout as timeout not connection failure`() {
        // 顺序敏感：SocketTimeoutException 是 IOException 子类，必须先命中超时分支
        assertThat(AiErrorMapper.mapException(SocketTimeoutException())).isEqualTo(ErrorMessages.TIMEOUT)
    }

    @Test
    fun `maps generic io exception as connection failure`() {
        // 与 ErrorMessages 同一处取文案：同一种故障在编辑器与 AI 面板里说的是同一句话
        assertThat(AiErrorMapper.mapException(IOException("broken pipe"))).isEqualTo(ErrorMessages.NETWORK)
    }

    @Test
    fun `maps unknown exception without crashing and keeps a redacted detail`() {
        val msg = AiErrorMapper.mapException(IllegalStateException("boom"))
        assertThat(msg).isInstanceOf(UiMessage.Res::class.java)
        val res = msg as UiMessage.Res
        assertThat(res.id).isEqualTo(R.string.ai_error_unknown_detail)
        // 这个分支刻意保留原文（先过 safeDetail 脱敏/压平/截断），是唯一允许带 String 实参的分支
        assertThat(res.args).containsExactly("boom")
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

    // ---- UserFacingMessage 优先于类型分支 ----
    // 带这个标记的异常在抛出那一刻就知道该跟用户说什么，再按类型套一层泛化文案等于把话说反。

    @Test
    fun `friendly io exception keeps its own copy instead of the network one`() {
        // 回归钉：FriendlyIOException **同时**是 IOException，按类型分流必然被说成
        // 「网络连接失败，请检查网络」——而它真正想说的是「账号或密码不正确」，
        // 用户照那句去查网络，永远查不到问题。
        val e = FriendlyIOException(UiMessage.Res(R.string.webdav_error_unauthorized))
        assertThat(AiErrorMapper.mapException(e))
            .isEqualTo(UiMessage.Res(R.string.webdav_error_unauthorized))
        assertThat(AiErrorMapper.mapException(e)).isNotEqualTo(ErrorMessages.NETWORK)
    }

    @Test
    fun `marked exception falls back to its message when it has no resource copy`() {
        // 收 String 的那个构造器：文案在抛出点由运行时数据拼成，还没资源化。
        // 标记的契约是「这个异常的 message 也是给用户看的」，故原样透出。
        val e = FriendlyIOException("远端返回了一个空目录")
        assertThat(AiErrorMapper.mapException(e)).isEqualTo(UiMessage.Raw("远端返回了一个空目录"))
    }

    @Test
    fun `marked non-io exception is not reported as an unknown error`() {
        // 另一个方向的受害者：不是 IOException 的标记异常会落到 else 分支，
        // 变成「发生未知错误：ContentBlockedException」——把一句已经写好的话丢了。
        val msg = AiErrorMapper.mapException(MarkedFailure(UiMessage.Res(R.string.ai_error_http_429)))
        assertThat(msg).isEqualTo(UiMessage.Res(R.string.ai_error_http_429))
    }

    @Test
    fun `marked exception with neither copy nor message still falls through to type dispatch`() {
        // 标记但两样都没有：不能就此返回空，仍按类型分流
        assertThat(AiErrorMapper.mapException(MarkedFailure(uiMessage = null, message = null)))
            .isInstanceOf(UiMessage.Res::class.java)
        assertThat((AiErrorMapper.mapException(MarkedFailure(null, null)) as UiMessage.Res).id)
            .isEqualTo(R.string.ai_error_unknown_detail)
    }

    @Test
    fun `marked exceptions are never retried even when they are io exceptions`() {
        // 带标记意味着「结论已经定了」：WebDAV 的 401 重发三次得到的是同一个 401，
        // 只把失败提示的到达时间乘以三。这条判断从前只写在 WebDavClient.retrying 里，
        // 同一套策略抄两份，AI 流那一侧就漏掉了。
        assertThat(AiErrorMapper.isRetryableException(FriendlyIOException("已成句"))).isFalse()
        assertThat(
            AiErrorMapper.isRetryableException(
                FriendlyIOException(UiMessage.Res(R.string.webdav_error_unauthorized))
            )
        ).isFalse()
        // 反例是刻意的：不带标记的裸 IOException 代表连接层瞬时故障，正该自动退避重试
        assertThat(AiErrorMapper.isRetryableException(IOException("reset"))).isTrue()
    }

    /** 带 [UserFacingMessage] 标记、但**不是** IOException 的失败，对应 `ContentBlockedException` 那一类。 */
    private class MarkedFailure(
        override val uiMessage: UiMessage?,
        message: String? = null
    ) : Exception(message), UserFacingMessage
}
