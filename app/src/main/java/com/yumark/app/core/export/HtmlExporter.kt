package com.yumark.app.core.export

import android.content.Context
import com.yumark.app.core.validation.FileNameValidator
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.ExportOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 纯 HTML 导出：commonmark 直接渲染，**不开 WebView**。
 *
 * 因此 `renderer.js` 那套相对图片解析在这条路上永远不会执行，图片必须在 Kotlin 侧自己走完
 * 两遍改写（见 [buildHtml]）——从前这里把 `![](images/x.png)` 原样写进产物，导出的 .html
 * 里那张图必然是裂的。
 */
@Singleton
class HtmlExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localImageBytes: LocalImageBytes
) {
    // Commonmark 解析器，支持 GFM (GitHub Flavored Markdown) 扩展
    private val parser: Parser by lazy {
        Parser.builder()
            .extensions(
                listOf(
                    TablesExtension.create(),           // 表格支持
                    StrikethroughExtension.create(),    // 删除线支持
                    TaskListItemsExtension.create()     // 任务列表支持
                )
            )
            .build()
    }

    // HTML 渲染器
    private val renderer: HtmlRenderer by lazy {
        HtmlRenderer.builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListItemsExtension.create()
                )
            )
            .build()
    }

    /**
     * @param imageResolver 相对图片引用的解析基址；null = 正文里的相对引用原样保留（仍会内联
     *   已是绝对地址的本地图片，见 [buildHtml]）。
     */
    fun export(
        document: Document,
        options: ExportOptions,
        imageResolver: ExportImageResolver? = null
    ): Result<File> = runCatching {
        val html = buildHtml(document, imageResolver)
        // 文件名消毒：与 Markdown 导出路径保持同一防线
        val safeName = FileNameValidator.sanitize(document.name)
        val file = File(options.outputDir, "$safeName.html")
        file.writeText(html)
        file
    }

    private fun buildHtml(doc: Document, imageResolver: ExportImageResolver?): String {
        // 将 Markdown 解析为 AST。
        //
        // 先过 normalizeImageTargets：不带 `<>` 的链接目标含未编码空格时 CommonMark 不判定为图片，
        // Typora/Windows 风格的 `![图](images\my pic.png)` 会原样落成一行字面文字，下面那两遍
        // 图片改写连 `<img>` 都找不到（renderer.js 的「步骤2.5」在 WebView 那侧做的是同一件事）。
        val parsedDocument = parser.parse(normalizeImageTargets(doc.content))

        // 渲染为 HTML
        val rawContentHtml = renderer.render(parsedDocument)

        // 图片两遍改写，顺序不能换：
        //   1) 相对引用 → 绝对 URL（与 renderer.js 的 resolveImages() 同一套规则，见
        //      ExportImagePaths.kt）。属性值是 HTML 转义过的，先还原 `&amp;` 再解析，否则
        //      文件名里的 `&` 会被当成 `&amp;` 这五个字符去拼路径。
        //   2) 绝对本地 URL → base64 data URI，让 .html 自包含：导出件是要发给别人的，
        //      应用私有目录和 SAF 授权都不跟着文件走。这一遍与 imageResolver 无关——正文里
        //      手写的 `file://` / `content://` 也一样要内联。
        val resolved = rewriteImageSrc(rawContentHtml) { raw ->
            resolveExportImageSrc(raw.replace("&amp;", "&").trim(), imageResolver)
        }
        val contentHtml = inlineLocalImages(resolved, localImageBytes::read)

        // 构建完整的 HTML 文档
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<!-- commonmark 的 HtmlRenderer 默认 escapeHtml=false，正文里的原生 HTML（<br>、<details>、
     <kbd> 等）会原样透出——这是 Markdown 的既有语义，转义掉会让导出件与预览不一致，所以保留。
     代价是文档正文里的 <script>/onclick/javascript: 也会一并进入导出文件，因此用 CSP 把
     导出件的脚本执行面直接关掉：script-src 'none' 同时封住 <script>、内联事件处理器和
     javascript: URL。附加的 meta CSP 只能收紧不能放宽，正文里再塞一个 <meta> 也无法解除。
     不启用 commonmark 的 sanitizeUrls：它只放行 http/https/mailto，会把用户文档里合法的
     file:// 与 data: 图片引用一并抹掉。样式/图片/字体保持宽松，不影响观感。 -->
<meta http-equiv="Content-Security-Policy" content="default-src * data: blob: file:; script-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; style-src * data: 'unsafe-inline'">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="referrer" content="no-referrer">
<meta name="generator" content="YuMark">
<title>${escapeHtml(doc.name)}</title>
<style>
/* 基础样式 */
body {
    max-width: 800px;
    margin: 0 auto;
    padding: 20px;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    line-height: 1.6;
    color: #333;
    background: #fff;
}

/* 标题样式 */
h1, h2, h3, h4, h5, h6 {
    margin-top: 24px;
    margin-bottom: 16px;
    font-weight: 600;
    line-height: 1.25;
}
h1 { font-size: 2em; border-bottom: 1px solid #eee; padding-bottom: 0.3em; }
h2 { font-size: 1.5em; border-bottom: 1px solid #eee; padding-bottom: 0.3em; }
h3 { font-size: 1.25em; }
h4 { font-size: 1em; }
h5 { font-size: 0.875em; }
h6 { font-size: 0.85em; color: #6a737d; }

/* 段落和列表 */
p { margin-bottom: 16px; }
ul, ol { padding-left: 2em; margin-bottom: 16px; }
li { margin-bottom: 4px; }

/* 代码块 */
code {
    background: #f6f8fa;
    padding: 2px 6px;
    border-radius: 3px;
    font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
    font-size: 85%;
}
pre {
    background: #f6f8fa;
    padding: 16px;
    border-radius: 6px;
    overflow-x: auto;
    margin-bottom: 16px;
}
pre code {
    background: transparent;
    padding: 0;
    border-radius: 0;
}

/* 引用块 */
blockquote {
    border-left: 4px solid #dfe2e5;
    padding-left: 16px;
    color: #6a737d;
    margin: 0 0 16px 0;
}

/* 图片 */
img {
    max-width: 100%;
    height: auto;
    display: block;
    margin: 16px 0;
}

/* 链接 */
a {
    color: #0366d6;
    text-decoration: none;
}
a:hover {
    text-decoration: underline;
}

/* 表格 */
table {
    border-collapse: collapse;
    width: 100%;
    margin-bottom: 16px;
}
th, td {
    border: 1px solid #dfe2e5;
    padding: 8px 13px;
}
th {
    background: #f6f8fa;
    font-weight: 600;
}
tr:nth-child(even) {
    background: #f6f8fa;
}

/* 分隔线 */
hr {
    border: none;
    border-top: 1px solid #eee;
    margin: 24px 0;
}

/* 任务列表 */
input[type="checkbox"] {
    margin-right: 8px;
}

/* 删除线 */
del {
    color: #6a737d;
}

/* 打印样式 */
@media print {
    body {
        max-width: none;
    }
    a {
        color: #000;
        text-decoration: underline;
    }
}
</style>
</head>
<body>
$contentHtml
</body>
</html>
""".trimIndent()
    }

    /**
     * HTML 转义，防止 XSS
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }
}
