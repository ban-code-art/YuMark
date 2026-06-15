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
