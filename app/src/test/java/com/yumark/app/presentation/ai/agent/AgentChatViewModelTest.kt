package com.yumark.app.presentation.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import com.yumark.app.domain.model.AgentStatusCode
import com.yumark.app.domain.model.Conversation
import com.yumark.app.domain.model.ConversationStatus
import com.yumark.app.domain.model.ConversationType
import com.yumark.app.domain.model.AgentStep
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskAggregate
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
import com.yumark.app.domain.repository.AgentTaskRepository
import com.yumark.app.domain.repository.ConversationRepository
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import com.yumark.app.domain.usecase.ai.agent.AgentMessageState
import com.yumark.app.domain.usecase.ai.agent.ExecuteAgentActionUseCase
import com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCase
import com.yumark.app.domain.usecase.ai.conversation.GetConversationUseCase
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentChatViewModelTest {

    private val getConversation: GetConversationUseCase = mockk()
    private val sendAgentMessage: SendAgentMessageUseCase = mockk()
    private val executeAgentAction: ExecuteAgentActionUseCase = mockk(relaxed = true)
    private val loadDocumentUseCase: LoadDocumentUseCase = mockk(relaxed = true)
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val agentTaskRepository: AgentTaskRepository = mockk(relaxed = true)
    private val imageProcessor: com.yumark.app.core.image.ImageProcessor = mockk(relaxed = true)
    private val agentUiPrefs: com.yumark.app.data.local.prefs.AgentUiPrefsDataStore = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { agentTaskRepository.observeTaskByConversation(any()) } returns flowOf(null)
        every { agentUiPrefs.taskPanelCollapsedFlow } returns flowOf(false)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `stop cancels streaming and finalizes the in-flight message`() = runTest(testDispatcher) {
        val streaming = Message(
            id = "a1", conversationId = "c1", role = MessageRole.ASSISTANT,
            content = "部分回答", isStreaming = true
        )
        val conversation = Conversation(
            id = "c1", title = "t", type = ConversationType.AGENT,
            messages = listOf(streaming), status = ConversationStatus.WORKING
        )
        val activeTask = aggregate(AgentTaskStatus.EXECUTING).task
        every { getConversation("c1") } returns flowOf(conversation)
        every { conversationRepository.observeConversation("c1") } returns flowOf(conversation)
        coEvery { agentTaskRepository.getTaskByConversationId("c1") } returns aggregate(AgentTaskStatus.EXECUTING)
        // 模拟流式进行中：发出 AssistantMessageStarted 后挂起，直到被取消
        every { sendAgentMessage(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(AgentMessageState.AssistantMessageStarted("a1"))
            awaitCancellation()
        }

        val vm = AgentChatViewModel(getConversation, sendAgentMessage, executeAgentAction, loadDocumentUseCase, conversationRepository, agentTaskRepository, imageProcessor, agentUiPrefs)
        vm.bind("c1", null, null, null)
        vm.send("hi")
        advanceUntilIdle()
        assertThat(vm.isStreaming.value).isTrue()

        vm.stop()
        advanceUntilIdle()

        assertThat(vm.isStreaming.value).isFalse()
        coVerify { conversationRepository.updateMessage(match { it.id == "a1" && !it.isStreaming }) }
        coVerify { conversationRepository.updateConversation(match { it.status == ConversationStatus.IDLE }) }
        coVerify {
            agentTaskRepository.updateTask(match {
                it.id == activeTask.id &&
                    it.status == AgentTaskStatus.BLOCKED &&
                    // 这一列会被时间线直接渲染。存中文原文的话英文环境下永远显示中文，
                    // 所以存稳定码、渲染时查表（见 AgentStatusCode）。
                    it.blockingReason == AgentStatusCode.BLOCKED_USER_STOPPED.encode()
            })
        }
        // 反向守护：别哪天又改回中文字面量 —— 那样 decode 只能按 Raw 原样透出。
        assertThat(AgentStatusCode.decode(AgentStatusCode.BLOCKED_USER_STOPPED.encode()))
            .isEqualTo(UiMessage.Res(R.string.agent_blocked_user_stopped))
    }

    @Test
    fun `停止时把还挂在 RUNNING 的步骤退回 PENDING`() = runTest(testDispatcher) {
        // 只改任务行、不动步骤行的话，界面会自相矛盾：状态胶囊已经是「已由你中断」，而
        // toUiStateOrNull 的 activeStep 在 currentStepId 被置空后正好退到「第一条 RUNNING
        // 步骤」那一支，AgentTimeline 继续按进行中画脉冲、把标题高亮着 —— 看上去像是
        // 点了停止没生效。
        //
        // 冷启动那条复位路径也救不回来：AgentTaskDao.resetRunningSteps 靠父任务仍在
        // liveStatuses（PLANNING/EXECUTING/REPLANNING）里定位，任务此时已是 BLOCKED，
        // 子查询选不到它，那条 RUNNING 步骤会永久停在活动态。
        val streaming = Message(
            id = "a1", conversationId = "c1", role = MessageRole.ASSISTANT,
            content = "部分回答", isStreaming = true
        )
        val conversation = Conversation(
            id = "c1", title = "t", type = ConversationType.AGENT,
            messages = listOf(streaming), status = ConversationStatus.WORKING
        )
        every { getConversation("c1") } returns flowOf(conversation)
        every { conversationRepository.observeConversation("c1") } returns flowOf(conversation)
        coEvery { agentTaskRepository.getTaskByConversationId("c1") } returns
            aggregate(AgentTaskStatus.EXECUTING)
        every { sendAgentMessage(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(AgentMessageState.AssistantMessageStarted("a1"))
            awaitCancellation()
        }

        val vm = AgentChatViewModel(getConversation, sendAgentMessage, executeAgentAction, loadDocumentUseCase, conversationRepository, agentTaskRepository, imageProcessor, agentUiPrefs)
        vm.bind("c1", null, null, null)
        vm.send("hi")
        advanceUntilIdle()

        vm.stop()
        advanceUntilIdle()

        // aggregate() 里 EXECUTING 下 step-2 是 RUNNING、step-1 是 DONE：只退前者
        coVerify {
            agentTaskRepository.markStepStatus("step-2", AgentTaskStepStatus.PENDING, any())
        }
        coVerify(exactly = 0) { agentTaskRepository.markStepStatus("step-1", any(), any()) }
        // 顺序是这个修复的实质：两笔写入之间进程被杀时，先写终态就退化成上面那个
        // 「冷启动也救不回来」的组合，反过来则只是多一条 PENDING 步骤，下次照样能重跑。
        coVerifyOrder {
            agentTaskRepository.markStepStatus("step-2", AgentTaskStepStatus.PENDING, any())
            agentTaskRepository.updateTask(match { it.status == AgentTaskStatus.BLOCKED })
        }
    }

    @Test
    fun `停止时把内存里的步骤补写进被中断的那条消息`() = runTest(testDispatcher) {
        // 流式期间落库的只有正文（AgentUseCases 那条 assistant.copy(content = …)），steps 只在收尾
        // 几处才写；用户点停止时协程被取消，那几处一处都到不了。不补这一笔，刚才看着走完的整条
        // 工具时间线就永久没了 —— 重开对话时那条消息只剩半截正文，不知道 Agent 做过什么。
        val streaming = Message(
            id = "a1", conversationId = "c1", role = MessageRole.ASSISTANT,
            content = "部分回答", isStreaming = true
        )
        val conversation = Conversation(
            id = "c1", title = "t", type = ConversationType.AGENT,
            messages = listOf(streaming), status = ConversationStatus.WORKING
        )
        every { getConversation("c1") } returns flowOf(conversation)
        every { conversationRepository.observeConversation("c1") } returns flowOf(conversation)
        coEvery { agentTaskRepository.getTaskByConversationId("c1") } returns null
        every { sendAgentMessage(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(AgentMessageState.AssistantMessageStarted("a1"))
            emit(AgentMessageState.ToolStep(AgentStep.ToolCalling("read_document", "笔记.md")))
            emit(AgentMessageState.ToolStep(AgentStep.ToolDone("read_document", true, "已读取")))
            awaitCancellation()
        }

        val vm = AgentChatViewModel(getConversation, sendAgentMessage, executeAgentAction, loadDocumentUseCase, conversationRepository, agentTaskRepository, imageProcessor, agentUiPrefs)
        vm.bind("c1", null, null, null)
        vm.send("hi")
        advanceUntilIdle()
        assertThat(vm.steps.value).hasSize(2)

        vm.stop()
        advanceUntilIdle()

        // 正文不能被覆盖成空，步骤要原样落到这一行上
        coVerify {
            conversationRepository.updateMessage(match {
                it.id == "a1" && it.content == "部分回答" && !it.isStreaming && it.steps.size == 2
            })
        }
        // 面板清空是给下一轮腾地方，与落库那份无关
        assertThat(vm.steps.value).isEmpty()
    }

    @Test
    fun `bind refreshes current document content when same document updates`() = runTest(testDispatcher) {
        val conversation = Conversation(id = "c1", title = "t", type = ConversationType.AGENT)
        every { getConversation("c1") } returns flowOf(conversation)
        every { conversationRepository.observeConversation("c1") } returns flowOf(conversation)

        val vm = AgentChatViewModel(getConversation, sendAgentMessage, executeAgentAction, loadDocumentUseCase, conversationRepository, agentTaskRepository, imageProcessor, agentUiPrefs)
        vm.bind("c1", "doc-1", "Doc", "old")
        vm.bind("c1", "doc-1", "Doc", "new")
        advanceUntilIdle()

        assertThat(vm.currentDocumentContent()).isEqualTo("new")
    }

    @Test
    fun `bind cancels previous related document sync when rebinding same conversation`() = runTest(testDispatcher) {
        val first = Conversation(
            id = "c1",
            title = "t",
            type = ConversationType.AGENT,
            relatedDocumentId = "old-doc",
            relatedDocumentName = "Old"
        )
        val conversationFlow = MutableStateFlow(first)
        every { getConversation("c1") } returns conversationFlow
        every { conversationRepository.observeConversation("c1") } returns conversationFlow

        val vm = AgentChatViewModel(getConversation, sendAgentMessage, executeAgentAction, loadDocumentUseCase, conversationRepository, agentTaskRepository, imageProcessor, agentUiPrefs)
        vm.bind("c1", "doc-1", "Doc 1", "one")
        advanceUntilIdle()
        vm.bind("c1", "doc-2", "Doc 2", "two")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            conversationRepository.updateConversation(match {
                it.relatedDocumentId == "doc-1" && it.relatedDocumentName == "Doc 1"
            })
        }
        coVerify(exactly = 1) {
            conversationRepository.updateConversation(match {
                it.relatedDocumentId == "doc-2" && it.relatedDocumentName == "Doc 2"
            })
        }
    }

    @Test
    fun `bind exposes task progress for active blocked and completed states`() = runTest(testDispatcher) {
        val conversation = Conversation(id = "c1", title = "t", type = ConversationType.AGENT)
        val taskFlow = MutableStateFlow<AgentTaskAggregate?>(aggregate(AgentTaskStatus.EXECUTING))
        every { getConversation("c1") } returns flowOf(conversation)
        every { conversationRepository.observeConversation("c1") } returns flowOf(conversation)
        every { agentTaskRepository.observeTaskByConversation("c1") } returns taskFlow

        val vm = AgentChatViewModel(getConversation, sendAgentMessage, executeAgentAction, loadDocumentUseCase, conversationRepository, agentTaskRepository, imageProcessor, agentUiPrefs)
        vm.bind("c1", null, null, null)
        advanceUntilIdle()

        assertThat(vm.taskProgress.value?.goal).isEqualTo("organize notes")
        assertThat(vm.taskProgress.value?.activeStepTitle).isEqualTo("Read source")
        assertThat(vm.taskProgress.value?.status).isEqualTo(AgentTaskStatus.EXECUTING)

        taskFlow.value = aggregate(AgentTaskStatus.BLOCKED, blockingReason = "missing document")
        advanceUntilIdle()
        assertThat(vm.taskProgress.value?.blockingReason).contains("missing document")
    }

    @Test
    fun `completed task progress stays visible but collapsed for review`() = runTest(testDispatcher) {
        val conversation = Conversation(id = "c1", title = "t", type = ConversationType.AGENT)
        every { getConversation("c1") } returns flowOf(conversation)
        every { conversationRepository.observeConversation("c1") } returns flowOf(conversation)
        every { agentTaskRepository.observeTaskByConversation("c1") } returns flowOf(
            aggregate(AgentTaskStatus.COMPLETED, finalSummary = "summary ready")
        )

        val vm = AgentChatViewModel(getConversation, sendAgentMessage, executeAgentAction, loadDocumentUseCase, conversationRepository, agentTaskRepository, imageProcessor, agentUiPrefs)
        vm.bind("c1", null, null, null)
        advanceUntilIdle()

        // 已完成的面板保留可见（默认收起、仅显示结果摘要），不再隐藏
        assertThat(vm.taskProgress.value?.status).isEqualTo(AgentTaskStatus.COMPLETED)
        assertThat(vm.taskProgress.value?.finalSummary).isEqualTo("summary ready")
    }

    @Test
    fun `refreshBaseContent 覆盖 bind 打底的旧 base 而不是直接返回`() = runTest(testDispatcher) {
        // diff 闸门的 base 从前只在缓存为空时才读一次：bind() 用编辑器内容打了底，
        // 之后用户改了文档、或同步拉回远端版本，base 就一直是旧的，
        // 用户按旧 base 审阅出来的合成正文会把那些改动悄悄还原回去。
        val conversation = Conversation(id = "c1", title = "t", type = ConversationType.AGENT)
        every { getConversation("c1") } returns flowOf(conversation)
        every { conversationRepository.observeConversation("c1") } returns flowOf(conversation)
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(document("doc-1", "库里的新正文"))

        val vm = AgentChatViewModel(getConversation, sendAgentMessage, executeAgentAction, loadDocumentUseCase, conversationRepository, agentTaskRepository, imageProcessor, agentUiPrefs)
        vm.bind("c1", "doc-1", "Doc", "bind 时的旧正文")
        advanceUntilIdle()
        assertThat(vm.baseContentFor("doc-1")).isEqualTo("bind 时的旧正文")

        vm.refreshBaseContent("doc-1")
        advanceUntilIdle()

        assertThat(vm.baseContentFor("doc-1")).isEqualTo("库里的新正文")
    }

    @Test
    fun `refreshBaseContent 在同一次加载在飞时不重复发请求`() = runTest(testDispatcher) {
        val conversation = Conversation(id = "c1", title = "t", type = ConversationType.AGENT)
        every { getConversation("c1") } returns flowOf(conversation)
        every { conversationRepository.observeConversation("c1") } returns flowOf(conversation)
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(document("doc-1", "正文"))

        val vm = AgentChatViewModel(getConversation, sendAgentMessage, executeAgentAction, loadDocumentUseCase, conversationRepository, agentTaskRepository, imageProcessor, agentUiPrefs)
        vm.bind("c1", null, null, null)
        advanceUntilIdle()

        vm.refreshBaseContent("doc-1")
        vm.refreshBaseContent("doc-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { loadDocumentUseCase("doc-1") }
    }

    private fun document(id: String, content: String) = com.yumark.app.domain.model.Document(
        id = id,
        name = "Doc",
        content = content,
        folderId = null,
        createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(0),
        updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(0),
        isFavorite = false,
        wordCount = 0,
        characterCount = 0
    )

    private fun aggregate(
        status: AgentTaskStatus,
        blockingReason: String? = null,
        finalSummary: String? = null
    ) = AgentTaskAggregate(
        task = AgentTask(
            id = "task-1",
            conversationId = "c1",
            goal = "organize notes",
            status = status,
            createdAt = 1L,
            updatedAt = 2L,
            currentStepId = "step-2",
            blockingReason = blockingReason,
            finalSummary = finalSummary
        ),
        steps = listOf(
            AgentTaskStep(
                id = "step-1",
                taskId = "task-1",
                title = "Search sources",
                description = "Find source notes",
                status = AgentTaskStepStatus.DONE,
                order = 0,
                completionCriteria = "sources found"
            ),
            AgentTaskStep(
                id = "step-2",
                taskId = "task-1",
                title = "Read source",
                description = "Read source note",
                status = if (status == AgentTaskStatus.BLOCKED) AgentTaskStepStatus.BLOCKED else AgentTaskStepStatus.RUNNING,
                order = 1,
                completionCriteria = "source read"
            )
        ),
        evidence = emptyList()
    )
}
