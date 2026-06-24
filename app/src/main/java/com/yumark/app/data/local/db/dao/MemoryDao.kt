package com.yumark.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yumark.app.data.local.db.entity.MemoryEntity

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE status = 'active'")
    suspend fun getActive(): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun list(limit: Int, offset: Int): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Query("UPDATE memories SET content = :content, category = :category, status = 'active', updated_at = :updatedAt WHERE id = :id")
    suspend fun updateContent(id: String, content: String, category: String, updatedAt: Long)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: String)
}
