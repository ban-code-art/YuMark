package com.yumark.app.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 分享导出结果时手里只有 File，mime 只能从扩展名反推 —— 这一步错了，
 * 二进制格式会被当纯文本发出去，接收方压根打不开。
 */
class ExportFormatTest {

    @Test
    fun `每种导出格式的扩展名都能反推回自己的 mime`() {
        ExportFormat.entries.forEach { format ->
            // RICH_HTML 与 HTML 共用 html 扩展名，反推只能落到其中一个，
            // 但两者 mime 相同，所以按 mime 断言而不是按枚举项断言。
            assertThat(ExportFormat.mimeForExtension(format.extension)).isEqualTo(format.mimeType)
        }
    }

    @Test
    fun `二进制格式不会退化成 text plain`() {
        assertThat(ExportFormat.mimeForExtension("pdf")).isEqualTo("application/pdf")
        assertThat(ExportFormat.mimeForExtension("docx"))
            .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        assertThat(ExportFormat.mimeForExtension("png")).isEqualTo("image/png")
    }

    @Test
    fun `markdown 长扩展名与 md 同义`() {
        assertThat(ExportFormat.mimeForExtension("markdown")).isEqualTo("text/markdown")
        assertThat(ExportFormat.mimeForExtension("md")).isEqualTo("text/markdown")
    }

    @Test
    fun `大小写与前导点都不影响判断`() {
        // File.extension 不带点，但调用方哪天传 ".PDF" 也不该把 PDF 发成纯文本。
        assertThat(ExportFormat.mimeForExtension("PDF")).isEqualTo("application/pdf")
        assertThat(ExportFormat.mimeForExtension(".Docx"))
            .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        assertThat(ExportFormat.mimeForExtension(".HTML")).isEqualTo("text/html")
    }

    @Test
    fun `认不出的扩展名退回 octet-stream 而不是谎称纯文本`() {
        // text/plain 是一句谎话：接收方按文本收下二进制，用户看到的是一堆乱码。
        listOf("", "zip", "bin", "txt", "xyz").forEach {
            assertThat(ExportFormat.mimeForExtension(it)).isEqualTo("application/octet-stream")
        }
    }
}
