package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.ChatMessage
import org.junit.jupiter.api.Test

/**
 * [AgentContextTrimmer] 单测：裁剪必须以「回合组」为单位，assistant 工具调用与它的
 * tool 结果永不拆散；CJK 字符按 1 token 估算；最近消息有保护区。
 */
class AgentContextTrimmerTest {

    private fun user(text: String) = ChatMessage(role = "user", content = text)
    private fun assistant(text: String) = ChatMessage(role = "assistant", content = text)
    private fun toolResult(text: String, id: String = "t1") =
        ChatMessage(role = "tool", content = text, toolCallId = id, toolName = "read_document")

    private fun cjk(length: Int) = "汉".repeat(length)

    @Test
    fun `预算内原样返回`() {
        val messages = listOf(user("a"), assistant("b"), user("c"), assistant("d"))
        val result = AgentContextTrimmer.trim(messages, budgetTokens = 10_000)
        assertThat(result).isSameInstanceAs(messages)
    }

    @Test
    fun `超预算时丢弃最旧的回合组`() {
        val messages = buildList {
            repeat(6) { index ->
                add(user(cjk(400) + " 第$index 轮"))       // ~400 tokens
                add(assistant(cjk(400)))
            }
        }
        // 6 组 × ~800 tokens ≈ 4800，预算 2000：至少砍掉一半最旧的组
        val result = AgentContextTrimmer.trim(messages, budgetTokens = 2_000, minKeepMessages = 2)

        assertThat(result.size).isLessThan(messages.size)
        // 丢弃从最旧开始：第一轮的 user 必须已经不在
        assertThat(result.first().role).isEqualTo("user")
        assertThat(result.first().content).doesNotContain("第0 轮")
        // 最近一轮必须整组保留（保护区）：组内的 user 与 assistant 都在
        val tail = result.joinToString("|") { it.content.orEmpty() }
        assertThat(tail).contains("第5 轮")
        assertThat(tail).doesNotContain("第2 轮")
    }

    @Test
    fun `工具调用与它的结果永不拆散`() {
        val messages = buildList {
            add(user(cjk(400) + " 问0"))
            add(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        com.yumark.app.domain.model.ToolCall(
                            id = "call-0", name = "read_document", arguments = "{}"
                        )
                    )
                )
            )
            add(toolResult(cjk(400) + " 结果0", "call-0"))
            repeat(8) { index ->
                add(user(cjk(300) + " 填充$index"))
                add(assistant(cjk(300)))
            }
        }
        val result = AgentContextTrimmer.trim(messages, budgetTokens = 2_000, minKeepMessages = 2)

        // 第 0 轮的 assistant(toolCalls) 若被丢弃，它的 tool 结果绝不能单独残留
        val keptToolCallIds = result.filter { it.role == "assistant" }
            .flatMap { it.toolCalls.orEmpty() }.map { it.id }.toSet()
        result.filter { it.role == "tool" }.forEach { toolMessage ->
            assertThat(keptToolCallIds).doesNotContain(toolMessage.toolCallId)
        }
    }

    @Test
    fun `最近消息保护区不被丢弃`() {
        val messages = buildList {
            repeat(10) { index -> add(user(cjk(300) + " 第$index 轮")) }
        }
        val result = AgentContextTrimmer.trim(messages, budgetTokens = 100, minKeepMessages = 4)

        // 预算小到裁不动保护区：至少保留 4 条最近的
        assertThat(result.size).isAtLeast(4)
        assertThat(result.takeLast(4).all { it.content.orEmpty().contains(cjk(300)) }).isTrue()
    }

    @Test
    fun `单条消息超预算时原样返回不做正文截断`() {
        val messages = listOf(user(cjk(60_000)))   // 远超任何预算
        val result = AgentContextTrimmer.trim(messages, budgetTokens = 1_000, minKeepMessages = 12)

        // 裁剪器不承担截断正文的责任：改写用户数据交给上层显式处理
        assertThat(result).containsExactlyElementsIn(messages)
    }

    @Test
    fun `token 估算 CJK 按字符计其余按四分之一计`() {
        assertThat(AgentContextTrimmer.estimateTokens(cjk(100))).isEqualTo(100)
        assertThat(AgentContextTrimmer.estimateTokens("a".repeat(100))).isEqualTo(25)
        // 混合：50 个 CJK + 40 个拉丁
        assertThat(AgentContextTrimmer.estimateTokens(cjk(50) + "a".repeat(40))).isEqualTo(60)
    }
}
