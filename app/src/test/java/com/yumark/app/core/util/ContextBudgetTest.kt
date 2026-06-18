package com.yumark.app.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ContextBudgetTest {

    @Test
    fun `outline extracts heading lines`() {
        val content = "# 标题1\n正文a\n## 小节\n正文b"
        val o = documentOutline(content)
        assertThat(o).contains("# 标题1")
        assertThat(o).contains("## 小节")
    }

    @Test
    fun `outline includes the first paragraph`() {
        val content = "# T\n这是开头段落"
        assertThat(documentOutline(content)).contains("这是开头段落")
    }

    @Test
    fun `outline falls back to content prefix when no headings`() {
        val content = "纯文本没有标题，只是一段内容。"
        assertThat(documentOutline(content)).contains("纯文本")
    }

    @Test
    fun `outline respects budget`() {
        val content = (1..200).joinToString("\n") { "# 标题 $it" }
        assertThat(documentOutline(content, budget = 100).length).isAtMost(101)
    }
}
