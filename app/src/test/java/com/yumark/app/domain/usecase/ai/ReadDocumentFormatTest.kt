package com.yumark.app.domain.usecase.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [formatDocumentForTool] 是 `read_document` 交给模型的那份正文。
 *
 * 这里钉住的核心不变量只有一条：**表头之后的部分与磁盘上的正文逐字节相同**。
 * 之前那版用 `"""…$content…""".trimIndent()` 拼，`trimIndent()` 跑在插值之后、按最小公共缩进
 * 裁整串，于是正文的形状反过来决定模板被怎么裁——两个方向都会让模型读到一份与文件不同的正文，
 * 而它正是照这份正文提 `edit_document` 的 `old_string` 的。
 */
class ReadDocumentFormatTest {

    @Test
    fun `header lines carry no leading whitespace`() {
        val out = formatDocumentForTool("笔记", null, "正文")
        assertThat(out.lineSequence().take(3).toList()).containsExactly(
            "【文档名称】笔记",
            "【文档路径】根目录",
            "【文档内容】"
        ).inOrder()
    }

    @Test
    fun `a folder id is reported as the path and null means root`() {
        assertThat(formatDocumentForTool("a", "f-1", "x")).contains("【文档路径】f-1")
        assertThat(formatDocumentForTool("a", null, "x")).contains("【文档路径】根目录")
    }

    /**
     * 顶格开头的多行文档：旧写法的最小公共缩进是 0，于是一个字符都不裁，模板的 12 个空格全部
     * 留下，正文第一行还额外顶着这 12 个空格——`# 标题` 在 Markdown 里变成缩进代码块。
     */
    @Test
    fun `a flush-left heading is not turned into an indented code block`() {
        val content = "# 标题\n\n第一段\n第二段"
        val out = formatDocumentForTool("笔记", null, content)
        assertThat(out).contains("【文档内容】\n# 标题")
        assertThat(out).doesNotContain("    # 标题")
    }

    /**
     * 反方向：整篇都缩进的文档（整个文件是一个缩进代码块）。旧写法的最小公共缩进落在正文自己
     * 身上，`trimIndent()` 把这份缩进从**正文**上剥掉，代码块直接不再是代码块。
     */
    @Test
    fun `an entirely indented document keeps its own indentation`() {
        val content = "    fun main() {\n        println(1)\n    }"
        val out = formatDocumentForTool("code", null, content)
        assertThat(out).endsWith(content)
    }

    /** 总不变量：表头之后就是原正文，逐字节一致。 */
    @Test
    fun `the body after the header is byte-identical to the input`() {
        val bodies = listOf(
            "",
            "单行",
            "# 标题\n正文",
            "    缩进正文\n\t制表符正文",
            "行尾有空格   \n下一行",
            "结尾换行\n",
            "\n开头空行"
        )
        for (content in bodies) {
            val out = formatDocumentForTool("n", "f", content)
            assertThat(out.substringAfter("【文档内容】\n")).isEqualTo(content)
        }
    }

    /** 空文档不该在正文位置凭空多出空白。 */
    @Test
    fun `an empty document ends right after the content header`() {
        assertThat(formatDocumentForTool("n", null, "")).isEqualTo(
            "【文档名称】n\n【文档路径】根目录\n【文档内容】\n"
        )
    }
}
