package com.yumark.app.data.ai.memory

import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.local.db.dao.MemoryDao
import com.yumark.app.data.local.db.entity.MemoryEntity
import com.yumark.app.domain.model.ToolCall
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MemoryServiceTest {

    private val dao: MemoryDao = mockk(relaxed = true)
    private val service = MemoryService(dao)

    @Test
    fun `identical content scores full similarity`() {
        assertThat(service.lexicalSimilarity("记住我喜欢简洁风格", "记住我喜欢简洁风格")).isEqualTo(1.0)
    }

    @Test
    fun `disjoint tokens score zero`() {
        assertThat(service.lexicalSimilarity("苹果", "香蕉")).isEqualTo(0.0)
    }

    @Test
    fun `substring inclusion adds bonus`() {
        // "偏好" 完全包含在长句中 → 余弦贡献 + 0.2 子串加成
        val sim = service.lexicalSimilarity("我喜欢简洁风格", "喜欢简洁")
        assertThat(sim).isGreaterThan(0.0)
    }

    @Test
    fun `save_memory persists when no similar exists`() = runTest {
        coEvery { dao.getActive() } returns emptyList()
        coEvery { dao.upsert(any()) } returns Unit
        val result = service.execute(toolCall("save_memory", """{"content":"我喜欢简洁风格","category":"preference"}"""))
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).contains("已保存新记忆")
    }

    @Test
    fun `save_memory updates when highly similar exists`() = runTest {
        val existing = MemoryEntity(
            id = "m1", content = "我喜欢简洁风格", category = "preference",
            source = "user_explicit", locked = false, status = "active",
            createdAt = 1, updatedAt = 1
        )
        coEvery { dao.getActive() } returns listOf(existing)
        coEvery { dao.updateContent(any(), any(), any(), any()) } returns Unit
        val result = service.execute(toolCall("save_memory", """{"content":"我喜欢简洁风格","category":"preference"}"""))
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).contains("已更新已有记忆")
    }

    @Test
    fun `search_memory returns no-match message when empty`() = runTest {
        coEvery { dao.getActive() } returns emptyList()
        val result = service.execute(toolCall("search_memory", """{"query":"anything"}"""))
        assertThat(result.getOrThrow()).contains("未找到")
    }

    private fun toolCall(name: String, args: String) = ToolCall(id = "c1", name = name, arguments = args)
}
