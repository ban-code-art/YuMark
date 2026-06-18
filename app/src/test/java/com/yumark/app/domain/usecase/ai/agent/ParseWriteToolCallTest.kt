package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.ToolCall
import org.junit.jupiter.api.Test

class ParseWriteToolCallTest {

    @Test
    fun `parses create_document tool call`() {
        val call = ToolCall("c1", "create_document", """{"title":"新笔记","content":"# 新笔记\n正文"}""")
        val action = parseWriteToolCall(call, currentDocumentId = null)
        assertThat(action).isNotNull()
        assertThat(action!!.type).isEqualTo(AgentActionType.CREATE_DOCUMENT)
        assertThat(action.description).isEqualTo("新笔记")
        assertThat(action.content).contains("# 新笔记")
        assertThat(action.targetDocumentId).isNull()
    }

    @Test
    fun `parses edit_document tool call with explicit target id`() {
        val call = ToolCall("c2", "edit_document", """{"document_id":"doc-9","new_content":"改后内容"}""")
        val action = parseWriteToolCall(call, currentDocumentId = "current-doc")
        assertThat(action!!.type).isEqualTo(AgentActionType.EDIT_DOCUMENT)
        assertThat(action.targetDocumentId).isEqualTo("doc-9")
        assertThat(action.content).isEqualTo("改后内容")
    }

    @Test
    fun `edit_document without document_id falls back to current document`() {
        val call = ToolCall("c3", "edit_document", """{"new_content":"x"}""")
        val action = parseWriteToolCall(call, currentDocumentId = "current-doc")
        assertThat(action!!.targetDocumentId).isEqualTo("current-doc")
    }

    @Test
    fun `edit_document with explicit target does not use current document`() {
        val call = ToolCall("c6", "edit_document", """{"document_id":"doc-9","new_content":"x"}""")
        val action = parseWriteToolCall(call, currentDocumentId = "current-doc")
        assertThat(action!!.targetDocumentId).isEqualTo("doc-9")
    }

    @Test
    fun `returns null for a read-only tool`() {
        val call = ToolCall("c4", "read_document", """{"document_id":"x"}""")
        assertThat(parseWriteToolCall(call, currentDocumentId = null)).isNull()
    }

    @Test
    fun `returns null on malformed arguments`() {
        val call = ToolCall("c5", "create_document", "not json")
        assertThat(parseWriteToolCall(call, currentDocumentId = null)).isNull()
    }
}
