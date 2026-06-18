package com.yumark.app.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AgentTaskStatus {
    PLANNING,
    EXECUTING,
    REPLANNING,
    BLOCKED,
    COMPLETED,
    FAILED
}

@Serializable
enum class AgentTaskStepStatus {
    PENDING,
    RUNNING,
    DONE,
    BLOCKED,
    FAILED,
    SKIPPED
}

@Serializable
enum class AgentEvidenceType {
    SEARCH_RESULT,
    DOCUMENT_SNAPSHOT,
    TOOL_ERROR,
    DECISION_NOTE,
    ACTION_PROPOSAL
}

@Serializable
data class AgentTask(
    val id: String,
    val conversationId: String,
    val goal: String,
    val status: AgentTaskStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val currentStepId: String? = null,
    val planVersion: Int = 1,
    val finalSummary: String? = null,
    val blockingReason: String? = null
)

@Serializable
data class AgentTaskStep(
    val id: String,
    val taskId: String,
    val title: String,
    val description: String,
    val status: AgentTaskStepStatus,
    val order: Int,
    val dependsOnStepIds: List<String> = emptyList(),
    val completionCriteria: String,
    val resultSummary: String? = null,
    val toolHints: List<String> = emptyList()
)

@Serializable
data class AgentEvidence(
    val id: String,
    val taskId: String,
    val stepId: String? = null,
    val type: AgentEvidenceType,
    val content: String,
    val sourceTool: String? = null,
    val createdAt: Long
)

data class AgentTaskAggregate(
    val task: AgentTask,
    val steps: List<AgentTaskStep>,
    val evidence: List<AgentEvidence>
)
