package com.yumark.app.core.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 离屏 WebView 文档渲染器：复用**预览管线**（assets/templates/renderer.html + marked/KaTeX/
 * Prism/Mermaid），把 Markdown 渲染成与预览一致的页面，再输出为 PDF 或长图位图。
 *
 * 所有 WebView 操作都在主线程进行（[Dispatchers.Main]）。渲染就绪（JS `Android.onReady`）后
 * 调 `renderMarkdown`，再等待 [RENDER_WAIT_MS] 让 KaTeX/Mermaid 等异步渲染收敛，然后导出。
 *
 * PDF 用 [PdfDocument] + 逐页把已布局 WebView 画到页面画布（按 A4 比例分页），既不依赖打印框架的
 * package-private 回调，也不需要生成整张超大位图。
 */
@Singleton
class WebViewDocumentRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val templateHtml: String by lazy {
        context.assets.open("templates/renderer.html").bufferedReader().use { it.readText() }
    }

    /** 渲染 Markdown 并分页绘制为 PDF 写入 [outFile]。 */
    suspend fun renderToPdf(markdown: String, outFile: File): File = withContext(Dispatchers.Main) {
        val webView = awaitRendered(markdown, layoutWidthPx = A4_WIDTH_PX)
        try {
            val fullHeight = layoutToContent(webView, A4_WIDTH_PX)
            writePdf(webView, A4_WIDTH_PX, fullHeight, outFile)
        } finally {
            destroy(webView)
        }
        outFile
    }

    /** 渲染 Markdown 并整页截成 PNG 写入 [outFile]。 */
    suspend fun renderToImage(markdown: String, outFile: File): File = withContext(Dispatchers.Main) {
        val webView = awaitRendered(markdown, layoutWidthPx = IMAGE_WIDTH_PX)
        try {
            val fullHeight = layoutToContent(webView, IMAGE_WIDTH_PX)
            val height = fullHeight.coerceIn(1, MAX_IMAGE_HEIGHT_PX)
            if (fullHeight > MAX_IMAGE_HEIGHT_PX) {
                android.util.Log.w("YuMarkExport", "长图超高($fullHeight)，已截断至 $MAX_IMAGE_HEIGHT_PX px")
            }
            val bitmap = Bitmap.createBitmap(IMAGE_WIDTH_PX, height, Bitmap.Config.RGB_565)
            Canvas(bitmap).apply { drawColor(Color.WHITE); webView.draw(this) }
            withContext(Dispatchers.IO) {
                outFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        } finally {
            destroy(webView)
        }
        outFile
    }

    /**
     * 渲染 Markdown 为**自包含富 HTML 字符串**（与预览一致：Prism 代码高亮 + KaTeX 公式 + Mermaid SVG）。
     *
     * 复用预览 WebView 管线（marked.js + Prism + KaTeX + Mermaid）渲染，再抽取 `#content` 的 innerHTML，
     * 把 KaTeX CSS（含 woff2 字体转 data URI）、Prism CSS 与基础排版样式内联进 `<style>`，
     * 生成不依赖 android_asset 的可移植 HTML。解决"导出走 commonmark、预览走 marked.js"的双轨分叉。
     *
     * 写入 [outFile] 并返回。所有 WebView 操作在主线程。
     */
    suspend fun renderToRichHtml(markdown: String, outFile: File): File {
        val html = renderToRichHtmlString(markdown)
        withContext(Dispatchers.IO) {
            outFile.writeText(html)
        }
        return outFile
    }

    private suspend fun renderToRichHtmlString(markdown: String): String = withContext(Dispatchers.Main) {
        val webView = awaitRendered(markdown, layoutWidthPx = A4_WIDTH_PX)
        try {
            val innerHtml = awaitContentInnerHtml(webView)
            val katexCss = inlineFontDataUris(readAsset("raw/katexcss.css"), "raw/fonts")
            val prismCss = readAsset("raw/prism.css")
            buildStandaloneHtml(innerHtml, katexCss, prismCss)
        } finally {
            destroy(webView)
        }
    }

    /** 抽取渲染后 `#content` 的 innerHTML（JSON 字符串解码）。 */
    private suspend fun awaitContentInnerHtml(webView: WebView): String =
        suspendCancellableCoroutine { cont ->
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            webView.evaluateJavascript(
                "(function(){var el=document.getElementById('content');return el?el.innerHTML:'';})();"
            ) { result ->
                // evaluateJavascript 回传的是 JSON 编码字符串（含引号与转义），需解码
                val decoded = result?.let { decodeJsonString(it) } ?: ""
                if (cont.isActive) cont.resume(decoded)
            }
            // 兜底超时
            handler.postDelayed({
                if (cont.isActive) cont.resume("")
            }, EXTRACT_TIMEOUT_MS)
            cont.invokeOnCancellation { /* webView 由调用方销毁 */ }
        }

    /** 把 CSS 中的 url(fonts/xxx.woff2) 替换为 base64 data URI，使 CSS 自包含。 */
    private fun inlineFontDataUris(css: String, fontsDir: String): String {
        val fontRef = Regex("""url\(\s*(?:fonts/)?([^)]+\.woff2)\s*\)""")
        return fontRef.replace(css) { m ->
            val fontName = m.groupValues[1].substringAfterLast('/')
            val fontBytes = runCatching { context.assets.open("$fontsDir/$fontName").use { it.readBytes() } }.getOrNull()
            if (fontBytes != null) "url(data:font/woff2;base64,${android.util.Base64.encodeToString(fontBytes, android.util.Base64.NO_WRAP)})"
            else m.value
        }
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    /** evaluateJavascript 回传的 JSON 字符串解码（去外层引号 + 反转义）。 */
    private fun decodeJsonString(raw: String): String {
        if (raw.isBlank() || raw == "null") return ""
        val s = if (raw.startsWith("\"") && raw.endsWith("\"")) raw.substring(1, raw.length - 1) else raw
        return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\/", "/")
            .replace("\\t", "\t").replace("\\\\", "\\")
    }

    private fun buildStandaloneHtml(bodyInnerHtml: String, katexCss: String, prismCss: String): String {
        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="generator" content="YuMark">
<style>
$katexCss
$prismCss
$BASE_RICH_CSS
</style>
</head>
<body>
$bodyInnerHtml
</body>
</html>""".trimIndent()
    }

    private val BASE_RICH_CSS = """
        body { max-width: 800px; margin: 0 auto; padding: 20px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; line-height: 1.6; color: #333; background: #fff; }
        img { max-width: 100%; height: auto; }
        table { border-collapse: collapse; width: 100%; margin: 12px 0; }
        th, td { border: 1px solid #dfe2e5; padding: 6px 13px; }
        th { background: #f6f8fa; }
        blockquote { border-left: 4px solid #dfe2e5; padding: 0 16px; color: #6a737d; margin: 0 0 16px 0; }
    """.trimIndent()

    /** 创建离屏 WebView，加载模板→渲染 markdown→等待异步渲染收敛，返回已渲染的 WebView。 */
    private suspend fun awaitRendered(markdown: String, layoutWidthPx: Int): WebView =
        suspendCancellableCoroutine { cont ->
            // 离屏 WebView 未 attach 到窗口，View.post 的 Runnable 会被推迟到 attach 才执行（永不执行）。
            // 因此一律用主线程 Handler 调度，而非 webView.post。
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val webView = WebView(context)
            webView.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = true            // 读取 file:///android_asset 资源
                loadsImagesAutomatically = true
            }
            webView.setBackgroundColor(Color.WHITE)
            // 先按导出宽度建立视口，确保内容按该宽度重排。
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(layoutWidthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(INITIAL_LAYOUT_HEIGHT_PX, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, layoutWidthPx, INITIAL_LAYOUT_HEIGHT_PX)

            var done = false
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onReady() {
                    handler.post {
                        val arg = JSONObject.quote(markdown)
                        webView.evaluateJavascript("window.renderMarkdown($arg);", null)
                        handler.postDelayed({
                            if (!done && cont.isActive) { done = true; cont.resume(webView) }
                        }, RENDER_WAIT_MS)
                    }
                }
                // renderer.html 用 `if (window.Android && Android.xxx)` 守卫，空实现即可。
                @JavascriptInterface fun log(message: String) {}
                @JavascriptInterface fun onOutline(json: String) {}
                @JavascriptInterface fun resetZoom() {}
            }, "Android")
            webView.webViewClient = WebViewClient()
            webView.loadDataWithBaseURL("file:///android_asset/", templateHtml, "text/html", "UTF-8", null)

            // 兜底：onReady 始终未到时超时失败，避免协程悬挂。
            handler.postDelayed({
                if (!done && cont.isActive) {
                    done = true
                    // 超时走异常返回，调用方的 finally{destroy} 尚未进入，需在此销毁离屏 WebView 防泄漏。
                    destroy(webView)
                    cont.resumeWithException(IOException("渲染超时：页面未就绪"))
                }
            }, READY_TIMEOUT_MS)

            cont.invokeOnCancellation { handler.post { destroy(webView) } }
        }

    /** 按内容全高重新测量并布局，返回内容像素高度。 */
    private fun layoutToContent(webView: WebView, widthPx: Int): Int {
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = webView.measuredHeight.coerceAtLeast(1)
        webView.layout(0, 0, widthPx, h)
        return h
    }

    /** 把已布局 WebView 按 A4 比例分页画进 PDF。 */
    private fun writePdf(webView: WebView, widthPx: Int, fullHeight: Int, outFile: File) {
        val pageHeight = (widthPx * A4_RATIO).roundToInt()
        val pageCount = ceil(fullHeight.toDouble() / pageHeight).toInt().coerceAtLeast(1)
        val pdf = PdfDocument()
        try {
            for (i in 0 until pageCount) {
                val pageInfo = PdfDocument.PageInfo.Builder(widthPx, pageHeight, i + 1).create()
                val page = pdf.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                canvas.save()
                canvas.translate(0f, (-i * pageHeight).toFloat()) // 平移到本页对应内容段
                webView.draw(canvas)                               // 画布裁剪到本页区域
                canvas.restore()
                pdf.finishPage(page)
            }
            outFile.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun destroy(webView: WebView) {
        runCatching {
            webView.removeJavascriptInterface("Android")
            webView.destroy()
        }
    }

    companion object {
        /** 渲染就绪后等待异步渲染（KaTeX/Mermaid）收敛的时间。 */
        private const val RENDER_WAIT_MS = 1500L
        /** onReady 始终未到的兜底超时。 */
        private const val READY_TIMEOUT_MS = 12_000L
        /** 渲染期初始布局高度（仅用于建立视口宽度）。 */
        private const val INITIAL_LAYOUT_HEIGHT_PX = 2000
        /** PDF 渲染宽度（A4 @约150dpi）。 */
        private const val A4_WIDTH_PX = 1240
        /** A4 高宽比（≈ √2）。 */
        private const val A4_RATIO = 1.4142
        /** 长图导出宽度（主流手机宽度）。 */
        private const val IMAGE_WIDTH_PX = 1080
        /** 长图最大高度，超出截断（防 OOM）。 */
        private const val MAX_IMAGE_HEIGHT_PX = 16000
        /** 抽取 innerHTML 的兜底超时。 */
        private const val EXTRACT_TIMEOUT_MS = 3_000L
    }
}
