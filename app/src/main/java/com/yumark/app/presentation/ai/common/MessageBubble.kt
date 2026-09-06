package com.yumark.app.presentation.ai.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
import com.yumark.app.presentation.theme.AppSpacing
import kotlinx.coroutines.delay

/** 聊天/Agent 通用消息气泡。 */
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    extraContent: @Composable (() -> Unit)? = null
) {
    val isUser = message.role == MessageRole.USER
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    // 气泡描边是装饰性容器边界（M3 的 outlineVariant），不是高强调控件轮廓（outline）；
    // 与 AgentTimeline 连接线、AppShell 分隔线同档，随主题 outline 对比修正一起收敛到 outlineVariant。
    val bubbleBorder = MaterialTheme.colorScheme.outlineVariant

    // 入场动画：alpha + 轻微上移，按 id 跑一次。
    // 仅用户气泡（纯文本）套 graphicsLayer——AI 气泡内是 WebView，放进带 alpha 的硬件图层
    // 会渲染成空白，故 AI 气泡不加该图层。
    val appear = remember(message.id) { Animatable(0f) }
    LaunchedEffect(message.id) { if (isUser) appear.animateTo(1f, tween(MessageBubbleMetrics.AppearDurationMs)) }
    val appearModifier = if (isUser) {
        Modifier.graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * MessageBubbleMetrics.AppearSlide.toPx()
        }
    } else Modifier

    val shape = RoundedCornerShape(
        topStart = AiDesign.BubbleCorner,
        topEnd = AiDesign.BubbleCorner,
        bottomStart = if (isUser) AiDesign.BubbleCorner else AiDesign.BubbleTailCorner,
        bottomEnd = if (isUser) AiDesign.BubbleTailCorner else AiDesign.BubbleCorner
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(appearModifier),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = AiDesign.BubbleMaxWidth)
                .clip(shape)
                .background(bubbleColor)
                .then(if (isUser) Modifier else Modifier.border(MessageBubbleMetrics.BorderWidth, bubbleBorder, shape))
                .padding(horizontal = AppSpacing.Cozy, vertical = AppSpacing.Default)
        ) {
            val shown = message.content.ifBlank { if (message.isStreaming) "▍" else "" }
            if (shown.isNotEmpty()) {
                // AI 助手消息使用 Markdown 渲染，用户消息保持纯文本
                if (isUser) {
                    Text(shown, color = textColor, style = MaterialTheme.typography.bodyMedium)
                } else {
                    MarkdownRenderedText(
                        markdown = shown,
                        isStreaming = message.isStreaming,
                        backgroundColor = bubbleColor,
                        textColor = textColor
                    )
                }
            }
            extraContent?.invoke()
        }
    }
}

/**
 * 在 WebView 中渲染 Markdown（用于 AI 助手消息气泡）。
 *
 * 渲染节流：流式期间每个 token 都会改变 [markdown]，若每次都全量 `evaluateJavascript`
 * 重新 `innerHTML`，WebView 会整块闪烁。改为：流式中按固定间隔（~120ms）合并渲染一次，
 * 流式结束后强制再渲染一次最终全文，保证内容完整。marked.js 无增量 patch 能力，
 * 单帧仍是全量替换，但频率降到肉眼不闪。
 */
@Composable
private fun MarkdownRenderedText(
    markdown: String,
    isStreaming: Boolean,
    backgroundColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color
) {
    val context = LocalContext.current
    val isDarkMode = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // 转换颜色为 CSS 格式
    val bgColorHex = backgroundColor.toArgb().let {
        "#%02X%02X%02X".format(
            android.graphics.Color.red(it),
            android.graphics.Color.green(it),
            android.graphics.Color.blue(it)
        )
    }
    val textColorHex = textColor.toArgb().let {
        "#%02X%02X%02X".format(
            android.graphics.Color.red(it),
            android.graphics.Color.green(it),
            android.graphics.Color.blue(it)
        )
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var isReady by remember { mutableStateOf(false) }
    // 上次已渲染的内容，避免对相同 markdown 重复 evaluateJavascript
    var lastRendered by remember { mutableStateOf("") }
    // 实例代号：渲染进程消失后这个 WebView 永久不可用，自增此值让 key() 重建整块 AndroidView。
    // 气泡不需要用户点重试——一条消息的重渲成本极低，直接静默自愈。
    var webViewGeneration by remember { mutableIntStateOf(0) }

    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <!--
              CSP：script-src 无 'unsafe-inline'/'unsafe-eval'，模型输出里的内联脚本与 on* 属性
              全部失效；connect-src 'none' 断掉任何外传通道。渲染逻辑因此必须留在
              raw/bubble.js，不能内联回本模板。style-src 需要 'unsafe-inline'（本模板内联主题色）。
            -->
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src file:; style-src file: 'unsafe-inline'; img-src file: blob: data: https: http:; font-src file: data:; connect-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'">
            <meta name="referrer" content="no-referrer">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <!-- purify → ym-sanitize → marked 顺序固定：净化器必须先于渲染逻辑就绪 -->
            <script src="file:///android_asset/raw/purify.js"></script>
            <script src="file:///android_asset/raw/ym-sanitize.js"></script>
            <script src="file:///android_asset/raw/markedjs.js"></script>
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Color Emoji", "Apple Color Emoji", "Segoe UI Emoji", sans-serif;
                    font-size: 14px;
                    line-height: 1.5;
                    color: $textColorHex;
                    background: $bgColorHex;
                    padding: 0;
                    word-wrap: break-word;
                }
                p { margin: 0.4em 0; }
                p:first-child { margin-top: 0; }
                p:last-child { margin-bottom: 0; }
                h1, h2, h3, h4, h5, h6 { margin: 0.6em 0 0.4em 0; font-weight: 600; }
                h1 { font-size: 1.4em; }
                h2 { font-size: 1.3em; }
                h3 { font-size: 1.2em; }
                h4, h5, h6 { font-size: 1.1em; }
                code {
                    font-family: 'Courier New', 'Noto Color Emoji', 'Apple Color Emoji', 'Segoe UI Emoji', monospace;
                    background: ${if (isDarkMode) "#2E2E2C" else "#F0F0F0"};
                    color: ${if (isDarkMode) "#E0DED6" else "#333333"};
                    padding: 1px 4px;
                    border-radius: 3px;
                    font-size: 0.9em;
                }
                pre {
                    background: ${if (isDarkMode) "#2E2E2C" else "#F0F0F0"};
                    color: ${if (isDarkMode) "#E0DED6" else "#333333"};
                    padding: 8px;
                    border-radius: 4px;
                    overflow-x: auto;
                    margin: 0.5em 0;
                }
                pre code { background: transparent; padding: 0; }
                ul, ol { margin: 0.4em 0; padding-left: 1.5em; }
                li { margin: 0.2em 0; }
                strong { font-weight: 600; }
                em { font-style: italic; }
                a { color: ${if (isDarkMode) "#7FB2E5" else "#0066CC"}; text-decoration: none; }
                blockquote {
                    border-left: 3px solid ${if (isDarkMode) "#555" else "#CCC"};
                    padding-left: 0.8em;
                    margin: 0.5em 0;
                    color: ${if (isDarkMode) "#AAA" else "#666"};
                }
                table { border-collapse: collapse; width: 100%; margin: 0.5em 0; font-size: 0.9em; }
                th, td { border: 1px solid ${if (isDarkMode) "#444" else "#DDD"}; padding: 4px 6px; text-align: left; }
                th { background: ${if (isDarkMode) "#333" else "#F5F5F5"}; font-weight: 600; }
            </style>
        </head>
        <body>
            <div id="content"></div>
            <!-- 渲染逻辑外置：CSP 无 'unsafe-inline'，内联脚本会静默不执行 -->
            <script src="file:///android_asset/raw/bubble.js"></script>
        </body>
        </html>
    """.trimIndent()

    // 把内容渲染到 WebView 的唯一入口：base64 编码后调 JS 更新 innerHTML。
    fun renderTo(view: WebView, content: String) {
        val markdownBase64 = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        view.evaluateJavascript("window.updateContent('$markdownBase64')", null)
        lastRendered = content
    }

    // key 在实例代号上：渲染进程消失后必须整块重建，原地复用一个已死的 WebView 只会得到空白气泡
    key(webViewGeneration) {
    AndroidView(
        factory = {
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                settings.javaScriptEnabled = true
                // 气泡只渲染 android_asset 下的模板与本地库，无需读用户文件/内容提供者。
                // allowFileAccess=false 仍允许 file:///android_asset 与 file:///android_res，
                // 因此模板与 raw/*.js 不受影响，但模型输出无法再通过 file:// 读取应用私有目录。
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                // 没有 WebViewClient 时，气泡里的链接会就地导航，把气泡变成浏览器。
                // http/https 交给系统浏览器，其余方案（intent:/file:/javascript: 等）一律拦下。
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = handleBubbleUrl(context, request?.url?.toString())

                    @Deprecated("兼容 API < 24")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                        handleBubbleUrl(context, url)

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?
                    ): Boolean {
                        // 返回 false（默认）= 框架杀掉整个应用进程。长会话里几十个气泡各自持有一个
                        // WebView，系统在内存压力下回收渲染器是常态，绝不能因此丢掉整个对话。
                        // 重置渲染缓存，否则新实例就绪后守卫判定"内容没变"而不渲染，气泡留白。
                        isReady = false
                        lastRendered = ""
                        webViewGeneration++
                        return true
                    }
                }

                // 添加接口供 JavaScript 回调
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun onReady() {
                        isReady = true
                    }
                }, "Android")

                loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
                webView = this
            }
        },
        update = { view ->
            // WebView 就绪后，若有未渲染内容（如首帧或 isReady 刚翻为 true）立即渲染一次。
            if (isReady && markdown.isNotEmpty() && lastRendered != markdown) {
                renderTo(view, markdown)
            }
        },
        onRelease = { view ->
            // 离开组合时销毁 WebView，防止长会话累积几十个 WebView 常驻内存。
            view.removeJavascriptInterface("Android")
            view.destroy()
            // 身份守卫：key 重建时「新 factory 先跑还是旧 onRelease 先跑」没有保证，
            // 无条件清空会把刚建好的新实例引用抹掉，流式渲染随后全部落空。
            if (webView === view) {
                webView = null
                isReady = false
            }
        },
        modifier = Modifier.fillMaxWidth().wrapContentHeight()
    )
    }

    // 流式期间节流渲染：合并高频 token 为约每 120ms 一帧；流式结束强制渲染最终全文。
    // 非流式（历史消息直接展示）由上面的 update 块一次性渲染，不走节流。
    if (isStreaming) {
        LaunchedEffect(markdown, isReady) {
            if (!isReady || markdown.isEmpty()) return@LaunchedEffect
            delay(STREAM_RENDER_THROTTLE_MS)
            // delay 期间 markdown 可能已更新；以最新值为准渲染
            val current = markdown
            if (lastRendered != current) {
                webView?.let { renderTo(it, current) }
            }
        }
    } else {
        // 流式刚结束：确保最终全文已渲染（节流可能漏掉最后一帧）
        LaunchedEffect(markdown, isReady) {
            if (!isReady || markdown.isEmpty()) return@LaunchedEffect
            if (lastRendered != markdown) {
                webView?.let { renderTo(it, markdown) }
            }
        }
    }
}

/** 流式渲染节流间隔（毫秒）。低于此间隔的多次 token 合并为一帧。 */
private const val STREAM_RENDER_THROTTLE_MS = 120L

/**
 * 气泡内链接的统一出口。
 *
 * 返回 true 表示「本 WebView 不加载它」。仅 http/https 交给系统处理，
 * 其余（intent:、file:、content:、javascript:、data: 等）直接丢弃——模型输出是不可信内容，
 * 不能让它决定 WebView 导航到哪里。
 */
private fun handleBubbleUrl(context: Context, url: String?): Boolean {
    val target = url?.trim().orEmpty()
    if (target.isEmpty()) return true
    val uri = target.toUri()
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return true
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        Log.d("MessageBubble", "no handler for link: ${e.message}")
    }
    return true
}

/**
 * 本气泡特有的度量，刻意不并入全局 [AppSpacing]：非用户气泡的发丝描边宽度（1dp）、入场动画的
 * 上移距离与时长。描边是绘制轴、动画量是运动轴，均离散于间距标度，保留原像素与原时长、不硬凑。
 * 按 FileListMetrics 先例落屏幕局部。
 */
private object MessageBubbleMetrics {
    /** AI 气泡描边：1dp 发丝线，勾出气泡边界（用户气泡用实底、无描边）。 */
    val BorderWidth = 1.dp
    /** 用户气泡入场上移距离：alpha 淡入同时轻微上移，收束到 0。 */
    val AppearSlide = 8.dp
    /** 用户气泡入场动画时长（毫秒）。 */
    const val AppearDurationMs = 260
}
