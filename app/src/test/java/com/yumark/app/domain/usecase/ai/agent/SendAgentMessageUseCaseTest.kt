package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.AiTool
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.Conversation
import com.yumark.app.domain.model.ConversationStatus
import com.yumark.app.domain.model.ConversationType
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.domain.model.ModelInfo
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.StreamEvent
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.repository.AiConfigRepository
import com.yumark.app.domain.repository.AgentTaskRepository
import com.yumark.app.domain.repository.ConversationRepository
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

    /** 按轮次返回预设事件序列的假适配器（非 mock，便于驱动 ReAct 循环）。 */
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

    private val config = AiConfig(apiKey = "k", modelName = "m")
    private val conversation = Conversation(id = "c1", title = "t", type = ConversationType.AGENT)

    private fun invalidPlannerRound(): List<StreamEvent> =
        listOf(StreamEvent.Content("not json"), StreamEvent.Done("not json"))

    @BeforeEach
    fun setup() {
        every { configRepository.observeConfig() } returns flowOf(config)
        every { conversationRepository.observeConversation("c1") } returns flowOf(conversation)
    }

    private fun useCase(adapter: AiApiAdapter): SendAgentMessageUseCase {
        every { adapterFactory.createAdapter(any()) } returns adapter
        return SendAgentMessageUseCase(
            conversationRepository,
            configRepository,
            adapterFactory,
            imageProcessor,
            agentTaskRepository,
            PlanAgentTaskUseCase(),
            ExecuteAgentTaskUseCase(agentTaskRepository, executeDocumentTool)
        )
    }

    @Test
    fun `single turn without tools completes with text`() = runTest {
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(StreamEvent.Content("hello"), StreamEvent.Done("hello"))
        ))
        val states = useCase(adapter).invoke("c1", "hi", null, null, null).toList()

        assertThat(states.filterIsInstance<AgentMessageState.Completed>().map { it.fullText })
            .contains("hello")
        coVerify(exactly = 0) { executeDocumentTool(any()) }
        coVerify {
            agentTaskRepository.createTask(
                match { it.conversationId == "c1" && it.status == AgentTaskStatus.EXECUTING },
                match { it.isNotEmpty() && it.first().title.contains("Respond") }
            )
        }
    }

    @Test
    fun `agent prompt only asks clarification for blocking missing information`() {
        val prompt = buildAgentSystemPrompt(documentName = null, documentContent = null)

        assertThat(prompt).contains("关键信息不足时先提问")
        assertThat(prompt).contains("不要因为缺少保存位置、标题、结构或深度而反复追问")
        assertThat(prompt).doesNotContain("信息不足时先提问，不要调用 create_document / edit_document。")
        assertThat(prompt).doesNotContain("保存位置时，先询问")
        assertThat(prompt).doesNotContain("标题/主题")
    }

    @Test
    fun `planner prompt allows create document planning without save location`() {
        val prompt = buildPlannerSystemPrompt(documentName = null, documentContent = null)

        assertThat(prompt).contains("do not require the user to supply save location")
        assertThat(prompt).contains("use create_document to produce a normal pending write proposal")
        assertThat(prompt).doesNotContain("do not include create_document")
        assertThat(prompt).doesNotContain("title/scope")
    }

    @Test
    fun `document creation without save location continues into planner`() = runTest {
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(StreamEvent.Content("我会生成待确认的新文档。"), StreamEvent.Done("我会生成待确认的新文档。")),
            listOf(StreamEvent.Content("""{"outcome":"COMPLETED","summary":"answered"}"""), StreamEvent.Done("""{"outcome":"COMPLETED","summary":"answered"}"""))
        ))

        val states = useCase(adapter)
            .invoke("c1", "创建一份md文档关于ai主题", null, null, null)
            .toList()

        assertThat(states.filterIsInstance<AgentMessageState.Completed>().single().fullText)
            .contains("我会生成待确认的新文档")
        assertThat(adapter.callCount).isAtLeast(1)
        assertThat(adapter.messagesByCall.first().single().content).contains("创建一份md文档关于ai主题")
        coVerify { agentTaskRepository.createTask(any(), any()) }
        coVerify(exactly = 0) { executeDocumentTool(any()) }
    }

    @Test
    fun `legacy document creation clarification follow up resumes original creation request`() = runTest {
        val priorUser = Message(
            id = "u0",
            conversationId = "c1",
            role = MessageRole.USER,
            content = "创建一份md文档关于ai主题"
        )
        val priorAssistant = Message(
            id = "a0",
            conversationId = "c1",
            role = MessageRole.ASSISTANT,
            content = "在创建之前我还需要确认：保存位置。请告诉我要创建到哪个目录或文件夹；确认后我会根据主题自行拟定标题、结构和内容，并生成可批准的新建文档。"
        )
        every { conversationRepository.observeConversation("c1") } returns flowOf(
            conversation.copy(messages = listOf(priorUser, priorAssistant))
        )
        val adapter = FakeAdapter(listOf(
            listOf(
                StreamEvent.Content(
                    """
                    {
                      "goal": "创建 AI 学习指南文档",
                      "success_criteria": ["生成待确认的新文档"],
                      "steps": [
                        {
                          "title": "Draft document",
                          "purpose": "Create the requested markdown document",
                          "suggested_tools": ["create_document"],
                          "completion_criteria": "A create_document proposal is ready",
                          "fallback": "Ask for more details"
                        }
                      ]
                    }
                    """.trimIndent()
                ),
                StreamEvent.Done("")
            ),
            listOf(StreamEvent.Content("我会生成待确认的新文档。"), StreamEvent.Done("我会生成待确认的新文档。")),
            listOf(StreamEvent.Content("""{"outcome":"COMPLETED","summary":"answered"}"""), StreamEvent.Done("""{"outcome":"COMPLETED","summary":"answered"}"""))
        ))

        useCase(adapter)
            .invoke(
                "c1",
                "保存到根目录",
                null,
                null,
                null
            )
            .toList()

        assertThat(adapter.callCount).isAtLeast(1)
        assertThat(adapter.messagesByCall.first().single().content)
            .contains("原始需求：创建一份md文档关于ai主题")
        assertThat(adapter.messagesByCall.first().single().content)
            .contains("补充信息：保存到根目录")
        coVerify { agentTaskRepository.createTask(any(), any()) }
    }

    @Test
    fun `legacy document creation clarification accumulates optional preferences`() = runTest {
        val priorUser = Message(
            id = "u0",
            conversationId = "c1",
            role = MessageRole.USER,
            content = "创建一份md文档关于ai主题"
        )
        val firstClarification = Message(
            id = "a0",
            conversationId = "c1",
            role = MessageRole.ASSISTANT,
            content = "在创建之前我还需要确认：保存位置。请告诉我要创建到哪个目录或文件夹；确认后我会根据主题自行拟定标题、结构和内容，并生成可批准的新建文档。"
        )
        val partialAnswer = Message(
            id = "u1",
            conversationId = "c1",
            role = MessageRole.USER,
            content = "详细一点，偏科普风格"
        )
        val secondClarification = Message(
            id = "a1",
            conversationId = "c1",
            role = MessageRole.ASSISTANT,
            content = "在创建之前我还需要确认：保存位置。请告诉我要创建到哪个目录或文件夹；确认后我会根据主题自行拟定标题、结构和内容，并生成可批准的新建文档。"
        )
        every { conversationRepository.observeConversation("c1") } returns flowOf(
            conversation.copy(messages = listOf(priorUser, firstClarification, partialAnswer, secondClarification))
        )
        val adapter = FakeAdapter(listOf(
            listOf(
                StreamEvent.Content(
                    """
                    {
                      "goal": "创建 AI 学习指南文档",
                      "success_criteria": ["生成待确认的新文档"],
                      "steps": [
                        {
                          "title": "Draft document",
                          "purpose": "Create the requested markdown document",
                          "suggested_tools": ["create_document"],
                          "completion_criteria": "A create_document proposal is ready",
                          "fallback": "Ask for more details"
                        }
                      ]
                    }
                    """.trimIndent()
                ),
                StreamEvent.Done("")
            ),
            listOf(StreamEvent.Content("我会生成待确认的新文档。"), StreamEvent.Done("我会生成待确认的新文档。")),
            listOf(StreamEvent.Content("""{"outcome":"COMPLETED","summary":"answered"}"""), StreamEvent.Done("""{"outcome":"COMPLETED","summary":"answered"}"""))
        ))

        useCase(adapter)
            .invoke("c1", "保存到根目录", null, null, null)
            .toList()

        val plannerInput = adapter.messagesByCall.first().single().content.orEmpty()
        assertThat(plannerInput).contains("原始需求：创建一份md文档关于ai主题")
        assertThat(plannerInput).contains("详细一点，偏科普风格")
        assertThat(plannerInput).contains("保存到根目录")
        coVerify { agentTaskRepository.createTask(any(), any()) }
    }

    @Test
    fun `specified document creation continues into planner`() = runTest {
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(StreamEvent.Content("我会生成待确认的新文档。"), StreamEvent.Done("我会生成待确认的新文档。")),
            listOf(StreamEvent.Content("""{"outcome":"COMPLETED","summary":"answered"}"""), StreamEvent.Done("""{"outcome":"COMPLETED","summary":"answered"}"""))
        ))

        useCase(adapter)
            .invoke(
                "c1",
                "创建一份标题为《AI 学习笔记》的 md 文档，结构包含概念、应用、风险三节，保存到根目录",
                null,
                null,
                null
            )
            .toList()

        assertThat(adapter.callCount).isAtLeast(1)
        coVerify { agentTaskRepository.createTask(any(), any()) }
    }

    @Test
    fun `converged answer finalizes persisted task as completed without a judge round`() = runTest {
        // 移除 completion judge 后：planner 轮（无效→兜底单步）+ 回答轮 = 2 轮，无判定请求
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(StreamEvent.Content("answer"), StreamEvent.Done("answer"))
        ))

        val states = useCase(adapter).invoke("c1", "hi", null, null, null).toList()

        assertThat(states.filterIsInstance<AgentMessageState.Completed>().map { it.fullText })
            .contains("answer")
        assertThat(adapter.callCount).isEqualTo(2)  // 不再发判定请求
        coVerify {
            agentTaskRepository.updateTask(match {
                it.status == AgentTaskStatus.COMPLETED && it.blockingReason == null
            })
        }
    }

    @Test
    fun `blocked tool failure finalizes persisted task with blocking reason`() = runTest {
        coEvery { executeDocumentTool(any()) } returns Result.failure(IllegalArgumentException("No matching document"))
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(
                StreamEvent.ToolCallComplete(listOf(ToolCall("call_1", "read_document", """{"document_id":"missing"}"""))),
                StreamEvent.Done("")
            )
        ))

        useCase(adapter).invoke("c1", "find missing note", null, null, null).toList()

        coVerify {
            agentTaskRepository.updateTask(match {
                it.status == AgentTaskStatus.BLOCKED &&
                    it.blockingReason?.contains("No matching document") == true
            })
        }
    }

    @Test
    fun `creates task from planner output before running answer loop`() = runTest {
        val plannerJson = """
            {
              "goal": "organize notes",
              "success_criteria": ["source notes are found", "answer is grounded"],
              "steps": [
                {
                  "title": "Search sources",
                  "purpose": "Find notes related to the request",
                  "suggested_tools": ["search_in_project"],
                  "completion_criteria": "Relevant notes are identified",
                  "fallback": "Ask the user for a document name"
                },
                {
                  "title": "Read source",
                  "purpose": "Read the most relevant source note",
                  "suggested_tools": ["read_document"],
                  "completion_criteria": "Source content is available",
                  "fallback": "Use available snippets and state the limitation"
                }
              ]
            }
        """.trimIndent()
        val adapter = FakeAdapter(listOf(
            listOf(StreamEvent.Content(plannerJson), StreamEvent.Done(plannerJson)),
            listOf(StreamEvent.Content("done"), StreamEvent.Done("done"))
        ))

        val states = useCase(adapter).invoke("c1", "organize notes", null, null, null).toList()

        assertThat(states.filterIsInstance<AgentMessageState.Completed>().map { it.fullText })
            .contains("done")
        // planner + 回答轮 = 2 轮（无判定请求）
        assertThat(adapter.callCount).isEqualTo(2)
        assertThat(adapter.configs.first().systemPrompt).contains("JSON")
        assertThat(adapter.messagesByCall.first().single().content).contains("organize notes")
        coVerify {
            agentTaskRepository.createTask(
                match { it.goal == "organize notes" && it.status == AgentTaskStatus.EXECUTING },
                match { steps ->
                    steps.map { it.title } == listOf("Search sources", "Read source") &&
                        steps.first().toolHints == listOf("search_in_project") &&
                        steps[1].toolHints == listOf("read_document")
                }
            )
        }
    }

    @Test
    fun `executes tool then converges on second round`() = runTest {
        coEvery { executeDocumentTool(any()) } returns Result.success("工具结果")
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(
                StreamEvent.ToolCallComplete(listOf(ToolCall("call_1", "read_document", """{"document_id":"x"}"""))),
                StreamEvent.Done("")
            ),
            listOf(StreamEvent.Content("最终答案"), StreamEvent.Done("最终答案"))
        ))

        val states = useCase(adapter).invoke("c1", "读一下", null, null, null).toList()

        coVerify(exactly = 1) { executeDocumentTool(any()) }
        // planner + 工具轮 + 回答轮 = 3 轮（无判定请求）
        assertThat(adapter.callCount).isEqualTo(3)
        assertThat(states.filterIsInstance<AgentMessageState.Completed>().map { it.fullText })
            .contains("最终答案")
        assertThat(states.filterIsInstance<AgentMessageState.ToolStep>()).isNotEmpty()
    }

    @Test
    fun `updates persisted task step status while executing tool calls`() = runTest {
        val createdSteps = mutableListOf<AgentTaskStep>()
        coEvery { agentTaskRepository.createTask(any(), any()) } answers {
            createdSteps += secondArg<List<AgentTaskStep>>()
            Unit
        }
        coEvery { executeDocumentTool(any()) } returns Result.success("tool result")
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(
                StreamEvent.ToolCallComplete(listOf(ToolCall("call_1", "read_document", """{"document_id":"x"}"""))),
                StreamEvent.Done("")
            ),
            listOf(StreamEvent.Content("final answer"), StreamEvent.Done("final answer"))
        ))

        useCase(adapter).invoke("c1", "read one", null, null, null).toList()

        assertThat(createdSteps).isNotEmpty()
        val firstStepId = createdSteps.first().id
        coVerify { agentTaskRepository.markStepStatus(firstStepId, AgentTaskStepStatus.RUNNING) }
        coVerify {
            agentTaskRepository.markStepStatus(
                firstStepId,
                AgentTaskStepStatus.DONE,
                match { it.contains("read_document") }
            )
        }
    }

    @Test
    fun `tool step then converged answer finalizes completed without judge round`() = runTest {
        coEvery { executeDocumentTool(any()) } returns Result.success("tool result")
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(
                StreamEvent.ToolCallComplete(listOf(ToolCall("call_1", "read_document", """{"document_id":"x"}"""))),
                StreamEvent.Done("")
            ),
            listOf(StreamEvent.Content("final answer"), StreamEvent.Done("final answer"))
        ))

        useCase(adapter).invoke("c1", "read one", null, null, null).toList()

        // planner + 工具轮 + 回答轮 = 3 轮，无判定请求
        assertThat(adapter.callCount).isEqualTo(3)
        coVerify {
            agentTaskRepository.updateTask(match { it.status == AgentTaskStatus.COMPLETED })
        }
    }

    @Test
    fun `failed tool execution blocks persisted task in real send loop`() = runTest {
        coEvery { executeDocumentTool(any()) } returns Result.failure(IllegalArgumentException("missing document"))
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(
                StreamEvent.ToolCallComplete(listOf(ToolCall("call_1", "read_document", """{"document_id":"missing"}"""))),
                StreamEvent.Done("")
            )
        ))

        val states = useCase(adapter).invoke("c1", "read missing", null, null, null).toList()

        assertThat(states.filterIsInstance<AgentMessageState.Completed>().single().fullText)
            .contains("missing document")
        coVerify {
            agentTaskRepository.updateTask(match {
                it.status == AgentTaskStatus.BLOCKED &&
                    it.blockingReason?.contains("missing document") == true
            })
        }
        coVerify {
            agentTaskRepository.markStepStatus(
                any(),
                AgentTaskStepStatus.BLOCKED,
                match { it.contains("missing document") }
            )
        }
    }

    @Test
    fun `stops at max steps when model keeps calling tools`() = runTest {
        coEvery { executeDocumentTool(any()) } returns Result.success("结果")
        // 每轮不同查询（避免循环检测先触发），逼近最大步数上限
        val rounds = listOf(invalidPlannerRound()) + (1..10).map { i ->
            listOf(
                StreamEvent.ToolCallComplete(listOf(ToolCall("c$i", "search_in_project", """{"query":"q$i"}"""))),
                StreamEvent.Done("")
            )
        }
        val adapter = FakeAdapter(rounds)

        val states = useCase(adapter).invoke("c1", "搜", null, null, null).toList()

        // 必须循环（>1 轮），且不超过最大步数（防失控）
        assertThat(adapter.callCount).isAtLeast(3)
        assertThat(adapter.callCount).isAtMost(8)
        assertThat(states.filterIsInstance<AgentMessageState.Completed>()).isNotEmpty()
    }

    @Test
    fun `edit_document tool call becomes a pending action and stops the loop`() = runTest {
        coEvery { executeDocumentTool(any()) } returns Result.success("")
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(
                StreamEvent.ToolCallComplete(
                    listOf(ToolCall("c1", "edit_document", """{"document_id":"doc-1","new_content":"新内容"}"""))
                ),
                StreamEvent.Done("")
            )
        ))

        val states = useCase(adapter).invoke("c1", "改写文档", "doc-1", "笔记", "旧内容").toList()

        assertThat(adapter.callCount).isEqualTo(2)  // planner + 写工具终点（无判定请求），不再请求第二轮回答
        val proposed = states.filterIsInstance<AgentMessageState.ActionProposed>()
        assertThat(proposed).isNotEmpty()
        assertThat(proposed.first().action.type)
            .isEqualTo(com.yumark.app.domain.model.AgentActionType.EDIT_DOCUMENT)
        assertThat(proposed.first().action.content).isEqualTo("新内容")
        coVerify(exactly = 0) { executeDocumentTool(any()) }  // 写工具不走只读执行器
    }

    @Test
    fun `write tool proposal updates persisted task step status`() = runTest {
        val createdSteps = mutableListOf<AgentTaskStep>()
        coEvery { agentTaskRepository.createTask(any(), any()) } answers {
            createdSteps += secondArg<List<AgentTaskStep>>()
            Unit
        }
        coEvery { executeDocumentTool(any()) } returns Result.success("")
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(
                StreamEvent.ToolCallComplete(
                    listOf(ToolCall("c1", "edit_document", """{"document_id":"doc-1","new_content":"new content"}"""))
                ),
                StreamEvent.Done("")
            )
        ))

        val states = useCase(adapter).invoke("c1", "edit document", "doc-1", "Note", "old content").toList()

        assertThat(states.filterIsInstance<AgentMessageState.ActionProposed>()).isNotEmpty()
        val firstStepId = createdSteps.first().id
        coVerify { agentTaskRepository.markStepStatus(firstStepId, AgentTaskStepStatus.RUNNING) }
        coVerify {
            agentTaskRepository.markStepStatus(
                firstStepId,
                AgentTaskStepStatus.DONE,
                match { it.contains("write proposal") }
            )
        }
        coVerify(exactly = 0) { executeDocumentTool(any()) }
    }

    @Test
    fun `missing config resets conversation back to idle`() = runTest {
        every { configRepository.observeConfig() } returns flowOf(config.copy(apiKey = ""))
        val adapter = FakeAdapter(emptyList())

        val states = useCase(adapter).invoke("c1", "hi", null, null, null).toList()

        assertThat(states.filterIsInstance<AgentMessageState.Error>()).isNotEmpty()
        coVerify { conversationRepository.updateConversation(match { it.status == ConversationStatus.WORKING }) }
        coVerify { conversationRepository.updateConversation(match { it.status == ConversationStatus.IDLE }) }
    }

    @Test
    fun `implicit full markdown text becomes a create document proposal for weak tool models`() = runTest {
        // 弱工具模型：既不发 create_document 工具调用，也不写 [[ACTION]]，直接把整篇 Markdown 吐在回复里
        val docText = """
            # 人工智能入门

            人工智能（AI）是研究、开发用于模拟、延伸和扩展人的智能的理论、方法、技术及应用系统的一门新技术科学。

            ## 核心概念
            - 机器学习：让机器从数据中学习规律
            - 深度学习：基于神经学习的子领域
            - 自然语言处理：让机器理解人类语言

            ## 应用场景
            人工智能已广泛应用于语音识别、图像识别、推荐系统、自动驾驶等领域。
        """.trimIndent()
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(StreamEvent.Content(docText), StreamEvent.Done(docText))
        ))

        val states = useCase(adapter)
            .invoke("c1", "创建一份关于人工智能的md文档", null, null, null)
            .toList()

        val proposed = states.filterIsInstance<AgentMessageState.ActionProposed>()
        assertThat(proposed).isNotEmpty()
        assertThat(proposed.first().action.type)
            .isEqualTo(com.yumark.app.domain.model.AgentActionType.CREATE_DOCUMENT)
        assertThat(proposed.first().action.content).isEqualTo(docText.trim())
        coVerify(exactly = 0) { executeDocumentTool(any()) }
    }

    @Test
    fun `implicit full markdown text becomes an edit proposal when editing current document`() = runTest {
        val docText = """
            # 润色后的笔记

            这是经过润色与补充的版本，结构更清晰、表述更完整。
            - 要点一
            - 要点二
        """.trimIndent()
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(StreamEvent.Content(docText), StreamEvent.Done(docText))
        ))

        val states = useCase(adapter)
            .invoke("c1", "帮我改写一下这篇文档", "doc-1", "我的笔记", "旧内容")
            .toList()

        val proposed = states.filterIsInstance<AgentMessageState.ActionProposed>()
        assertThat(proposed).isNotEmpty()
        assertThat(proposed.first().action.type)
            .isEqualTo(com.yumark.app.domain.model.AgentActionType.EDIT_DOCUMENT)
        assertThat(proposed.first().action.targetDocumentId).isEqualTo("doc-1")
        assertThat(proposed.first().action.content).isEqualTo(docText.trim())
    }

    @Test
    fun `plain short answer is not misread as a document write`() = runTest {
        // 普通问答：用户无创建/改写意图，且回复短小 → 不应产出任何 action
        val answer = "RAG 是检索增强生成，通过外挂知识库提升大模型回答的事实准确性。"
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(StreamEvent.Content(answer), StreamEvent.Done(answer))
        ))

        val states = useCase(adapter).invoke("c1", "什么是 RAG", null, null, null).toList()

        assertThat(states.filterIsInstance<AgentMessageState.ActionProposed>()).isEmpty()
        assertThat(states.filterIsInstance<AgentMessageState.Completed>().map { it.fullText })
            .contains(answer)
    }

    @Test
    fun `short creation reply without document body does not trigger implicit proposal`() = runTest {
        // 创建意图但模型只回了短句（无结构、不足长度）→ 不应误判为待创建文档
        val answer = "好的，我来帮你创建。"
        val adapter = FakeAdapter(listOf(
            invalidPlannerRound(),
            listOf(StreamEvent.Content(answer), StreamEvent.Done(answer))
        ))

        val states = useCase(adapter)
            .invoke("c1", "创建一份md文档关于ai主题", null, null, null)
            .toList()

        assertThat(states.filterIsInstance<AgentMessageState.ActionProposed>()).isEmpty()
    }
}
