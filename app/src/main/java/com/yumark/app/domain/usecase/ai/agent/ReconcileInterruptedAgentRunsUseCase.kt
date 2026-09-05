package com.yumark.app.domain.usecase.ai.agent

import com.yumark.app.domain.model.AgentStatusCode
import com.yumark.app.domain.repository.AgentTaskRepository
import com.yumark.app.domain.repository.ConversationRepository
import javax.inject.Inject

/** [ReconcileInterruptedAgentRunsUseCase] 复位掉的行数，两张表分开报，便于日志与测试断言。 */
data class ReconciledAgentRuns(val conversations: Int, val tasks: Int) {
    val isEmpty: Boolean get() = conversations == 0 && tasks == 0
}

/**
 * 启动期复位「上一次进程死在 Agent 运行途中」留下的状态。
 *
 * 为什么必须有这一步：`conversations.status` 与 `agent_tasks.status` 都是落库字段，而清理它们的
 * 代码全在协程的收尾里 —— `AgentUseCases` 那段 `withContext(NonCancellable)` 只能挡住协程取消
 * 与异常，进程被系统杀掉（后台回收、崩溃、用户划掉任务卡）时一行都不会执行。于是重启后：
 * - 对话列表里那条对话永远显示「Agent 正在工作」；
 * - 时间线把任务画成活动态，RUNNING 那一步永远转圈；
 * - 而「停止」按钮只在 ViewModel 的 `_isStreaming` 为真时才出现，冷启动它是 false ——
 *   用户没有任何入口能把这个状态清掉，只能清数据。
 *
 * 注意 `Message.isStreaming` 不在此列：`messages` 表根本没有这一列（见
 * `MessageEntity`），重启后它天然回到 false，无需复位。
 */
class ReconcileInterruptedAgentRunsUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val agentTaskRepository: AgentTaskRepository
) {
    suspend operator fun invoke(): Result<ReconciledAgentRuns> = runCatching {
        // 先任务后对话：任务那条是 @Transaction，失败时对话状态还没动，语义上仍是「没复位过」，
        // 下次启动会原样重试。反过来的话会留下「对话 IDLE 但任务还在 EXECUTING」的错配。
        val tasks = agentTaskRepository.reconcileInterruptedTasks(INTERRUPT_REASON)
        val conversations = conversationRepository.resetInterruptedRuns()
        ReconciledAgentRuns(conversations = conversations, tasks = tasks)
    }

    private companion object {
        /**
         * 落库的是稳定码而非中文原文：`blocking_reason` 是纯文本列，时间线直接渲染它，
         * 从前六处写入全是中文字面量，英文环境下这一行永远是中文。查表与历史行的兼容
         * 规则见 [AgentStatusCode]。
         */
        val INTERRUPT_REASON = AgentStatusCode.BLOCKED_PROCESS_KILLED.encode()
    }
}
