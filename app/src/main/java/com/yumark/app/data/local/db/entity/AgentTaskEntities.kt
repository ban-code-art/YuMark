package com.yumark.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_tasks",
    indices = [Index(value = ["conversation_id"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AgentTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val goal: String,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "current_step_id") val currentStepId: String? = null,
    @ColumnInfo(name = "plan_version") val planVersion: Int = 1,
    @ColumnInfo(name = "final_summary") val finalSummary: String? = null,
    @ColumnInfo(name = "blocking_reason") val blockingReason: String? = null
)

@Entity(
    tableName = "agent_task_steps",
    indices = [Index(value = ["task_id"]), Index(value = ["task_id", "step_order"])],
    foreignKeys = [
        ForeignKey(
            entity = AgentTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AgentTaskStepEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    val title: String,
    val description: String,
    val status: String,
    @ColumnInfo(name = "step_order") val stepOrder: Int,
    @ColumnInfo(name = "depends_on_step_ids_json") val dependsOnStepIdsJson: String,
    @ColumnInfo(name = "completion_criteria") val completionCriteria: String,
    @ColumnInfo(name = "result_summary") val resultSummary: String? = null,
    @ColumnInfo(name = "tool_hints_json") val toolHintsJson: String
)

@Entity(
    tableName = "agent_evidence",
    indices = [Index(value = ["task_id"]), Index(value = ["step_id"])],
    foreignKeys = [
        ForeignKey(
            entity = AgentTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AgentTaskStepEntity::class,
            parentColumns = ["id"],
            childColumns = ["step_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class AgentEvidenceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "step_id") val stepId: String? = null,
    val type: String,
    val content: String,
    @ColumnInfo(name = "source_tool") val sourceTool: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
