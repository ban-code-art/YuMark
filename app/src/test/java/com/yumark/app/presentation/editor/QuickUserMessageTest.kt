package com.yumark.app.presentation.editor

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [buildQuickUserMessage] 是划词 AI 每一轮真正发出去的那条 user 消息。
 *
 * 两条不变量：**选区逐字节原样进围栏**，且**围栏不会被选区自己关掉**。
 * 旧写法把选区插进缩进 16 的 `"""…"""` 再 `.trimIndent()`——多行顶格选区什么都不裁（围栏连
 * 16 个空格一起发出去），每行都缩进的选区反而被剥掉自己的缩进；处理模式下模型照剥过的文本
 * 改写、改写结果又替换回选区，用户的缩进一个来回就没了。
 */
class QuickUserMessageTest {

    @Test
    fun `a single-line selection produces the exact expected message`() {
        assertThat(buildQuickUserMessage("hello", "翻译成中文", QuickAiMode.AI_QUERY))
            .isEqualTo("选中的文本：\n```\nhello\n```\n\n我的问题：\n翻译成中文")
    }

    @Test
    fun `the two modes differ only in the label`() {
        val query = buildQuickUserMessage("s", "m", QuickAiMode.AI_QUERY)
        val edit = buildQuickUserMessage("s", "m", QuickAiMode.AGENT_EDIT)
        assertThat(query).contains("我的问题：")
        assertThat(query).doesNotContain("我的需求：")
        assertThat(edit).contains("我的需求：")
        assertThat(edit).doesNotContain("我的问题：")
        assertThat(edit.replace("我的需求：", "我的问题：")).isEqualTo(query)
    }

    /** 多行顶格选区：围栏必须自己顶格，不能带着模板缩进发出去。 */
    @Test
    fun `fences are flush left for a multi-line selection`() {
        val out = buildQuickUserMessage("第一行\n第二行\n第三行", "精简", QuickAiMode.AGENT_EDIT)
        assertThat(out).startsWith("选中的文本：\n```\n第一行")
        assertThat(out.lineSequence().filter { it.contains("```") }.toList())
            .containsExactly("```", "```")
    }

    /** 每行都缩进的选区（缩进代码块、嵌套列表）必须原样保留缩进。 */
    @Test
    fun `an entirely indented selection keeps its indentation`() {
        val selected = "    fun main() {\n        println(1)\n    }"
        assertThat(selectionOf(buildQuickUserMessage(selected, "加注释", QuickAiMode.AGENT_EDIT)))
            .isEqualTo(selected)
    }

    /** 总不变量：围栏之间取回来的就是原选区，逐字节一致。 */
    @Test
    fun `the selection round-trips byte-identical`() {
        val selections = listOf(
            "",
            "单行",
            "# 标题\n正文",
            "  两格缩进\n\t制表符",
            "行尾空格   \n下一行",
            "空行\n\n之间",
            "\n开头就是空行"
        )
        for (selected in selections) {
            assertThat(selectionOf(buildQuickUserMessage(selected, "m", QuickAiMode.AI_QUERY)))
                .isEqualTo(selected)
        }
    }

    /**
     * 选区本身带 ``` 代码块时，围栏必须加长。
     *
     * CommonMark 里闭合围栏只要不短于开启围栏就算闭合，所以等长的三反引号会被选区里那一行当场
     * 关掉，后半段选区漏到围栏外面，模型分不清哪里是原文、哪里是指令。
     */
    @Test
    fun `a selection containing a fence gets a longer outer fence`() {
        val selected = "前言\n```kotlin\nval x = 1\n```\n后记"
        val out = buildQuickUserMessage(selected, "解释", QuickAiMode.AI_QUERY)
        assertThat(out).startsWith("选中的文本：\n````\n")
        assertThat(selectionOf(out, fence = "````")).isEqualTo(selected)
    }

    /** 围栏长度是按最长连续反引号串算的，不是「有没有围栏」。 */
    @Test
    fun `the outer fence is always one backtick longer than the longest run`() {
        assertThat(buildQuickUserMessage("`inline`", "m", QuickAiMode.AI_QUERY))
            .startsWith("选中的文本：\n```\n")
        assertThat(buildQuickUserMessage("``a``", "m", QuickAiMode.AI_QUERY))
            .startsWith("选中的文本：\n```\n")
        assertThat(buildQuickUserMessage("````four````", "m", QuickAiMode.AI_QUERY))
            .startsWith("选中的文本：\n`````\n")
    }

    /** 用户那句话也可能是多行的，同样不能被缩进裁剪波及。 */
    @Test
    fun `a multi-line user message is appended verbatim`() {
        val message = "要求一\n要求二\n  要求三"
        val out = buildQuickUserMessage("s", message, QuickAiMode.AGENT_EDIT)
        assertThat(out).endsWith("我的需求：\n$message")
    }

    private fun selectionOf(message: String, fence: String = "```"): String =
        message.substringAfter("选中的文本：\n$fence\n").substringBeforeLast("\n$fence\n\n")
}
