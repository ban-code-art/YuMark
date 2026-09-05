package com.yumark.app.domain.model

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import org.junit.jupiter.api.Test

/**
 * `agent_tasks.blocking_reason` / `summary` 是落库的纯文本列，时间线直接渲染它。
 * 存码而不存中文原文，才能在英文环境下显示英文；而历史行里已经存着中文，必须原样透出。
 */
class AgentStatusCodeTest {

    @Test
    fun `每个码都能被自己 encode 出的字符串解回来`() {
        AgentStatusCode.entries.forEach { code ->
            val stored = if (code.takesCount) code.encode(12) else code.encode()
            assertThat(AgentStatusCode.decode(stored)).isEqualTo(code.toUiMessage(12.takeIf { code.takesCount }))
        }
    }

    @Test
    fun `码是持久化契约，取值不许悄悄改`() {
        // 改 code 会让所有历史行退回原文显示（甚至显示成裸码），所以钉死在测试里。
        assertThat(AgentStatusCode.entries.map { it.code }).containsExactly(
            "blocked.empty_response",
            "blocked.duplicate_tool_call",
            "blocked.max_steps",
            "blocked.interrupted",
            "blocked.user_stopped",
            "blocked.process_killed",
            "summary.empty_response",
            "summary.completed",
            "summary.action_proposed"
        ).inOrder()
    }

    @Test
    fun `带参数的码把步数一路带到文案里`() {
        assertThat(AgentStatusCode.BLOCKED_MAX_STEPS.encode(12)).isEqualTo("blocked.max_steps#12")
        assertThat(AgentStatusCode.decode("blocked.max_steps#12"))
            .isEqualTo(UiMessage.of(R.string.agent_blocked_max_steps, 12))
    }

    @Test
    fun `历史行的中文原文原样透出而不是被吞掉`() {
        // 升级前落库的行就是这些中文字面量。decode 认不出它们，但用户仍要看得懂，
        // 所以走 Raw 原样显示 —— 绝不能返回 null 或空串，那是把唯一的线索抹掉。
        listOf(
            "任务被中断（取消或异常）",
            "用户已停止本轮 Agent 执行",
            "应用上次退出时任务被中断，可重新发起",
            "已达最大步数 12"
        ).forEach {
            assertThat(AgentStatusCode.decode(it)).isEqualTo(UiMessage.Raw(it))
        }
    }

    @Test
    fun `模型自己写的摘要原样透出，含井号也不误拆`() {
        // summary 列混着模型正文，里面出现 # 很正常（Markdown 标题）。
        val summary = "已经把 ## 小结 那一节改写完了"
        assertThat(AgentStatusCode.decode(summary)).isEqualTo(UiMessage.Raw(summary))
    }

    @Test
    fun `空值与空白返回 null，界面就不画这一行`() {
        assertThat(AgentStatusCode.decode(null)).isNull()
        assertThat(AgentStatusCode.decode("")).isNull()
        assertThat(AgentStatusCode.decode("   ")).isNull()
    }

    @Test
    fun `认不出的码与坏参数都不把裸码甩给用户`() {
        // 未知前缀：整段原样透出（可能是别的版本写的自由文本）
        assertThat(AgentStatusCode.decode("blocked.unknown_thing"))
            .isEqualTo(UiMessage.Raw("blocked.unknown_thing"))
        assertThat(AgentStatusCode.decode("blocked.unknown_thing#7"))
            .isEqualTo(UiMessage.Raw("blocked.unknown_thing#7"))
        // 已知码 + 坏参数：仍走文案，不退回裸码
        assertThat(AgentStatusCode.decode("blocked.max_steps#abc"))
            .isEqualTo(UiMessage.Res(R.string.agent_blocked_max_steps))
    }
}
