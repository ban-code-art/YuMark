package com.yumark.app.domain.usecase.ai.agent

import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentEvidence
import com.yumark.app.domain.model.AgentEvidenceType
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.repository.AgentTaskRepository
import com.yumark.app.domain.usecase.ai.ExecuteDocumentToolUseCase
import java.util.UUID
import javax.inject.Inject

enum class AgentStepExecutionOutcome {
    TOOL_CALL,
    STEP_DONE,
    STEP_BLOCKED
}

data class AgentToolExecutionResult(
    val call: ToolCall,
    val ok: Boolean,
    val content: String
)

data class AgentStepExecutionResult(
    val outcome: AgentStepExecutionOutcome,
    val toolResults: List<AgentToolExecutionResult> = emptyList(),
    val proposedAction: AgentAction? = null,
    val blockingReason: String? = null
)

class ExecuteAgentTaskUseCase @Inject constructor(
    private val repository: AgentTaskRepository,
    private val executeDocumentTool: ExecuteDocumentToolUseCase
) {
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }
    internal var idProvider: () -> String = { UUID.randomUUID().toString() }

    suspend operator fun invoke(
        task: AgentTask,
        step: AgentTaskStep,
        toolCalls: List<ToolCall>,
        currentDocumentId: String?
    ): Result<AgentStepExecutionResult> = runCatching {
        require(toolCalls.isNotEmpty()) { "At least one tool call is required" }
        repository.markStepStatus(step.id, AgentTaskStepStatus.RUNNING)

        val writeCall = toolCalls.firstOrNull { it.name == "create_document" || it.name == "edit_document" }
        if (writeCall != null) {
            val action = parseWriteToolCall(writeCall, currentDocumentId)
                ?: error("Invalid write tool call: ${writeCall.name}")
            repository.appendEvidence(
                evidence(
                    task = task,
                    step = step,
                    type = AgentEvidenceType.ACTION_PROPOSAL,
                    content = action.content,
                    sourceTool = writeCall.name
                )
            )
            repository.markStepStatus(step.id, AgentTaskStepStatus.DONE, "write proposal ready")
            return@runCatching AgentStepExecutionResult(
                outcome = AgentStepExecutionOutcome.STEP_DONE,
                proposedAction = action
            )
        }

        val results = toolCalls.map { call ->
            val result = executeDocumentTool(call)
            if (result.isFailure) {
                val reason = result.exceptionOrNull()?.message ?: "Tool failed: ${call.name}"
                repository.appendEvidence(
                    evidence(
                        task = task,
                        step = step,
                        type = AgentEvidenceType.TOOL_ERROR,
                        content = reason,
                        sourceTool = call.name
                    )
                )
                repository.markStepStatus(step.id, AgentTaskStepStatus.BLOCKED, reason)
                repository.updateTask(
                    task.copy(
                        status = AgentTaskStatus.BLOCKED,
                        updatedAt = nowProvider(),
                        currentStepId = step.id,
                        blockingReason = reason
                    )
                )
                return@runCatching AgentStepExecutionResult(
                    outcome = AgentStepExecutionOutcome.STEP_BLOCKED,
                    blockingReason = reason
                )
            }

            val content = result.getOrThrow()
            repository.appendEvidence(
                evidence(
                    task = task,
                    step = step,
                    type = evidenceTypeFor(call),
                    content = content,
                    sourceTool = call.name
                )
            )
            AgentToolExecutionResult(call = call, ok = true, content = content)
        }

        repository.markStepStatus(
            step.id,
            AgentTaskStepStatus.DONE,
            results.joinToString { "${it.call.name}: ${it.content.take(80)}" }
        )
        AgentStepExecutionResult(
            outcome = AgentStepExecutionOutcome.STEP_DONE,
            toolResults = results
        )
    }

    private fun evidence(
        task: AgentTask,
        step: AgentTaskStep,
        type: AgentEvidenceType,
        content: String,
        sourceTool: String
    ) = AgentEvidence(
        id = idProvider(),
        taskId = task.id,
        stepId = step.id,
        type = type,
        content = content,
        sourceTool = sourceTool,
        createdAt = nowProvider()
    )

    private fun evidenceTypeFor(call: ToolCall): AgentEvidenceType =
        when (call.name) {
            "search_in_project", "list_documents" -> AgentEvidenceType.SEARCH_RESULT
            "read_document" -> AgentEvidenceType.DOCUMENT_SNAPSHOT
            else -> AgentEvidenceType.DECISION_NOTE
        }
}
