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
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document as CmDocument
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Word (.docx) 导出器：把 Markdown 经 Commonmark AST 转为**手写最小 OOXML**，打包成 .docx。
 *
 * 不依赖 Apache POI（体积大、Android 兼容差）。覆盖：标题 h1–h6、段落、粗体/斜体/删除线、
 * 行内代码、代码块、有序/无序列表、引用块、表格、分隔线、链接（按样式文本）、图片（占位为 alt 文本）。
 * 列表用前缀符号 + 缩进近似（不依赖 numbering.xml）。
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

    fun export(document: Document, options: ExportOptions): Result<File> = runCatching {
        val root = parser.parse(document.content)
        val body = StringBuilder()
        renderBlocks(root, body, indent = 0)
        val documentXml = DOC_PREFIX + body.toString() + DOC_SUFFIX

        val safeName = FileNameValidator.sanitize(document.name)
        val file = File(options.outputDir, "$safeName.docx")
        writeDocx(file, documentXml)
        file
    }

    // ---- 块级渲染 ----

    private fun renderBlocks(parent: Node, sb: StringBuilder, indent: Int) {
        var child = parent.firstChild
        while (child != null) {
            when (child) {
                is Heading -> paragraph(sb, headingFmt(child.level), child, indent)
                is Paragraph -> paragraph(sb, Fmt(), child, indent)
                is FencedCodeBlock -> codeBlock(sb, child.literal, indent)
                is IndentedCodeBlock -> codeBlock(sb, child.literal, indent)
                is BlockQuote -> renderBlocks(child, sb, indent + 1) // 引用：增加缩进
                is BulletList -> renderList(child, sb, ordered = false, indent)
                is OrderedList -> renderList(child, sb, ordered = true, indent)
                is ThematicBreak -> sb.append(HR_PARAGRAPH)
                is TableBlock -> renderTable(child, sb)
                else -> renderBlocks(child, sb, indent) // 未知容器：下钻
            }
            child = child.next
        }
    }

    /** 一个段落：可选前缀 run（列表符号），其后是行内内容。 */
    private fun paragraph(sb: StringBuilder, base: Fmt, inlineParent: Node, indent: Int, prefix: String? = null) {
        sb.append("<w:p>")
        if (indent > 0) sb.append("<w:pPr><w:ind w:left=\"${indent * 360}\"/></w:pPr>")
        if (prefix != null) sb.append(run(prefix, base))
        renderInlines(inlineParent, sb, base)
        sb.append("</w:p>")
    }

    private fun codeBlock(sb: StringBuilder, literal: String, indent: Int) {
        // 每行一个段落，等宽字体 + 浅底纹
        literal.trimEnd('\n').split("\n").forEach { line ->
            sb.append("<w:p>")
            sb.append("<w:pPr>")
            if (indent > 0) sb.append("<w:ind w:left=\"${indent * 360}\"/>")
            sb.append("<w:shd w:val=\"clear\" w:fill=\"F6F8FA\"/>")
            sb.append("</w:pPr>")
            sb.append(run(line.ifEmpty { " " }, Fmt(mono = true, sizeHalfPt = 20)))
            sb.append("</w:p>")
        }
    }

    private fun renderList(list: Node, sb: StringBuilder, ordered: Boolean, indent: Int) {
        var item = list.firstChild
        var index = (list as? OrderedList)?.startNumber ?: 1
        while (item != null) {
            if (item is ListItem) {
                val marker = if (ordered) "$index. " else "• "
                var block = item.firstChild
                var first = true
                while (block != null) {
                    when (block) {
                        is Paragraph -> paragraph(sb, Fmt(), block, indent + 1, prefix = if (first) marker else null)
                        is BulletList -> renderList(block, sb, ordered = false, indent + 1)
                        is OrderedList -> renderList(block, sb, ordered = true, indent + 1)
                        is FencedCodeBlock -> codeBlock(sb, block.literal, indent + 1)
                        is IndentedCodeBlock -> codeBlock(sb, block.literal, indent + 1)
                        else -> renderBlocks(block, sb, indent + 1)
                    }
                    first = false
                    block = block.next
                }
                index++
            }
            item = item.next
        }
    }

    private fun renderTable(table: TableBlock, sb: StringBuilder) {
        sb.append(
            "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>" +
                "<w:tblBorders>" +
                listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") {
                    "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"DFE2E5\"/>"
                } +
                "</w:tblBorders></w:tblPr>"
        )
        var section = table.firstChild
        while (section != null) {
            when (section) {
                is TableHead, is TableBody -> {
                    var row = section.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            sb.append("<w:tr>")
                            var cell = row.firstChild
                            while (cell != null) {
                                if (cell is TableCell) {
                                    sb.append("<w:tc><w:tcPr/><w:p>")
                                    val header = section is TableHead
                                    renderInlines(cell, sb, Fmt(bold = header))
                                    sb.append("</w:p></w:tc>")
                                }
                                cell = cell.next
                            }
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

    // ---- 行内渲染 ----

    private fun renderInlines(parent: Node, sb: StringBuilder, fmt: Fmt) {
        var child = parent.firstChild
        while (child != null) {
            when (child) {
                is Text -> sb.append(run(child.literal, fmt))
                is StrongEmphasis -> renderInlines(child, sb, fmt.copy(bold = true))
                is Emphasis -> renderInlines(child, sb, fmt.copy(italic = true))
                is Strikethrough -> renderInlines(child, sb, fmt.copy(strike = true))
                is Code -> sb.append(run(child.literal, fmt.copy(mono = true)))
                is Link -> renderInlines(child, sb, fmt.copy(link = true))
                is Image -> sb.append(run("[图片: ${child.title ?: altText(child)}]", fmt.copy(italic = true)))
                is SoftLineBreak -> sb.append(run(" ", fmt))
                is HardLineBreak -> sb.append("<w:r><w:br/></w:r>")
                else -> renderInlines(child, sb, fmt) // 其他内联容器下钻
            }
            child = child.next
        }
    }

    private fun altText(node: Node): String {
        val sb = StringBuilder()
        var c = node.firstChild
        while (c != null) { if (c is Text) sb.append(c.literal); c = c.next }
        return sb.toString().ifBlank { "image" }
    }

    /** 一个文本 run，按 [fmt] 设置运行属性。 */
    private fun run(text: String, fmt: Fmt): String {
        val rPr = StringBuilder("<w:rPr>")
        if (fmt.bold) rPr.append("<w:b/>")
        if (fmt.italic) rPr.append("<w:i/>")
        if (fmt.strike) rPr.append("<w:strike/>")
        if (fmt.mono) rPr.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
        if (fmt.link) rPr.append("<w:color w:val=\"0366D6\"/><w:u w:val=\"single\"/>")
        fmt.sizeHalfPt?.let { rPr.append("<w:sz w:val=\"$it\"/><w:szCs w:val=\"$it\"/>") }
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

    // ---- 打包 ----

    private fun writeDocx(file: File, documentXml: String) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.put("[Content_Types].xml", CONTENT_TYPES)
            zip.put("_rels/.rels", RELS)
            zip.put("word/document.xml", documentXml)
            zip.put("word/_rels/document.xml.rels", DOCUMENT_RELS)
        }
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    companion object {
        private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

        private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

        private const val DOCUMENT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""

        private const val DOC_PREFIX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>"""

        // sectPr 给出页面尺寸/边距；body 结尾必须有它。
        private const val DOC_SUFFIX = """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr></w:body></w:document>"""

        // 分隔线：底部边框的空段落
        private const val HR_PARAGRAPH = """<w:p><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="DDDDDD"/></w:pBdr></w:pPr></w:p>"""
    }
}
