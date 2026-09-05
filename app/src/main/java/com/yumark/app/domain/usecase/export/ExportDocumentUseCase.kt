package com.yumark.app.domain.usecase.export

import com.yumark.app.R
import com.yumark.app.core.export.DocxExporter
import com.yumark.app.core.export.ExportImageResolver
import com.yumark.app.core.export.HtmlExporter
import com.yumark.app.core.export.LocalImageBytes
import com.yumark.app.core.export.WebViewDocumentRenderer
import com.yumark.app.core.util.FriendlyValidationException
import com.yumark.app.core.util.PathSafety
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.validation.FileNameValidator
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.ExportFormat
import com.yumark.app.domain.model.ExportOptions
import com.yumark.app.domain.repository.DocumentRepository
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class ExportDocumentUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val htmlExporter: HtmlExporter,
    private val webViewRenderer: WebViewDocumentRenderer,
    private val docxExporter: DocxExporter,
    private val localImageBytes: LocalImageBytes
) {
    /**
     * @param imageResolver 正文里**相对路径图片引用**（`![](images/a.png)`）的解析基址，
     *   由调用方按文档来源提供（导入库文档 / 外部工作区文档）。
     *   不传只有绝对 `file://`、`content://` 与网络图片能出图：离屏渲染的 baseURL 是
     *   `file:///android_asset/`，相对引用会解析到 assets 里去，必然 404，导出件里那几张图是空的。
     *   除 MARKDOWN 外**所有格式都用它**：WebView 三种（RICH_HTML / PDF / IMAGE）交给
     *   `renderer.js` 的 `resolveImages()`，HTML 与 WORD 这两条不开 WebView 的路
     *   则由 `resolveExportImageSrc`（那段 JS 的 Kotlin 孪生实现）在导出器内部自己解。
     */
    suspend operator fun invoke(
        documentId: String,
        format: ExportFormat,
        options: ExportOptions,
        imageResolver: ExportImageResolver? = null
    ): Result<File> = try {
        Result.success(export(documentId, format, options, imageResolver))
    } catch (e: CancellationException) {
        // 不写 runCatching：它捕获 Throwable，会把取消一起裹成 Result.failure。
        // PDF / 长图导出要驱动 WebView 渲染，是秒级操作，用户中途退出编辑器就会让
        // viewModelScope 取消它。取消不是失败——裹成 failure 后，只要调用方不是走
        // ErrorHandler（classify 会把 CancellationException 重新抛出）而是 getOrNull()，
        // 取消信号就断在这里，父作用域以为任务正常跑完了。
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun export(
        documentId: String,
        format: ExportFormat,
        options: ExportOptions,
        imageResolver: ExportImageResolver?
    ): File {
        // 抛 FriendlyValidationException 而不是裸 IllegalArgumentException：
        // ErrorHandler.classify 有意把 IllegalArgumentException 归成「出现未知问题，请重试」
        // （那个类型绝大多数时候携带的是给开发者看的英文断言），于是「导出目录不存在」
        // 这种完全可诊断的前置条件失败，到用户眼里变成一句无从下手的未知错误。
        // 文案里不带绝对路径：应用私有目录路径对用户毫无意义，而带 UserFacingMessage 标记的
        // message 是会被原样透到 Snackbar 的。
        if (!options.outputDir.exists()) {
            throw FriendlyValidationException(UiMessage.Res(R.string.export_error_dir_missing))
        }
        if (!options.outputDir.canWrite()) {
            throw FriendlyValidationException(
                UiMessage.Res(R.string.export_error_dir_not_writable)
            )
        }

        val document = documentRepository.getDocumentById(documentId).getOrThrow()

        return when (format) {
            ExportFormat.MARKDOWN -> exportMarkdown(document, options)
            ExportFormat.HTML -> exportHtml(document, options, imageResolver)
            ExportFormat.RICH_HTML -> exportRichHtml(document, options, imageResolver)
            ExportFormat.PDF -> exportPdf(document, options, imageResolver)
            ExportFormat.WORD -> exportWord(document, options, imageResolver)
            ExportFormat.IMAGE -> exportImage(document, options, imageResolver)
        }
    }

    /**
     * 导出件的落盘文件名：直接用 [FileNameValidator.sanitize]。
     *
     * 这里原本抄了一份一模一样的清理逻辑（同样的非法字符表、同样的 `..` 替换、同样的截断到 200），
     * 于是同一件事有两处实现——`sanitize` 修好「截断可能切开代理对」之后，这一份还留着
     * `substring(0, 200)`，而这一份才是真正决定导出文件名的那个：正文里带 emoji 的长标题在
     * 第 200 个 Char 处被切成半个字符，落盘时按 UTF-8 编不出来，文件名里就是一个乱码字节
     * （分享给别的 App 时还可能直接打不开）。
     */
    private fun sanitizeFileName(name: String): String = FileNameValidator.sanitize(name)

    /**
     * 验证导出文件路径在允许的目录内。
     *
     * 判定逻辑集中在 [PathSafety.requireInside]（含为什么不能用字符串前缀比较的完整理由），
     * 与 `FileManager.validatePathInDirectory` 共用同一套实现，避免两处安全校验强度不一致。
     */
    private fun validateOutputPath(file: File, allowedDir: File) {
        PathSafety.requireInside(file, allowedDir, label = "Output path")
    }

    private fun exportMarkdown(document: Document, options: ExportOptions): File {
        val safeFileName = sanitizeFileName(document.name)
        val outputFile = File(options.outputDir, "$safeFileName.md")

        // 验证输出路径安全性
        validateOutputPath(outputFile, options.outputDir)

        outputFile.writeText(document.content)
        return outputFile
    }

    /**
     * 纯 HTML 导出。[imageResolver] 必须往下传：这条路不开 WebView，`renderer.js` 的相对图片
     * 解析不会执行，基址只能由 [HtmlExporter] 自己拼（见那边的 buildHtml）。
     *
     * 落盘路径校验交给 [HtmlExporter]（它自己也走 [FileNameValidator.sanitize]），与其余分支
     * 各自 validateOutputPath 的写法不同——文件名在那边生成，这里拿不到最终名字。
     */
    private fun exportHtml(
        document: Document,
        options: ExportOptions,
        imageResolver: ExportImageResolver?
    ): File {
        return htmlExporter.export(document, options, imageResolver).getOrThrow()
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun exportRichHtml(
        document: Document,
        options: ExportOptions,
        imageResolver: ExportImageResolver?
    ): File {
        val safeFileName = sanitizeFileName(document.name)
        val outputFile = File(options.outputDir, "$safeFileName.html")
        validateOutputPath(outputFile, options.outputDir)
        // 复用预览 WebView 管线渲染（含 KaTeX/Mermaid/Prism），输出自包含富 HTML
        return webViewRenderer.renderToRichHtml(document.content, outputFile, imageResolver)
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun exportPdf(
        document: Document,
        options: ExportOptions,
        imageResolver: ExportImageResolver?
    ): File {
        val safeFileName = sanitizeFileName(document.name)
        val outputFile = File(options.outputDir, "$safeFileName.pdf")
        validateOutputPath(outputFile, options.outputDir)
        // 复用预览 WebView 管线渲染（含 KaTeX/Mermaid/Prism），打印为 PDF
        return webViewRenderer.renderToPdf(document.content, outputFile, imageResolver)
    }

    /**
     * Word 导出。和 [exportHtml] 一样是**不开 WebView**的一条路，所以 [imageResolver] 必须往下传，
     * 否则相对路径图片在 .docx 里全部退化成 `[图片: alt]` 文字。
     *
     * [LocalImageBytes.read] 以函数引用而非构造器依赖的形式交给 [DocxExporter]：它要 `Context`，
     * 注进那个类就会让 OOXML 拼装再也无法在 JVM 单测里构造出来。
     */
    private fun exportWord(
        document: Document,
        options: ExportOptions,
        imageResolver: ExportImageResolver?
    ): File {
        return docxExporter.export(
            document = document,
            options = options,
            imageResolver = imageResolver,
            loadImageBytes = localImageBytes::read
        ).getOrThrow()
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun exportImage(
        document: Document,
        options: ExportOptions,
        imageResolver: ExportImageResolver?
    ): File {
        val safeFileName = sanitizeFileName(document.name)
        val outputFile = File(options.outputDir, "$safeFileName.png")
        validateOutputPath(outputFile, options.outputDir)
        // 整页渲染截成长图
        return webViewRenderer.renderToImage(document.content, outputFile, imageResolver)
    }
}
