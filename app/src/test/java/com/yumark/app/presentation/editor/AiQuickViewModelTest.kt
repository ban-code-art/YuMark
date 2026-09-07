package com.yumark.app.presentation.editor

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.AiTool
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.MessageRole
import com.yumark.app.domain.model.ModelInfo
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.StreamEvent
import com.yumark.app.domain.repository.AiConfigRepository
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 划词快捷对话的收尾口径：空补全要报错，不能留一个空气泡在会话里。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiQuickViewModelTest {

    private class FakeAdapter(private val events: List<StreamEvent>) : AiApiAdapter {
        override fun sendChatStream(
            messages: List<ChatMessage>,
            config: AiRequestConfig,
            tools: List<AiTool>
        ): Flow<StreamEvent> = events.asFlow()

        override suspend fun testConnection(model: String) = ModelTestResult(true, 0, 0, true)
        override suspend fun fetchAvailableModels(): List<ModelInfo> = emptyList()
        override fun close() {}
    }

    private val configRepository: AiConfigRepository = mockk()
    private val adapterFactory: com.yumark.app.domain.repository.ai.AiAdapterProvider = mockk()
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { configRepository.observeConfig() } returns flowOf(AiConfig(apiKey = "k", modelName = "m"))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun viewModel(adapter: AiApiAdapter): AiQuickViewModel {
        every { adapterFactory.chatAdapter(any()) } returns adapter
        return AiQuickViewModel(configRepository, adapterFactory)
    }

    @Test
    fun `空补全不挂空气泡而是报错`() = runTest(testDispatcher) {
        val vm = viewModel(FakeAdapter(listOf(StreamEvent.Done(""))))
        vm.setSelectedText("被选中的一段话")
        vm.updateUserInput("解释一下")

        vm.send()
        advanceUntilIdle()

        // 会话里只剩用户那一条，助手侧不留任何空壳
        assertThat(vm.conversationHistory.value.map { it.role }).containsExactly(MessageRole.USER)
        assertThat(vm.error.value).isEqualTo(UiMessage.Res(R.string.ai_error_empty_response))
        assertThat(vm.isLoading.value).isFalse()
    }

    @Test
    fun `正常回复追加助手消息且不报错`() = runTest(testDispatcher) {
        val vm = viewModel(FakeAdapter(listOf(StreamEvent.Content("这段话在说"), StreamEvent.Done("这段话在说 X"))))
        vm.setSelectedText("被选中的一段话")
        vm.updateUserInput("解释一下")

        vm.send()
        advanceUntilIdle()

        val history = vm.conversationHistory.value
        assertThat(history.map { it.role }).containsExactly(MessageRole.USER, MessageRole.ASSISTANT)
        assertThat(history.last().content).isEqualTo("这段话在说 X")
        assertThat(vm.error.value).isNull()
        assertThat(vm.isLoading.value).isFalse()
    }

    @Test
    fun `Done 不带 fullText 时保留流式已经收到的正文`() = runTest(testDispatcher) {
        // 反向守护：末帧不带 fullText 的 provider 不少，正文全在流里，不能误判成空补全。
        val vm = viewModel(FakeAdapter(listOf(StreamEvent.Content("流里收到的正文"), StreamEvent.Done(""))))
        vm.setSelectedText("被选中的一段话")
        vm.updateUserInput("解释一下")

        vm.send()
        advanceUntilIdle()

        assertThat(vm.conversationHistory.value.last().content).isEqualTo("流里收到的正文")
        assertThat(vm.error.value).isNull()
    }
}
