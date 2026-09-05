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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.yumark.app.R
import com.yumark.app.core.util.FriendlyIOException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.webview.WebViewFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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
 * 调 `renderMarkdown`，然后**轮询页面状态直到异步渲染收敛**（见 [RenderSettleTracker]）再导出——
 * 不是睡固定时长：慢设备上睡不够会导出空图，快设备上纯属白等。
 *
 * PDF 用 [PdfDocument] + 逐页把已布局 WebView 画到页面画布（按 A4 比例分页），既不依赖打印框架的
 * package-private 回调，也不需要生成整张超大位图。
 */
@Singleton
class WebViewDocumentRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localImageBytes: LocalImageBytes
) {
    private val templateHtml: String by lazy {
        context.assets.open("templates/renderer.html").bufferedReader().use { it.readText() }
    }

    /**
     * 渲染 Markdown 并分页绘制为 PDF 写入 [outFile]。
     *
     * [imageResolver] 是文档正文里**相对路径图片引用**的解析基址（导入库 / 外部工作区文档）。
     * 不传就只有绝对 `file://` / `content://` / 网络图片能出图：离屏页面的 baseURL 是
     * `file:///android_asset/`，`images/x.png` 会解析到 assets 里去，必然 404。
     */
    suspend fun renderToPdf(
        markdown: String,
        outFile: File,
        imageResolver: ExportImageResolver? = null
    ): File = withContext(Dispatchers.Main) {
        val webView = awaitRendered(markdown, A4_WIDTH_PX, imageResolver)
        try {
            val fullHeight = layoutToContent(webView, A4_WIDTH_PX)
            writePdf(webView, A4_WIDTH_PX, fullHeight, outFile)
        } finally {
            destroy(webView)
        }
        outFile
    }

    /**
     * 渲染 Markdown 并整页截成 PNG 写入 [outFile]。[imageResolver] 见 [renderToPdf]。
     *
     * 文档渲染高度超过 [MAX_IMAGE_HEIGHT_PX] 时**抛 [FriendlyIOException]**，不出图。
     * 从前这里是 `coerceIn` 截断 + 一条 `Log.w`，然后照样返回 [outFile]：调用侧拿到的是
     * `Result.success`，用户拿到的是一张在半句话处结束的长图，而**没有任何提示**——
     * 收图的人看不出后面还有内容，比导出失败有害得多。PDF 那条路是分页的、没有这个上限，
     * 所以文案直接指路 PDF（见 `export_image_too_tall`）。
     *
     * 上限本身保留：1080×16000 的 RGB_565 位图已经 34.5MB，再往上就是低端机上的 OOM。
     */
    suspend fun renderToImage(
        markdown: String,
        outFile: File,
        imageResolver: ExportImageResolver? = null
    ): File = withContext(Dispatchers.Main) {
        val webView = awaitRendered(markdown, IMAGE_WIDTH_PX, imageResolver)
        try {
            val fullHeight = layoutToContent(webView, IMAGE_WIDTH_PX)
            if (fullHeight > MAX_IMAGE_HEIGHT_PX) {
                throw FriendlyIOException(
                    UiMessage.of(R.string.export_image_too_tall, fullHeight, MAX_IMAGE_HEIGHT_PX)
                )
            }
            val bitmap = createBitmap(IMAGE_WIDTH_PX, fullHeight.coerceAtLeast(1), Bitmap.Config.RGB_565)
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
     * 本地图片（`file://` / `content://`）同样转成 base64 data URI 内联：导出件是要分享出去的，
     * 而应用私有目录与 SAF 授权都不跟着文件走，留原 URI 等于在别人的设备上必然裂图。
     *
     * 写入 [outFile] 并返回。所有 WebView 操作在主线程。
     */
    suspend fun renderToRichHtml(
        markdown: String,
        outFile: File,
        imageResolver: ExportImageResolver? = null
    ): File {
        val bodyHtml = extractRichBody(markdown, imageResolver)
        withContext(Dispatchers.IO) {
            val katexCss = inlineFontDataUris(readAsset("raw/katexcss.css"), "raw/fonts")
            val prismCss = readAsset("raw/prism.css")
            outFile.writeText(
                buildStandaloneHtml(
                    inlineLocalImages(bodyHtml, localImageBytes::read), katexCss, prismCss
                )
            )
        }
        return outFile
    }

    /** 渲染并抽取 `#content` 的 innerHTML（WebView 部分，必须在主线程）。 */
    private suspend fun extractRichBody(
        markdown: String,
        imageResolver: ExportImageResolver?
    ): String = withContext(Dispatchers.Main) {
        val webView = awaitRendered(markdown, A4_WIDTH_PX, imageResolver)
        try {
            awaitContentInnerHtml(webView)
        } finally {
            destroy(webView)
        }
    }

    /**
     * 抽取渲染后 `#content` 的 innerHTML。
     *
     * 抽不到（超时 / 回传值无法解码）一律抛异常，**不能**返回空串：那会让导出静默产出一个
     * 空文档，用户拿到手才发现，且现场已经销毁，无从复盘。
     */
    private suspend fun awaitContentInnerHtml(webView: WebView): String =
        suspendCancellableCoroutine { cont ->
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            webView.evaluateJavascript(
                "(function(){var el=document.getElementById('content');return el?el.innerHTML:null;})();"
            ) { result ->
                // evaluateJavascript 回传的是 JSON 编码字符串（含引号与转义），需解码
                val decoded = decodeEvaluateJavascriptString(result)
                if (!cont.isActive) return@evaluateJavascript
                if (decoded != null) cont.resume(decoded)
                else cont.resumeWithException(
                    FriendlyIOException(UiMessage.Res(R.string.webview_render_timeout))
                )
            }
            // 兜底超时：同样按失败出口走
            handler.postDelayed({
                if (cont.isActive) cont.resumeWithException(
                    FriendlyIOException(UiMessage.Res(R.string.webview_render_timeout))
                )
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

    private fun buildStandaloneHtml(bodyInnerHtml: String, katexCss: String, prismCss: String): String {
        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<!-- 导出产物本身不含任何脚本（DOMPurify 已剥离）：显式 script-src 'none' 固化这一点，
     使文件在桌面浏览器里被打开时也无法执行脚本。样式/图片/字体保持宽松，不影响观感。 -->
<meta http-equiv="Content-Security-Policy" content="default-src * data: blob: file:; script-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; style-src * data: 'unsafe-inline'">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="referrer" content="no-referrer">
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

    /**
     * 创建离屏 WebView，加载模板→注入图片解析基址→渲染 markdown→**轮询到异步渲染收敛**，
     * 返回已渲染的 WebView。
     *
     * 收敛判定见 [RenderSettleTracker]：KaTeX 与 Mermaid 都是异步的，Mermaid 还会先打上
     * `data-processed` 再去渲染（该属性是"开工"而非"完工"标记），所以只能按"页面里还有几个
     * 未出 svg 的 mermaid 块 + 高度是否还在变"来判断，不能睡固定时长。
     */
    private suspend fun awaitRendered(
        markdown: String,
        layoutWidthPx: Int,
        imageResolver: ExportImageResolver?
    ): WebView =
        suspendCancellableCoroutine { cont ->
            // 离屏 WebView 未 attach 到窗口，View.post 的 Runnable 会被推迟到 attach 才执行（永不执行）。
            // 因此一律用主线程 Handler 调度，而非 webView.post。
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val webView = WebView(context)
            webView.settings.apply {
                javaScriptEnabled = true
                // 注意：file:///android_asset 与 file:///android_res 即使 allowFileAccess=false 也可读，
                // 这里保持 true 是为了导出文档里用户手写的绝对 file:// 图片仍能出图。
                // 文档正文的脚本执行面已由 renderer.html 的 CSP + DOMPurify 关闭。
                allowFileAccess = true
                loadsImagesAutomatically = true
                domStorageEnabled = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }
            webView.setBackgroundColor(Color.WHITE)
            // 先按导出宽度建立视口，确保内容按该宽度重排。
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(layoutWidthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(INITIAL_LAYOUT_HEIGHT_PX, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, layoutWidthPx, INITIAL_LAYOUT_HEIGHT_PX)

            var done = false
            // onReady 到达后必须让 READY_TIMEOUT_MS 兜底失效：收敛轮询本身可以合法地跑到
            // SETTLE_TIMEOUT_MS，两个超时叠在同一条时间轴上会让一次健康的慢渲染被判失败。
            var readyReceived = false

            /**
             * 统一的失败出口：幂等（`done` 守卫）、先销毁离屏 WebView 再抛。
             *
             * 抛 [FriendlyIOException] 而不是裸 [java.io.IOException]：后者会被
             * `ErrorHandler.classify` 归成 `AppError.Storage`，用户看到的是"请检查存储空间"——
             * 而真实原因是渲染没起来/渲染进程被回收，指错方向比不说更糟。
             *
             * 文案里只能带原因，不能带"导出"前缀：调用链末端的
             * `EditorViewModel.exportAs` 会拼成"导出失败：<原因>"。
             */
            fun failRender(message: UiMessage) {
                if (done || !cont.isActive) return
                done = true
                // 调用方的 finally{destroy} 尚未进入，需在此销毁离屏 WebView 防泄漏。
                destroy(webView)
                cont.resumeWithException(FriendlyIOException(message))
            }

            /**
             * 成功出口：幂等，把已渲染的 WebView 交给调用方（由调用方 finally 销毁）。
             */
            fun succeed() {
                if (done || !cont.isActive) return
                done = true
                cont.resume(webView)
            }

            /**
             * 轮询页面探针直到渲染收敛。
             *
             * 探针脚本走 [WebView.evaluateJavascript] 注入而不是写在 renderer.html 里：模板的 CSP 是
             * `script-src file:`，内联 `<script>` 会被拦掉；而 evaluateJavascript 不受页面 CSP 约束
             * （预览页的 `window.renderMarkdown` 就是这么调进去的）。renderer.js 属只读文件，
             * 也不能在里面加完成回调。
             */
            fun pollSettle(tracker: RenderSettleTracker) {
                if (done || !cont.isActive) return
                webView.evaluateJavascript(RENDER_PROBE_JS) { raw ->
                    if (done || !cont.isActive) return@evaluateJavascript
                    when (val d = tracker.onProbe(parseRenderProbe(decodeEvaluateJavascriptString(raw)))) {
                        SettleDecision.Settled -> succeed()
                        // 软超时：页面已有内容就带着未完成的 mermaid 出图——缺一张图的 PDF
                        // 也比没有 PDF 好（与本文件对子资源失败的既有取舍一致）；
                        // 真正空白才算失败，否则用户拿到的是一张白纸。
                        SettleDecision.TimedOutWithContent -> succeed()
                        SettleDecision.TimedOutEmpty ->
                            failRender(UiMessage.Res(R.string.webview_render_timeout))
                        is SettleDecision.Wait -> handler.postDelayed({ pollSettle(tracker) }, d.delayMs)
                    }
                }
            }

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onReady() {
                    handler.post {
                        readyReceived = true
                        // 图片解析基址必须先于 renderMarkdown 注入：renderer.js 的 resolveImages()
                        // 是在渲染时按当前 resolver 改写 <img src> 的，晚一步注入等于这次渲染没有基址，
                        // 相对路径图片会解析到 file:///android_asset/ 下必然 404。
                        imageResolver?.let {
                            webView.evaluateJavascript("window.setImageResolver(${it.toJson()});", null)
                        }
                        val arg = JSONObject.quote(markdown)
                        webView.evaluateJavascript("window.renderMarkdown($arg);", null)
                        pollSettle(RenderSettleTracker(markdown.isNotBlank()))
                    }
                }
                // renderer.html 用 `if (window.Android && Android.xxx)` 守卫，空实现即可。
                // 参数必须保留：签名要与 JS 侧的调用一致，否则 @JavascriptInterface 找不到方法。
                @Suppress("UNUSED_PARAMETER")
                @JavascriptInterface fun log(message: String) {}
                @Suppress("UNUSED_PARAMETER")
                @JavascriptInterface fun onOutline(json: String) {}
                @JavascriptInterface fun resetZoom() {}
            }, "Android")
            // 离屏导出 WebView 只应停留在 loadDataWithBaseURL 的那一页：
            // 拒绝一切后续导航（返回 true = 不加载），避免文档内容把导出页面换走。
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean = true

                @Deprecated("兼容 API < 24")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    // 子资源（文档里的远端图片）失败不该让整次导出失败：缺图的 PDF 也比没有 PDF 好。
                    if (request?.isForMainFrame != true) return
                    failRender(WebViewFailure.loadMessage(error?.errorCode ?: WebViewFailure.ERROR_UNKNOWN))
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    // 返回 false 会让框架杀掉整个应用进程——导出一张大图正是最容易触发内存回收的场景，
                    // 决不能让"导出失败"升级成"应用闪退"。
                    failRender(WebViewFailure.renderProcessMessage(detail?.didCrash() == true))
                    return true
                }
            }
            webView.loadDataWithBaseURL("file:///android_asset/", templateHtml, "text/html", "UTF-8", null)

            // 兜底：onReady 始终未到时超时失败，避免协程悬挂。onReady 已到达则交给收敛轮询自己的超时。
            // 文案用逗号而非冒号：上层会拼成"导出失败：渲染超时，页面未就绪，请重试"。
            handler.postDelayed(
                { if (!readyReceived) failRender(UiMessage.Res(R.string.webview_render_timeout)) },
                READY_TIMEOUT_MS
            )

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

    /**
     * 把已布局 WebView 按 A4 分页画进 PDF。
     *
     * ### 单位：`PageInfo` 收的是**点**，不是像素
     * `PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber)` 的两个尺寸是
     * PostScript 点（1/72 英寸）。从前这里直接把渲染像素宽 [A4_WIDTH_PX]（1240，A4 @约150dpi）
     * 递进去，等于声明「页宽 1240 点 = 17.2 英寸 = 437 mm」—— 产物是一张**约 A2 大小**的页面。
     * 阅读器里看不出异常（矢量页面自适应窗口），但打印或转 A4 时整页被缩到约 48%，正文小到
     * 难以阅读，页边距也全错。
     *
     * 所以页面按真实 A4 点数声明（595×842），再把画布缩放 595/1240 ≈ 0.48，让 1240px 宽的
     * 渲染结果正好铺满 595pt 的页宽。缩放发生在绘制阶段而不是渲染阶段：WebView 仍按 1240px
     * 排版，文字与图片的相对布局不变，只是最终以更高的等效 DPI 落到页面上。
     */
    private fun writePdf(webView: WebView, widthPx: Int, fullHeight: Int, outFile: File) {
        // 像素 → 点 的换算比。按宽度定，高度跟着走，横向不会出现裁切或留白。
        val scale = A4_WIDTH_PT.toFloat() / widthPx
        // 每页承载的内容像素高度：由 A4 的点数反推，保证一页装下的内容量与真实 A4 一致
        val pageHeightPx = (A4_HEIGHT_PT / scale).roundToInt().coerceAtLeast(1)
        val pageCount = ceil(fullHeight.toDouble() / pageHeightPx).toInt().coerceAtLeast(1)
        val pdf = PdfDocument()
        try {
            for (i in 0 until pageCount) {
                val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, i + 1).create()
                val page = pdf.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                // withScale / withTranslation = save + 变换 + block + restoreToCount，
                // 异常路径也会还原画布状态，比手写 save()/restore() 稳。
                // 顺序：先缩放再平移，于是平移量仍按**内容像素**计，与分页切口同一坐标系。
                canvas.withScale(scale, scale) {
                    withTranslation(y = (-i * pageHeightPx).toFloat()) { // 平移到本页对应内容段
                        webView.draw(this)                               // 画布裁剪到本页区域
                    }
                }
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
        /**
         * 收敛探针：回传 `JSON.stringify({len,mermaid,images,height})`。
         *
         * - `mermaid`：还没长出 `<svg>` 的 mermaid 容器数。**不能**用 `data-processed` 判断完成——
         *   打包进 assets 的 mermaid 是先 `setAttribute("data-processed","true")` 再 `await` 渲染，
         *   那是"开工"标记，按它判定会在图还没画出来时就截图。
         * - `images`：`complete === false` 的 `<img>` 数。加载失败的图 `complete` 也会变 true，
         *   所以坏图不会把导出卡到超时。
         * - `height` 连续两次不变才算稳定：KaTeX/表格重排会让高度跳变，跳变期间分页会错位。
         */
        private const val RENDER_PROBE_JS = """
            (function(){
              var c=document.getElementById('content');
              var m=0,i=0;
              if(c){
                var ms=c.querySelectorAll('.mermaid,.language-mermaid,pre.mermaid');
                for(var k=0;k<ms.length;k++){ if(!ms[k].querySelector('svg')) m++; }
                var im=c.querySelectorAll('img');
                for(var k=0;k<im.length;k++){ if(im[k].complete!==true) i++; }
              }
              return JSON.stringify({
                len: c?c.innerHTML.length:0, mermaid:m, images:i,
                height: document.body?document.body.scrollHeight:0
              });
            })();
        """
        /** onReady 始终未到的兜底超时。 */
        private const val READY_TIMEOUT_MS = 12_000L
        /** 渲染期初始布局高度（仅用于建立视口宽度）。 */
        private const val INITIAL_LAYOUT_HEIGHT_PX = 2000
        /** PDF 渲染宽度（A4 @约150dpi）。 */
        private const val A4_WIDTH_PX = 1240
        /**
         * A4 页宽/页高，单位 **PostScript 点**（1/72 英寸）：210×297 mm = 595×842 pt。
         *
         * 这两个常量只给 [PdfDocument.PageInfo] 用 —— 那个 API 的尺寸单位是点而不是像素，
         * 递像素进去会得到一张 A2 大小的页面（详见 [writePdf]）。
         */
        private const val A4_WIDTH_PT = 595
        private const val A4_HEIGHT_PT = 842
        /** 长图导出宽度（主流手机宽度）。 */
        private const val IMAGE_WIDTH_PX = 1080
        /**
         * 长图最大高度。**超出即拒绝导出**（[renderToImage] 抛 [FriendlyIOException]），不再截断。
         *
         * 上限的由来是内存而非美观：1080 × 16000 的 RGB_565 位图是 34.5MB，
         * 低端机上再往上就是 OOM。改这个数要连带算一遍这个乘积。
         */
        private const val MAX_IMAGE_HEIGHT_PX = 16000
        /** 抽取 innerHTML 的兜底超时。 */
        private const val EXTRACT_TIMEOUT_MS = 3_000L
    }
}
