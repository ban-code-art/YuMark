package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.AgentEvidenceType
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.repository.AgentTaskRepository
import com.yumark.app.domain.usecase.ai.ExecuteDocumentToolUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ExecuteAgentTaskUseCaseTest {

    private val repository: AgentTaskRepository = mockk(relaxed = true)
    private val executeDocumentTool: ExecuteDocumentToolUseCase = mockk()

    private val useCase = ExecuteAgentTaskUseCase(
        repository = repository,
        executeDocumentTool = executeDocumentTool
    ).apply {
        nowProvider = { 100L }
        idProvider = { "evidence-1" }
    }

    @Test
    fun `successful read tool marks step done and records document evidence`() = runTest {
        val call = ToolCall("call-1", "read_document", """{"document_id":"doc-1"}""")
        coEvery { executeDocumentTool(call) } returns Result.success("document body")

        val result = useCase(task(), step(), listOf(call), currentDocumentId = "doc-1").getOrThrow()

        assertThat(result.outcome).isEqualTo(AgentStepExecutionOutcome.STEP_DONE)
        assertThat(result.toolResults.single().content).isEqualTo("document body")
        coVerify { repository.markStepStatus("step-1", AgentTaskStepStatus.RUNNING, null) }
        coVerify {
            repository.appendEvidence(match {
                it.id == "evidence-1" &&
                    it.type == AgentEvidenceType.DOCUMENT_SNAPSHOT &&
                    it.content == "document body" &&
                    it.sourceTool == "read_document"
            })
        }
        coVerify { repository.markStepStatus("step-1", AgentTaskStepStatus.DONE, match { it.contains("read_document") }) }
    }

    @Test
    fun `failed tool marks step and task blocked with tool error evidence`() = runTest {
        val call = ToolCall("call-1", "read_document", """{"document_id":"missing"}""")
        coEvery { executeDocumentTool(call) } returns Result.failure(IllegalArgumentException("missing document"))

        val result = useCase(task(), step(), listOf(call), currentDocumentId = "doc-1").getOrThrow()

        assertThat(result.outcome).isEqualTo(AgentStepExecutionOutcome.STEP_BLOCKED)
        assertThat(result.blockingReason).contains("missing document")
        coVerify {
            repository.appendEvidence(match {
                it.type == AgentEvidenceType.TOOL_ERROR &&
                    it.content.contains("missing document") &&
                    it.sourceTool == "read_document"
            })
        }
        coVerify { repository.markStepStatus("step-1", AgentTaskStepStatus.BLOCKED, match { it.contains("missing document") }) }
        coVerify {
            repository.updateTask(match {
                it.id == "task-1" &&
                    it.status == AgentTaskStatus.BLOCKED &&
                    it.blockingReason?.contains("missing document") == true
            })
        }
    }

    @Test
    fun `write tool becomes action proposal without executing document tool`() = runTest {
        val call = ToolCall("call-1", "edit_document", """{"document_id":"doc-1","new_content":"updated"}""")

        val result = useCase(task(), step(), listOf(call), currentDocumentId = "doc-1").getOrThrow()

        assertThat(result.outcome).isEqualTo(AgentStepExecutionOutcome.STEP_DONE)
        val proposedAction = result.proposedAction ?: error("expected proposed action")
        assertThat(proposedAction.type).isEqualTo(AgentActionType.EDIT_DOCUMENT)
        assertThat(proposedAction.content).isEqualTo("updated")
        coVerify(exactly = 0) { executeDocumentTool(any()) }
        coVerify {
            repository.appendEvidence(match {
                it.type == AgentEvidenceType.ACTION_PROPOSAL &&
                    it.sourceTool == "edit_document" &&
                    it.content.contains("updated")
            })
        }
        coVerify { repository.markStepStatus("step-1", AgentTaskStepStatus.DONE, match { it.contains("proposal") }) }
    }

    private fun task() = AgentTask(
        id = "task-1",
        conversationId = "conversation-1",
        goal = "organize notes",
        status = AgentTaskStatus.EXECUTING,
        createdAt = 1L,
        updatedAt = 2L,
        currentStepId = "step-1"
    )

    private fun step() = AgentTaskStep(
        id = "step-1",
        taskId = "task-1",
        title = "Read source",
        description = "Read the current source document",
        status = AgentTaskStepStatus.PENDING,
        order = 0,
        completionCriteria = "Source document content is known",
        toolHints = listOf("read_document")
    )
}
