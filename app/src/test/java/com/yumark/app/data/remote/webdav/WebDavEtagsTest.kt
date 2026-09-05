package com.yumark.app.data.remote.webdav

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [WebDavEtags] 守的是一条会**永久打死上传**的路。
 *
 * 服务器给弱验证器（`W/"abc"`）时，规范化会把它剥成 `abc`——光看这个字符串再也认不出它原本是弱的。
 * 若就这么当 `If-Match` 发出去，按 RFC 9110 §13.1.1 + §8.8.3.2（`If-Match` 用强比较，强比较要求两边
 * 都不是弱标签）服务器只能回 412，而 412 在 [WebDavClient.upload] 里**刻意不退化**：那台服务器上凡是
 * 远端已存在的文档，上传每一轮都失败，界面上只有一句「失败 N 篇」。nginx 及各类反代一开 gzip
 * 就会把强 ETag 改写成弱的，所以这不是小众配置。
 *
 * 另一半是「两条通道剥得一样」：PROPFIND 的 `getetag` 与 PUT 响应头都走这一个函数。从前两边各写了
 * 一份 `removePrefix("W/")…`，一旦分叉，同一版内容就会算出两个不同的版本标记 → 每次上传后都判成
 * 「远端变了」→ 本地也改过时凭空多出一份「冲突副本」。
 */
class WebDavEtagsTest {

    // ===== normalize：剥成不透明实体部分 =====

    @Test
    fun `strips wrapping quotes from a strong validator`() {
        assertThat(WebDavEtags.normalize("\"abc123\"")).isEqualTo("abc123")
    }

    @Test
    fun `strips the weak marker and the quotes`() {
        assertThat(WebDavEtags.normalize("W/\"abc123\"")).isEqualTo("abc123")
    }

    /**
     * 小写 `w/` 也要认。RFC 写的是大写，但服务器实现千奇百怪；只认大写的话 `w/"abc"` 会被剥成
     * `w/"abc`（`trim('"')` 只掉了尾引号），那个畸形字符串既会当强标签发出去，又会与 PUT 响应
     * 那条通道算出的标记不一致。
     */
    @Test
    fun `accepts a lower-case weak marker`() {
        assertThat(WebDavEtags.normalize("w/\"abc123\"")).isEqualTo("abc123")
    }

    @Test
    fun `tolerates surrounding whitespace and missing quotes`() {
        assertThat(WebDavEtags.normalize("  \"abc\" ")).isEqualTo("abc")
        assertThat(WebDavEtags.normalize("abc")).isEqualTo("abc")
        assertThat(WebDavEtags.normalize(" W/ \"abc\" ")).isEqualTo("abc")
    }

    /**
     * 空一律给 null，不给空串。空串是非空引用：`canTrustPutEtag` 会当它是个能用的基线存进
     * `sync_state.remote_etag`，而下一轮比较侧的 `remoteVersionTag` 见空串就退到 `mtime:…`，
     * 两个字符串必然不等 → 每轮都判「远端变了」。给 null 才会进 `pendingBaseline`，
     * 由 `refreshMissingBaselines` 补一个真标记回来。
     */
    @Test
    fun `blank and empty validators collapse to null`() {
        assertThat(WebDavEtags.normalize(null)).isNull()
        assertThat(WebDavEtags.normalize("")).isNull()
        assertThat(WebDavEtags.normalize("   ")).isNull()
        assertThat(WebDavEtags.normalize("\"\"")).isNull()
        assertThat(WebDavEtags.normalize("W/\"\"")).isNull()
    }

    // ===== isWeak：这一位决定条件请求能不能用 =====

    @Test
    fun `flags weak validators regardless of case or leading space`() {
        assertThat(WebDavEtags.isWeak("W/\"abc\"")).isTrue()
        assertThat(WebDavEtags.isWeak("w/\"abc\"")).isTrue()
        assertThat(WebDavEtags.isWeak("  W/\"abc\"")).isTrue()
    }

    @Test
    fun `strong validators and absent validators are not weak`() {
        assertThat(WebDavEtags.isWeak("\"abc\"")).isFalse()
        assertThat(WebDavEtags.isWeak("abc")).isFalse()
        assertThat(WebDavEtags.isWeak(null)).isFalse()
        assertThat(WebDavEtags.isWeak("")).isFalse()
    }

    /**
     * 实体部分**本身**以 `W/` 开头的强标签不能被误判成弱。判断看的是引号外面那两个字符，
     * 而强标签的第一个字符是引号。误判的代价是白白少用一次条件请求（可接受），
     * 但这条用例把边界钉住，免得哪天有人把判断改成 `contains`。
     */
    @Test
    fun `a strong tag whose body starts with the weak marker stays strong`() {
        assertThat(WebDavEtags.isWeak("\"W/abc\"")).isFalse()
        assertThat(WebDavEtags.normalize("\"W/abc\"")).isEqualTo("W/abc")
    }
}
