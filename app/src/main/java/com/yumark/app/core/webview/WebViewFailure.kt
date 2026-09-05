package com.yumark.app.core.webview

import com.yumark.app.R
import com.yumark.app.core.util.UiMessage

/**
 * WebView 失败文案表：把 WebView 的错误码翻成能直接进 Snackbar / 错误卡片的一句话。
 *
 * 三条硬约束：
 * 1. **不回显 URL 与 description**。预览加载的是 `file:///data/user/0/…` 与 `content://…`，
 *    远端图片的 description 里还会带完整查询串；这些一律不上界面，只在 DEBUG 下由调用点
 *    脱敏后进 logcat。因此这里所有入口都只收错误码，拿不到 URL——用签名把约束钉死。
 * 2. **零 Android 运行时依赖**。错误码用本地常量复刻 `android.webkit.WebViewClient.ERROR_*`
 *    （取值自 API 23 起冻结），使映射表能被 JVM 单测直接覆盖：`android.jar` 的单测桩
 *    一访问就抛 `Stub!`。出口是 [UiMessage] 而不是 String，同理——它只持有 `R.string` 的
 *    int 与实参，解析留给界面层（见 `presentation.common.resolve`），本文件仍是纯 Kotlin。
 * 3. 文案面向人，不含错误码之外的技术细节；HTTP 状态码可以留，它是用户能拿去搜的线索。
 */
object WebViewFailure {

    // ---- android.webkit.WebViewClient.ERROR_* 的取值复刻 ----

    const val ERROR_UNKNOWN = -1
    const val ERROR_HOST_LOOKUP = -2
    const val ERROR_UNSUPPORTED_AUTH_SCHEME = -3
    const val ERROR_AUTHENTICATION = -4
    const val ERROR_PROXY_AUTHENTICATION = -5
    const val ERROR_CONNECT = -6
    const val ERROR_IO = -7
    const val ERROR_TIMEOUT = -8
    const val ERROR_REDIRECT_LOOP = -9
    const val ERROR_UNSUPPORTED_SCHEME = -10
    const val ERROR_FAILED_SSL_HANDSHAKE = -11
    const val ERROR_BAD_URL = -12
    const val ERROR_FILE = -13
    const val ERROR_FILE_NOT_FOUND = -14
    const val ERROR_TOO_MANY_REQUESTS = -15
    const val ERROR_UNSAFE_RESOURCE = -16

    /** 渲染器迟迟不回 `onReady`：模板脚本没跑起来，页面会无声空白。 */
    val NOT_READY: UiMessage = UiMessage.Res(R.string.webview_error_not_ready)

    /** 兜底文案：未知错误码，以及框架给不出错误码时。 */
    val GENERIC: UiMessage = UiMessage.Res(R.string.webview_error_generic)

    /** 主文档加载失败的文案。[errorCode] 为 `WebResourceError.getErrorCode()`。 */
    fun loadMessage(errorCode: Int): UiMessage = when (errorCode) {
        ERROR_HOST_LOOKUP -> UiMessage.Res(R.string.webview_error_host_lookup)
        ERROR_UNSUPPORTED_AUTH_SCHEME -> UiMessage.Res(R.string.webview_error_unsupported_auth)
        ERROR_AUTHENTICATION -> UiMessage.Res(R.string.webview_error_auth)
        ERROR_PROXY_AUTHENTICATION -> UiMessage.Res(R.string.webview_error_proxy_auth)
        ERROR_CONNECT -> UiMessage.Res(R.string.webview_error_connect)
        ERROR_IO -> UiMessage.Res(R.string.webview_error_io)
        ERROR_TIMEOUT -> UiMessage.Res(R.string.webview_error_timeout)
        ERROR_REDIRECT_LOOP -> UiMessage.Res(R.string.webview_error_redirect_loop)
        ERROR_UNSUPPORTED_SCHEME -> UiMessage.Res(R.string.webview_error_unsupported_scheme)
        ERROR_FAILED_SSL_HANDSHAKE -> UiMessage.Res(R.string.webview_error_ssl)
        ERROR_BAD_URL -> UiMessage.Res(R.string.webview_error_bad_url)
        ERROR_FILE -> UiMessage.Res(R.string.webview_error_file)
        ERROR_FILE_NOT_FOUND -> UiMessage.Res(R.string.webview_error_file_not_found)
        ERROR_TOO_MANY_REQUESTS -> UiMessage.Res(R.string.webview_error_too_many_requests)
        ERROR_UNSAFE_RESOURCE -> UiMessage.Res(R.string.webview_error_unsafe_resource)
        // ERROR_UNKNOWN 与任何未来新增/厂商自定义码都落到这里
        else -> GENERIC
    }

    /** 主文档拿到 HTTP 错误响应的文案。状态码保留，便于用户/支持人员对照。 */
    fun httpMessage(statusCode: Int): UiMessage = when {
        statusCode == 401 || statusCode == 403 ->
            UiMessage.of(R.string.webview_http_forbidden, statusCode)
        statusCode == 404 || statusCode == 410 ->
            UiMessage.of(R.string.webview_http_not_found, statusCode)
        statusCode == 408 || statusCode == 504 ->
            UiMessage.of(R.string.webview_http_timeout, statusCode)
        // 只在 429 上触发，状态码写死在文案里，不需要实参
        statusCode == 429 -> UiMessage.Res(R.string.webview_http_too_many)
        statusCode in 500..599 -> UiMessage.of(R.string.webview_http_server, statusCode)
        statusCode in 400..499 -> UiMessage.of(R.string.webview_http_client, statusCode)
        else -> GENERIC
    }

    /**
     * 渲染进程消失的文案。[didCrash] 即 `RenderProcessGoneDetail.didCrash()`：
     * true 是渲染器自己崩了，false 是系统在内存压力下回收了它——后者在低端机上是常态，
     * 不是缺陷，文案要说清是"内存不足"而不是"出错了"。
     */
    fun renderProcessMessage(didCrash: Boolean): UiMessage = UiMessage.Res(
        if (didCrash) R.string.webview_render_crash else R.string.webview_render_reclaimed
    )
}
