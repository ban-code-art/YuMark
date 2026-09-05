package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.AgentActionType
import org.junit.jupiter.api.Test

class ParseAgentActionTest {

    @Test
    fun `returns null when no action block`() {
        val text = "这是一个普通回复，没有任何操作。"
        assertThat(parseAgentAction(text, currentDocumentId = null)).isNull()
    }

    @Test
    fun `parses CREATE_DOCUMENT action`() {
        val text = """
            好的，我帮你创建一篇文档。

            [[ACTION]]
            type: CREATE_DOCUMENT
            description: 创建一篇关于 Kotlin 的笔记
            [[CONTENT]]
            # Kotlin 笔记

            这是正文内容。
            [[/ACTION]]
        """.trimIndent()

        val action = parseAgentAction(text, currentDocumentId = "doc-1")

        assertThat(action).isNotNull()
        assertThat(action!!.type).isEqualTo(AgentActionType.CREATE_DOCUMENT)
        assertThat(action.description).isEqualTo("创建一篇关于 Kotlin 的笔记")
        assertThat(action.content).contains("# Kotlin 笔记")
        // CREATE 不绑定目标文档
        assertThat(action.targetDocumentId).isNull()
    }

    @Test
    fun `EDIT_DOCUMENT binds current document id`() {
        val text = """
            [[ACTION]]
            type: EDIT_DOCUMENT
            description: 补充结论段落
            [[CONTENT]]
            更新后的完整内容
            [[/ACTION]]
        """.trimIndent()

        val action = parseAgentAction(text, currentDocumentId = "doc-42")

        assertThat(action).isNotNull()
        assertThat(action!!.type).isEqualTo(AgentActionType.EDIT_DOCUMENT)
        assertThat(action.targetDocumentId).isEqualTo("doc-42")
    }

    @Test
    fun `EDIT_DOCUMENT without open document degrades to CREATE_DOCUMENT`() {
        val text = """
            [[ACTION]]
            type: EDIT_DOCUMENT
            description: 把这篇文档改得更有条理
            [[CONTENT]]
            # 周报模板

            ## 本周进展
            [[/ACTION]]
        """.trimIndent()

        val action = parseAgentAction(text, currentDocumentId = null)

        assertThat(action).isNotNull()
        // 没有可编辑的目标时不能留下一张点了就报「操作失败」的死卡片
        assertThat(action!!.type).isEqualTo(AgentActionType.CREATE_DOCUMENT)
        assertThat(action.targetDocumentId).isNull()
        // 标题取正文首个 Markdown 标题，而不是"把这篇文档改得更有条理"这种编辑指令
        assertThat(action.description).isEqualTo("周报模板")
        assertThat(action.content).contains("## 本周进展")
    }

    @Test
    fun `degraded title falls back to first non-blank line`() {
        val text = """
            [[ACTION]]
            type: EDIT_DOCUMENT
            description: 重写
            [[CONTENT]]
            没有标题行的纯段落正文。

            第二段。
            [[/ACTION]]
        """.trimIndent()

        val action = parseAgentAction(text, currentDocumentId = null)

        assertThat(action!!.description).isEqualTo("没有标题行的纯段落正文。")
    }

    @Test
    fun `returns null when content is empty`() {
        val text = """
            [[ACTION]]
            type: CREATE_DOCUMENT
            description: 空内容
            [[CONTENT]]
            [[/ACTION]]
        """.trimIndent()

        assertThat(parseAgentAction(text, currentDocumentId = null)).isNull()
    }

    @Test
    fun `returns null when action block is unterminated`() {
        val text = """
            [[ACTION]]
            type: CREATE_DOCUMENT
            description: 没有结束标记
            [[CONTENT]]
            一些内容但没有闭合
        """.trimIndent()

        assertThat(parseAgentAction(text, currentDocumentId = null)).isNull()
    }
}
