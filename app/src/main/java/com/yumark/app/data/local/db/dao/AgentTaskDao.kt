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

    /** 把仍属于「活着的任务」的 RUNNING 步骤退回 [pending]，让它能被重跑。 */
    @Query("""
        UPDATE agent_task_steps
        SET status = :pending
        WHERE status = :running
          AND task_id IN (SELECT id FROM agent_tasks WHERE status IN (:liveStatuses))
    """)
    suspend fun resetRunningSteps(liveStatuses: List<String>, running: String, pending: String): Int

    /** 把仍停在 [liveStatuses] 的任务改判 [blocked] 并写明原因，返回受影响行数。 */
    @Query("""
        UPDATE agent_tasks
        SET status = :blocked, updated_at = :now, current_step_id = NULL, blocking_reason = :reason
        WHERE status IN (:liveStatuses)
    """)
    suspend fun blockInterruptedTasks(
        liveStatuses: List<String>,
        blocked: String,
        now: Long,
        reason: String
    ): Int

    /**
     * 复位「上次进程死在半路」的任务：进程被杀时没有任何 finally 能跑，
     * `AgentUseCases` 里 `withContext(NonCancellable)` 的那段收尾只挡得住协程取消与异常。
     *
     * 顺序不能反：先退 RUNNING 步骤（它靠父任务仍在 [liveStatuses] 里定位），再改判任务本身。
     * 反过来做第一条 UPDATE 就选不到任何行，RUNNING 步骤会永远停在活动态。
     *
     * 只在启动时调用——此刻进程里必然没有正在跑的任务，所以不需要区分「这一行是别人正在用的」。
     */
    @Transaction
    suspend fun reconcileInterrupted(
        liveStatuses: List<String>,
        blocked: String,
        running: String,
        pending: String,
        now: Long,
        reason: String
    ): Int {
        resetRunningSteps(liveStatuses, running, pending)
        return blockInterruptedTasks(liveStatuses, blocked, now, reason)
    }
}
