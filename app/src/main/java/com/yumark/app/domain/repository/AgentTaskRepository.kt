package com.yumark.app.domain.repository

import com.yumark.app.domain.model.AgentEvidence
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskAggregate
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStepStatus
import kotlinx.coroutines.flow.Flow

interface AgentTaskRepository {
    fun observeTaskByConversation(conversationId: String): Flow<AgentTaskAggregate?>
    suspend fun getTaskByConversationId(conversationId: String): AgentTaskAggregate?
    suspend fun createTask(task: AgentTask, steps: List<AgentTaskStep>)
    suspend fun updateTask(task: AgentTask)
    suspend fun replaceSteps(taskId: String, steps: List<AgentTaskStep>)
    suspend fun appendEvidence(evidence: AgentEvidence)
    suspend fun markStepStatus(stepId: String, status: AgentTaskStepStatus, resultSummary: String? = null)

    /**
     * 把仍停在 PLANNING/EXECUTING/REPLANNING 的任务改判 BLOCKED（原因写 [reason]），
     * 并把它们的 RUNNING 步骤退回 PENDING。返回被改判的任务数。仅供启动期调用。
     */
    suspend fun reconcileInterruptedTasks(reason: String): Int
}
