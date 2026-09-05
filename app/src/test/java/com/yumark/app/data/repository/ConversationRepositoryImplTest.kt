package com.yumark.app.data.repository

import com.yumark.app.data.local.db.dao.ConversationDao
import com.yumark.app.data.local.db.dao.MessageDao
import com.google.common.truth.Truth.assertThat
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ConversationRepositoryImplTest {

    private val conversationDao: ConversationDao = mockk(relaxed = true)
    private val messageDao: MessageDao = mockk(relaxed = true)
    private val repository = ConversationRepositoryImpl(conversationDao, messageDao)

    @AfterEach
    fun tearDown() = clearAllMocks()

    @Test
    fun `resetInterruptedRuns only clears WORKING and passes the names the DB stores`() = runTest {
        coEvery { conversationDao.resetInterruptedStatus(any(), any()) } returns 3

        val affected = repository.resetInterruptedRuns()

        assertThat(affected).isEqualTo(3)
        // 字面量故意写死：status 列存的是 ConversationStatus.name，枚举改名而 SQL 参数没跟着改，
        // 这条复位就会静默失配。COMPLETED 不在其中——那是 Agent 正常收尾的终态，不是残留。
        coVerify { conversationDao.resetInterruptedStatus(working = "WORKING", idle = "IDLE") }
    }
}
