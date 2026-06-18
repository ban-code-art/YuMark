package com.yumark.app.presentation.ai.common

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
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
    else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (isUser) 14.dp else 2.dp,
                        bottomEnd = if (isUser) 2.dp else 14.dp
                    )
                )
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
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

    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <script src="file:///android_asset/raw/markedjs.js"></script>
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
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
                    font-family: 'Courier New', monospace;
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
            <script>
                // 全局更新函数，供 Android 调用
                window.updateContent = function(base64Markdown) {
                    try {
                        if (typeof marked === 'undefined') {
                            document.getElementById('content').textContent = 'marked.js 未加载';
                            return;
                        }
                        marked.setOptions({ breaks: true, gfm: true });

                        var binaryStr = atob(base64Markdown);
                        var markdown = decodeURIComponent(escape(binaryStr));

                        // 直接渲染为 HTML
                        document.getElementById('content').innerHTML = marked.parse(markdown);
                    } catch(e) {
                        console.error('Render error:', e);
                    }
                };

                // 通知 Android WebView 已就绪
                window.onload = function() {
                    if (window.Android && window.Android.onReady) {
                        window.Android.onReady();
                    }
                };
            </script>
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

    AndroidView(
        factory = {
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                settings.javaScriptEnabled = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

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
            webView = null
            isReady = false
        },
        modifier = Modifier.fillMaxWidth().wrapContentHeight()
    )

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
