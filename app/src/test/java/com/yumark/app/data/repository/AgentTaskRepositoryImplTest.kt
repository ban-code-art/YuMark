package com.yumark.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.local.db.dao.AgentTaskDao
import com.yumark.app.data.local.db.entity.AgentEvidenceEntity
import com.yumark.app.data.local.db.entity.AgentTaskEntity
import com.yumark.app.data.local.db.entity.AgentTaskStepEntity
import com.yumark.app.domain.model.AgentEvidence
import com.yumark.app.domain.model.AgentEvidenceType
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStepStatus
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentTaskRepositoryImplTest {

    private val dao: AgentTaskDao = mockk(relaxed = true)
    private lateinit var repository: AgentTaskRepositoryImpl

    @BeforeEach
    fun setup() {
        repository = AgentTaskRepositoryImpl(dao)
    }

    @AfterEach
    fun tearDown() = clearAllMocks()

    @Test
    fun `createTask stores task and initial steps`() = runTest {
        val task = task()
        val steps = listOf(step("step-1"), step("step-2", order = 1))

        repository.createTask(task, steps)

        coVerify {
            dao.insertTask(match {
                it.id == "task-1" &&
                    it.conversationId == "conversation-1" &&
                    it.goal == "整理当前笔记" &&
                    it.status == "PLANNING"
            })
            dao.replaceSteps("task-1", match { it.map(AgentTaskStepEntity::id) == listOf("step-1", "step-2") })
        }
    }

    @Test
    fun `observeTaskByConversation maps task aggregate`() = runTest {
        every { dao.observeTaskByConversation("conversation-1") } returns flowOf(
            AgentTaskDao.TaskAggregate(
                task = taskEntity(),
                steps = listOf(stepEntity("step-2", order = 1), stepEntity("step-1", order = 0)),
                evidence = listOf(evidenceEntity("evidence-2", createdAt = 5L), evidenceEntity("evidence-1", createdAt = 4L))
            )
        )

        val result = repository.observeTaskByConversation("conversation-1")

        result.collect { aggregate ->
            assertThat(aggregate?.task?.id).isEqualTo("task-1")
            assertThat(aggregate?.task?.status).isEqualTo(AgentTaskStatus.EXECUTING)
            assertThat(aggregate?.steps?.map { it.id }).containsExactly("step-1", "step-2").inOrder()
            assertThat(aggregate?.evidence?.map { it.id }).containsExactly("evidence-1", "evidence-2").inOrder()
        }
    }

    @Test
    fun `getTaskByConversationId returns mapped aggregate`() = runTest {
        coEvery { dao.getTaskByConversationId("conversation-1") } returns AgentTaskDao.TaskAggregate(
            task = taskEntity(),
            steps = listOf(stepEntity("step-1")),
            evidence = listOf(evidenceEntity("evidence-1"))
        )

        val aggregate = repository.getTaskByConversationId("conversation-1")

        assertThat(aggregate?.task?.id).isEqualTo("task-1")
        assertThat(aggregate?.steps?.single()?.toolHints).containsExactly("read_document")
        assertThat(aggregate?.evidence?.single()?.type).isEqualTo(AgentEvidenceType.SEARCH_RESULT)
    }

    @Test
    fun `markStepStatus updates status and result summary`() = runTest {
        repository.markStepStatus(
            stepId = "step-1",
            status = AgentTaskStepStatus.DONE,
            resultSummary = "已读取目标文档"
        )

        coVerify {
            dao.updateStepStatus(
                stepId = "step-1",
                status = "DONE",
                resultSummary = "已读取目标文档"
            )
        }
    }

    @Test
    fun `appendEvidence stores evidence row`() = runTest {
        val evidence = AgentEvidence(
            id = "evidence-1",
            taskId = "task-1",
            stepId = "step-1",
            type = AgentEvidenceType.SEARCH_RESULT,
            content = "found document",
            sourceTool = "search_in_project",
            createdAt = 4L
        )

        repository.appendEvidence(evidence)

        coVerify {
            dao.insertEvidence(match {
                it.id == "evidence-1" &&
                    it.type == "SEARCH_RESULT" &&
                    it.sourceTool == "search_in_project"
            })
        }
    }

    @Test
    fun `updateTask persists updated task fields`() = runTest {
        repository.updateTask(
            task(status = AgentTaskStatus.BLOCKED).copy(
                updatedAt = 9L,
                currentStepId = "step-2",
                blockingReason = "missing document"
            )
        )

        coVerify {
            dao.updateTask(match {
                it.id == "task-1" &&
                    it.status == "BLOCKED" &&
                    it.updatedAt == 9L &&
                    it.currentStepId == "step-2" &&
                    it.blockingReason == "missing document"
            })
        }
    }

    @Test
    fun `replaceSteps serializes dependsOn and tool hints`() = runTest {
        val capturedSteps = slot<List<AgentTaskStepEntity>>()

        repository.replaceSteps(
            "task-1",
            listOf(
                step("step-2", order = 1).copy(
                    dependsOnStepIds = listOf("step-1"),
                    toolHints = listOf("read_document", "search_in_project")
                )
            )
        )

        coVerify { dao.replaceSteps("task-1", capture(capturedSteps)) }
        assertThat(capturedSteps.captured.single().dependsOnStepIdsJson).isEqualTo("""["step-1"]""")
        assertThat(capturedSteps.captured.single().toolHintsJson).isEqualTo("""["read_document","search_in_project"]""")
    }

    private fun task(status: AgentTaskStatus = AgentTaskStatus.PLANNING) = AgentTask(
        id = "task-1",
        conversationId = "conversation-1",
        goal = "整理当前笔记",
        status = status,
        createdAt = 1L,
        updatedAt = 2L,
        currentStepId = "step-1",
        planVersion = 1,
        finalSummary = null,
        blockingReason = null
    )

    private fun step(id: String, order: Int = 0) = AgentTaskStep(
        id = id,
        taskId = "task-1",
        title = "读取文档",
        description = "读取目标文档内容",
        status = AgentTaskStepStatus.PENDING,
        order = order,
        dependsOnStepIds = emptyList(),
        completionCriteria = "获得文档内容",
        resultSummary = null,
        toolHints = listOf("read_document")
    )

    private fun taskEntity() = AgentTaskEntity(
        id = "task-1",
        conversationId = "conversation-1",
        goal = "整理当前笔记",
        status = "EXECUTING",
        createdAt = 1L,
        updatedAt = 2L,
        currentStepId = "step-1",
        planVersion = 1,
        finalSummary = null,
        blockingReason = null
    )

    private fun stepEntity(id: String, order: Int = 0) = AgentTaskStepEntity(
        id = id,
        taskId = "task-1",
        title = "读取文档",
        description = "读取目标文档内容",
        status = "PENDING",
        stepOrder = order,
        dependsOnStepIdsJson = "[]",
        completionCriteria = "获得文档内容",
        resultSummary = null,
        toolHintsJson = """["read_document"]"""
    )

    private fun evidenceEntity(id: String, createdAt: Long = 4L) = AgentEvidenceEntity(
        id = id,
        taskId = "task-1",
        stepId = "step-1",
        type = "SEARCH_RESULT",
        content = "found document",
        sourceTool = "search_in_project",
        createdAt = createdAt
    )
}
