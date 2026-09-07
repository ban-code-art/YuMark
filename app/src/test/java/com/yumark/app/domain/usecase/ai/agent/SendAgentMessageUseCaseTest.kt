package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.repository.ai.AiAdapterProvider
import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskAggregate
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStepStatus
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
    private val adapterFactory: AiAdapterProvider = mockk()

    /** 压缩器桩：默认返回 null（回退纯裁剪，既有用例行为不变）。 */
    private val conversationCompressor: com.yumark.app.domain.usecase.ai.agent.ConversationCompressor = mockk {
        coEvery { compress(any(), any()) } returns null
    }
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
        every { adapterFactory.chatAdapter(any()) } returns adapter
        // 两个端口：webSearch 直连，memory+rag 聚合为一个 AgentToolService（按工具名分发）
        val memoryAndKnowledge = object : com.yumark.app.domain.repository.ai.AgentToolService {
            override suspend fun execute(toolCall: com.yumark.app.domain.model.ToolCall): Result<String> =
                if (toolCall.name in setOf("save_memory", "search_memory", "list_memories")) {
                    memoryService.execute(toolCall)
                } else {
                    ragPipeline.execute(toolCall)
                }
        }
        return SendAgentMessageUseCase(
            conversationRepository,
            configRepository,
            adapterFactory,
            conversationCompressor,
            imageProcessor,
            agentTaskRepository,
            executeDocumentTool,
            buildWriteProposal,
            webSearchService,
            memoryAndKnowledge
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
    fun `同轮多个只读工具并行执行且消息按调用顺序回填`() = runTest {
        coEvery { executeDocumentTool(any()) } returns Result.success("文档内容")
        // 两个只读调用（read_document × 2）：应并行执行，tool 消息按调用顺序回填
        val adapter = FakeAdapter(listOf(
            listOf(
                StreamEvent.ToolCallComplete(listOf(
                    ToolCall("c1", "read_document", """{"document_id":"x"}"""),
                    ToolCall("c2", "read_document", """{"document_id":"y"}""")
                )),
                StreamEvent.Done("")
            ),
            listOf(StreamEvent.Content("最终答案"), StreamEvent.Done("最终答案"))
        ))

        val states = useCase(adapter).invoke("c1", "读两篇", null, null, null).toList()

        // 两个工具都执行了（mockk 记录两条不同 document_id 的调用）
        coVerify(exactly = 1) { executeDocumentTool(match { it.arguments.contains("x") }) }
        coVerify(exactly = 1) { executeDocumentTool(match { it.arguments.contains("y") }) }
        assertThat(adapter.callCount).isEqualTo(2)
        // tool 消息顺序与 tool_calls 顺序一致（OpenAI 协议硬约束）
        val toolMessages = adapter.messagesByCall[1].filter { it.role == "tool" }
        assertThat(toolMessages.map { it.toolCallId }).containsExactly("c1", "c2").inOrder()
        assertThat(states.filterIsInstance<AgentMessageState.Completed>().map { it.fullText }).contains("最终答案")
    }

    @Test
    fun `同轮写提议不参与并行并立即收敛等待审批`() = runTest {
        val proposalAction = com.yumark.app.domain.model.AgentAction(
            type = com.yumark.app.domain.model.AgentActionType.CREATE_DOCUMENT,
            description = "新文档", targetDocumentId = null, content = "新内容"
        )
        coEvery { executeDocumentTool(any()) } returns Result.success("工具结果")
        coEvery { buildWriteProposal(any(), any()) } returns Result.success(proposalAction)
        val adapter = FakeAdapter(listOf(
            listOf(
                StreamEvent.ToolCallComplete(listOf(
                    ToolCall("c1", "read_document", """{"document_id":"x"}"""),
                    ToolCall("c2", "create_document", """{"content":"新内容"}""")
                )),
                StreamEvent.Done("")
            )
        ))

        val states = useCase(adapter).invoke("c1", "建一篇", null, null, null).toList()

        // 混合轮次走串行：写提议产生即收敛（不再进入下一轮）
        assertThat(adapter.callCount).isEqualTo(1)
        assertThat(states.filterIsInstance<AgentMessageState.ActionProposed>()).isNotEmpty()
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
    fun `第二轮开头就出错时不能删掉第一轮已经显示的正文与步骤`() = runTest {
        // full 每轮开头都被清空。出错分支从前只看 full 是否为空就决定删整条消息，于是第二轮
        // 一开始断网时，用户眼前那条消息（第一轮的正文 + 整条工具时间线）会凭空消失。
        coEvery { executeDocumentTool(any()) } returns Result.success("工具结果")
        val adapter = FakeAdapter(listOf(
            listOf(
                StreamEvent.Content("先读一下文档"),
                StreamEvent.ToolCallComplete(listOf(ToolCall("c1", "read_document", """{"document_id":"x"}"""))),
                StreamEvent.Done("先读一下文档")
            ),
            listOf(StreamEvent.Error(com.yumark.app.core.util.UiMessage.Raw("网络中断")))
        ))

        val states = useCase(adapter).invoke("c1", "读一下", null, null, null).toList()

        assertThat(states.filterIsInstance<AgentMessageState.Error>()).isNotEmpty()
        coVerify(exactly = 0) { conversationRepository.deleteMessage(any()) }
        // 正文保留 + 步骤一起写回（其余几处收尾都带 steps，只有出错这处漏了）
        coVerify {
            conversationRepository.updateMessage(match {
                it.content == "先读一下文档" && !it.isStreaming && it.steps.isNotEmpty()
            })
        }
    }

    @Test
    fun `第一轮什么都没产生就出错时删掉空消息`() = runTest {
        // 反向守护：真正一个字、一步都没有的空气泡应该删掉，不能因为上一条测试而变成永不删除。
        val adapter = FakeAdapter(listOf(listOf(StreamEvent.Error(com.yumark.app.core.util.UiMessage.Raw("网络中断")))))

        val states = useCase(adapter).invoke("c1", "hi", null, null, null).toList()

        assertThat(states.filterIsInstance<AgentMessageState.Error>()).isNotEmpty()
        coVerify(exactly = 1) { conversationRepository.deleteMessage(any()) }
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

    /** 让 getTaskByConversationId("c1") 返回一个含一条 RUNNING 步、一条 DONE 步的进行中任务。 */
    private fun seedRunningTask() {
        val now = System.currentTimeMillis()
        val task = AgentTask("t1", "c1", "整理", AgentTaskStatus.EXECUTING, now, now)
        val running = AgentTaskStep("s1", "t1", "检索", "", AgentTaskStepStatus.RUNNING, 0, completionCriteria = "")
        val done = AgentTaskStep("s2", "t1", "阅读", "", AgentTaskStepStatus.DONE, 1, completionCriteria = "")
        coEvery { agentTaskRepository.getTaskByConversationId("c1") } returns
            AgentTaskAggregate(task, listOf(running, done), emptyList())
    }

    @Test
    fun `完成收尾把残留 RUNNING 步收敛为 DONE 且不动已完成步`() = runTest {
        // 模型把某步标 in_progress 后直接给最终答案、不再补收尾 update_plan：该 RUNNING 步必须随
        // 任务终态收敛，否则时间线在 COMPLETED 胶囊下永久脉冲（finally/stop 只覆盖了中断路径）。
        seedRunningTask()
        val adapter = FakeAdapter(listOf(listOf(StreamEvent.Content("最终答案"), StreamEvent.Done("最终答案"))))

        useCase(adapter).invoke("c1", "hi", null, null, null).toList()

        coVerify(exactly = 1) { agentTaskRepository.markStepStatus("s1", AgentTaskStepStatus.DONE, any()) }
        coVerify(exactly = 0) { agentTaskRepository.markStepStatus("s2", any(), any()) }
        coVerify { agentTaskRepository.updateTask(match { it.status == AgentTaskStatus.COMPLETED && it.currentStepId == null }) }
    }

    @Test
    fun `阻塞收尾把残留 RUNNING 步收敛为 BLOCKED`() = runTest {
        // 空响应 → 任务 BLOCKED；在途那步正是被阻塞的那步。
        seedRunningTask()
        val adapter = FakeAdapter(listOf(listOf(StreamEvent.Done(""))))

        useCase(adapter).invoke("c1", "hi", null, null, null).toList()

        coVerify(exactly = 1) { agentTaskRepository.markStepStatus("s1", AgentTaskStepStatus.BLOCKED, any()) }
        coVerify { agentTaskRepository.updateTask(match { it.status == AgentTaskStatus.BLOCKED }) }
    }

    @Test
    fun `失败收尾把残留 RUNNING 步收敛为 FAILED`() = runTest {
        // 到达 MAX_TURNS 仍未收敛 → 任务 FAILED；未完成的在途步随之判 FAILED。
        seedRunningTask()
        coEvery { executeDocumentTool(any()) } returns Result.success("结果")
        val rounds = (1..15).map { i ->
            listOf(
                StreamEvent.ToolCallComplete(listOf(ToolCall("c$i", "search_in_project", """{"query":"q$i"}"""))),
                StreamEvent.Done("")
            )
        }

        useCase(FakeAdapter(rounds)).invoke("c1", "搜", null, null, null).toList()

        coVerify(exactly = 1) { agentTaskRepository.markStepStatus("s1", AgentTaskStepStatus.FAILED, any()) }
        coVerify { agentTaskRepository.updateTask(match { it.status == AgentTaskStatus.FAILED }) }
    }

    @Test
    fun `重规划后 currentStepId 不保留旧步骤的悬空指针`() = runTest {
        // replaceSteps 重铸全部步骤 UUID：若 updateTask 保留 existing.currentStepId，它就指向
        // 已删除的步骤。今天没有代码写非 null 值，但一旦将来有人写，悬空指针只能靠 UI 侧
        // toUiStateOrNull 的回退链「碰巧遮蔽」——显式清空不依赖那两条侥幸。
        seedRunningTask()
        val replan = """{"steps":[{"title":"新计划步骤","status":"in_progress"}]}"""
        val adapter = FakeAdapter(listOf(
            listOf(StreamEvent.ToolCallComplete(listOf(ToolCall("c1", "update_plan", replan))), StreamEvent.Done("")),
            listOf(StreamEvent.Content("完成"), StreamEvent.Done("完成"))
        ))

        useCase(adapter).invoke("c1", "重规划", null, null, null).toList()

        coVerify(exactly = 1) { agentTaskRepository.replaceSteps("t1", any()) }
        // replan 后的 updateTask 必须把 currentStepId 置 null（旧值 "s-old" 若被保留即悬空）
        coVerify(atLeast = 1) {
            agentTaskRepository.updateTask(match { it.id == "t1" && it.currentStepId == null })
        }
        coVerify(exactly = 0) {
            agentTaskRepository.updateTask(match { it.id == "t1" && it.currentStepId != null })
        }
    }
}
