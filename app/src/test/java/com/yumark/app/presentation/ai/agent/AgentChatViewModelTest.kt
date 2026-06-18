package com.yumark.app.presentation.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.Conversation
import com.yumark.app.domain.model.ConversationStatus
import com.yumark.app.domain.model.ConversationType
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
                    it.blockingReason?.contains("用户已停止") == true
            })
        }
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
