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
}
