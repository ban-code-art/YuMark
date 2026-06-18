package com.yumark.app.domain.usecase.ai.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

enum class TaskCompletionOutcome {
    COMPLETED,
    BLOCKED,
    FAILED
}

data class TaskCompletionDecision(
    val outcome: TaskCompletionOutcome,
    val summary: String,
    val blockingReason: String? = null
)

class EvaluateTaskCompletionUseCase @Inject constructor() {
    operator fun invoke(rawJudgeOutput: String): Result<TaskCompletionDecision> = runCatching {
        val payload = agentRuntimeJson.decodeFromString(CompletionPayload.serializer(), extractJsonObject(rawJudgeOutput))
        val outcome = runCatching { TaskCompletionOutcome.valueOf(payload.outcome) }
            .getOrElse { error("Unknown completion outcome: ${payload.outcome}") }
        val summary = payload.summary.trim()
        require(summary.isNotBlank()) { "Completion summary is required" }
        val blockingReason = payload.blockingReason?.trim()?.takeIf { it.isNotBlank() }
        TaskCompletionDecision(
            outcome = outcome,
            summary = summary,
            blockingReason = blockingReason
        )
    }
}

@Serializable
private data class CompletionPayload(
    val outcome: String = "",
    val summary: String = "",
    @SerialName("blocking_reason") val blockingReason: String? = null
)
