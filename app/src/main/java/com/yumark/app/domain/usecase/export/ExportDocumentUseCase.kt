package com.yumark.app.domain.usecase.export

import com.yumark.app.core.export.DocxExporter
import com.yumark.app.core.export.HtmlExporter
import com.yumark.app.core.export.WebViewDocumentRenderer
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.ExportFormat
import com.yumark.app.domain.model.ExportOptions
import com.yumark.app.domain.repository.DocumentRepository
import java.io.File
import javax.inject.Inject

class ExportDocumentUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val htmlExporter: HtmlExporter,
    private val webViewRenderer: WebViewDocumentRenderer,
    private val docxExporter: DocxExporter
) {
    suspend operator fun invoke(
        documentId: String,
        format: ExportFormat,
        options: ExportOptions
    ): Result<File> = runCatching {
        // 验证导出目录
        if (!options.outputDir.exists()) {
            throw IllegalArgumentException("Output directory does not exist: ${options.outputDir}")
        }
        if (!options.outputDir.canWrite()) {
            throw IllegalArgumentException("No write permission for directory: ${options.outputDir}")
        }

        val document = documentRepository.getDocumentById(documentId).getOrThrow()

        when (format) {
            ExportFormat.MARKDOWN -> exportMarkdown(document, options)
            ExportFormat.HTML -> exportHtml(document, options)
            ExportFormat.PDF -> exportPdf(document, options)
            ExportFormat.WORD -> exportWord(document, options)
            ExportFormat.IMAGE -> exportImage(document, options)
        }
    }

    /**
     * 清理文件名，移除非法字符和路径分隔符
     * 防止路径遍历攻击
     */
    private fun sanitizeFileName(name: String): String {
        // 移除路径分隔符和其他危险字符
        val cleaned = name
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace("..", "_")
            .trim()

        // 如果清理后为空，使用默认名称
        if (cleaned.isBlank()) {
            return "document"
        }

        // 限制文件名长度（留出扩展名空间）
        return if (cleaned.length > 200) {
            cleaned.substring(0, 200)
        } else {
            cleaned
        }
    }

    /**
     * 验证导出文件路径在允许的目录内
     */
    private fun validateOutputPath(file: File, allowedDir: File) {
        val canonicalFile = file.canonicalFile
        val canonicalDir = allowedDir.canonicalFile

        if (!canonicalFile.path.startsWith(canonicalDir.path)) {
            throw SecurityException(
                "Output path ${canonicalFile.path} is outside allowed directory ${canonicalDir.path}"
            )
        }
    }

    private fun exportMarkdown(document: Document, options: ExportOptions): File {
        val safeFileName = sanitizeFileName(document.name)
        val outputFile = File(options.outputDir, "$safeFileName.md")

        // 验证输出路径安全性
        validateOutputPath(outputFile, options.outputDir)

        outputFile.writeText(document.content)
        return outputFile
    }

    private fun exportHtml(document: Document, options: ExportOptions): File {
        return htmlExporter.export(document, options).getOrThrow()
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun exportPdf(document: Document, options: ExportOptions): File {
        val safeFileName = sanitizeFileName(document.name)
        val outputFile = File(options.outputDir, "$safeFileName.pdf")
        validateOutputPath(outputFile, options.outputDir)
        // 复用预览 WebView 管线渲染（含 KaTeX/Mermaid/Prism），打印为 PDF
        return webViewRenderer.renderToPdf(document.content, outputFile)
    }

    private fun exportWord(document: Document, options: ExportOptions): File {
        return docxExporter.export(document, options).getOrThrow()
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun exportImage(document: Document, options: ExportOptions): File {
        val safeFileName = sanitizeFileName(document.name)
        val outputFile = File(options.outputDir, "$safeFileName.png")
        validateOutputPath(outputFile, options.outputDir)
        // 整页渲染截成长图
        return webViewRenderer.renderToImage(document.content, outputFile)
    }
}
