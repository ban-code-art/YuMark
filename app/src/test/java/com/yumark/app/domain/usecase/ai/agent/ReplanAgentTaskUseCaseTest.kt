package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.AgentEvidence
import com.yumark.app.domain.model.AgentEvidenceType
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskAggregate
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.domain.repository.AgentTaskRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ReplanAgentTaskUseCaseTest {

    private val repository: AgentTaskRepository = mockk(relaxed = true)
    private val useCase = ReplanAgentTaskUseCase(repository).apply {
        nowProvider = { 200L }
    }

    @Test
    fun `applies one replan while preserving completed steps`() = runTest {
        val aggregate = aggregate(planVersion = 1)
        val newPlan = AgentPlan(
            goal = "organize notes",
            successCriteria = listOf("summary is ready"),
            steps = listOf(
                AgentPlanStep(
                    title = "Search alternate note",
                    purpose = "Find a source after the first read failed",
                    suggestedTools = listOf("search_in_project"),
                    completionCriteria = "Alternate source is identified",
                    fallback = "Ask the user for the document name"
                )
            )
        )

        val result = useCase(aggregate, failedStepId = "step-2", failureReason = "missing doc", newPlan = newPlan)
            .getOrThrow()

        assertThat(result.task.status).isEqualTo(AgentTaskStatus.EXECUTING)
        assertThat(result.task.planVersion).isEqualTo(2)
        assertThat(result.steps.map { it.id }).contains("step-1")
        assertThat(result.steps.first { it.id == "step-1" }.status).isEqualTo(AgentTaskStepStatus.DONE)
        assertThat(result.steps.any { it.title == "Search alternate note" }).isTrue()
        coVerify { repository.replaceSteps("task-1", match { it.first().id == "step-1" && it.any { step -> step.title == "Search alternate note" } }) }
        coVerify { repository.updateTask(match { it.status == AgentTaskStatus.EXECUTING && it.planVersion == 2 }) }
        coVerify { repository.appendEvidence(match { it.type == AgentEvidenceType.DECISION_NOTE && it.content.contains("missing doc") }) }
    }

    @Test
    fun `blocks when replan budget has already been used`() = runTest {
        val aggregate = aggregate(planVersion = 2)
        val newPlan = AgentPlan(
            goal = "organize notes",
            successCriteria = listOf("summary is ready"),
            steps = emptyList()
        )

        val result = useCase(aggregate, failedStepId = "step-2", failureReason = "missing doc", newPlan = newPlan)
            .getOrThrow()

        assertThat(result.task.status).isEqualTo(AgentTaskStatus.BLOCKED)
        assertThat(result.task.blockingReason).contains("missing doc")
        coVerify(exactly = 0) { repository.replaceSteps(any(), any()) }
        coVerify { repository.updateTask(match { it.status == AgentTaskStatus.BLOCKED }) }
    }

    private fun aggregate(planVersion: Int) = AgentTaskAggregate(
        task = AgentTask(
            id = "task-1",
            conversationId = "conversation-1",
            goal = "organize notes",
            status = AgentTaskStatus.REPLANNING,
            createdAt = 1L,
            updatedAt = 2L,
            currentStepId = "step-2",
            planVersion = planVersion
        ),
        steps = listOf(
            step("step-1", AgentTaskStepStatus.DONE, order = 0),
            step("step-2", AgentTaskStepStatus.BLOCKED, order = 1)
        ),
        evidence = listOf(
            AgentEvidence(
                id = "evidence-1",
                taskId = "task-1",
                stepId = "step-2",
                type = AgentEvidenceType.TOOL_ERROR,
                content = "missing doc",
                sourceTool = "read_document",
                createdAt = 100L
            )
        )
    )

    private fun step(id: String, status: AgentTaskStepStatus, order: Int) = AgentTaskStep(
        id = id,
        taskId = "task-1",
        title = if (id == "step-1") "Completed source scan" else "Read missing note",
        description = "step",
        status = status,
        order = order,
        completionCriteria = "done",
        toolHints = listOf("read_document")
    )
}
