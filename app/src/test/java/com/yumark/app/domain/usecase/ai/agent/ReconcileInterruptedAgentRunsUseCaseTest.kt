package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import com.yumark.app.domain.model.AgentStatusCode
import com.yumark.app.domain.repository.AgentTaskRepository
import com.yumark.app.domain.repository.ConversationRepository
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * [ReconcileInterruptedAgentRunsUseCase] —— 启动期清理「上次进程死在 Agent 运行途中」的落库状态。
 *
 * 被守住的事故：`conversations.status` 与 `agent_tasks.status` 都是持久化字段，清理它们的代码全在
 * 协程收尾里，进程被系统杀掉时一行都不执行。重启后那条对话永远显示「Agent 正在工作」，而「停止」
 * 按钮只在进程内 `_isStreaming` 为真时出现，冷启动是 false —— 用户没有任何入口能清掉它。
 */
class ReconcileInterruptedAgentRunsUseCaseTest {

    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val agentTaskRepository: AgentTaskRepository = mockk(relaxed = true)
    private val useCase = ReconcileInterruptedAgentRunsUseCase(conversationRepository, agentTaskRepository)

    @AfterEach
    fun tearDown() = clearAllMocks()

    @Test
    fun `两张表的复位行数分开报`() = runTest {
        coEvery { agentTaskRepository.reconcileInterruptedTasks(any()) } returns 2
        coEvery { conversationRepository.resetInterruptedRuns() } returns 3

        val runs = useCase().getOrThrow()

        assertThat(runs.tasks).isEqualTo(2)
        assertThat(runs.conversations).isEqualTo(3)
        assertThat(runs.isEmpty).isFalse()
    }

    @Test
    fun `没有残留时返回全零`() = runTest {
        coEvery { agentTaskRepository.reconcileInterruptedTasks(any()) } returns 0
        coEvery { conversationRepository.resetInterruptedRuns() } returns 0

        val runs = useCase().getOrThrow()

        assertThat(runs.isEmpty).isTrue()
    }

    @Test
    fun `先复位任务再复位对话`() = runTest {
        // 反过来的话，任务复位失败会留下「对话已 IDLE、任务还 EXECUTING」的错配：
        // 列表看着正常，点进去时间线还在转圈，而下次启动的复位再也认不出这条对话有问题。
        useCase()

        coVerifyOrder {
            agentTaskRepository.reconcileInterruptedTasks(any())
            conversationRepository.resetInterruptedRuns()
        }
    }

    @Test
    fun `任务复位抛异常时返回失败且不动对话状态`() = runTest {
        coEvery { agentTaskRepository.reconcileInterruptedTasks(any()) } throws IllegalStateException("db locked")

        val result = useCase()

        assertThat(result.isFailure).isTrue()
        // 启动期不能因为清不掉残留就崩：调用方只记一笔非致命，所以这里必须是 Result 而不是抛出
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        coVerify(exactly = 0) { conversationRepository.resetInterruptedRuns() }
    }

    @Test
    fun `改判理由落库的是稳定码而不是中文原文`() = runTest {
        val reason = slot<String>()

        useCase()

        coVerify { agentTaskRepository.reconcileInterruptedTasks(capture(reason)) }
        // 这一列会被时间线渲染。存中文原文的话英文环境下永远显示中文，所以存码、渲染时查表。
        assertThat(reason.captured).isEqualTo(AgentStatusCode.BLOCKED_PROCESS_KILLED.encode())
        // 反向守护：别哪天又改回中文字面量
        assertThat(AgentStatusCode.decode(reason.captured))
            .isEqualTo(UiMessage.Res(R.string.agent_blocked_process_killed))
    }
}
