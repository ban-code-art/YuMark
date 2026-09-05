package com.yumark.app.data.repository

import com.yumark.app.data.local.db.dao.AgentTaskDao
import com.yumark.app.data.local.db.entity.AgentEvidenceEntity
import com.yumark.app.data.local.db.entity.AgentTaskEntity
import com.yumark.app.data.local.db.entity.AgentTaskStepEntity
import com.yumark.app.data.mapper.toDomain
import com.yumark.app.data.mapper.toEntity
import com.yumark.app.domain.model.AgentEvidence
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskAggregate
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.domain.repository.AgentTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentTaskRepositoryImpl @Inject constructor(
    private val dao: AgentTaskDao
) : AgentTaskRepository {
    override fun observeTaskByConversation(conversationId: String): Flow<AgentTaskAggregate?> =
        dao.observeTaskByConversation(conversationId).map { it?.toDomain() }

    override suspend fun getTaskByConversationId(conversationId: String): AgentTaskAggregate? =
        dao.getTaskByConversationId(conversationId)?.toDomain()

    override suspend fun createTask(task: AgentTask, steps: List<AgentTaskStep>) {
        dao.insertTask(task.toEntity())
        dao.replaceSteps(task.id, steps.map { it.toEntity() })
    }

    override suspend fun updateTask(task: AgentTask) {
        dao.updateTask(task.toEntity())
    }

    override suspend fun replaceSteps(taskId: String, steps: List<AgentTaskStep>) {
        dao.replaceSteps(taskId, steps.map { it.toEntity() })
    }

    override suspend fun appendEvidence(evidence: AgentEvidence) {
        dao.insertEvidence(evidence.toEntity())
    }

    override suspend fun markStepStatus(stepId: String, status: AgentTaskStepStatus, resultSummary: String?) {
        dao.updateStepStatus(stepId, status.name, resultSummary)
    }

    override suspend fun reconcileInterruptedTasks(reason: String): Int =
        dao.reconcileInterrupted(
            liveStatuses = LIVE_TASK_STATUSES.map { it.name },
            blocked = AgentTaskStatus.BLOCKED.name,
            running = AgentTaskStepStatus.RUNNING.name,
            pending = AgentTaskStepStatus.PENDING.name,
            now = System.currentTimeMillis(),
            reason = reason
        )

    private companion object {
        /** 「还没走到终态」的任务状态。与 `stop()` 里判断是否需要改判的那三个保持一致。 */
        val LIVE_TASK_STATUSES = listOf(
            AgentTaskStatus.PLANNING,
            AgentTaskStatus.EXECUTING,
            AgentTaskStatus.REPLANNING
        )
    }
}
