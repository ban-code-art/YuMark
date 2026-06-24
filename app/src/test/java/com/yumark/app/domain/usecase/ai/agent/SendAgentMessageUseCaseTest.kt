package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.AiTool
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.Conversation
import com.yumark.app.domain.model.ConversationStatus
import com.yumark.app.domain.model.ConversationType
import com.yumark.app.domain.model.ModelInfo
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.StreamEvent
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.repository.AiConfigRepository
import com.yumark.app.domain.repository.AgentTaskRepository
import com.yumark.app.domain.repository.ConversationRepository
import com.yumark.app.domain.usecase.ai.DocumentContextTools
import com.yumark.app.domain.usecase.ai.ExecuteDocumentToolUseCase
import io.mockk.coEvery
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

@OptIn(ExperimentalCoroutinesApi::class)
class SendAgentMessageUseCaseTest {

    /** 按轮次返回预设事件序列的假适配器（驱动单循环）。 */
    private class FakeAdapter(private val rounds: List<List<StreamEvent>>) : AiApiAdapter {
        var callCount = 0
            private set
        val configs = mutableListOf<AiRequestConfig>()
        val messagesByCall = mutableListOf<List<ChatMessage>>()

        override fun sendChatStream(
            messages: List<ChatMessage>,
            config: AiRequestConfig,
            tools: List<AiTool>
        ): Flow<StreamEvent> {
            configs.add(config)
            messagesByCall.add(messages)
            val events = rounds.getOrElse(callCount) { listOf(StreamEvent.Done("")) }
            callCount++
            return events.asFlow()
        }

        override suspend fun testConnection(model: String) = ModelTestResult(true, 0, 0, true)
        override suspend fun fetchAvailableModels(): List<ModelInfo> = emptyList()
        override fun close() {}
    }

    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val configRepository: AiConfigRepository = mockk()
    private val adapterFactory: AiAdapterFactory = mockk()
    private val executeDocumentTool: ExecuteDocumentToolUseCase = mockk()
    private val imageProcessor: com.yumark.app.core.image.ImageProcessor = mockk()
    private val agentTaskRepository: AgentTaskRepository = mockk(relaxed = true)
    private val buildWriteProposal: BuildWriteProposalUseCase = mockk()
    private val webSearchService: com.yumark.app.data.ai.web.WebSearchService = mockk(relaxed = true)
    private val memoryService: com.yumark.app.data.ai.memory.MemoryService = mockk(relaxed = true)
    private val ragPipeline: com.yumark.app.data.ai.rag.RagPipeline = mockk(relaxed = true)

    private val config = AiConfig(apiKey = "k", modelName = "m")
    private val conversation = Conversation(id = "c1", title = "t", type = ConversationType.AGENT)

    @BeforeEach
    fun setup() {
        every { configRepository.observeConfig() } returns flowOf(config)
        every { conversationRepository.observeConversation("c1") } returns flowOf(conversation)
        coEvery { agentTaskRepository.getTaskByConversationId(any()) } returns null
        coEvery { webSearchService.search(any()) } returns Result.success("（测试：网络搜索未启用）")
        coEvery { memoryService.execute(any()) } returns Result.success("（测试：记忆工具）")
        coEvery { ragPipeline.execute(any()) } returns Result.success("（测试：知识库）")
    }

    private fun useCase(adapter: AiApiAdapter): SendAgentMessageUseCase {
        every { adapterFactory.createAdapter(any()) } returns adapter
        return SendAgentMessageUseCase(
            conversationRepository,
            configRepository,
            adapterFactory,
            imageProcessor,
            agentTaskRepository,
            executeDocumentTool,
            buildWriteProposal,
            webSearchService,
            memoryService,
            ragPipeline
        )
    }

    @Test
    fun `single text turn completes without tools`() = runTest {
        val adapter = FakeAdapter(listOf(
            listOf(StreamEvent.Content("hello"), StreamEvent.Done("hello"))
        ))
        val states = useCase(adapter).invoke("c1", "hi", null, null, null).toList()

        assertThat(states.filterIsInstance<AgentMessageState.Completed>().map { it.fullText }).contains("hello")
        assertThat(adapter.callCount).isEqualTo(1)  // 无预规划轮
        coVerify(exactly = 0) { executeDocumentTool(any()) }
    }

    @Test
    fun `read tool then converges on next round`() = runTest {
        coEvery { executeDocumentTool(any()) } returns Result.success("工具结果")
        val adapter = FakeAdapter(listOf(
            listOf(StreamEvent.ToolCallComplete(listOf(ToolCall("c1", "read_document", """{"document_id":"x"}"""))), StreamEvent.Done("")),
            listOf(StreamEvent.Content("最终答案"), StreamEvent.Done("最终答案"))
        ))

        val states = useCase(adapter).invoke("c1", "读一下", null, null, null).toList()

        coVerify(exactly = 1) { executeDocumentTool(any()) }
        assertThat(adapter.callCount).isEqualTo(2)
        assertThat(states.filterIsInstance<AgentMessageState.Completed>().map { it.fullText }).contains("最终答案")
        assertThat(states.filterIsInstance<AgentMessageState.ToolStep>()).isNotEmpty()
    }

    @Test
    fun `update_plan creates a model-driven task`() = runTest {
        val planArgs = """{"steps":[{"title":"检索资料","status":"in_progress"},{"title":"撰写","status":"pending"}]}"""
        val adapter = FakeAdapter(listOf(
            listOf(StreamEvent.ToolCallComplete(listOf(ToolCall("c1", "update_plan", planArgs))), StreamEvent.Done("")),
            listOf(StreamEvent.Content("done"), StreamEvent.Done("done"))
        ))

        useCase(adapter).invoke("c1", "整理笔记", null, null, null).toList()

        coVerify {
            agentTaskRepository.createTask(
                match { it.conversationId == "c1" },
                match { steps -> steps.map { it.title } == listOf("检索资料", "撰写") }
            )
        }
    }

    @Test
    fun `edit tool proposal stops the loop and emits action`() = runTest {
        coEvery { buildWriteProposal(any(), any()) } returns Result.success(
            AgentAction(AgentActionType.EDIT_DOCUMENT, "编辑文档", targetDocumentId = "doc-1", content = "合成后的新全文")
        )
        val adapter = FakeAdapter(listOf(
            listOf(
                StreamEvent.ToolCallComplete(listOf(ToolCall("c1", "edit_document",
                    """{"document_id":"doc-1","edits":[{"old_string":"旧","new_string":"新"}]}"""))),
                StreamEvent.Done("")
            )
        ))

        val states = useCase(adapter).invoke("c1", "改一下第二节", "doc-1", "笔记", "旧内容").toList()

        assertThat(adapter.callCount).isEqualTo(1)  // 写提议即结束本轮
        val proposed = states.filterIsInstance<AgentMessageState.ActionProposed>()
        assertThat(proposed).isNotEmpty()
        assertThat(proposed.first().action.type).isEqualTo(AgentActionType.EDIT_DOCUMENT)
        assertThat(proposed.first().action.content).isEqualTo("合成后的新全文")
        coVerify(exactly = 0) { executeDocumentTool(any()) }
    }

    @Test
    fun `failed edit feeds error back and loop continues`() = runTest {
        coEvery { buildWriteProposal(any(), any()) } returns Result.failure(
            com.yumark.app.domain.usecase.ai.EditException("第1处编辑未命中：请先用 read_document 获取确切原文。")
        )
        val adapter = FakeAdapter(listOf(
            listOf(StreamEvent.ToolCallComplete(listOf(ToolCall("c1", "edit_document",
                """{"document_id":"doc-1","edits":[{"old_string":"x","new_string":"y"}]}"""))), StreamEvent.Done("")),
            listOf(StreamEvent.Content("我重新定位后再试"), StreamEvent.Done("我重新定位后再试"))
        ))

        val states = useCase(adapter).invoke("c1", "改写", "doc-1", "笔记", "旧内容").toList()

        assertThat(adapter.callCount).isEqualTo(2)  // 失败回填后继续
        assertThat(states.filterIsInstance<AgentMessageState.ActionProposed>()).isEmpty()
        // 第二轮上下文里应带有上一轮的工具错误回填
        val toolMsgs = adapter.messagesByCall[1].filter { it.role == "tool" }
        assertThat(toolMsgs.any { it.content?.contains("ERROR") == true }).isTrue()
    }

    @Test
    fun `doom loop on repeated identical tool calls stops`() = runTest {
        coEvery { executeDocumentTool(any()) } returns Result.success("结果")
        val sameCall = listOf(
            StreamEvent.ToolCallComplete(listOf(ToolCall("c1", "search_in_project", """{"query":"q"}"""))),
            StreamEvent.Done("")
        )
        val adapter = FakeAdapter(listOf(sameCall, sameCall))

        val states = useCase(adapter).invoke("c1", "搜", null, null, null).toList()

        assertThat(adapter.callCount).isEqualTo(2)
        assertThat(states.filterIsInstance<AgentMessageState.Completed>()).isNotEmpty()
    }

    @Test
    fun `stops at max turns when model keeps calling distinct tools`() = runTest {
        coEvery { executeDocumentTool(any()) } returns Result.success("结果")
        val rounds = (1..15).map { i ->
            listOf(
                StreamEvent.ToolCallComplete(listOf(ToolCall("c$i", "search_in_project", """{"query":"q$i"}"""))),
                StreamEvent.Done("")
            )
        }
        val adapter = FakeAdapter(rounds)

        val states = useCase(adapter).invoke("c1", "搜", null, null, null).toList()

        assertThat(adapter.callCount).isAtMost(10)  // MAX_TURNS
        assertThat(adapter.callCount).isAtLeast(3)
        assertThat(states.filterIsInstance<AgentMessageState.Completed>()).isNotEmpty()
    }

    @Test
    fun `empty response yields actionable notice and blocked`() = runTest {
        val adapter = FakeAdapter(listOf(listOf(StreamEvent.Done(""))))
        val states = useCase(adapter).invoke("c1", "hi", null, null, null).toList()

        assertThat(states.filterIsInstance<AgentMessageState.Notice>()).isNotEmpty()
        assertThat(states.filterIsInstance<AgentMessageState.ActionProposed>()).isEmpty()
    }

    @Test
    fun `missing config resets conversation back to idle`() = runTest {
        every { configRepository.observeConfig() } returns flowOf(config.copy(apiKey = ""))
        val states = useCase(FakeAdapter(emptyList())).invoke("c1", "hi", null, null, null).toList()

        assertThat(states.filterIsInstance<AgentMessageState.Error>()).isNotEmpty()
        coVerify { conversationRepository.updateConversation(match { it.status == ConversationStatus.WORKING }) }
        coVerify { conversationRepository.updateConversation(match { it.status == ConversationStatus.IDLE }) }
    }

    @Test
    fun `implicit full markdown becomes create proposal for weak tool models`() = runTest {
        val docText = """
            # 人工智能入门

            人工智能（AI）是研究、开发用于模拟、延伸和扩展人的智能的理论、方法、技术及应用系统的一门新技术科学。

            ## 核心概念
            - 机器学习：让机器从数据中学习规律
            - 深度学习：基于神经网络的子领域
        """.trimIndent()
        val adapter = FakeAdapter(listOf(listOf(StreamEvent.Content(docText), StreamEvent.Done(docText))))

        val states = useCase(adapter).invoke("c1", "创建一份关于人工智能的md文档", null, null, null).toList()

        val proposed = states.filterIsInstance<AgentMessageState.ActionProposed>()
        assertThat(proposed).isNotEmpty()
        assertThat(proposed.first().action.type).isEqualTo(AgentActionType.CREATE_DOCUMENT)
    }

    @Test
    fun `implicit full markdown becomes edit proposal when editing current document`() = runTest {
        val docText = """
            # 润色后的笔记

            这是经过润色与补充的版本，结构更清晰、表述更完整。
            - 要点一
            - 要点二
        """.trimIndent()
        val adapter = FakeAdapter(listOf(listOf(StreamEvent.Content(docText), StreamEvent.Done(docText))))

        val states = useCase(adapter).invoke("c1", "帮我增加内容", "doc-1", "我的笔记", "旧内容").toList()

        val proposed = states.filterIsInstance<AgentMessageState.ActionProposed>()
        assertThat(proposed).isNotEmpty()
        assertThat(proposed.first().action.type).isEqualTo(AgentActionType.EDIT_DOCUMENT)
        assertThat(proposed.first().action.targetDocumentId).isEqualTo("doc-1")
    }

    @Test
    fun `plain short answer is not misread as a document write`() = runTest {
        val answer = "RAG 是检索增强生成，通过外挂知识库提升大模型回答的事实准确性。"
        val adapter = FakeAdapter(listOf(listOf(StreamEvent.Content(answer), StreamEvent.Done(answer))))

        val states = useCase(adapter).invoke("c1", "什么是 RAG", null, null, null).toList()

        assertThat(states.filterIsInstance<AgentMessageState.ActionProposed>()).isEmpty()
        assertThat(states.filterIsInstance<AgentMessageState.Completed>().map { it.fullText }).contains(answer)
    }

    @Test
    fun `system prompt is tool-first with surgical edit guidance`() {
        val prompt = buildAgentSystemPrompt(documentName = null, documentContent = null, tools = DocumentContextTools.getAllTools())
        assertThat(prompt).contains("外科式编辑")
        assertThat(prompt).contains("edit_document")
        assertThat(prompt).contains("update_plan")
        assertThat(prompt).contains("old_string")
    }

    @Test
    fun `system prompt injects full current document content when small`() {
        val prompt = buildAgentSystemPrompt(documentName = "笔记", documentContent = "# 标题\n正文", tools = DocumentContextTools.getAllTools())
        assertThat(prompt).contains("当前打开的文档：《笔记》")
        assertThat(prompt).contains("正文")
    }
}
