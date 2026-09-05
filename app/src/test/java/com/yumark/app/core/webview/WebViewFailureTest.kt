package com.yumark.app.core.webview

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import org.junit.jupiter.api.Test

/**
 * [WebViewFailure] 的文案契约。
 *
 * 出口是 [UiMessage] 而不是 String，所以这里测的是**映射关系**：哪个错误码/状态码对应哪条
 * `R.string`，而不是那条资源的具体字样（后者住在 `res/values/strings_errors.xml` 与它的 en 语区
 * 副本里，断言中文字面量会在换语区时假失败）。
 *
 * 盯住三件事：每个错误码都映到专属文案、未知码有兜底、以及文案不可能夹带路径/URL——最后一条现在
 * 是结构性的：映射表只吃 int，实参里出现 String 才有外泄通道。
 */
class WebViewFailureTest {

    /** 全部已知错误码，顺序与 android.webkit.WebViewClient.ERROR_* 一致 */
    private val knownCodes = listOf(
        WebViewFailure.ERROR_HOST_LOOKUP,
        WebViewFailure.ERROR_UNSUPPORTED_AUTH_SCHEME,
        WebViewFailure.ERROR_AUTHENTICATION,
        WebViewFailure.ERROR_PROXY_AUTHENTICATION,
        WebViewFailure.ERROR_CONNECT,
        WebViewFailure.ERROR_IO,
        WebViewFailure.ERROR_TIMEOUT,
        WebViewFailure.ERROR_REDIRECT_LOOP,
        WebViewFailure.ERROR_UNSUPPORTED_SCHEME,
        WebViewFailure.ERROR_FAILED_SSL_HANDSHAKE,
        WebViewFailure.ERROR_BAD_URL,
        WebViewFailure.ERROR_FILE,
        WebViewFailure.ERROR_FILE_NOT_FOUND,
        WebViewFailure.ERROR_TOO_MANY_REQUESTS,
        WebViewFailure.ERROR_UNSAFE_RESOURCE
    )

    // ---- 错误码取值 ----

    @Test
    fun `错误码取值与框架常量一致`() {
        // 这里是本文件唯一的"魔法数字"来源：改错一个，映射表就会静默错位
        assertThat(knownCodes).containsExactly(
            -2, -3, -4, -5, -6, -7, -8, -9, -10, -11, -12, -13, -14, -15, -16
        ).inOrder()
        assertThat(WebViewFailure.ERROR_UNKNOWN).isEqualTo(-1)
    }

    // ---- loadMessage ----

    @Test
    fun `每个已知错误码都有专属文案`() {
        val messages = knownCodes.map { WebViewFailure.loadMessage(it) }

        assertThat(messages.toSet()).hasSize(knownCodes.size)
        assertThat(messages.none { it == WebViewFailure.GENERIC }).isTrue()
    }

    @Test
    fun `未知错误码退回兜底文案`() {
        // ERROR_UNKNOWN、正数、以及未来新增/厂商自定义的码都不能让界面空着
        assertThat(WebViewFailure.loadMessage(WebViewFailure.ERROR_UNKNOWN))
            .isEqualTo(WebViewFailure.GENERIC)
        assertThat(WebViewFailure.loadMessage(0)).isEqualTo(WebViewFailure.GENERIC)
        assertThat(WebViewFailure.loadMessage(-99)).isEqualTo(WebViewFailure.GENERIC)
        assertThat(WebViewFailure.loadMessage(Int.MIN_VALUE)).isEqualTo(WebViewFailure.GENERIC)
    }

    @Test
    fun `找不到文件与读文件失败分开说`() {
        // 这两个是预览最常见的两种失败，混成一句话用户没法判断该换文件还是清存储
        assertThat(WebViewFailure.loadMessage(WebViewFailure.ERROR_FILE_NOT_FOUND))
            .isEqualTo(UiMessage.Res(R.string.webview_error_file_not_found))
        assertThat(WebViewFailure.loadMessage(WebViewFailure.ERROR_FILE))
            .isEqualTo(UiMessage.Res(R.string.webview_error_file))
    }

    @Test
    fun `网络类错误码各自映到自己的文案`() {
        // 域名解析失败与连不上是两回事：前者多半是 DNS/断网，后者可能是端口被挡
        assertThat(WebViewFailure.loadMessage(WebViewFailure.ERROR_HOST_LOOKUP))
            .isEqualTo(UiMessage.Res(R.string.webview_error_host_lookup))
        assertThat(WebViewFailure.loadMessage(WebViewFailure.ERROR_CONNECT))
            .isEqualTo(UiMessage.Res(R.string.webview_error_connect))
    }

    // ---- httpMessage ----

    @Test
    fun `HTTP 状态码分档且状态码本身保留`() {
        // 状态码作为实参跟着资源 id 一起走，解析时填进 %1$d——这是唯一被允许带上界面的技术细节
        assertThat(WebViewFailure.httpMessage(401))
            .isEqualTo(UiMessage.of(R.string.webview_http_forbidden, 401))
        assertThat(WebViewFailure.httpMessage(403))
            .isEqualTo(UiMessage.of(R.string.webview_http_forbidden, 403))
        assertThat(WebViewFailure.httpMessage(404))
            .isEqualTo(UiMessage.of(R.string.webview_http_not_found, 404))
        assertThat(WebViewFailure.httpMessage(410))
            .isEqualTo(UiMessage.of(R.string.webview_http_not_found, 410))
        assertThat(WebViewFailure.httpMessage(408))
            .isEqualTo(UiMessage.of(R.string.webview_http_timeout, 408))
        // 429 只在一个状态码上触发，状态码写死在文案里，所以没有实参
        assertThat(WebViewFailure.httpMessage(429))
            .isEqualTo(UiMessage.Res(R.string.webview_http_too_many))
        assertThat(WebViewFailure.httpMessage(500))
            .isEqualTo(UiMessage.of(R.string.webview_http_server, 500))
        assertThat(WebViewFailure.httpMessage(503))
            .isEqualTo(UiMessage.of(R.string.webview_http_server, 503))
        // 没有专属分支的 4xx 落到通用客户端错误档
        assertThat(WebViewFailure.httpMessage(451))
            .isEqualTo(UiMessage.of(R.string.webview_http_client, 451))
    }

    @Test
    fun `非错误段状态码退回兜底文案`() {
        // 框架只在 4xx-5xx 调 onReceivedHttpError；真拿到别的值说明上游变了，不能拼出怪句子
        assertThat(WebViewFailure.httpMessage(302)).isEqualTo(WebViewFailure.GENERIC)
        assertThat(WebViewFailure.httpMessage(0)).isEqualTo(WebViewFailure.GENERIC)
        assertThat(WebViewFailure.httpMessage(200)).isEqualTo(WebViewFailure.GENERIC)
    }

    @Test
    fun `504 归为响应超时而不是服务端出错`() {
        // 504 同时落在 500..599 里，判定顺序写反就会被并进"服务端出错"
        assertThat(WebViewFailure.httpMessage(504))
            .isEqualTo(UiMessage.of(R.string.webview_http_timeout, 504))
    }

    // ---- renderProcessMessage ----

    @Test
    fun `渲染进程崩溃与被回收是两句不同的话`() {
        val crashed = WebViewFailure.renderProcessMessage(didCrash = true)
        val reclaimed = WebViewFailure.renderProcessMessage(didCrash = false)

        assertThat(crashed).isNotEqualTo(reclaimed)
        // 被系统回收在低端机上是常态而非缺陷，文案必须指向内存而不是"出错了"——
        // 两个资源 key 本身就把这层区分钉住了，别让它俩指到同一条上去
        assertThat(reclaimed).isEqualTo(UiMessage.Res(R.string.webview_render_reclaimed))
        assertThat(crashed).isEqualTo(UiMessage.Res(R.string.webview_render_crash))
    }

    // ---- 全量文案的共性 ----

    private fun allMessages(): List<UiMessage> =
        knownCodes.map { WebViewFailure.loadMessage(it) } +
            listOf(0, -1, -99).map { WebViewFailure.loadMessage(it) } +
            listOf(401, 404, 408, 429, 451, 500, 504, 302).map { WebViewFailure.httpMessage(it) } +
            listOf(true, false).map { WebViewFailure.renderProcessMessage(it) } +
            listOf(WebViewFailure.NOT_READY, WebViewFailure.GENERIC)

    @Test
    fun `所有文案都来自资源而不是运行期字符串`() {
        // 「文案非空」在资源化之后的等价物：清一色 Res，且 id 不是 0（R 里没有 0 号资源）。
        // 出现 Raw 就意味着那句话绕过了 strings.xml——既不可翻译，也不再受本表约束。
        allMessages().forEach { message ->
            assertThat(message).isInstanceOf(UiMessage.Res::class.java)
            assertThat((message as UiMessage.Res).id).isNotEqualTo(0)
        }
    }

    @Test
    fun `所有文案都不可能夹带 URL 或路径`() {
        // 映射表只吃 int，拿不到 URL；这条断言防的是后来者把 description / URL 当实参传进来。
        // 实参只允许 Int（状态码）——String 实参是唯一能把路径带上界面的通道。
        allMessages().forEach { message ->
            (message as UiMessage.Res).args.forEach { arg ->
                assertThat(arg).isInstanceOf(Int::class.javaObjectType)
            }
        }
    }

    @Test
    fun `三张表不复用同一条资源`() {
        // 除刻意兜底的 GENERIC 外，每种失败都该有自己的一句话：复制粘贴时少改一个 R.string，
        // 两种失败就会说出同一句，用户据此做的判断（换文件？清存储？等一会？）也就错了。
        val distinct = knownCodes.map { WebViewFailure.loadMessage(it) } +
            listOf(401, 404, 408, 429, 500, 451).map { WebViewFailure.httpMessage(it) } +
            listOf(true, false).map { WebViewFailure.renderProcessMessage(it) } +
            listOf(WebViewFailure.NOT_READY, WebViewFailure.GENERIC)

        val ids = distinct.map { (it as UiMessage.Res).id }

        // 15 个加载失败 + 6 个 HTTP 档位 + 2 个渲染进程 + NOT_READY + GENERIC；
        // 钉住条数是为了让「把某一档从清单里删掉」不能换来一次绿灯
        assertThat(ids).hasSize(25)
        assertThat(ids.toSet()).hasSize(ids.size)
    }
}
