package com.yumark.app.data.remote.webdav

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserAction
import org.junit.jupiter.api.Test

/**
 * WebDAV 的 HTTP 状态码分流：文案对不对、哪些值得重试、正文怎么解。
 *
 * 这三个都是纯函数（[webDavStatusMessage] / [isRetryableWebDavStatus] / [decodeMarkdown]），
 * 从 `WebDavClient` 里抽出来正是为了能在 JVM 上真跑——Ktor 的请求路径在单测里跑不了。
 * 动作标签按 ErrorHandlerTest 的惯例写成 `R.string.action_*` 字面量，而不是 `action.labelRes`：
 * 后者会让「枚举项指错资源」这类改动照样通过。
 */
class WebDavStatusTest {

    @Test
    fun `401 和 403 说的是账号而不是网络`() {
        // 报「连接失败：HTTP 401」会把用户推去查网络，真正要改的是账号密码
        assertThat(webDavStatusMessage(UserAction.CONNECT, 401))
            .isEqualTo(UiMessage.Res(R.string.webdav_error_unauthorized))
        assertThat(webDavStatusMessage(UserAction.LIST_REMOTE_DIR, 403))
            .isEqualTo(UiMessage.Res(R.string.webdav_error_forbidden))
    }

    @Test
    fun `507 与 413 都归到空间不足并带上真实状态码`() {
        assertThat(webDavStatusMessage(UserAction.UPLOAD_REMOTE_FILE, 507))
            .isEqualTo(UiMessage.of(R.string.webdav_error_insufficient_storage, 507))
        assertThat(webDavStatusMessage(UserAction.UPLOAD_REMOTE_FILE, 413))
            .isEqualTo(UiMessage.of(R.string.webdav_error_insufficient_storage, 413))
    }

    @Test
    fun `409 412 423 429 各自成句而不落到兜底`() {
        // 这四条各指向一件具体的事（建上级目录 / 什么都不用做 / 等一会儿再来），
        // 落到兜底的「上传失败：HTTP 412」就指不出用户该做什么了。
        // 状态码本身写在文案里（见 strings_errors.xml），这里只核到资源 id——JVM 上没有资源表。
        val expected = mapOf(
            409 to R.string.webdav_error_conflict_path,
            412 to R.string.webdav_error_precondition_failed,
            423 to R.string.webdav_error_locked,
            429 to R.string.webdav_error_rate_limited
        )
        expected.forEach { (status, res) ->
            assertWithMessage("HTTP $status")
                .that(webDavStatusMessage(UserAction.UPLOAD_REMOTE_FILE, status))
                .isEqualTo(UiMessage.Res(res))
        }
        // 四条互不相同：复制粘贴时把两个状态码指到同一个 key，上面那圈断言照样全过
        assertThat(expected.values.toSet()).hasSize(4)
    }

    @Test
    fun `兜底走「动作失败：HTTP xxx」两段式`() {
        val msg = webDavStatusMessage(UserAction.UPLOAD_REMOTE_FILE, 500)
        assertThat(msg).isEqualTo(
            UiMessage.of(
                R.string.error_action_failed,
                UiMessage.Res(R.string.action_upload_remote_file),
                UiMessage.of(R.string.webdav_error_http_status, 500)
            )
        )
        // 换个动作，前半句就得跟着换——否则「上传失败」会说成「列目录失败」
        val listing = webDavStatusMessage(UserAction.LIST_REMOTE_DIR, 500) as UiMessage.Res
        assertThat(listing.args[0]).isEqualTo(UiMessage.Res(R.string.action_list_remote_dir))
    }

    @Test
    fun `只有服务端瞬时状态才重试`() {
        // 408 请求超时 / 423 被锁 / 429 限流 会自己好转
        assertThat(isRetryableWebDavStatus(408)).isTrue()
        assertThat(isRetryableWebDavStatus(423)).isTrue()
        assertThat(isRetryableWebDavStatus(429)).isTrue()
        assertThat(isRetryableWebDavStatus(500)).isTrue()
        assertThat(isRetryableWebDavStatus(503)).isTrue()
        // 501「不支持这个方法」重发一百次都是同一个答案，只会让失败来得更慢
        assertThat(isRetryableWebDavStatus(501)).isFalse()
        // 其余 4xx 是请求本身有问题
        listOf(400, 401, 403, 404, 409, 412).forEach {
            assertThat(isRetryableWebDavStatus(it)).isFalse()
        }
    }

    @Test
    fun `decodeMarkdown 硬按 UTF-8 解并去掉开头的 BOM`() {
        val bom = Char(0xFEFF).toString()
        // BOM 进正文会改变内容哈希，于是每次同步都判成「本地有变化」，无限上传
        assertThat(decodeMarkdown((bom + "# 标题").toByteArray(Charsets.UTF_8)))
            .isEqualTo("# 标题")
        assertThat(decodeMarkdown("中文正文".toByteArray(Charsets.UTF_8))).isEqualTo("中文正文")
        // 只去开头那个：正文中间的 U+FEFF 是用户自己的内容，不该被我们改掉
        assertThat(decodeMarkdown("a${bom}b".toByteArray(Charsets.UTF_8))).isEqualTo("a${bom}b")
        assertThat(decodeMarkdown(ByteArray(0))).isEmpty()
    }
}
