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

    @Test
    fun `xml special chars are escaped`(@TempDir dir: File) {
        val xml = entry(exporter.export(doc("a < b & c > d"), ExportOptions(dir)).getOrThrow(), "word/document.xml")
        assertThat(xml).contains("a &lt; b &amp; c &gt; d")
    }
}
