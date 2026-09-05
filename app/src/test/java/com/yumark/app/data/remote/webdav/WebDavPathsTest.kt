package com.yumark.app.data.remote.webdav

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 路径段编解码是同步链路上最安静的一类 bug：编少了指错文件，解错了每次同步都重复建一篇文档。
 * 这里逐条钉住 [WebDavPaths] 的取舍（见其 KDoc），改动时不许悄悄放宽。
 */
class WebDavPathsTest {

    @Test
    fun `encodes CJK per UTF-8 byte`() {
        // 与 PROPFIND 真实返回的 href 对齐（见 WebDavXmlTest 的 %E4%B8%AD%E6%96%87.md）
        assertThat(WebDavPaths.encodeSegment("中文.md")).isEqualTo("%E4%B8%AD%E6%96%87.md")
    }

    @Test
    fun `escapes everything outside unreserved`() {
        assertThat(WebDavPaths.encodeSegment("a b.md")).isEqualTo("a%20b.md")
        assertThat(WebDavPaths.encodeSegment("a#b")).isEqualTo("a%23b")
        assertThat(WebDavPaths.encodeSegment("a?b")).isEqualTo("a%3Fb")
        // `+` 必须转义：有服务器按 form-urlencoded 把它解成空格
        assertThat(WebDavPaths.encodeSegment("a+b")).isEqualTo("a%2Bb")
        // 文件名里真实存在的 `%` 也要转义，否则 %20 会被服务器解成空格
        assertThat(WebDavPaths.encodeSegment("100%")).isEqualTo("100%25")
        // 段内斜杠不许原样穿过去，否则凭空多一层目录
        assertThat(WebDavPaths.encodeSegment("a/b")).isEqualTo("a%2Fb")
        assertThat(WebDavPaths.encodeSegment("a&b=c")).isEqualTo("a%26b%3Dc")
    }

    @Test
    fun `keeps unreserved characters as-is`() {
        assertThat(WebDavPaths.encodeSegment("A-z_0.9~")).isEqualTo("A-z_0.9~")
    }

    @Test
    fun `encodePath encodes each segment and drops empty ones`() {
        // 用户把同步目录填成 `/YuMark//子 目录/` 是常态
        assertThat(WebDavPaths.encodePath("/YuMark//子 目录/"))
            .isEqualTo("YuMark/%E5%AD%90%20%E7%9B%AE%E5%BD%95")
        assertThat(WebDavPaths.encodePath("")).isEmpty()
        assertThat(WebDavPaths.encodePath("///")).isEmpty()
    }

    @Test
    fun `decodeSegment leaves plus alone`() {
        // URLDecoder.decode 会把 `+` 解成空格，那样远端的 a+b.md 永远匹配不上本地同名文档
        assertThat(WebDavPaths.decodeSegment("a+b.md")).isEqualTo("a+b.md")
        assertThat(WebDavPaths.decodeSegment("a%2Bb.md")).isEqualTo("a+b.md")
        assertThat(WebDavPaths.decodeSegment("%E4%B8%AD%E6%96%87.md")).isEqualTo("中文.md")
    }

    @Test
    fun `decodeSegment keeps malformed escapes verbatim`() {
        // href 是服务器给的外部输入，一个畸形转义不该让整次 PROPFIND 解析失败
        assertThat(WebDavPaths.decodeSegment("100%zz")).isEqualTo("100%zz")
        assertThat(WebDavPaths.decodeSegment("abc%2")).isEqualTo("abc%2")
        assertThat(WebDavPaths.decodeSegment("no-escape")).isEqualTo("no-escape")
    }

    @Test
    fun `encode then decode round-trips a hostile name`() {
        val name = "a+b #1 中文 100% x?y.md"
        assertThat(WebDavPaths.decodeSegment(WebDavPaths.encodeSegment(name))).isEqualTo(name)
    }

    @Test
    fun `nameFromHref handles full URLs, absolute paths and directories`() {
        assertThat(WebDavPaths.nameFromHref("https://host/dav/YuMark/Note.md")).isEqualTo("Note.md")
        assertThat(WebDavPaths.nameFromHref("/dav/YuMark/Note.md")).isEqualTo("Note.md")
        assertThat(WebDavPaths.nameFromHref("/dav/YuMark/")).isEqualTo("YuMark")
        assertThat(WebDavPaths.nameFromHref("  /dav/%E4%B8%AD%E6%96%87.md  ")).isEqualTo("中文.md")
        // 确有服务器在 href 里直接回中文原文（未编码）
        assertThat(WebDavPaths.nameFromHref("/dav/中文.md")).isEqualTo("中文.md")
    }

    @Test
    fun `nameFromHref splits before decoding`() {
        // 顺序反了的话 %2F 会解出一个假的段边界，a%2Fb.md 变成「目录 a 下的 b.md」
        assertThat(WebDavPaths.nameFromHref("/dav/YuMark/a%2Fb.md")).isEqualTo("a/b.md")
    }
}
