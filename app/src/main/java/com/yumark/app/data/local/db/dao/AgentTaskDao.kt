package com.yumark.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Relation
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.yumark.app.data.local.db.entity.AgentEvidenceEntity
import com.yumark.app.data.local.db.entity.AgentTaskEntity
import com.yumark.app.data.local.db.entity.AgentTaskStepEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentTaskDao {
    data class TaskAggregate(
        @Embedded val task: AgentTaskEntity,
        @Relation(parentColumn = "id", entityColumn = "task_id")
        val steps: List<AgentTaskStepEntity>,
        @Relation(parentColumn = "id", entityColumn = "task_id")
        val evidence: List<AgentEvidenceEntity>
    )

    @Transaction
    @Query("""
        SELECT * FROM agent_tasks
        WHERE conversation_id = :conversationId
        LIMIT 1
    """)
    fun observeTaskByConversation(conversationId: String): Flow<TaskAggregate?>

    @Transaction
    @Query("""
        SELECT * FROM agent_tasks
        WHERE conversation_id = :conversationId
        LIMIT 1
    """)
    suspend fun getTaskByConversationId(conversationId: String): TaskAggregate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: AgentTaskEntity)

    @Update
    suspend fun updateTask(task: AgentTaskEntity)

    @Query("DELETE FROM agent_task_steps WHERE task_id = :taskId")
    suspend fun deleteSteps(taskId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<AgentTaskStepEntity>)

    @Transaction
    suspend fun replaceSteps(taskId: String, steps: List<AgentTaskStepEntity>) {
        deleteSteps(taskId)
        if (steps.isNotEmpty()) insertSteps(steps)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidence(evidence: AgentEvidenceEntity)

    @Query("""
        UPDATE agent_task_steps
        SET status = :status, result_summary = :resultSummary
        WHERE id = :stepId
    """)
    suspend fun updateStepStatus(stepId: String, status: String, resultSummary: String? = null)
}
