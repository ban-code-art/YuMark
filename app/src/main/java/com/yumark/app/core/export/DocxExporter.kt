package com.yumark.app.core.export

import com.yumark.app.core.validation.FileNameValidator
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.ExportOptions
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document as CmDocument
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Word (.docx) 导出器：把 Markdown 经 Commonmark AST 转为**手写最小 OOXML**，打包成 .docx。
 *
 * 不依赖 Apache POI（体积大、Android 兼容差）。覆盖：标题 h1–h6、段落、粗体/斜体/删除线、
 * 行内代码、代码块、有序/无序列表、**任务列表勾选框**、引用块、表格、分隔线、
 * 链接（按样式文本）、**图片（真正嵌入 `word/media/`，读不到或格式不支持时退回 alt 文本占位）**、
 * **块级/行内原生 HTML（剥标签保文字，其中 `<img>` 照常嵌入、`<br>` 变真换行）**。
 * 列表用前缀符号 + 缩进近似（不依赖 numbering.xml）。
 *
 * ### 有意的降级（与预览/HTML/PDF/长图不一致，但不丢内容）
 * - **数学公式**：`$…$` / `$$…$$` 原样落成普通文字。Word 的公式是 OMML（`m:oMath`）而不是
 *   LaTeX，要转得先在本地实现一遍 LaTeX→OMML；同一份 .docx 里塞 MathML 又只有新版 Word 认。
 *   文字形态至少可读、可搜索、可自己再排。
 * - **mermaid**：代码块原样保留成等宽源码（`renderer.js` 那侧是渲染成 SVG 的）。要出真图得开
 *   WebView 截图再嵌位图，而这条路径**刻意不开 WebView**（见 [export] 的 `imageResolver` 注释）。
 * - **`<u>`/`<span style>` 之类行内 HTML 的样式**：标签丢掉、文字保留。
 *
 * 这三条与 [HtmlExporter] 声明「原样保留原生 HTML」是同一取向：宁可**丢格式**，绝不**丢内容**。
 */
@Singleton
class DocxExporter @Inject constructor() {

    private val parser: Parser by lazy {
        Parser.builder().extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create()
            )
        ).build()
    }

    /**
     * @param imageResolver 正文里相对路径图片引用（`![](images/a.png)`）的解析基址。
     *   这条路**不开 WebView**，`renderer.js` 的 `resolveImages()` 永远不执行，所以相对引用
     *   只能由 [resolveExportImageSrc]（那段 JS 的 Kotlin 孪生实现）在这里自己解。
     * @param loadImageBytes 按解析后的 URL 读图片字节；返回 null 表示读不到。
     *
     * 两个参数都有默认值，且都是**函数/数据参数而非构造器注入**：读字节要 `Context`
     * （[LocalImageBytes]），一注入这个类就在 JVM 单测里构造不出来，而 OOXML 拼装恰恰是
     * 这个类里最需要单测盯着的部分。默认值让 `DocxExporter().export(doc, options)` 继续可用。
     */
    fun export(
        document: Document,
        options: ExportOptions,
        imageResolver: ExportImageResolver? = null,
        loadImageBytes: (String) -> ByteArray? = { null }
    ): Result<File> = runCatching {
        // 先归一化图片目标再解析：Typora/Windows 风格的 `![图](images\my pic.png)` 不归一化的话
        // commonmark 根本不把它当图片，AST 里没有 Image 节点，下面整套嵌图逻辑全都不会触发。
        val root = parser.parse(normalizeImageTargets(document.content))
        val body = StringBuilder()
        // 每次导出新建一个收集器：这个类是 @Singleton，把图片编号挂在实例字段上，
        // 两次并发导出就会让 A 的图片进 B 的 rels，两份产物同时坏掉。
        val media = MediaBundle(imageResolver, loadImageBytes)
        renderBlocks(root, body, indent = 0, media = media)
        val documentXml = DOC_PREFIX + body.toString() + DOC_SUFFIX

        val safeName = FileNameValidator.sanitize(document.name)
        val file = File(options.outputDir, "$safeName.docx")
        writeDocx(file, documentXml, media.items)
        file
    }

    // ---- 块级渲染 ----

    private fun renderBlocks(parent: Node, sb: StringBuilder, indent: Int, media: MediaBundle) {
        var child = parent.firstChild
        while (child != null) {
            when (child) {
                is Heading -> paragraph(sb, headingFmt(child.level), child, indent, media)
                is Paragraph -> paragraph(sb, Fmt(), child, indent, media)
                is FencedCodeBlock -> codeBlock(sb, child.literal, indent)
                is IndentedCodeBlock -> codeBlock(sb, child.literal, indent)
                is BlockQuote -> renderBlocks(child, sb, indent + 1, media) // 引用：增加缩进
                is BulletList -> renderList(child, sb, ordered = false, indent = indent, media = media)
                is OrderedList -> renderList(child, sb, ordered = true, indent = indent, media = media)
                is ThematicBreak -> sb.append(HR_PARAGRAPH)
                is TableBlock -> renderTable(child, sb, media)
                is HtmlBlock -> htmlBlock(sb, child.literal, indent, media)
                else -> renderBlocks(child, sb, indent, media) // 未知容器：下钻
            }
            child = child.next
        }
    }

    /** 一个段落：可选前缀 run（列表符号），其后是行内内容。 */
    private fun paragraph(
        sb: StringBuilder,
        base: Fmt,
        inlineParent: Node,
        indent: Int,
        media: MediaBundle,
        prefix: String? = null
    ) {
        sb.append("<w:p>")
        if (indent > 0) sb.append("<w:pPr><w:ind w:left=\"${indent * 360}\"/></w:pPr>")
        if (prefix != null) sb.append(run(prefix, base))
        renderInlines(inlineParent, sb, base, media)
        sb.append("</w:p>")
    }

    private fun codeBlock(sb: StringBuilder, literal: String, indent: Int) {
        // 每行一个段落，等宽字体 + 浅底纹
        literal.trimEnd('\n').split("\n").forEach { line ->
            sb.append("<w:p>")
            sb.append("<w:pPr>")
            // pPr 子元素按 ECMA-376 Part 1 §17.3.1.26 `CT_PPr` 的次序：shd 在 ind 之前。
            // 同样是 xs:sequence，反了 Word 就拒开整篇文档。
            sb.append("<w:shd w:val=\"clear\" w:fill=\"F6F8FA\"/>")
            if (indent > 0) sb.append("<w:ind w:left=\"${indent * 360}\"/>")
            sb.append("</w:pPr>")
            sb.append(run(line.ifEmpty { " " }, Fmt(mono = true, sizeHalfPt = 20)))
            sb.append("</w:p>")
        }
    }

    private fun renderList(list: Node, sb: StringBuilder, ordered: Boolean, indent: Int, media: MediaBundle) {
        var item = list.firstChild
        // commonmark 0.30 弃用 getStartNumber()（int，无起始号时返回 1），改用
        // getMarkerStartNumber()（Integer?，无起始号时为 null）——语义上把「没写序号」和
        // 「写了 1.」区分开，这里两者都当 1 起排，行为不变。
        var index = (list as? OrderedList)?.markerStartNumber ?: 1
        while (item != null) {
            if (item is ListItem) {
                val marker = if (ordered) "$index. " else "• "
                var block = item.firstChild
                var first = true
                while (block != null) {
                    when (block) {
                        is Paragraph -> paragraph(
                            sb, Fmt(), block, indent + 1, media,
                            prefix = if (first) marker else null
                        )
                        is BulletList -> renderList(block, sb, ordered = false, indent = indent + 1, media = media)
                        is OrderedList -> renderList(block, sb, ordered = true, indent = indent + 1, media = media)
                        is FencedCodeBlock -> codeBlock(sb, block.literal, indent + 1)
                        is IndentedCodeBlock -> codeBlock(sb, block.literal, indent + 1)
                        else -> renderBlocks(block, sb, indent + 1, media)
                    }
                    first = false
                    block = block.next
                }
                index++
            }
            item = item.next
        }
    }

    private fun renderTable(table: TableBlock, sb: StringBuilder, media: MediaBundle) {
        val columns = tableColumnCount(table)
        sb.append(
            "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>" +
                "<w:tblBorders>" +
                listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") {
                    "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"DFE2E5\"/>"
                } +
                "</w:tblBorders></w:tblPr>"
        )
        // tblGrid 在 `CT_Tbl`（ECMA-376 Part 1 §17.4.38）的 xs:sequence 里是 **minOccurs 默认 1**，
        // 即必填，位置固定在 tblPr 之后、第一个 tr 之前。少了它整张表就是 schema-invalid：
        // Word 桌面版通常还能容错打开，但严格校验器、以及部分 WPS / Pages / 在线预览会直接报
        // 「文件已损坏」——而这里是**导出**，产物落到别人机器上才发现坏掉，本地一点提示都没有。
        // pPr 那边的次序注释（见 :152）是同一件事的另一半：xs:sequence 既管顺序也管必填。
        //
        // 元素本身无条件写出（`CT_TblGridBase` 的 gridCol 是 minOccurs=0，空 tblGrid 合法），
        // gridCol 只在真有列时写：一张连 tr 都没有的退化表也得留着这个必填元素。
        sb.append("<w:tblGrid>")
        if (columns > 0) {
            // 等宽切分正文可用宽度。`tblW type="auto"` 下 Word 会按内容重算列宽，这些值是初始
            // 布局提示而不是硬约束；给等宽是因为 GFM 表格语法里没有任何列宽信息可依据
            // （`|:---|` 只表示对齐），凭内容猜宽度不会比让 Word 自己算更准。
            val colWidth = CONTENT_WIDTH_TWIPS / columns
            repeat(columns) { sb.append("<w:gridCol w:w=\"$colWidth\"/>") }
        }
        sb.append("</w:tblGrid>")
        var section = table.firstChild
        while (section != null) {
            when (section) {
                is TableHead, is TableBody -> {
                    var row = section.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            sb.append("<w:tr>")
                            var cell = row.firstChild
                            var emitted = 0
                            while (cell != null) {
                                if (cell is TableCell) {
                                    sb.append("<w:tc><w:tcPr/><w:p>")
                                    val header = section is TableHead
                                    renderInlines(cell, sb, Fmt(bold = header), media)
                                    sb.append("</w:p></w:tc>")
                                    emitted++
                                }
                                cell = cell.next
                            }
                            // 补齐到网格宽度。**实测（commonmark 0.30）解析器自己已经按 GFM 补过空
                            // 单元格、也裁过超出表头的部分**，所以正常路径上这个 repeat 是 0 次 ——
                            // 留着是防解析器换版本：OOXML 允许短行，但 Word 画出来是右边缺格的豁口行，
                            // 而 tblGrid 的列数按最大值算，短行就会与网格不齐。多出来的单元格不裁
                            // （真出现说明列数是解析器给的，网格宽度已经按最大值算过了）。
                            repeat(columns - emitted) { sb.append("<w:tc><w:tcPr/><w:p/></w:tc>") }
                            sb.append("</w:tr>")
                        }
                        row = row.next
                    }
                }
            }
            section = section.next
        }
        sb.append("</w:tbl>")
        sb.append("<w:p/>") // 表格后空段，避免与后续内容粘连
    }

    /**
     * 表格列数 = 各行单元格数的**最大值**，不是表头的单元格数。
     *
     * 按 GFM，正文行多出的单元格应当被丢弃、少的应当补空，理论上 commonmark 出来的每一行都等宽；
     * 取最大值是为了不把这个前提写死进 OOXML —— 万一某个版本的解析器放行了超宽行，
     * 用表头列数算出的 tblGrid 就比实际 tc 数少，Word 得自己去猜多出来那列的宽度。
     */
    private fun tableColumnCount(table: TableBlock): Int {
        var max = 0
        var section = table.firstChild
        while (section != null) {
            if (section is TableHead || section is TableBody) {
                var row = section.firstChild
                while (row != null) {
                    if (row is TableRow) {
                        var n = 0
                        var cell = row.firstChild
                        while (cell != null) {
                            if (cell is TableCell) n++
                            cell = cell.next
                        }
                        if (n > max) max = n
                    }
                    row = row.next
                }
            }
            section = section.next
        }
        return max
    }

    // ---- 行内渲染 ----

    private fun renderInlines(parent: Node, sb: StringBuilder, fmt: Fmt, media: MediaBundle) {
        var child = parent.firstChild
        while (child != null) {
            when (child) {
                is Text -> sb.append(run(child.literal, fmt))
                is StrongEmphasis -> renderInlines(child, sb, fmt.copy(bold = true), media)
                is Emphasis -> renderInlines(child, sb, fmt.copy(italic = true), media)
                is Strikethrough -> renderInlines(child, sb, fmt.copy(strike = true), media)
                is Code -> sb.append(run(child.literal, fmt.copy(mono = true)))
                is Link -> renderInlines(child, sb, fmt.copy(link = true), media)
                is Image -> sb.append(image(child, fmt, media))
                is SoftLineBreak -> sb.append(run(" ", fmt))
                is HardLineBreak -> sb.append("<w:r><w:br/></w:r>")
                is HtmlInline -> htmlInline(sb, child.literal, fmt, media)
                // 任务列表的勾选框。TaskListItemsExtension 已经注册（见 parser 那一节），
                // 标记被后处理器插成 ListItem 里段落的第一个子节点，所以它出现在**行内**这一层。
                // 落到下面的 `else` 就是「下钻子节点」，而这是个叶子节点 —— 于是
                // `- [x] 已完成` 与 `- [ ] 未完成` 在 .docx 里都只剩 `• 已完成`／`• 未完成`，
                // **勾选状态完全不可区分**，而预览、HTML、PDF、长图四种格式都画得出勾。
                //
                // U+2611/U+2610 而不是 `[x]`/`[ ]`：这两个字形在 Segoe UI Symbol 与常见 CJK 字体里
                // 都有，Word 缺字时也会自动回落；正文已经在用 U+2022 当项目符号，同一套路。
                is TaskListItemMarker -> sb.append(run(if (child.isChecked) "☑ " else "☐ ", fmt))
                else -> renderInlines(child, sb, fmt, media) // 其他内联容器下钻
            }
            child = child.next
        }
    }

    /**
     * 一张图：能拿到字节且格式在白名单里就真嵌入，否则退回原来的 `[图片: alt]` 文本 run。
     *
     * 退化而不是报错：`export()` 是一个大 `runCatching`，为一张读不到的图整篇导出失败，
     * 对用户来说远差于「这一处变成一行字」。
     */
    private fun image(node: Image, fmt: Fmt, media: MediaBundle): String {
        val alt = node.title ?: altText(node)
        val item = media.resolve(node.destination.orEmpty()) ?: return run("[图片: $alt]", fmt.copy(italic = true))
        return drawing(item, media.nextDocPrId(), alt)
    }

    /**
     * 块级原生 HTML：`<details>`、`<div>`、手写 `<table>`、单独成段的 `<img>`……
     *
     * [HtmlBlock] 是**叶子节点** —— 整块原文都在 `literal` 里，一个子节点都没有。落到
     * `renderBlocks` 的 `else`「未知容器下钻」分支就等于什么都不做，于是
     * `<details><summary>点开</summary>内容</details>` 在 .docx 里**连里面的文字一起消失**，
     * 连 `[图片: alt]` 那种占位都没有；而预览、HTML、富 HTML、PDF、长图五种格式全都保留
     * （[HtmlExporter] 明确声明「原样保留原生 HTML」）。同一份文档六种导出对不上，且无任何提示。
     *
     * Word 里没有「原生 HTML」这一层，能做的是降级：`<img>` 照常嵌成真图片，其余标签剥掉、
     * 只留文字。**丢格式，不丢内容** —— 这与 mermaid/公式在 DOCX 里退化成源码文字是同一取向。
     */
    private fun htmlBlock(sb: StringBuilder, literal: String, indent: Int, media: MediaBundle) {
        val runs = StringBuilder()
        RawHtml.forEachImage(literal) { src, alt ->
            val item = media.resolve(src)
            runs.append(
                if (item != null) drawing(item, media.nextDocPrId(), alt)
                else run("[图片: $alt]", Fmt(italic = true))
            )
        }
        val text = RawHtml.textOf(literal)
        if (text.isNotEmpty()) runs.append(run(text, Fmt()))
        // 纯结构块（`<div>`、`</details>`、HTML 注释）剥完什么都不剩，别留个空段落撑出空行
        if (runs.isEmpty()) return
        sb.append("<w:p>")
        if (indent > 0) sb.append("<w:pPr><w:ind w:left=\"${indent * 360}\"/></w:pPr>")
        sb.append(runs)
        sb.append("</w:p>")
    }

    /**
     * 行内原生 HTML。[HtmlInline] 的 `literal` 是**单个标签**（`<u>`、`</u>`、`<br>`、`<img …>`），
     * 标签之间的文字是相邻的 [Text] 节点 —— 所以这里只需处理"标签本身有内容"的两种：
     * `<br>` 变成真换行、`<img>` 嵌成真图片。其余成对标签丢掉即可，文字由 [Text] 分支照常输出。
     */
    private fun htmlInline(sb: StringBuilder, literal: String, fmt: Fmt, media: MediaBundle) {
        when (RawHtml.tagName(literal)) {
            "br" -> sb.append("<w:r><w:br/></w:r>")
            "img" -> RawHtml.forEachImage(literal) { src, alt ->
                val item = media.resolve(src)
                sb.append(
                    if (item != null) drawing(item, media.nextDocPrId(), alt)
                    else run("[图片: $alt]", fmt.copy(italic = true))
                )
            }
            else -> Unit // <u>/<span>/<sub>…：丢格式，不丢字
        }
    }

    /**
     * 一张图的 `w:drawing`：`wp:inline`（随文排版，不是浮动锚点）+ `pic:pic` 指向 media 里的字节。
     *
     * `wp:inline` 的子元素次序由 ECMA-376 Part 1 §20.4.2.8 `CT_Inline` 规定，也是 `xs:sequence`：
     * `wp:extent → wp:effectExtent → wp:docPr → wp:cNvGraphicFramePr → a:graphic`。
     * 和 `rPr`/`pPr` 一样，错序 = schema 违规 = Word 拒开整篇文档，而 WPS/LibreOffice 照开。
     *
     * 每次出现都用一个新的 `wp:docPr id`（[MediaBundle.nextDocPrId]），即使两处引用同一张图
     * 共用一个 `r:embed`：docPr 标识的是「文档里的这个绘图对象」，重号会让 Word 报修复。
     */
    private fun drawing(item: MediaItem, docPrId: Int, alt: String): String {
        val (cx, cy) = emuSize(item.info.widthPx, item.info.heightPx)
        val descr = escapeXml(alt)
        return "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>" +
            "<wp:docPr id=\"$docPrId\" name=\"Picture $docPrId\" descr=\"$descr\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic><a:graphicData uri=\"$PIC_NS\">" +
            "<pic:pic>" +
            "<pic:nvPicPr><pic:cNvPr id=\"0\" name=\"Picture $docPrId\" descr=\"$descr\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"${item.relId}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic>" +
            "</a:graphicData></a:graphic>" +
            "</wp:inline></w:drawing></w:r>"
    }

    /**
     * 像素 → EMU，超出正文宽度时等比缩小。
     *
     * **只缩不放**：一张 32×32 的图标被拉到 15cm 只会是一团马赛克，原尺寸才是作者的意图。
     * 全程 `Long`：`100000px * 9525 * 5731510` 在 `Int` 里早就溢出成负数，
     * 负的 `cx` 会让 Word 判定文档损坏。
     */
    private fun emuSize(widthPx: Int, heightPx: Int): Pair<Long, Long> {
        val w = widthPx.toLong() * EMU_PER_PX
        val h = heightPx.toLong() * EMU_PER_PX
        if (w <= CONTENT_WIDTH_EMU) return w to h
        // 至少 1 EMU：宽高比极端的长条图缩完可能不足 1，0 同样是"文档损坏"
        return CONTENT_WIDTH_EMU to (h * CONTENT_WIDTH_EMU / w).coerceAtLeast(1L)
    }

    private fun altText(node: Node): String {
        val sb = StringBuilder()
        var c = node.firstChild
        while (c != null) { if (c is Text) sb.append(c.literal); c = c.next }
        return sb.toString().ifBlank { "image" }
    }

    /**
     * 一个文本 run，按 [fmt] 设置运行属性。
     *
     * rPr 子元素**必须**按 ECMA-376 Part 1 §17.3.2.27 里 `EG_RPrBase` 的声明次序输出：
     * `rFonts → b → i → strike → color → sz → szCs → u`。
     * OOXML 的复杂类型用的是 `xs:sequence` 而非 `xs:all`——顺序错了就是 schema 违规，
     * Word 会当"文件已损坏"整篇拒开；而 WPS/LibreOffice 宽容得多，本机打得开不等于没坏。
     */
    private fun run(text: String, fmt: Fmt): String {
        val rPr = StringBuilder("<w:rPr>")
        if (fmt.mono) rPr.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
        if (fmt.bold) rPr.append("<w:b/>")
        if (fmt.italic) rPr.append("<w:i/>")
        if (fmt.strike) rPr.append("<w:strike/>")
        if (fmt.link) rPr.append("<w:color w:val=\"0366D6\"/>")
        fmt.sizeHalfPt?.let { rPr.append("<w:sz w:val=\"$it\"/><w:szCs w:val=\"$it\"/>") }
        // u 在 EG_RPrBase 里排在 szCs 之后，不能跟 color 挤在一起写
        if (fmt.link) rPr.append("<w:u w:val=\"single\"/>")
        rPr.append("</w:rPr>")
        return "<w:r>$rPr<w:t xml:space=\"preserve\">${escapeXml(text)}</w:t></w:r>"
    }

    private fun headingFmt(level: Int): Fmt {
        val size = when (level) {
            1 -> 48; 2 -> 36; 3 -> 32; 4 -> 28; 5 -> 24; else -> 22
        }
        return Fmt(bold = true, sizeHalfPt = size)
    }

    private data class Fmt(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val mono: Boolean = false,
        val link: Boolean = false,
        val sizeHalfPt: Int? = null
    )

    // ---- 图片收集 ----

    /** 一个已确定要嵌入的图片部件。[partName] 相对 `word/`，rels 的 Target 和 zip 路径共用它。 */
    private class MediaItem(
        val relId: String,
        val partName: String,
        val info: ImageBinaryInfo,
        val bytes: ByteArray
    )

    /**
     * 一次导出期间的图片收集器：URL → 字节 → 嗅探 → 白名单 → 编号，全部状态都在这里。
     *
     * **必须是 `export()` 里的局部对象**。[DocxExporter] 是 `@Singleton`，编号计数器一旦挂到
     * 实例字段上，两次并发导出就会交叉：A 的 `rId2` 指向 B 写进 zip 的 `image2.png`，
     * 两份产物同时损坏，而且是那种本机偶尔能打开的间歇性损坏。
     */
    private class MediaBundle(
        private val resolver: ExportImageResolver?,
        private val load: (String) -> ByteArray?
    ) {
        /** key 是字节的 SHA-256：同一张图被引用多次只进一份 `word/media/`，共用一个 relId。 */
        private val byHash = LinkedHashMap<String, MediaItem>()
        private var nextDocPr = 1

        val items: Collection<MediaItem> get() = byHash.values

        /** 绘图对象编号，与 relId 无关：同图两处引用 = 两个 docPr id + 一个 relId。 */
        fun nextDocPrId(): Int = nextDocPr++

        /** 解析并登记一张图；任何一步失败都返回 null，由调用方退回文字占位。 */
        fun resolve(rawUrl: String): MediaItem? {
            if (rawUrl.isBlank()) return null
            // resolveExportImageSrc 返回 null 的含义是"原样保留"（已是绝对地址/没有基址），
            // 不是"解析失败"——这里必须回落到 rawUrl，否则本地绝对路径的图全都嵌不进去。
            val url = resolveExportImageSrc(rawUrl, resolver) ?: rawUrl
            val bytes = load(url)?.takeIf { it.isNotEmpty() } ?: return null
            val hash = sha256Hex(bytes)
            byHash[hash]?.let { return it }
            val info = sniffImageInfo(bytes) ?: return null
            if (info.mime !in EMBEDDABLE_MIMES) return null
            val n = byHash.size + 1
            return MediaItem("rId$n", "media/image$n.${info.ext}", info, bytes)
                .also { byHash[hash] = it }
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }

    // ---- 打包 ----

    private fun writeDocx(file: File, documentXml: String, media: Collection<MediaItem>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.put("[Content_Types].xml", contentTypes(media))
            zip.put("_rels/.rels", RELS)
            zip.put("word/document.xml", documentXml)
            zip.put("word/_rels/document.xml.rels", documentRels(media))
            media.forEach { zip.put("word/${it.partName}", it.bytes) }
        }
    }

    /**
     * `[Content_Types].xml`：每种实际用到的图片扩展名补一条 `<Default>`。
     *
     * 按扩展名去重是硬要求——同名 `<Default Extension>` 出现两次是 OPC 违规，Word 直接拒开。
     * 没有图片时输出与改造前完全一致的三条声明。
     */
    private fun contentTypes(media: Collection<MediaItem>): String {
        val defaults = media.distinctBy { it.info.ext }
            .joinToString("") { "<Default Extension=\"${it.info.ext}\" ContentType=\"${it.info.mime}\"/>" }
        return CONTENT_TYPES_PREFIX + defaults + CONTENT_TYPES_SUFFIX
    }

    /** `word/_rels/document.xml.rels`：Target 相对 `word/`，即 `media/image1.png`。 */
    private fun documentRels(media: Collection<MediaItem>): String {
        val rels = media.joinToString("") {
            "<Relationship Id=\"${it.relId}\" Type=\"$IMAGE_REL_TYPE\" Target=\"${it.partName}\"/>"
        }
        return DOCUMENT_RELS_PREFIX + rels + DOCUMENT_RELS_SUFFIX
    }

    private fun ZipOutputStream.put(name: String, content: String) =
        put(name, content.toByteArray(Charsets.UTF_8))

    private fun ZipOutputStream.put(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    companion object {
        /**
         * 能真正嵌进 .docx 的格式白名单。
         *
         * 刻意不含 `image/webp`：Word 2016 及更早没有 WebP 解码器，嵌进去用户看到的是一个红叉框，
         * 比一行 `[图片: alt]` 更糟——文字至少说得清那里本来有张图。嗅探仍然认 WebP，
         * 目的是**确定地**知道它是 WebP 而不是靠文件名猜。
         */
        private val EMBEDDABLE_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/bmp")

        private const val PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture"
        private const val IMAGE_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"

        /** 96 dpi 下 1px = 914400/96 = 9525 EMU。 */
        private const val EMU_PER_PX = 9525L

        /**
         * 正文可用宽度（twip），由 [DOC_SUFFIX] 里的 `pgSz`/`pgMar` 推出来而不是硬写 9026：
         * 页宽与边距任何一处改了，这个数必须跟着改，写死就会静默失配。
         * 表格的 `w:gridCol` 与图片的 [CONTENT_WIDTH_EMU] 都从这一个数派生，只有一处真值。
         */
        private const val CONTENT_WIDTH_TWIPS = 11906L - 1440L - 1440L

        /** 正文可用宽度（EMU）。1 twip = 1/1440 inch = 914400/1440 = 635 EMU。 */
        private const val CONTENT_WIDTH_EMU = CONTENT_WIDTH_TWIPS * 635L

        private const val CONTENT_TYPES_PREFIX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
"""

        private const val CONTENT_TYPES_SUFFIX = """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

        private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

        private const val DOCUMENT_RELS_PREFIX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">"""

        private const val DOCUMENT_RELS_SUFFIX = """</Relationships>"""

        // 五个命名空间必须全在根元素上：`a:`/`pic:`/`r:`/`wp:` 只在图片那段出现，
        // 但 XML 的命名空间前缀无法"就地声明后半篇生效"——漏一个，整篇是 not-well-formed。
        private const val DOC_PREFIX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>"""

        // sectPr 给出页面尺寸/边距；body 结尾必须有它。
        private const val DOC_SUFFIX = """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr></w:body></w:document>"""

        // 分隔线：底部边框的空段落
        private const val HR_PARAGRAPH = """<w:p><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="DDDDDD"/></w:pBdr></w:pPr></w:p>"""
    }
}

// ---- 原生 HTML 的最小解析 ----

/**
 * 只做 DOCX 降级需要的三件事：认出标签名、抠出 `<img>` 的 src/alt、把标签剥成纯文字。
 *
 * 刻意**不**引 HTML 解析器（jsoup 之类）：正文里的原生 HTML 通常是一小段手写标记，
 * 为它加一个几百 KB 的依赖不划算，而这三件事用正则就够 —— 解析错的代价是"某个标签的
 * 格式没保住"，不是产物损坏（真正会让 Word 拒开整篇的是 [escapeXml]，那一步在下游）。
 *
 * `internal` 顶层而不是 [DocxExporter] 里的 `private object`：这几个正则的边界情况（不带引号的
 * 属性值、`&amp;` 的还原顺序）只能靠单元测试钉住，而 `private` 成员从 `app/src/test/` 根本
 * 看不见——AGP 只把测试源集设成主源集的 friend，friend 打开的是 `internal`，不是 `private`。
 */
internal object RawHtml {
    private val TAG = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)
    private val IMG = Regex(
        """<img\b[^>]*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val NAME = Regex("""</?\s*([a-zA-Z][-\w:.]*)""")

    /** 属性值三种写法都收：双引号、单引号、**不带引号**（`<img src=a.png>` 也是合法 HTML）。 */
    private val ATTR = Regex("""([a-zA-Z_:][-\w:.]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""")

    private val WS = Regex("""\s+""")

    /** `</details>` → `details`；不是标签（如 HTML 注释）时返回 null。 */
    fun tagName(literal: String): String? =
        NAME.find(literal)?.groupValues?.get(1)?.lowercase()

    fun forEachImage(literal: String, emit: (src: String, alt: String) -> Unit) {
        IMG.findAll(literal).forEach { m ->
            val attrs = attrsOf(m.value)
            val src = attrs["src"]?.takeIf { it.isNotBlank() } ?: return@forEach
            emit(src, attrs["alt"].orEmpty())
        }
    }

    /**
     * 剥掉所有标签、还原基本实体、压掉连续空白。
     *
     * 标签换成空格而不是直接删：`a<br>b` 删成 `ab` 会把两个词粘成一个，换空格再压缩
     * 至少保住词边界。
     */
    fun textOf(literal: String): String =
        unescape(TAG.replace(literal, " ")).replace(WS, " ").trim()

    private fun attrsOf(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { m ->
            // 三个候选分组只有一个非空（未匹配的分组是空串）；都空说明值本来就是 ""
            val raw = m.groupValues[3].ifEmpty { m.groupValues[4] }.ifEmpty { m.groupValues[5] }
            m.groupValues[1].lowercase() to unescape(raw)
        }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        // &amp; 必须放最后：先换它的话 `&amp;lt;`（用户想要的是字面 `&lt;`）会被两步
        // 还原成 `<`，再进 escapeXml 变成 `&lt;` —— 看着没错，实际把转义层数吃掉了一层。
        .replace("&amp;", "&")
}

/**
 * 转义 XML 特殊字符，并**先剥掉 XML 1.0 不允许出现的字符**。
 *
 * 剥离不是洁癖：`word/document.xml` 里只要出现一个裸的 U+0001 或 U+000B，Word 就报
 * 「内容有问题」拒开**整篇** .docx —— 丢的不是一个字符，是整份导出件。而这类字符很容易
 * 混进正文：从 PDF、终端、模型输出里复制粘贴时经常夹带控制字符，用户在编辑器里根本看不见。
 *
 * 合法集（XML 1.0 §2.2）：`#x9 #xA #xD`、`#x20–#xD7FF`、`#xE000–#xFFFD`、`#x10000–#x10FFFF`。
 * 代理对要成对才合法：成对的原样保留（它们组成的是补充平面字符，如 emoji），落单的丢掉
 * —— 落单代理连 UTF-8 都编不出来，留着必然写出畸形字节。
 *
 * `internal` 顶层的理由同 [RawHtml]：这一步是「整篇 .docx 能不能被 Word 打开」的唯一闸门，
 * 而它的判据全是逐字符的边界条件，非测试不可。
 */
internal fun escapeXml(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c == '"' -> sb.append("&quot;")
            c == '\'' -> sb.append("&apos;")
            // 三个被 XML 明确允许的控制字符
            c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
            c.isHighSurrogate() -> {
                val low = text.getOrNull(i + 1)
                if (low != null && low.isLowSurrogate()) {
                    sb.append(c).append(low)
                    i++
                }
                // 落单高代理：丢弃
            }
            c.isLowSurrogate() -> Unit          // 落单低代理：丢弃
            c.code < 0x20 -> Unit               // 其余 C0 控制字符：XML 1.0 不允许
            c.code == 0xFFFE || c.code == 0xFFFF -> Unit
            else -> sb.append(c)                // 0x20–0xD7FF / 0xE000–0xFFFD
        }
        i++
    }
    return sb.toString()
}
