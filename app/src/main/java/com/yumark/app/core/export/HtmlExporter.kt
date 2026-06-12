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

@Singleton
class HtmlExporter @Inject constructor(
    @ApplicationContext private val context: Context
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

    fun export(document: Document, options: ExportOptions): Result<File> = runCatching {
        val html = buildHtml(document)
        // 文件名消毒：与 Markdown 导出路径保持同一防线
        val safeName = FileNameValidator.sanitize(document.name)
        val file = File(options.outputDir, "$safeName.html")
        file.writeText(html)
        file
    }

    private fun buildHtml(doc: Document): String {
        // 将 Markdown 解析为 AST
        val parsedDocument = parser.parse(doc.content)

        // 渲染为 HTML
        val contentHtml = renderer.render(parsedDocument)

        // 构建完整的 HTML 文档
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
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
