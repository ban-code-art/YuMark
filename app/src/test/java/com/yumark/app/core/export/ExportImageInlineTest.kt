package com.yumark.app.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * 导出侧图片内联的单测。
 *
 * 只覆盖纯逻辑（改写 `<img src>`、按字节判 MIME、resolver 序列化）；"改写后的 src 在 WebView 里
 * 真能出图"必须在设备上验证，JVM 里没有 WebView 也没有 ContentResolver。
 *
 * 夹具字节来自 [pngBytes] 等共享构造器（`ImageByteFixtures.kt`）。**不能再像从前那样喂
 * `"ABC".toByteArray()`**：内联现在按字节判格式，任意字符串一律被拒（见
 * 「非图片字节不内联」那两条），从前那种夹具能通过恰恰是缺陷本身。
 */
class ExportImageInlineTest {

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `本地图片换成 data URI`() {
        val html = """<p><img src="file:///a/x.png" alt="示例"></p>"""
        val out = inlineLocalImages(html) { pngBytes() }
        // base64 写成字面量而不是 b64(pngBytes())：那样只能证明"两边用了同一个编码器"，
        // 证不了输出真是这串字节。值由 pngBytes(2,2) 的 29 字节算出。
        val expected = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAAA="
        assertThat(out).contains("src=\"$expected\"")
        // 其余属性不能被吃掉
        assertThat(out).contains("alt=\"示例\"")
    }

    @Test
    fun `网络图与已有 data URI 保持原样`() {
        // 喂真图片字节：拦住它们的必须是 URL 判定，不是"字节认不出"
        val html = """<img src="https://e.com/a.png"><img src="data:image/png;base64,AAAA">"""
        assertThat(inlineLocalImages(html) { pngBytes() }).isEqualTo(html)
    }

    @Test
    fun `读不到就保留原 URI，不丢图也不让导出失败`() {
        val html = """<img src="content://p/1.png">"""
        assertThat(inlineLocalImages(html) { null }).isEqualTo(html)
        assertThat(inlineLocalImages(html) { ByteArray(0) }).isEqualTo(html)
    }

    @Test
    fun `属性里的 amp 实体先还原再交给读取器`() {
        var seen: String? = null
        val html = """<img src="content://p/doc?a=1&amp;b=2">"""
        inlineLocalImages(html) { seen = it; null }
        assertThat(seen).isEqualTo("content://p/doc?a=1&b=2")
    }

    @Test
    fun `单引号属性与多张图都能改写`() {
        // 两个 src 的扩展名一个 gif 一个 webp，字节都是 GIF —— 两张都必须声明 image/gif。
        // 这条同时钉住"扩展名不参与判定"。
        val html = "<img src='file:///a/1.gif'><img src=\"/sdcard/2.webp\">"
        val out = inlineLocalImages(html) { gifBytes() }
        val expected = "data:image/gif;base64,R0lGODlhAgACAHAAAA=="
        assertThat(out).isEqualTo("<img src='$expected'><img src=\"$expected\">")
    }

    @Test
    fun `MIME 由字节判定，扩展名撒谎也照样对`() {
        // 微信下载下来的 `.jpg` 里躺着 WebP 字节是常态。从前按扩展名声明 image/jpeg，
        // 浏览器拿到一个类型对不上的 data URI，画出来是裂图。
        val out = inlineLocalImages("""<img src="file:///a/photo.jpg">""") { webpVp8Bytes() }
        assertThat(out).contains("data:image/webp;base64,")
        assertThat(out).doesNotContain("image/jpeg")
    }

    @Test
    fun `非图片字节不内联，src 原样留着`() {
        // 正文里的 `![](…)` 不全是应用自己写的（导入的 md、WebDAV 同步下来的正文、
        // AI 改写出的正文都能带任意引用）。一个改名成 .png 的 PDF 从前会被照着扩展名
        // 声明成 image/png 内联进导出件，再随 ACTION_SEND 一起发出去。
        val html = """<img src="file:///a/x.png">"""
        assertThat(inlineLocalImages(html) { pdfBytes() }).isEqualTo(html)
    }

    @Test
    fun `任意字符串不再被当成图片`() {
        // 从前这个夹具（"ABC"）会被内联成 data:image/png;base64,QUJD —— 缺陷本身
        val html = """<img src="/sdcard/a.png">"""
        assertThat(inlineLocalImages(html) { "ABC".toByteArray() }).isEqualTo(html)
        assertThat(inlineLocalImages(html) { byteArrayOf(1) }).isEqualTo(html)
    }

    @Test
    fun `宽高为 0 的坏图也不内联`() {
        // sniffImageInfo 的 `1..MAX_DIM` 闸门顺带管住这一类：坏图内联进去在浏览器里
        // 同样是裂图，不如保留原 URI 让文件本身还点得开
        val html = """<img src="/sdcard/a.png">"""
        assertThat(inlineLocalImages(html) { pngBytes(w = 0, h = 0) }).isEqualTo(html)
    }

    @Test
    fun `只内联本地来源`() {
        assertThat(isInlinableLocalUrl("FILE:///a.png")).isTrue()
        assertThat(isInlinableLocalUrl("content://p/1")).isTrue()
        assertThat(isInlinableLocalUrl("/sdcard/a.png")).isTrue()
        assertThat(isInlinableLocalUrl("https://e.com/a.png")).isFalse()
        assertThat(isInlinableLocalUrl("images/a.png")).isFalse()
    }

    @Test
    fun `resolver JSON 转义引号与换行，注入语句不会写坏`() {
        val json = ExportImageResolver("content://a?x=1", "他说\"嗨\"\n\\dir", true).toJson()
        assertThat(json).isEqualTo(
            """{"prefix":"content://a?x=1","base":"他说\"嗨\"\n\\dir","encodeAll":true}"""
        )
    }

    // ---- rewriteImageSrc：不带引号的 src ----
    // commonmark 的 HtmlRenderer 默认 escapeHtml=false，用户在正文里手写的 `<img src=a.png>`
    // 会原样透进渲染结果。只认带引号的话这一张既不会被解析成绝对路径、也不会被内联成 data URI，
    // 导出的 .html 里就是一张裂图，而且没有任何报错。

    @Test
    fun `不带引号的 src 也被内联，并补上双引号`() {
        // 补引号是必须的：data URI 含 `,` `;` `=`，裸值形态下后面再挂一个属性就会被解析成 src 的一部分
        val out = inlineLocalImages("<img src=/sdcard/a.png alt=示例>") { gifBytes() }
        assertThat(out).isEqualTo(
            """<img src="data:image/gif;base64,${b64(gifBytes())}" alt=示例>"""
        )
    }

    @Test
    fun `不带引号且读不到时保留原值但仍补引号`() {
        // 补引号本身是无损的：值没变，只是不再依赖裸值的分隔规则
        assertThat(inlineLocalImages("<img src=/sdcard/a.png>") { null })
            .isEqualTo("""<img src="/sdcard/a.png">""")
    }

    @Test
    fun `不带引号且字节不是图片时同样只补引号`() {
        // 「拒绝内联」这条路必须与「读不到」表现一致：否则改写层会因为格式判定的结果不同
        // 而少补一次引号，裸 src 后面挂着的属性就被吞进 src
        assertThat(inlineLocalImages("<img src=/sdcard/a.png alt=x>") { pdfBytes() })
            .isEqualTo("""<img src="/sdcard/a.png" alt=x>""")
    }

    @Test
    fun `src 前后的空白与大小写都认`() {
        val out = inlineLocalImages("<IMG SRC = 'file:///a/1.gif'>") { gifBytes() }
        assertThat(out).isEqualTo("<IMG SRC = 'data:image/gif;base64,${b64(gifBytes())}'>")
    }

    @Test
    fun `srcset 不被当成 src 改写`() {
        // `\ssrc\s*=` 要求 src 后面紧跟（可含空白的）等号；srcset 的 `set` 挡住了这一步。
        // 若这里失手，响应式图片的候选列表会被整段换成一个 data URI。
        val out = inlineLocalImages("""<img srcset="a.png 2x" src=/sdcard/b.png>""") { gifBytes() }
        assertThat(out).isEqualTo(
            """<img srcset="a.png 2x" src="data:image/gif;base64,${b64(gifBytes())}">"""
        )
    }

    @Test
    fun `transform 拿到的是原始属性值，img 之外的 src 不动`() {
        val seen = mutableListOf<String>()
        val html = """<img src=a.png><script src="x.js"></script><audio src="y.mp3">"""
        val out = rewriteImageSrc(html) { seen += it; null }
        // 只遍历 <img>：改 <script src> 会把导出件里的脚本引用换掉
        assertThat(seen).containsExactly("a.png")
        assertThat(out).isEqualTo("""<img src="a.png"><script src="x.js"></script><audio src="y.mp3">""")
    }
}
