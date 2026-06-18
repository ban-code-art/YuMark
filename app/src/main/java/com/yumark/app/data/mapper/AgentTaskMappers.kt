package com.yumark.app.data.mapper

import com.yumark.app.data.local.db.dao.AgentTaskDao
import com.yumark.app.data.local.db.entity.AgentEvidenceEntity
import com.yumark.app.data.local.db.entity.AgentTaskEntity
import com.yumark.app.data.local.db.entity.AgentTaskStepEntity
import com.yumark.app.domain.model.AgentEvidence
import com.yumark.app.domain.model.AgentEvidenceType
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskAggregate
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStepStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val agentTaskJson = Json { ignoreUnknownKeys = true }

fun AgentTaskEntity.toDomain(): AgentTask =
    AgentTask(
        id = id,
        conversationId = conversationId,
        goal = goal,
        // DB 中可能残留旧版本/重命名前的枚举名，valueOf 会抛异常导致整条任务读不出来；兜底回落 EXECUTING。
        status = runCatching { AgentTaskStatus.valueOf(status) }.getOrElse { AgentTaskStatus.EXECUTING },
        createdAt = createdAt,
        updatedAt = updatedAt,
        currentStepId = currentStepId,
        planVersion = planVersion,
        finalSummary = finalSummary,
        blockingReason = blockingReason
    )

fun AgentTask.toEntity(): AgentTaskEntity =
    AgentTaskEntity(
        id = id,
        conversationId = conversationId,
        goal = goal,
        status = status.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        currentStepId = currentStepId,
        planVersion = planVersion,
        finalSummary = finalSummary,
        blockingReason = blockingReason
    )

fun AgentTaskStepEntity.toDomain(): AgentTaskStep =
    AgentTaskStep(
        id = id,
        taskId = taskId,
        title = title,
        description = description,
        status = runCatching { AgentTaskStepStatus.valueOf(status) }.getOrElse { AgentTaskStepStatus.PENDING },
        order = stepOrder,
        dependsOnStepIds = agentTaskJson.decodeFromString(dependsOnStepIdsJson),
        completionCriteria = completionCriteria,
        resultSummary = resultSummary,
        toolHints = agentTaskJson.decodeFromString(toolHintsJson)
    )

fun AgentTaskStep.toEntity(): AgentTaskStepEntity =
    AgentTaskStepEntity(
        id = id,
        taskId = taskId,
        title = title,
        description = description,
        status = status.name,
        stepOrder = order,
        dependsOnStepIdsJson = agentTaskJson.encodeToString(dependsOnStepIds),
        completionCriteria = completionCriteria,
        resultSummary = resultSummary,
        toolHintsJson = agentTaskJson.encodeToString(toolHints)
    )

fun AgentEvidenceEntity.toDomain(): AgentEvidence =
    AgentEvidence(
        id = id,
        taskId = taskId,
        stepId = stepId,
        type = runCatching { AgentEvidenceType.valueOf(type) }.getOrElse { AgentEvidenceType.DECISION_NOTE },
        content = content,
        sourceTool = sourceTool,
        createdAt = createdAt
    )

fun AgentEvidence.toEntity(): AgentEvidenceEntity =
    AgentEvidenceEntity(
        id = id,
        taskId = taskId,
        stepId = stepId,
        type = type.name,
        content = content,
        sourceTool = sourceTool,
        createdAt = createdAt
    )

fun AgentTaskDao.TaskAggregate.toDomain(): AgentTaskAggregate =
    AgentTaskAggregate(
        task = task.toDomain(),
        steps = steps.sortedBy { it.stepOrder }.map { it.toDomain() },
        evidence = evidence.sortedBy { it.createdAt }.map { it.toDomain() }
    )
