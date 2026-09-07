package com.yumark.app.domain.usecase.ai.chat

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import com.yumark.app.domain.repository.ai.AiAdapterProvider
import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.AiTool
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.Conversation
import com.yumark.app.domain.model.ConversationType
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
import com.yumark.app.domain.model.ModelInfo
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.StreamEvent
import com.yumark.app.domain.repository.AiConfigRepository
import com.yumark.app.domain.repository.ConversationRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 普通聊天的收尾口径：空补全不能变成空气泡，上下文不能把占位消息发给模型。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendChatMessageUseCaseTest {

    /** 按预设序列吐事件的假适配器，同时记录每次请求带上的上下文。 */
    private class FakeAdapter(private val events: List<StreamEvent>) : AiApiAdapter {
        val messagesByCall = mutableListOf<List<ChatMessage>>()

        override fun sendChatStream(
            messages: List<ChatMessage>,
            config: AiRequestConfig,
            tools: List<AiTool>
        ): Flow<StreamEvent> {
            messagesByCall.add(messages)
            return events.asFlow()
        }

        override suspend fun testConnection(model: String) = ModelTestResult(true, 0, 0, true)
        override suspend fun fetchAvailableModels(): List<ModelInfo> = emptyList()
        override fun close() {}
    }

    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val configRepository: AiConfigRepository = mockk()
    private val adapterFactory: AiAdapterProvider = mockk()

    private val config = AiConfig(apiKey = "k", modelName = "m")
    private val conversation = Conversation(id = "c1", title = "t", type = ConversationType.CHAT)

    @BeforeEach
    fun setup() {
        every { configRepository.observeConfig() } returns flowOf(config)
        every { conversationRepository.observeConversation("c1") } returns flowOf(conversation)
    }

    private fun useCase(adapter: AiApiAdapter): SendChatMessageUseCase {
        every { adapterFactory.chatAdapter(any()) } returns adapter
        return SendChatMessageUseCase(conversationRepository, configRepository, adapterFactory)
    }

    private fun errorIdOf(state: ChatMessageState.Error): Int =
        (state.message as UiMessage.Res).id

    @Test
    fun `空补全的 Done 删掉占位而不是留下一个空气泡`() = runTest {
        // 适配层把「重试若干次仍是空补全」也收敛成 Done("")（OpenAiAdapter.runAttempt 末尾；
        // Claude/Gemini 连空重试都没有）。照常落库的话库里多一条正文为空的助手消息，
        // 界面上是个永久空气泡，用户拿不到任何线索。
        val adapter = FakeAdapter(listOf(StreamEvent.Done("")))

        val states = useCase(adapter).invoke("c1", "在吗").toList()

        val errors = states.filterIsInstance<ChatMessageState.Error>()
        assertThat(errors).hasSize(1)
        assertThat(errorIdOf(errors.first())).isEqualTo(R.string.ai_error_empty_response)
        assertThat(states.filterIsInstance<ChatMessageState.Completed>()).isEmpty()
        coVerify(exactly = 1) { conversationRepository.deleteMessage(any()) }
        coVerify(exactly = 0) { conversationRepository.updateMessage(any()) }
    }

    @Test
    fun `Done 的 fullText 为空但流式已有正文时照常收尾`() = runTest {
        // 反向守护：不少 provider 的最后一帧本来就不带 fullText，正文全在流里。
        // 这种情况不能误判成空补全。
        val adapter = FakeAdapter(listOf(StreamEvent.Content("已经收到"), StreamEvent.Done("")))

        val states = useCase(adapter).invoke("c1", "在吗").toList()

        assertThat(states.filterIsInstance<ChatMessageState.Completed>().map { it.fullText })
            .containsExactly("已经收到")
        assertThat(states.filterIsInstance<ChatMessageState.Error>()).isEmpty()
        coVerify(exactly = 0) { conversationRepository.deleteMessage(any()) }
        coVerify {
            conversationRepository.updateMessage(match { it.content == "已经收到" && !it.isStreaming })
        }
    }

    @Test
    fun `正常回复落库并完成`() = runTest {
        val adapter = FakeAdapter(listOf(StreamEvent.Content("答"), StreamEvent.Done("答案全文")))

        val states = useCase(adapter).invoke("c1", "问题").toList()

        assertThat(states.filterIsInstance<ChatMessageState.Completed>().map { it.fullText })
            .containsExactly("答案全文")
        coVerify(exactly = 0) { conversationRepository.deleteMessage(any()) }
        coVerify {
            conversationRepository.updateMessage(match { it.content == "答案全文" && !it.isStreaming })
        }
    }

    @Test
    fun `未配置时只保存用户消息就报错`() = runTest {
        every { configRepository.observeConfig() } returns flowOf(config.copy(apiKey = ""))

        val states = useCase(FakeAdapter(emptyList())).invoke("c1", "问题").toList()

        val errors = states.filterIsInstance<ChatMessageState.Error>()
        assertThat(errors).hasSize(1)
        assertThat(errorIdOf(errors.first())).isEqualTo(R.string.ai_error_not_configured)
        // 用户消息进了库（重开对话看得见自己问过什么），但助手占位一条都不该建
        coVerify(exactly = 1) { conversationRepository.addMessage(any()) }
        coVerify(exactly = 0) { conversationRepository.updateMessage(any()) }
    }

    @Test
    fun `上下文里不含流式占位与空正文消息`() = runTest {
        // isStreaming 没有落库（messages 表无此列），从库里读回来永远是 false ——
        // 真正把空占位挡在请求外的是 content.isNotBlank() 这一条。
        every { conversationRepository.observeConversation("c1") } returns flowOf(
            conversation.copy(
                messages = listOf(
                    Message(conversationId = "c1", role = MessageRole.USER, content = "上一轮的问题"),
                    Message(conversationId = "c1", role = MessageRole.ASSISTANT, content = "上一轮的回答"),
                    Message(conversationId = "c1", role = MessageRole.ASSISTANT, content = "", isStreaming = true),
                    Message(conversationId = "c1", role = MessageRole.ASSISTANT, content = "   ")
                )
            )
        )
        val adapter = FakeAdapter(listOf(StreamEvent.Done("好")))

        useCase(adapter).invoke("c1", "这一轮").toList()

        assertThat(adapter.messagesByCall).hasSize(1)
        assertThat(adapter.messagesByCall.first().map { it.content })
            .containsExactly("上一轮的问题", "上一轮的回答")
        assertThat(adapter.messagesByCall.first().map { it.role })
            .containsExactly("user", "assistant")
    }
}
