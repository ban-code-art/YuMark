package com.yumark.app.domain.model

import com.yumark.app.R
import com.yumark.app.core.util.UiMessage

/**
 * `agent_tasks` 两列（`blocking_reason` / `summary`）的**稳定码**。
 *
 * 从前这两列直接存中文字面量（`AgentUseCases` 4 处、`AgentChatSheet.stop()` 1 处、
 * `ReconcileInterruptedAgentRunsUseCase` 1 处），时间线 `AgentTimeline` 又把它原样渲染 ——
 * 结果是把界面语言切成英文后，「阻塞：任务被中断（取消或异常）」这行仍然是中文，
 * 而且已经落库的行改不了。
 *
 * 现在写入点存 [code]，渲染前才用 [decode] 查表成 [UiMessage]。这样做而不是把列拆成
 * 「资源 ID + 参数」的两三列，是因为**不需要数据库迁移**：
 * - 历史行里存的是中文原文，[decode] 认不出，按 [UiMessage.Raw] 原样透出，旧任务照样看得懂；
 * - `summary` 列本来就混着模型自己写的正文，那部分永远该原样显示，同一条规则正好覆盖。
 *
 * 因此 [code] 的取值是**持久化契约**：改文案随时可以，改 code 会让历史行退回原文显示。
 */
enum class AgentStatusCode(val code: String, private val stringRes: Int, val takesCount: Boolean = false) {
    // ---- blocking_reason ----
    BLOCKED_EMPTY_RESPONSE("blocked.empty_response", R.string.agent_blocked_empty_response),
    BLOCKED_DUPLICATE_TOOL_CALL("blocked.duplicate_tool_call", R.string.agent_blocked_duplicate_tool_call),
    BLOCKED_MAX_STEPS("blocked.max_steps", R.string.agent_blocked_max_steps, takesCount = true),
    BLOCKED_INTERRUPTED("blocked.interrupted", R.string.agent_blocked_interrupted),
    BLOCKED_USER_STOPPED("blocked.user_stopped", R.string.agent_blocked_user_stopped),
    BLOCKED_PROCESS_KILLED("blocked.process_killed", R.string.agent_blocked_process_killed),

    // ---- summary ----
    SUMMARY_EMPTY_RESPONSE("summary.empty_response", R.string.agent_summary_empty_response),
    SUMMARY_COMPLETED("summary.completed", R.string.agent_summary_completed),
    SUMMARY_ACTION_PROPOSED("summary.action_proposed", R.string.agent_summary_action_proposed);

    /**
     * 编码成落库字符串。带参数的码写成 `code#12`。
     *
     * 分隔符选 `#` 而不是 `:`：码里带点已经够特征，而 `:` 在中文摘要里出现过（「结果：…」），
     * 用它会让「恰好含冒号的自由文本」被误拆。
     */
    fun encode(count: Int? = null): String = if (count == null) code else "$code$ARG_SEPARATOR$count"

    /** 单独取文案，供已知码的调用方直接用。 */
    fun toUiMessage(count: Int? = null): UiMessage =
        if (takesCount && count != null) UiMessage.of(stringRes, count) else UiMessage.Res(stringRes)

    companion object {
        private const val ARG_SEPARATOR = '#'

        private val byCode: Map<String, AgentStatusCode> = entries.associateBy { it.code }

        /**
         * 落库字符串 → 可渲染文案。
         *
         * 认不出的一律 [UiMessage.Raw] 原样透出（历史中文行、模型写的摘要），
         * 绝不返回空串或占位文案 —— 那等于把用户唯一的线索抹掉。
         */
        fun decode(stored: String?): UiMessage? {
            if (stored == null) return null
            val trimmed = stored.trim()
            if (trimmed.isEmpty()) return null
            val sep = trimmed.indexOf(ARG_SEPARATOR)
            if (sep < 0) return byCode[trimmed]?.toUiMessage() ?: UiMessage.Raw(stored)
            val entry = byCode[trimmed.substring(0, sep)] ?: return UiMessage.Raw(stored)
            // 参数缺失/不是数字只可能来自被改坏的行。此时按无参渲染，界面上会看到字面的
            // `%1$d` —— 难看但诚实；绝不为了好看编一个步数出来。
            val count = trimmed.substring(sep + 1).toIntOrNull()
            return entry.toUiMessage(count)
        }
    }
}
