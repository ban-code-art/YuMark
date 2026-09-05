package com.yumark.app.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * `ExportImagePaths.kt` 的单测：锁住它与 `renderer.js` 里 `resolveImages()` 的行为一致。
 *
 * 用例照着那段 JS 写（`app/src/main/assets/raw/renderer.js` 的「3. 相对路径图片解析」一节）。
 * 两份实现只要有一边改了没跟上，同一篇文档就会在 PDF 里有图、在 .html 里没图，而两条路都不
 * 报错——所以这里的断言值必须与 JS 的输出逐字对应，不能「看着合理」就改。
 */
class ExportImagePathsTest {

    private fun resolver(
        prefix: String = "file:///w/",
        base: String = "",
        encodeAll: Boolean = false,
        appPrefix: String? = null
    ) = ExportImageResolver(prefix, base, encodeAll, appPrefix)

    // ---- resolveExportImageSrc ----

    @Test
    fun `已是绝对地址的引用一律不动`() {
        val r = resolver(appPrefix = "file:///f/")
        for (u in listOf(
            "https://e.com/a.png", "http://e.com/a.png", "data:image/png;base64,AA",
            "file:///a/b.png", "content://p/1", "blob:abc", "FILE:///A.PNG"
        )) {
            assertThat(resolveExportImageSrc(u, r)).isNull()
        }
    }

    @Test
    fun `没有 resolver 或空引用返回 null`() {
        assertThat(resolveExportImageSrc("images/a.png", null)).isNull()
        assertThat(resolveExportImageSrc("", resolver())).isNull()
    }

    @Test
    fun `scheme 名单只在开头匹配，路径中间出现不算绝对地址`() {
        // ^ 锚定：`img/data:x.png` 是一个合法的相对路径，不能被当成 data: URI 跳过
        assertThat(resolveExportImageSrc("img/data:x.png", resolver(base = "d")))
            .isEqualTo("file:///w/d/img/data%3Ax.png")
    }

    @Test
    fun `应用自管图片优先命中 appPrefix，与文档基址无关`() {
        val r = resolver(
            prefix = "content://saf/", base = "primary:Docs", encodeAll = true,
            appPrefix = "file:///data/user/0/com.yumark.app/files/"
        )
        val uuid = "0191a2b3-c4d5-6e7f-8a9b-0c1d2e3f4a5b"
        assertThat(resolveExportImageSrc("images/$uuid.png", r))
            .isEqualTo("file:///data/user/0/com.yumark.app/files/images/$uuid.png")
    }

    @Test
    fun `images 下非 UUID 形态落回文档基址，不被 appPrefix 抢走`() {
        // 导入库文档正文里的 images/pic.png 指的是它自己那份被镜像下来的资源
        val r = resolver(
            prefix = "content://saf/", base = "primary:Docs", encodeAll = true,
            appPrefix = "file:///f/"
        )
        assertThat(resolveExportImageSrc("images/pic.png", r))
            .isEqualTo("content://saf/primary%3ADocs%2Fimages%2Fpic.png")
    }

    @Test
    fun `appPrefix 为 null 时 UUID 图片也走文档基址`() {
        val uuid = "0191a2b3-c4d5-6e7f-8a9b-0c1d2e3f4a5b"
        assertThat(resolveExportImageSrc("images/$uuid.jpg", resolver(base = "sub")))
            .isEqualTo("file:///w/sub/images/$uuid.jpg")
    }

    @Test
    fun `appPrefix 为空串时与 null 等价，不拼裸路径`() {
        // 与 renderer.js 的真值判断对齐：`if (cfg.appPrefix && …)` 把 "" 当假值跳过。
        // Kotlin 侧若只判 null，会返回无 scheme 的裸相对路径，被消费方当本地文件路径读
        // （LocalImageBytes 对 scheme==null 走文件读）或原样写进导出件 src。
        val uuid = "0191a2b3-c4d5-6e7f-8a9b-0c1d2e3f4a5b"
        val r = resolver(prefix = "", appPrefix = "")
        assertThat(resolveExportImageSrc("images/$uuid.png", r)).isNull()
        // 有文档基址时照常走基址，不被空 appPrefix 截胡
        val r2 = resolver(base = "sub", appPrefix = "")
        assertThat(resolveExportImageSrc("images/$uuid.png", r2))
            .isEqualTo("file:///w/sub/images/$uuid.png")
    }

    @Test
    fun `prefix 为空且不是应用图片时无从解析`() {
        val r = resolver(prefix = "", appPrefix = "file:///f/")
        assertThat(resolveExportImageSrc("images/pic.png", r)).isNull()
        assertThat(resolveExportImageSrc("../a.png", r)).isNull()
    }

    @Test
    fun `已编码的引用不会二次编码`() {
        // images/我.jpg 在正文里写成 %E6%88%91：解码后再统一编码，结果必须与原串一致
        assertThat(resolveExportImageSrc("images/%E6%88%91.jpg", resolver()))
            .isEqualTo("file:///w/images/%E6%88%91.jpg")
    }

    @Test
    fun `半个转义序列按字面量处理，与 JS 的 try catch 一致`() {
        assertThat(resolveExportImageSrc("images/50%.png", resolver()))
            .isEqualTo("file:///w/images/50%25.png")
    }

    // ---- joinImagePath ----

    @Test
    fun `父目录不能越过根`() {
        assertThat(joinImagePath("a/b", "../../../c.png")).isEqualTo("c.png")
        assertThat(joinImagePath("", "../x.png")).isEqualTo("x.png")
        assertThat(joinImagePath("a/b", "../c/d.png")).isEqualTo("a/c/d.png")
        // 中途才越界的写法也拦得住：事后比较拼出来的字符串前缀是看不出来的
        assertThat(joinImagePath("a", "b/../../../../e.png")).isEqualTo("e.png")
    }

    @Test
    fun `前导斜杠丢掉基址的目录链`() {
        assertThat(joinImagePath("a/b", "/img/x.png")).isEqualTo("img/x.png")
    }

    @Test
    fun `SAF documentId 的 root 前缀不参与路径回退`() {
        assertThat(joinImagePath("primary:Docs/notes", "../img/x.png"))
            .isEqualTo("primary:Docs/img/x.png")
        // root 后面已经没有目录段了，`..` 不能把 root: 本身吃掉
        assertThat(joinImagePath("primary:", "../../x.png")).isEqualTo("primary:x.png")
    }

    @Test
    fun `反斜杠归一化成斜杠，多余分隔符与单点被忽略`() {
        assertThat(joinImagePath("", "img\\sub\\x.png")).isEqualTo("img/sub/x.png")
        assertThat(joinImagePath("a", "./b//c.png")).isEqualTo("a/b/c.png")
    }

    // ---- encodeUriComponent / encodePathSegments ----

    @Test
    fun `空格编成 %20 而不是加号`() {
        assertThat(encodeUriComponent("a b")).isEqualTo("a%20b")
        // URLEncoder 会留着 + 当已编码的空格；encodeURIComponent 必须转义它
        assertThat(encodeUriComponent("a+b")).isEqualTo("a%2Bb")
    }

    @Test
    fun `ECMA 的不转义集合保持原样`() {
        assertThat(encodeUriComponent("-_.!~*'()")).isEqualTo("-_.!~*'()")
        assertThat(encodeUriComponent("aZ09")).isEqualTo("aZ09")
    }

    @Test
    fun `非 ASCII 按 UTF-8 逐字节转义，十六进制大写`() {
        assertThat(encodeUriComponent("中")).isEqualTo("%E4%B8%AD")
        assertThat(encodeUriComponent("#?&=")).isEqualTo("%23%3F%26%3D")
    }

    @Test
    fun `逐段编码保留路径分隔符`() {
        assertThat(encodePathSegments("a b/中 文.png"))
            .isEqualTo("a%20b/%E4%B8%AD%20%E6%96%87.png")
    }

    // ---- decodeUriComponentOrNull ----

    @Test
    fun `加号不解成空格`() {
        // URLDecoder 会解成 "a b"，而文件名里的 + 是合法字符
        assertThat(decodeUriComponentOrNull("a+b")).isEqualTo("a+b")
    }

    @Test
    fun `百分号转义解回原文`() {
        assertThat(decodeUriComponentOrNull("%E4%B8%AD")).isEqualTo("中")
        assertThat(decodeUriComponentOrNull("a%20b")).isEqualTo("a b")
        assertThat(decodeUriComponentOrNull("a%2fb")).isEqualTo("a/b")
    }

    @Test
    fun `非法转义序列返回 null`() {
        assertThat(decodeUriComponentOrNull("%")).isNull()
        assertThat(decodeUriComponentOrNull("%E")).isNull()
        assertThat(decodeUriComponentOrNull("%ZZ")).isNull()
        // 单独的 0xFF 不是合法 UTF-8：JS 那边抛 URIError，这里用替换字符还原成 null
        assertThat(decodeUriComponentOrNull("%FF")).isNull()
    }

    @Test
    fun `输入本来就带替换字符时不算解码失败`() {
        assertThat(decodeUriComponentOrNull("%41�")).isEqualTo("A�")
    }

    // ---- normalizeImageTargets（renderer.js「步骤2.5」的孪生实现）----
    // 不做这一步图片会**整段变成字面文字**：CommonMark 规定不带 `<>` 的链接目标里出现未编码
    // 空格时整个 `![…](…)` 不构成图片，AST 里连 Image 节点都没有，.html / .docx 里出现的是
    // 一行字面的 `![图](images\my pic.png)`。而 PDF / 长图走 WebView，renderer.js 就地改写过
    // 源文本 —— 同一篇文档五种格式里三种有图两种是文字，且两条路都不报错。

    @Test
    fun `反斜杠与空格归一化，Typora 风格引用才解析成图片`() {
        assertThat(normalizeImageTargets("""![图](images\my pic.png)"""))
            .isEqualTo("![图](images/my%20pic.png)")
    }

    @Test
    fun `括号编成 %28 %29，file (1) 这种文件名不会截断目标`() {
        // 相册导出的重名文件全是这个形态；不编码的话 `)` 会提前闭合链接目标
        assertThat(normalizeImageTargets("![x](image (1).png)"))
            .isEqualTo("![x](image%20%281%29.png)")
    }

    @Test
    fun `末尾 title 里的空格不动`() {
        // 归一化只能动路径：把 title 里的空格也编成 %20，用户看到的图片说明就变成了带 %20 的乱码
        assertThat(normalizeImageTargets("""![](a b.png "标 题")"""))
            .isEqualTo("""![](a%20b.png "标 题")""")
        assertThat(normalizeImageTargets("""![](a b.png 'T t')"""))
            .isEqualTo("""![](a%20b.png 'T t')""")
        // 路径与 title 之间的多余空白会收成一个 —— 对渲染无影响，钉住是为了让改动可见
        assertThat(normalizeImageTargets("""![](a b.png   "T")"""))
            .isEqualTo("""![](a%20b.png "T")""")
    }

    @Test
    fun `尖括号形式的目标原样保留`() {
        // `<...>` 里本来就允许空格，CommonMark 自己会解；再编码反而弄坏
        assertThat(normalizeImageTargets("![x](<my pic.png>)"))
            .isEqualTo("![x](<my pic.png>)")
    }

    @Test
    fun `代码区域里的图片语法原样呈现`() {
        // 正文里演示 Markdown 语法的代码块被改写，就是把用户想展示的原文改掉了
        val fenced = "```\n![图](my pic.png)\n```"
        assertThat(normalizeImageTargets(fenced)).isEqualTo(fenced)
        val tilde = "~~~\n![图](my pic.png)\n~~~"
        assertThat(normalizeImageTargets(tilde)).isEqualTo(tilde)
        assertThat(normalizeImageTargets("`![图](my pic.png)`")).isEqualTo("`![图](my pic.png)`")
        assertThat(normalizeImageTargets("``![图](my pic.png)``")).isEqualTo("``![图](my pic.png)``")
    }

    @Test
    fun `代码区域外的同一段照旧归一化`() {
        // 保护范围必须只是代码区域本身：连带把后面的正文也跳过，那半篇文档的图就没了
        assertThat(normalizeImageTargets("`![a](x y.png)` 与 ![b](z w.png)"))
            .isEqualTo("`![a](x y.png)` 与 ![b](z%20w.png)")
    }

    @Test
    fun `多行里的多张图各自归一化`() {
        // 目标不允许换行，所以两张图不会被并成一个匹配
        assertThat(normalizeImageTargets("![a](x y.png)\n![b](z w.png)"))
            .isEqualTo("![a](x%20y.png)\n![b](z%20w.png)")
    }

    @Test
    fun `不含图片语法时原样返回`() {
        // 早退分支：一整篇没有图片的文档不该被正则扫一遍
        assertThat(normalizeImageTargets("普通文字 (a b) 与 [链接](a b.md)"))
            .isEqualTo("普通文字 (a b) 与 [链接](a b.md)")
    }

    @Test
    fun `只改图片不改普通链接`() {
        // 链接目标带空格也会失效，但那是另一个问题；这个函数的契约只覆盖图片
        assertThat(normalizeImageTargets("[链接](a b.md) 与 ![图](c d.png)"))
            .isEqualTo("[链接](a b.md) 与 ![图](c%20d.png)")
    }

    @Test
    fun `已编码与空目标都保持原样`() {
        assertThat(normalizeImageTargets("![x](a%20b.png)")).isEqualTo("![x](a%20b.png)")
        // `![只有 alt]()`：目标为空时不匹配，也不该被补出什么东西
        assertThat(normalizeImageTargets("![只有 alt]()")).isEqualTo("![只有 alt]()")
    }

    // ---- 与 HtmlExporter 相同的组合：rewriteImageSrc + resolveExportImageSrc ----

    @Test
    fun `整段 HTML 只改写相对引用，其余原样`() {
        val html = """<p><img src="images/pic.png" alt="示例"><img src="https://e.com/a.png"></p>"""
        val out = rewriteImageSrc(html) { raw ->
            resolveExportImageSrc(raw.replace("&amp;", "&").trim(), resolver(base = "d"))
        }
        assertThat(out).contains("src=\"file:///w/d/images/pic.png\"")
        assertThat(out).contains("src=\"https://e.com/a.png\"")
        assertThat(out).contains("alt=\"示例\"")
    }
}
