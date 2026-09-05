package com.yumark.app.core.export

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.ExportOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class DocxExporterTest {

    private val exporter = DocxExporter()

    private fun doc(content: String): Document =
        Document.create(id = "d1", name = "测试文档").copy(content = content)

    private fun entry(file: File, name: String): String =
        ZipFile(file).use { zip ->
            val e = zip.getEntry(name) ?: error("missing entry $name")
            zip.getInputStream(e).bufferedReader().use { it.readText() }
        }

    @Test
    fun `produces a valid docx package`(@TempDir dir: File) {
        val md = "# 标题\n\n正文段落。"
        val file = exporter.export(doc(md), ExportOptions(outputDir = dir)).getOrThrow()

        assertThat(file.exists()).isTrue()
        assertThat(file.name).endsWith(".docx")
        // OOXML 必需部件
        ZipFile(file).use { zip ->
            assertThat(zip.getEntry("[Content_Types].xml")).isNotNull()
            assertThat(zip.getEntry("_rels/.rels")).isNotNull()
            assertThat(zip.getEntry("word/document.xml")).isNotNull()
        }
        val xml = entry(file, "word/document.xml")
        assertThat(xml).contains("<w:document")
        assertThat(xml).contains("标题")
        assertThat(xml).contains("正文段落。")
        assertThat(xml).contains("<w:sectPr>")
    }

    @Test
    fun `heading is bold and sized`(@TempDir dir: File) {
        val xml = entry(exporter.export(doc("# H1"), ExportOptions(dir)).getOrThrow(), "word/document.xml")
        assertThat(xml).contains("<w:b/>")
        assertThat(xml).contains("<w:sz w:val=\"48\"")
    }

    @Test
    fun `inline formatting maps to runs`(@TempDir dir: File) {
        val xml = entry(
            exporter.export(doc("**粗** *斜* ~~删~~ `代码`"), ExportOptions(dir)).getOrThrow(),
            "word/document.xml"
        )
        assertThat(xml).contains("<w:b/>")
        assertThat(xml).contains("<w:i/>")
        assertThat(xml).contains("<w:strike/>")
        assertThat(xml).contains("Consolas")
    }

    @Test
    fun `lists render with markers`(@TempDir dir: File) {
        val xml = entry(
            exporter.export(doc("- 一\n- 二\n\n1. A\n2. B"), ExportOptions(dir)).getOrThrow(),
            "word/document.xml"
        )
        assertThat(xml).contains("•")
        assertThat(xml).contains("1. ")
        assertThat(xml).contains("<w:ind w:left=")
    }

    @Test
    fun `table renders as w_tbl`(@TempDir dir: File) {
        val md = "| A | B |\n|---|---|\n| 1 | 2 |"
        val xml = entry(exporter.export(doc(md), ExportOptions(dir)).getOrThrow(), "word/document.xml")
        assertThat(xml).contains("<w:tbl>")
        assertThat(xml).contains("<w:tr>")
        assertThat(xml).contains("<w:tc>")
    }

    // ---- tblGrid：`CT_Tbl` 的 xs:sequence 里 minOccurs 默认 1，即必填 ----
    // 少了它整张表 schema-invalid。Word 桌面版通常容错打开，所以「本机打得开」证明不了什么，
    // 只能靠断言守住（与 rPr/pPr 次序同一类问题，见 propsContaining 的注释）。

    @Test
    fun `tbl 里必须有 tblGrid 且排在 tblPr 之后第一个 tr 之前`(@TempDir dir: File) {
        val md = "| A | B |\n|---|---|\n| 1 | 2 |"
        val xml = entry(exporter.export(doc(md), ExportOptions(dir)).getOrThrow(), "word/document.xml")
        val tbl = Regex("<w:tbl>.*?</w:tbl>").find(xml)?.value ?: error("no <w:tbl> in $xml")
        assertThat(tbl).contains("<w:tblGrid>")
        // 次序断言写成偏移比较而不是 contains 一整串拼好的 XML：后者会把边框那一长串
        // 也钉死，改个边框颜色就要重写这条用例
        val pr = tbl.indexOf("</w:tblPr>")
        val grid = tbl.indexOf("<w:tblGrid>")
        val firstRow = tbl.indexOf("<w:tr>")
        assertThat(pr).isLessThan(grid)
        assertThat(grid).isLessThan(firstRow)
    }

    @Test
    fun `gridCol 数目等于列数，宽度等分正文可用宽`(@TempDir dir: File) {
        // 9026 = pgSz 11906 - pgMar left 1440 - right 1440，与 CONTENT_WIDTH_TWIPS 同源。
        // 数值写字面量而不是引用常量：常量是 private，而且这条用例的意义正是钉住
        // 「页面几何改了、表格宽度会跟着改」这层联动
        val md = "| A | B | C |\n|---|---|---|\n| 1 | 2 | 3 |"
        val xml = entry(exporter.export(doc(md), ExportOptions(dir)).getOrThrow(), "word/document.xml")
        val grid = Regex("<w:tblGrid>.*?</w:tblGrid>").find(xml)?.value ?: error("no tblGrid in $xml")
        assertThat(Regex("<w:gridCol ").findAll(grid).count()).isEqualTo(3)
        assertThat(grid).isEqualTo(
            "<w:tblGrid>" + "<w:gridCol w:w=\"${9026 / 3}\"/>".repeat(3) + "</w:tblGrid>"
        )
    }

    @Test
    fun `两张表各自算自己的列数`(@TempDir dir: File) {
        // 列数是每张表的局部属性。若实现把它算在共享状态上，第二张表会沿用第一张的网格
        val md = "| A | B |\n|---|---|\n| 1 | 2 |\n\n段落\n\n| X |\n|---|\n| 9 |"
        val xml = entry(exporter.export(doc(md), ExportOptions(dir)).getOrThrow(), "word/document.xml")
        val grids = Regex("<w:tblGrid>.*?</w:tblGrid>").findAll(xml).map { it.value }.toList()
        assertThat(grids).hasSize(2)
        assertThat(Regex("<w:gridCol ").findAll(grids[0]).count()).isEqualTo(2)
        assertThat(Regex("<w:gridCol ").findAll(grids[1]).count()).isEqualTo(1)
    }

    @Test
    fun `短行补齐到网格宽度，每行 tc 数都等于列数`(@TempDir dir: File) {
        // 钉的是「行宽 == 网格宽」这条不变量，不管补齐是谁做的：实测 commonmark 0.30 自己
        // 就按 GFM 补了空单元格（导出侧的 repeat 因此是 0 次，留着防解析器换版本）。
        // 这条同时反向钉住 tblGrid 的列数取的是最大值 —— 若按「最后一行」算，
        // 网格会缩成 1 列而表头的 3 个 tc 溢出网格。
        val md = "| A | B | C |\n|---|---|---|\n| 1 |"
        val xml = entry(exporter.export(doc(md), ExportOptions(dir)).getOrThrow(), "word/document.xml")
        val grid = Regex("<w:tblGrid>.*?</w:tblGrid>").find(xml)?.value ?: error("no tblGrid in $xml")
        assertThat(Regex("<w:gridCol ").findAll(grid).count()).isEqualTo(3)
        val rows = Regex("<w:tr>.*?</w:tr>").findAll(xml).map { it.value }.toList()
        assertThat(rows).hasSize(2)
        for (row in rows) {
            assertThat(Regex("<w:tc>").findAll(row).count()).isEqualTo(3)
        }
        // 补出来的是空段落单元格，不是带内容的（`<w:p></w:p>` 与 `<w:p/>` 等价，这里断言实际形态）
        assertThat(rows[1]).contains("<w:tc><w:tcPr/><w:p></w:p></w:tc>")
    }

    @Test
    fun `xml special chars are escaped`(@TempDir dir: File) {
        val xml = entry(exporter.export(doc("a < b & c > d"), ExportOptions(dir)).getOrThrow(), "word/document.xml")
        assertThat(xml).contains("a &lt; b &amp; c &gt; d")
    }

    /**
     * 取出第一个含 [marker] 的 `<w:rPr>` / `<w:pPr>` 块。
     *
     * OOXML 的这两个复杂类型是 `xs:sequence`：子元素次序错了就是 schema 违规，
     * Word 直接判"文件已损坏"整篇拒开，而 WPS/LibreOffice 照样能开——本机打得开不等于没坏，
     * 所以次序只能靠断言守住。
     */
    private fun propsContaining(xml: String, tag: String, marker: String): String =
        Regex("<w:$tag>.*?</w:$tag>").findAll(xml).map { it.value }.firstOrNull { marker in it }
            ?: error("no <w:$tag> containing $marker in $xml")

    @Test
    fun `rPr 子元素按 EG_RPrBase 次序输出`(@TempDir dir: File) {
        // 标题里的链接：同一个 rPr 里同时有 b / color / sz / szCs / u
        val xml = entry(
            exporter.export(doc("# [链接](https://e.com)"), ExportOptions(dir)).getOrThrow(),
            "word/document.xml"
        )
        val rPr = propsContaining(xml, "rPr", "<w:u ")
        assertThat(rPr.indexOf("<w:b/>")).isLessThan(rPr.indexOf("<w:color"))
        assertThat(rPr.indexOf("<w:color")).isLessThan(rPr.indexOf("<w:sz "))
        assertThat(rPr.indexOf("<w:sz ")).isLessThan(rPr.indexOf("<w:szCs"))
        // ECMA-376 §17.3.2.27：u 排在 szCs 之后。旧实现把它紧跟 color 写在 sz 之前。
        assertThat(rPr.indexOf("<w:szCs")).isLessThan(rPr.indexOf("<w:u "))
    }

    @Test
    fun `rFonts 排在 b 之前`(@TempDir dir: File) {
        val xml = entry(
            exporter.export(doc("# `代码`"), ExportOptions(dir)).getOrThrow(),
            "word/document.xml"
        )
        val rPr = propsContaining(xml, "rPr", "Consolas")
        assertThat(rPr.indexOf("<w:rFonts")).isLessThan(rPr.indexOf("<w:b/>"))
        assertThat(rPr.indexOf("<w:b/>")).isLessThan(rPr.indexOf("<w:sz "))
        assertThat(rPr.indexOf("<w:sz ")).isLessThan(rPr.indexOf("<w:szCs"))
    }

    @Test
    fun `代码块 pPr 里 shd 在 ind 之前`(@TempDir dir: File) {
        // 引用块套代码块：这样 codeBlock 的 indent 大于 0，shd 与 ind 会同时出现在一个 pPr 里
        val md = "> ```\n> x\n> ```"
        val xml = entry(exporter.export(doc(md), ExportOptions(dir)).getOrThrow(), "word/document.xml")
        val pPr = propsContaining(xml, "pPr", "<w:shd")
        assertThat(pPr).contains("<w:ind w:left=")
        // ECMA-376 §17.3.1.26 CT_PPr：shd 在 ind 之前
        assertThat(pPr.indexOf("<w:shd")).isLessThan(pPr.indexOf("<w:ind"))
    }

    // ---- 图片嵌入 ----

    private fun entryBytes(file: File, name: String): ByteArray =
        ZipFile(file).use { zip ->
            val e = zip.getEntry(name) ?: error("missing entry $name")
            zip.getInputStream(e).use { it.readBytes() }
        }

    private fun names(file: File): List<String> =
        ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }

    // 图片字节夹具来自 ImageByteFixtures.kt（`pngBytes`/`gifBytes`/`webpVp8Bytes`）。
    // 这里从前有一套私有的 be32/le16/le32/png/gif/webp，与 ImageBinaryInfoTest 的那套已经漂开
    // （VP8 frame tag 一边 0x10 一边 0x00）。WebP 仍然是**认得出来但故意不嵌**：
    // Word 2016 及更早没有 WebP 解码器，见 EMBEDDABLE_MIMES。

    /**
     * 记录问过哪些 URL 的字节加载器。表里没有的 URL 返回 null，即「读不到」。
     *
     * 记 [asked] 是为了锁住**解析后**的 URL：`DocxExporter` 该先把正文里的相对引用交给
     * [resolveExportImageSrc]，再拿结果去读字节。少了这一步，相册插进来的图在 .docx 里全是占位符，
     * 而导出本身不报任何错。
     */
    private class RecordingLoader(vararg pairs: Pair<String, ByteArray>) : (String) -> ByteArray? {
        private val table = pairs.toMap()
        val asked = mutableListOf<String>()
        override fun invoke(url: String): ByteArray? {
            asked += url
            return table[url]
        }
    }

    private fun exportWithImages(
        md: String,
        dir: File,
        loader: (String) -> ByteArray?,
        resolver: ExportImageResolver? = null
    ): File = exporter.export(
        document = doc(md),
        options = ExportOptions(dir),
        imageResolver = resolver,
        loadImageBytes = loader
    ).getOrThrow()

    @Test
    fun `PNG 真正嵌进 word_media 并接上 rels 与 Content_Types`(@TempDir dir: File) {
        val bytes = pngBytes(400, 300)
        val file = exportWithImages("![示意图](a.png)", dir, RecordingLoader("a.png" to bytes))

        assertThat(names(file)).contains("word/media/image1.png")
        // 字节必须原样落盘：转码或截断都会让 Word 静默不画这张图
        assertThat(entryBytes(file, "word/media/image1.png")).isEqualTo(bytes)

        val xml = entry(file, "word/document.xml")
        assertThat(xml).contains("<w:drawing>")
        assertThat(xml).contains("r:embed=\"rId1\"")
        // 400×300 px × 9525 EMU/px，未超正文宽所以原样
        assertThat(xml).contains("<wp:extent cx=\"3810000\" cy=\"2857500\"/>")
        assertThat(xml).doesNotContain("[图片:")

        val rels = entry(file, "word/_rels/document.xml.rels")
        assertThat(rels).contains("Id=\"rId1\"")
        assertThat(rels).contains("relationships/image\"")
        // Target 相对 word/ 而不是包根：写成 word/media/... 时 Word 找不到部件
        assertThat(rels).contains("Target=\"media/image1.png\"")
        assertThat(entry(file, "[Content_Types].xml"))
            .contains("<Default Extension=\"png\" ContentType=\"image/png\"/>")
    }

    @Test
    fun `读不到字节时退回文字占位且不产出 media 部件`(@TempDir dir: File) {
        val file = exportWithImages("![说明](missing.png)", dir, RecordingLoader())

        val xml = entry(file, "word/document.xml")
        assertThat(xml).contains("[图片: 说明]")
        assertThat(xml).doesNotContain("<w:drawing>")
        assertThat(names(file).none { it.startsWith("word/media/") }).isTrue()
        // 关系表与类型表必须回到"没有图片"的样子：声明了却没有对应部件同样是包违规
        assertThat(entry(file, "word/_rels/document.xml.rels")).doesNotContain("relationships/image")
        assertThat(entry(file, "[Content_Types].xml")).doesNotContain("image/")
    }

    @Test
    fun `加载器返回空数组视为读不到`(@TempDir dir: File) {
        // 0 字节的部件在 Word 里就是一个红叉框
        val file = exportWithImages("![x](a.png)", dir, RecordingLoader("a.png" to ByteArray(0)))
        assertThat(entry(file, "word/document.xml")).contains("[图片: x]")
        assertThat(names(file).none { it.startsWith("word/media/") }).isTrue()
    }

    @Test
    fun `空 URL 不去读字节直接占位`(@TempDir dir: File) {
        val loader = RecordingLoader()
        val file = exportWithImages("![只有 alt]()", dir, loader)
        assertThat(loader.asked).isEmpty()
        assertThat(entry(file, "word/document.xml")).contains("[图片: 只有 alt]")
    }

    @Test
    fun `WebP 认得出来但仍退回文字占位`(@TempDir dir: File) {
        val bytes = webpVp8Bytes(640, 360)
        // 先确认这批字节确实被认成 WebP——否则这条用例可能是因为"没认出来"而绿
        assertThat(sniffImageInfo(bytes)?.mime).isEqualTo("image/webp")

        val file = exportWithImages("![w](a.webp)", dir, RecordingLoader("a.webp" to bytes))
        assertThat(entry(file, "word/document.xml")).contains("[图片: w]")
        assertThat(names(file).none { it.startsWith("word/media/") }).isTrue()
    }

    @Test
    fun `同一张图引用两次只落一份部件但 docPr id 各不相同`(@TempDir dir: File) {
        val bytes = pngBytes(100, 50)
        val file = exportWithImages(
            "![一](a.png)\n\n![二](b.png)",
            dir,
            // 两个 URL、两个数组实例、同一份字节：去重键是内容的 SHA-256，不是 URL 也不是引用
            RecordingLoader("a.png" to bytes, "b.png" to bytes.copyOf())
        )

        assertThat(names(file).filter { it.startsWith("word/media/") })
            .containsExactly("word/media/image1.png")
        assertThat(entry(file, "word/_rels/document.xml.rels").split("relationships/image").size - 1)
            .isEqualTo(1)

        val xml = entry(file, "word/document.xml")
        assertThat(Regex("r:embed=\"rId1\"").findAll(xml).count()).isEqualTo(2)
        // docPr id 是文档内唯一的绘图对象编号：两处复用同一个 id，Word 会判文档损坏
        val ids = Regex("<wp:docPr id=\"(\\d+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertThat(ids).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `两种格式各出一条 Default 同格式不重复`(@TempDir dir: File) {
        val file = exportWithImages(
            "![a](a.png)\n\n![b](b.gif)\n\n![c](c.png)",
            dir,
            RecordingLoader(
                "a.png" to pngBytes(10, 10),
                "b.gif" to gifBytes(20, 20),
                "c.png" to pngBytes(30, 30)   // 与 a 字节不同、格式相同
            )
        )
        val types = entry(file, "[Content_Types].xml")
        // OPC 规定同一 Extension 只能有一条 Default，重复即包违规
        assertThat(Regex("<Default Extension=\"png\"").findAll(types).count()).isEqualTo(1)
        assertThat(types).contains("<Default Extension=\"gif\" ContentType=\"image/gif\"/>")
        // 编号连续、扩展名跟着真实字节走
        assertThat(names(file).filter { it.startsWith("word/media/") })
            .containsExactly(
                "word/media/image1.png",
                "word/media/image2.gif",
                "word/media/image3.png"
            )
    }

    @Test
    fun `wp inline 子元素按 CT_Inline 次序输出`(@TempDir dir: File) {
        val xml = entry(
            exportWithImages("![x](a.png)", dir, RecordingLoader("a.png" to pngBytes(40, 20))),
            "word/document.xml"
        )
        // CT_Inline 同样是 xs:sequence：次序错了 Word 判"文件已损坏"，WPS／LibreOffice 照样能开
        val at = listOf(
            "<wp:extent", "<wp:effectExtent", "<wp:docPr",
            "<wp:cNvGraphicFramePr", "<a:graphic"
        ).map { xml.indexOf(it) }
        at.forEach { assertThat(it).isGreaterThan(-1) }
        assertThat(at).isInOrder()
    }

    @Test
    fun `pic 内部三处 sequence 次序正确`(@TempDir dir: File) {
        val xml = entry(
            exportWithImages("![x](a.png)", dir, RecordingLoader("a.png" to pngBytes(40, 20))),
            "word/document.xml"
        )
        // CT_PictureNonVisual：cNvPr → cNvPicPr
        assertThat(xml.indexOf("<pic:cNvPr ")).isLessThan(xml.indexOf("<pic:cNvPicPr/>"))
        // CT_BlipFillProperties：blip → stretch
        assertThat(xml.indexOf("<a:blip ")).isLessThan(xml.indexOf("<a:stretch>"))
        // CT_ShapeProperties：xfrm → prstGeom
        assertThat(xml.indexOf("<a:xfrm>")).isLessThan(xml.indexOf("<a:prstGeom "))
    }

    @Test
    fun `根元素声明了 drawing 需要的五个命名空间`(@TempDir dir: File) {
        // 少一个前缀声明就是 XML 不合法，整篇打不开——即使这篇文档一张图都没有也得声明
        val xml = entry(exporter.export(doc("# x"), ExportOptions(dir)).getOrThrow(), "word/document.xml")
        listOf("xmlns:w=", "xmlns:r=", "xmlns:wp=", "xmlns:a=", "xmlns:pic=")
            .forEach { assertThat(xml).contains(it) }
    }

    @Test
    fun `超出正文宽度的图等比缩到正文宽`(@TempDir dir: File) {
        // 1200px × 9525 = 11430000 EMU，远超 A4 减去左右页边距后的 5731510
        val xml = entry(
            exportWithImages("![x](a.png)", dir, RecordingLoader("a.png" to pngBytes(1200, 600))),
            "word/document.xml"
        )
        assertThat(xml).contains("<wp:extent cx=\"5731510\" cy=\"2865755\"/>")
        // a:ext 必须与 wp:extent 一致：两者不等时 Word 按 a:ext 画，wp 留下的空位对不上
        assertThat(xml).contains("<a:ext cx=\"5731510\" cy=\"2865755\"/>")
    }

    @Test
    fun `小图不放大`(@TempDir dir: File) {
        val xml = entry(
            exportWithImages("![x](a.png)", dir, RecordingLoader("a.png" to pngBytes(16, 16))),
            "word/document.xml"
        )
        assertThat(xml).contains("<wp:extent cx=\"152400\" cy=\"152400\"/>")
    }

    @Test
    fun `极端宽高比的缩放在 Long 上算`(@TempDir dir: File) {
        // 99999×1：h × 正文宽 = 54_592_632_750，Int 装不下，溢出后算出的是负数尺寸
        val xml = entry(
            exportWithImages("![x](a.png)", dir, RecordingLoader("a.png" to pngBytes(99999, 1))),
            "word/document.xml"
        )
        assertThat(xml).contains("<wp:extent cx=\"5731510\" cy=\"57\"/>")
    }

    @Test
    fun `alt 文本进 descr 前做 XML 转义`(@TempDir dir: File) {
        val xml = entry(
            exportWithImages("![引号 \" 与 & 号](a.png)", dir, RecordingLoader("a.png" to pngBytes(10, 10))),
            "word/document.xml"
        )
        // descr 是属性值：漏转义 " 会提前闭合属性，漏转义 & 直接让 document.xml 不是合法 XML
        assertThat(xml).contains("descr=\"引号 &quot; 与 &amp; 号\"")
    }

    @Test
    fun `相对路径图片经 imageResolver 解析后才去读字节`(@TempDir dir: File) {
        val loader = RecordingLoader("file:///ws/notes/pics/a.png" to pngBytes(20, 10))
        val file = exportWithImages(
            "![x](pics/a.png)",
            dir,
            loader,
            ExportImageResolver(prefix = "file:///ws/", base = "notes", encodeAll = false)
        )
        // Word 导出不开 WebView，renderer.js 的 resolveImages() 永远不执行，
        // 基址只能由导出器自己拼——少了这一步，工具栏插进来的图导出后全是占位符
        assertThat(loader.asked).containsExactly("file:///ws/notes/pics/a.png")
        assertThat(names(file)).contains("word/media/image1.png")
    }

    @Test
    fun `已是绝对地址的引用原样交给加载器`(@TempDir dir: File) {
        // resolveExportImageSrc 对绝对地址返回 null，含义是"保留原值"而非"解析失败"；
        // 把 null 当失败处理会让所有 file:// / content:// 图片一张都嵌不进去
        val loader = RecordingLoader("file:///sd/a.png" to pngBytes(20, 10))
        val file = exportWithImages(
            "![x](file:///sd/a.png)",
            dir,
            loader,
            ExportImageResolver(prefix = "file:///ws/", base = "notes", encodeAll = false)
        )
        assertThat(loader.asked).containsExactly("file:///sd/a.png")
        assertThat(names(file)).contains("word/media/image1.png")
    }

    @Test
    fun `表格与列表里的图片一样嵌入`(@TempDir dir: File) {
        val md = "- ![a](a.png)\n\n| H |\n|---|\n| ![b](b.gif) |"
        val file = exportWithImages(
            md, dir,
            RecordingLoader("a.png" to pngBytes(10, 10), "b.gif" to gifBytes(10, 10))
        )
        // 收集器要一路穿过 renderList / renderTable 才到得了这两处；漏传一处那一处就退回占位
        assertThat(names(file).filter { it.startsWith("word/media/") })
            .containsExactly("word/media/image1.png", "word/media/image2.gif")
        val xml = entry(file, "word/document.xml")
        assertThat(Regex("<w:drawing>").findAll(xml).count()).isEqualTo(2)
        assertThat(xml).doesNotContain("[图片:")
    }

    // ---- RawHtml ----
    // Markdown 里内嵌的裸 HTML（`<details>`、`<img>`、`<br>`）在 DOCX 里没有对应结构，只能降级
    // 成纯文字 + 嵌图。这三个函数全是正则，边界情况（不带引号的属性值、实体还原顺序）改一个
    // 字符就会静默换语义：导出不报错，用户拿到的是少了图、或者把两个词粘在一起的文档。

    @Test
    fun `tagName 认标签名并统一小写`() {
        assertThat(RawHtml.tagName("<details>")).isEqualTo("details")
        // 闭合标签也要认：调用方靠它判断 <details> 区段的进出
        assertThat(RawHtml.tagName("</details>")).isEqualTo("details")
        // HTML 大小写不敏感，下游是按小写名比对的
        assertThat(RawHtml.tagName("<DIV class=x>")).isEqualTo("div")
        assertThat(RawHtml.tagName("<br/>")).isEqualTo("br")
        // `</ p>`：斜杠与名字之间允许空白
        assertThat(RawHtml.tagName("</ p>")).isEqualTo("p")
    }

    @Test
    fun `tagName 对非标签返回 null`() {
        // 注释被当成标签名的话，<details> 的配对计数会错位
        assertThat(RawHtml.tagName("<!-- 注释 -->")).isNull()
        assertThat(RawHtml.tagName("纯文字")).isNull()
        assertThat(RawHtml.tagName("<>")).isNull()
    }

    /** 收集 [RawHtml.forEachImage] 的回调实参，顺序即回调顺序。 */
    private fun images(literal: String): List<Pair<String, String>> =
        buildList { RawHtml.forEachImage(literal) { src, alt -> add(src to alt) } }

    @Test
    fun `forEachImage 三种属性写法都认`() {
        assertThat(images("""<img src="a.png" alt="图">""")).containsExactly("a.png" to "图")
        assertThat(images("<img src='b.png'>")).containsExactly("b.png" to "")
        // 不带引号也是合法 HTML；漏掉这种写法，那张图在 .docx 里就是个占位符
        assertThat(images("<img src=c.png>")).containsExactly("c.png" to "")
        // 标签名与属性名都大小写不敏感
        assertThat(images("""<IMG SRC="d.png" ALT="大写">""")).containsExactly("d.png" to "大写")
    }

    @Test
    fun `forEachImage 跳过没有可用 src 的 img`() {
        assertThat(images("<img alt=x>")).isEmpty()
        // src="" 会让 loadImageBytes 拿到空串去读文件；当成「没有 src」才对
        assertThat(images("""<img src="" alt=x>""")).isEmpty()
        assertThat(images("""<img src="   ">""")).isEmpty()
    }

    @Test
    fun `forEachImage 按出现顺序逐个回调`() {
        // 一个 HTML 块里可以有多张图；只认第一张会静默丢掉后面的
        assertThat(images("""<p><img src="1.png" alt="一"><br><img src=2.gif></p>"""))
            .containsExactly("1.png" to "一", "2.gif" to "").inOrder()
    }

    @Test
    fun `forEachImage 还原 src 与 alt 里的实体`() {
        // 查询串里的 & 在 HTML 里本该写成 &amp;；不还原就拿着一个多了 4 个字符的路径去读文件
        assertThat(images("""<img src="a.png?x=1&amp;y=2" alt="&quot;引号&quot;">"""))
            .containsExactly("a.png?x=1&y=2" to "\"引号\"")
        assertThat(images("""<img src="a.png" alt="&#39;单引号&#39;">"""))
            .containsExactly("a.png" to "'单引号'")
    }

    @Test
    fun `textOf 把标签换成空格而不是直接删`() {
        // 删成 ab 会把两个词粘成一个：正文里 `a<br>b` 的两侧本来是两个词
        assertThat(RawHtml.textOf("a<br>b")).isEqualTo("a b")
        assertThat(RawHtml.textOf("<div>甲</div><div>乙</div>")).isEqualTo("甲 乙")
    }

    @Test
    fun `textOf 压掉连续空白并去首尾`() {
        assertThat(RawHtml.textOf("  <p>  甲   乙 </p> ")).isEqualTo("甲 乙")
        // 换行与制表也算空白：内嵌 HTML 常常是多行缩进写的
        assertThat(RawHtml.textOf("<td>\n\t甲\n</td>")).isEqualTo("甲")
    }

    @Test
    fun `textOf 还原实体且 amp 放在最后换`() {
        assertThat(RawHtml.textOf("<b>a &lt; b &amp; c &gt; d</b>")).isEqualTo("a < b & c > d")
        assertThat(RawHtml.textOf("&nbsp;甲&nbsp;乙&nbsp;")).isEqualTo("甲 乙")
        // 次序临界：用户写 &amp;lt; 想要的是字面 &lt;。先换 &amp; 的话会先得到 &lt;，
        // 再被下一步换成 <，进 escapeXml 又变回 &lt; —— 看着没错，实际吃掉了一层转义。
        assertThat(RawHtml.textOf("&amp;lt;")).isEqualTo("&lt;")
        assertThat(RawHtml.textOf("&amp;amp;")).isEqualTo("&amp;")
    }

    // ---- escapeXml ----
    // 这一步是「整篇 .docx 能不能被 Word 打开」的唯一闸门：漏掉一个非法字符不是少一个字，
    // 是用户拿到一个报「内容有问题」的文件。逐字符的边界只能靠断言钉住。

    @Test
    fun `escapeXml 转义五个 XML 特殊字符`() {
        assertThat(escapeXml("""a<b>c&d"e'f"""))
            .isEqualTo("a&lt;b&gt;c&amp;d&quot;e&apos;f")
    }

    @Test
    fun `escapeXml 剥掉 XML 1_0 不允许的控制字符但留下制表与换行`() {
        // U+0000 / U+0001 / U+000B / U+001F 在编辑器里根本看不见，却是 Word 判「内容有问题」
        // 整篇拒开的直接原因；从 PDF、终端、模型输出里复制粘贴时经常夹带。
        // 用 Char(code) 拼而不写进字面量：裸控制字节一旦存进源文件，任何一次编码规范化都会让
        // 这条用例悄悄退化成恒等断言 —— 绿着，却什么都没测。
        val raw = "a${Char(0)}b${Char(1)}c${Char(11)}d${Char(31)}e\tf\ng\rh"
        // \t \n \r 是 XML 1.0 明确允许的三个 C0 字符，剥错了会把代码块和表格压成一行
        assertThat(escapeXml(raw)).isEqualTo("abcde\tf\ng\rh")
    }

    @Test
    fun `escapeXml 保留成对代理丢弃落单代理`() {
        // 😀 = U+1F600 = D83D DE00：成对的是合法补充平面字符，必须原样留着
        assertThat(escapeXml("笑😀了")).isEqualTo("笑😀了")
        // 落单代理连 UTF-8 都编不出来，留着必然写出畸形字节
        assertThat(escapeXml("a\uD83Db")).isEqualTo("ab")
        assertThat(escapeXml("a\uDE00b")).isEqualTo("ab")
        // 高代理在末尾：不能越界读下一个字符
        assertThat(escapeXml("a\uD83D")).isEqualTo("a")
    }

    @Test
    fun `escapeXml 丢弃 FFFE 与 FFFF`() {
        assertThat(escapeXml("a￾b￿c")).isEqualTo("abc")
        // U+FFFD（替换字符）是合法的，不能一起丢
        assertThat(escapeXml("a�b")).isEqualTo("a�b")
    }
}
