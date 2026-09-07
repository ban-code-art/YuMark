package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.StreamEvent
import com.yumark.app.domain.repository.ai.AiAdapterProvider
import com.yumark.app.domain.repository.ai.AiApiAdapter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [ConversationCompressor]（专项①）语义：产出注入格式、失败/空回退 null、
 * 请求构造带摘要指令与低档参数。
 */
class ConversationCompressorTest {

    private val adapterProvider: AiAdapterProvider = mockk()
    private val adapter: AiApiAdapter = mockk()
    private val compressor = ConversationCompressor(adapterProvider)
    private val config = AiConfig(apiKey = "k", modelName = "m")

    private val requestSlot = slot<AiRequestConfig>()

    private fun stubAdapter(events: Flow<StreamEvent>) {
        every { adapterProvider.chatAdapter(any()) } returns adapter
        every { adapter.sendChatStream(any(), capture(requestSlot), any()) } returns events
    }

    @Test
    fun `成功产出摘要文本`() = runTest {
        stubAdapter(flowOf(StreamEvent.Content("任务A完成。"), StreamEvent.Done("任务A完成。")))

        val out = compressor.compress(
            listOf(ChatMessage(role = "user", content = "做A"), ChatMessage(role = "assistant", content = "A完成")),
            config
        )

        assertThat(out).isEqualTo("任务A完成。")
    }

    @Test
    fun `请求携带低档参数与摘要指令`() = runTest {
        stubAdapter(flowOf(StreamEvent.Content("s"), StreamEvent.Done("s")))

        compressor.compress(
            listOf(ChatMessage(role = "user", content = "x")),
            config
        )

        // 成本护栏：低温 + 小预算（与主对话解耦）
        assertThat(requestSlot.captured.temperature).isEqualTo(0.2f)
        assertThat(requestSlot.captured.maxTokens).isEqualTo(1024)
        assertThat(requestSlot.captured.model).isEqualTo("m")
    }

    @Test
    fun `流中 Error 事件返回 null（回退裁剪）`() = runTest {
        stubAdapter(flowOf(StreamEvent.Error(com.yumark.app.core.util.UiMessage.Raw("boom"))))

        val out = compressor.compress(
            listOf(ChatMessage(role = "user", content = "x")), config
        )

        assertThat(out).isNull()
    }

    @Test
    fun `空产出与空消息返回 null`() = runTest {
        stubAdapter(flowOf(StreamEvent.Done("")))

        assertThat(compressor.compress(listOf(ChatMessage(role = "user", content = "x")), config)).isNull()
        assertThat(compressor.compress(emptyList(), config)).isNull()
    }

    @Test
    fun `多段正文拼接为完整摘要`() = runTest {
        stubAdapter(flow {
            emit(StreamEvent.Content("第一段。"))
            emit(StreamEvent.Content("第二段。"))
            emit(StreamEvent.Done("第一段。第二段。"))
        })

        val out = compressor.compress(
            listOf(ChatMessage(role = "user", content = "x")), config
        )

        assertThat(out).isEqualTo("第一段。第二段。")
    }

    @Test
    fun `异常被吞掉返回 null（绝不阻塞对话）`() = runTest {
        every { adapterProvider.chatAdapter(any()) } throws RuntimeException("adapter boom")

        val out = compressor.compress(
            listOf(ChatMessage(role = "user", content = "x")), config
        )

        assertThat(out).isNull()
    }
}
