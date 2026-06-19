package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuildWriteProposalUseCaseTest {

    private val loadDocument: LoadDocumentUseCase = mockk()
    private val useCase = BuildWriteProposalUseCase(loadDocument)

    private fun doc(text: String): Document = mockk { every { content } returns text }

    @Test
    fun `create proposal carries content and title`() = runTest {
        val call = ToolCall("c1", "create_document", """{"title":"我的文档","content":"# 正文\n内容"}""")
        val action = useCase(call, currentDocumentId = null).getOrThrow()
        assertThat(action.type).isEqualTo(AgentActionType.CREATE_DOCUMENT)
        assertThat(action.description).isEqualTo("我的文档")
        assertThat(action.content).contains("# 正文")
    }

    @Test
    fun `edit applies surgical edits to base content`() = runTest {
        coEvery { loadDocument("doc-1") } returns Result.success(doc("# 标题\n旧句子。"))
        val call = ToolCall(
            "c1", "edit_document",
            """{"document_id":"doc-1","edits":[{"old_string":"旧句子。","new_string":"新句子。"}]}"""
        )
        val action = useCase(call, currentDocumentId = null).getOrThrow()
        assertThat(action.type).isEqualTo(AgentActionType.EDIT_DOCUMENT)
        assertThat(action.targetDocumentId).isEqualTo("doc-1")
        assertThat(action.content).isEqualTo("# 标题\n新句子。")
    }

    @Test
    fun `edit defaults to current document when id omitted`() = runTest {
        coEvery { loadDocument("cur") } returns Result.success(doc("foo"))
        val call = ToolCall("c1", "edit_document", """{"edits":[{"old_string":"foo","new_string":"bar"}]}""")
        val action = useCase(call, currentDocumentId = "cur").getOrThrow()
        assertThat(action.targetDocumentId).isEqualTo("cur")
        assertThat(action.content).isEqualTo("bar")
    }

    @Test
    fun `edit fails when old_string not found`() = runTest {
        coEvery { loadDocument("doc-1") } returns Result.success(doc("hello"))
        val call = ToolCall(
            "c1", "edit_document",
            """{"document_id":"doc-1","edits":[{"old_string":"missing","new_string":"x"}]}"""
        )
        val r = useCase(call, currentDocumentId = null)
        assertThat(r.isFailure).isTrue()
        assertThat(r.exceptionOrNull()!!.message).contains("read_document")
    }

    @Test
    fun `edit fails when no target document`() = runTest {
        val call = ToolCall("c1", "edit_document", """{"edits":[{"old_string":"a","new_string":"b"}]}""")
        val r = useCase(call, currentDocumentId = null)
        assertThat(r.isFailure).isTrue()
        assertThat(r.exceptionOrNull()!!.message).contains("缺少目标文档")
    }
}
