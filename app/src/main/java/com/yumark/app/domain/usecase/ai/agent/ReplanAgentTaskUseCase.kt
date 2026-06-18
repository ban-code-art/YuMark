package com.yumark.app.domain.usecase.ai.agent

import com.yumark.app.domain.model.AgentEvidence
import com.yumark.app.domain.model.AgentEvidenceType
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskAggregate
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.domain.repository.AgentTaskRepository
import java.util.UUID
import javax.inject.Inject

data class ReplanAgentTaskResult(
    val task: AgentTask,
    val steps: List<AgentTaskStep>
)

class ReplanAgentTaskUseCase @Inject constructor(
    private val repository: AgentTaskRepository
) {
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }
    internal var idProvider: () -> String = { UUID.randomUUID().toString() }

    suspend operator fun invoke(
        aggregate: AgentTaskAggregate,
        failedStepId: String,
        failureReason: String,
        newPlan: AgentPlan
    ): Result<ReplanAgentTaskResult> = runCatching {
        val task = aggregate.task
        val alreadyReplanned = task.planVersion >= 2
        if (alreadyReplanned) {
            val blockedTask = task.copy(
                status = AgentTaskStatus.BLOCKED,
                updatedAt = nowProvider(),
                blockingReason = failureReason
            )
            repository.updateTask(blockedTask)
            return@runCatching ReplanAgentTaskResult(blockedTask, aggregate.steps)
        }

        val preserved = aggregate.steps.filter { it.id != failedStepId }
            .map { step ->
                if (step.status == AgentTaskStepStatus.BLOCKED) step.copy(status = AgentTaskStepStatus.DONE)
                else step
            }
        val newSteps = newPlan.toTaskSteps(task.id).map { step ->
            step.copy(taskId = task.id)
        }
        val merged = preserved + newSteps
        val replannedTask = task.copy(
            status = AgentTaskStatus.EXECUTING,
            updatedAt = nowProvider(),
            planVersion = task.planVersion + 1,
            currentStepId = merged.firstOrNull { it.status == AgentTaskStepStatus.PENDING }?.id,
            blockingReason = null
        )
        repository.replaceSteps(task.id, merged)
        repository.updateTask(replannedTask)
        repository.appendEvidence(
            AgentEvidence(
                id = idProvider(),
                taskId = task.id,
                stepId = failedStepId,
                type = AgentEvidenceType.DECISION_NOTE,
                content = "Replanned after failure: $failureReason",
                sourceTool = "replanner",
                createdAt = nowProvider()
            )
        )
        ReplanAgentTaskResult(replannedTask, merged)
    }
}
