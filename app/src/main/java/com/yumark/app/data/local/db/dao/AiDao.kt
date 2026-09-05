package com.yumark.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yumark.app.data.local.db.entity.ConversationEntity
import com.yumark.app.data.local.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    /**
     * 把仍停在 [working] 的对话复位成 [idle]，返回受影响行数。
     *
     * `status` 是**落库**字段，而 `Message.isStreaming` 不是（`messages` 表没有这一列，
     * 见 [MessageEntity]）。所以进程被杀死在流式过程中时，重启后转圈的不是消息气泡，而是这一行：
     * 列表里那条对话永远显示「Agent 正在工作」，时间线把 RUNNING 步骤画成活动态。
     * 而「停止」按钮只在 ViewModel 的 `_isStreaming` 为真时出现，冷启动它是 false ——
     * 用户没有任何入口能把它清掉。
     *
     * 状态名由调用方传入（`ConversationStatus.WORKING.name`）而不写死在 SQL 里：枚举改名时
     * 编译期就会断，而写死的字符串只会静默失配、这条复位从此什么都不做。
     */
    @Query("UPDATE conversations SET status = :idle WHERE status = :working")
    suspend fun resetInterruptedStatus(working: String, idle: String): Int
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
