package com.yumark.app.domain.usecase.ai.agent

import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.domain.usecase.ai.DocumentContextTools
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

data class AgentPlan(
    val goal: String,
    val successCriteria: List<String>,
    val steps: List<AgentPlanStep>
)

data class AgentPlanStep(
    val title: String,
    val purpose: String,
    val suggestedTools: List<String>,
    val completionCriteria: String,
    val fallback: String
)

class PlanAgentTaskUseCase @Inject constructor() {
    operator fun invoke(rawPlannerOutput: String): Result<AgentPlan> = runCatching {
        val payload = agentRuntimeJson.decodeFromString(PlannerPayload.serializer(), extractJsonObject(rawPlannerOutput))
        require(payload.goal.isNotBlank()) { "Planner goal is required" }
        require(payload.successCriteria.isNotEmpty()) { "Planner success_criteria is required" }
        require(payload.steps.isNotEmpty()) { "Planner steps are required" }

        val allowedTools = DocumentContextTools.getAllTools().map { it.name }.toSet()
        AgentPlan(
            goal = payload.goal.trim(),
            successCriteria = payload.successCriteria.map { criterion ->
                criterion.trim().also { require(it.isNotBlank()) { "Planner success criteria cannot be blank" } }
            },
            steps = payload.steps.mapIndexed { index, step ->
                val title = step.title.trim()
                val purpose = step.purpose.trim()
                val completionCriteria = step.completionCriteria.trim()
                val fallback = step.fallback.trim()
                require(title.isNotBlank()) { "Planner step ${index + 1} title is required" }
                require(purpose.isNotBlank()) { "Planner step ${index + 1} purpose is required" }
                require(completionCriteria.isNotBlank()) { "Planner step ${index + 1} completion_criteria is required" }
                require(fallback.isNotBlank()) { "Planner step ${index + 1} fallback is required" }
                step.suggestedTools.forEach { tool ->
                    require(tool in allowedTools) { "Unsupported tool in planner step ${index + 1}: $tool" }
                }
                AgentPlanStep(
                    title = title,
                    purpose = purpose,
                    suggestedTools = step.suggestedTools,
                    completionCriteria = completionCriteria,
                    fallback = fallback
                )
            }
        )
    }
}

fun AgentPlan.toTaskSteps(taskId: String): List<AgentTaskStep> =
    steps.mapIndexed { index, step ->
        AgentTaskStep(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            title = step.title,
            description = buildString {
                append(step.purpose)
                if (step.fallback.isNotBlank()) append("\nFallback: ").append(step.fallback)
            },
            status = AgentTaskStepStatus.PENDING,
            order = index,
            completionCriteria = step.completionCriteria,
            toolHints = step.suggestedTools
        )
    }

@Serializable
private data class PlannerPayload(
    val goal: String = "",
    @SerialName("success_criteria") val successCriteria: List<String> = emptyList(),
    val steps: List<PlannerStepPayload> = emptyList()
)

@Serializable
private data class PlannerStepPayload(
    val title: String = "",
    val purpose: String = "",
    @SerialName("suggested_tools") val suggestedTools: List<String> = emptyList(),
    @SerialName("completion_criteria") val completionCriteria: String = "",
    val fallback: String = ""
)
