package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.text.ContentHash
import com.yumark.app.core.util.UiMessage
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
import com.yumark.app.domain.repository.ConversationRepository
import com.yumark.app.domain.repository.DocumentVersionRepository
import com.yumark.app.domain.usecase.CreateDocumentUseCase
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import com.yumark.app.domain.usecase.SaveDocumentUseCase
import com.yumark.app.domain.usecase.ai.EditException
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * [ExecuteAgentActionUseCase] 的基线校验（lost update 闸门）。
 *
 * 被守住的事故：提议里的 [AgentAction.content] 是「提议生成那一刻的原文 + 模型的编辑」，
 * 批准时整篇覆盖目标文档。提议随消息落库，批准可以晚到下一次冷启动之后——这期间用户改了
 * 文档、或同步拉回远端版本，覆盖就把那些改动无声吃掉了。
 */
class ExecuteAgentActionUseCaseTest {

    private val createDocumentUseCase: CreateDocumentUseCase = mockk()
    private val saveDocumentUseCase: SaveDocumentUseCase = mockk()
    private val loadDocumentUseCase: LoadDocumentUseCase = mockk()
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val documentVersionRepository: DocumentVersionRepository = mockk(relaxed = true)

    private val useCase = ExecuteAgentActionUseCase(
        createDocumentUseCase,
        saveDocumentUseCase,
        loadDocumentUseCase,
        conversationRepository,
        documentVersionRepository
    )

    @AfterEach
    fun tearDown() = clearAllMocks()

    @Test
    fun `基线未变时写入合成正文并把消息标成已执行`() = runTest {
        val base = "# 标题\n第一段。"
        givenDocument(base)
        coEvery { saveDocumentUseCase(any()) } returns Result.success(Unit)
        val saved = slot<Document>()

        val result = useCase(message(), editAction(base), finalContent = "# 标题\n第一段。\n第二段。")

        assertThat(result.getOrNull()).isEqualTo("doc-1")
        coVerify { saveDocumentUseCase(capture(saved)) }
        // finalContent 优先：用户在 diff 闸门里逐 hunk 勾出来的才是他要的那份
        assertThat(saved.captured.content).isEqualTo("# 标题\n第一段。\n第二段。")
        coVerify {
            conversationRepository.updateMessage(
                match { it.agentAction?.status == AgentActionStatus.EXECUTED }
            )
        }
    }

    @Test
    fun `finalContent 为空时整篇覆盖提议内容`() = runTest {
        val base = "原文"
        givenDocument(base)
        coEvery { saveDocumentUseCase(any()) } returns Result.success(Unit)
        val saved = slot<Document>()

        val result = useCase(message(), editAction(base, content = "提议全文"), finalContent = null)

        assertThat(result.isSuccess).isTrue()
        coVerify { saveDocumentUseCase(capture(saved)) }
        assertThat(saved.captured.content).isEqualTo("提议全文")
    }

    @Test
    fun `基线已变时拒绝执行且不写文档不落历史不改消息`() = runTest {
        // 提议按「旧正文」生成，批准时库里已经是「用户后来改过的正文」
        givenDocument("用户后来改过的正文")

        val result = useCase(message(), editAction(baseAtProposal = "旧正文"))

        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(EditException::class.java)
        assertThat((error as EditException).uiMessage)
            .isEqualTo(UiMessage.Res(R.string.agent_edit_base_changed))
        // 三个副作用一个都不能发生：写了就是丢改动，落历史就是给用户留下看不懂的版本噪音，
        // 改消息状态会让那张卡片再也点不了「批准」
        coVerify(exactly = 0) { saveDocumentUseCase(any()) }
        coVerify(exactly = 0) { documentVersionRepository.snapshotIfChanged(any(), any(), any()) }
        coVerify(exactly = 0) { conversationRepository.updateMessage(any()) }
    }

    @Test
    fun `老提议没有基线指纹时按从前的行为放行`() = runTest {
        // baseContentHash 落地之前存下的提议：无基线可比，不能因为升级就全部失效
        givenDocument("库里现在的正文")
        coEvery { saveDocumentUseCase(any()) } returns Result.success(Unit)

        val legacy = AgentAction(
            type = AgentActionType.EDIT_DOCUMENT,
            description = "编辑文档",
            targetDocumentId = "doc-1",
            content = "提议全文",
            baseContentHash = null
        )
        val result = useCase(message(), legacy)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { saveDocumentUseCase(any()) }
    }

    @Test
    fun `覆盖前先落一条改动前快照`() = runTest {
        val base = "改动前正文"
        givenDocument(base, wordCount = 5)
        coEvery { saveDocumentUseCase(any()) } returns Result.success(Unit)

        useCase(message(), editAction(base))

        // 改动前的内容必须先入历史，否则 Agent 的整篇覆盖没有回退点
        coVerify { documentVersionRepository.snapshotIfChanged("doc-1", base, 5) }
    }

    @Test
    fun `创建文档不受基线校验影响`() = runTest {
        val created = document(id = "new-1", content = "")
        coEvery { createDocumentUseCase(any(), any()) } returns Result.success(created)
        coEvery { saveDocumentUseCase(any()) } returns Result.success(Unit)
        coEvery { loadDocumentUseCase("new-1") } returns Result.success(created.copy(content = "AI 写的正文"))

        val action = AgentAction(
            type = AgentActionType.CREATE_DOCUMENT,
            description = "写一篇周报",
            targetDocumentId = null,
            content = "AI 写的正文"
        )
        val result = useCase(message(), action)

        assertThat(result.getOrNull()).isEqualTo("new-1")
        coVerify { saveDocumentUseCase(match { it.content == "AI 写的正文" }) }
    }

    @Test
    fun `没有目标文档的老编辑提议降级成新建而不是失败`() = runTest {
        // parseAgentAction 修好之前落库的提议：type=EDIT 但 targetDocumentId 为空。
        // 从前这里 error(...)，经 onFailureReport 只剩一句笼统的「操作失败」，卡片永远点不动。
        val created = document(id = "new-2", content = "")
        coEvery { createDocumentUseCase(any(), any()) } returns Result.success(created)
        coEvery { saveDocumentUseCase(any()) } returns Result.success(Unit)
        // 首个历史版本走 snapshotVersion(id) 的"回读库里最新内容"分支，与 CREATE 完全一致
        coEvery { loadDocumentUseCase("new-2") } returns
            Result.success(created.copy(content = "# 降级标题\n正文"))

        val orphan = AgentAction(
            type = AgentActionType.EDIT_DOCUMENT,
            description = "把这篇改一下",
            targetDocumentId = null,
            content = "# 降级标题\n正文"
        )
        val result = useCase(message(), orphan)

        assertThat(result.getOrNull()).isEqualTo("new-2")
        // 标题来自正文首个标题行，不是"把这篇改一下"
        coVerify { createDocumentUseCase("降级标题", any()) }
        coVerify { saveDocumentUseCase(match { it.content == "# 降级标题\n正文" }) }
        // 已批准的内容不能丢，消息照旧标成已执行
        coVerify { conversationRepository.updateMessage(match { it.agentAction?.status == AgentActionStatus.EXECUTED }) }
        // 只落新建文档的首个版本，没有"改动前快照"——本来就没有旧文档可覆盖
        coVerify(exactly = 1) { documentVersionRepository.snapshotIfChanged(any(), any(), any()) }
    }

    private fun givenDocument(content: String, wordCount: Int = 0) {
        coEvery { loadDocumentUseCase("doc-1") } returns
            Result.success(document(content = content, wordCount = wordCount))
    }

    /** 目标文档在提议生成那一刻是 [baseAtProposal]，指纹按它算。 */
    private fun editAction(baseAtProposal: String, content: String = "合成后的新全文") = AgentAction(
        type = AgentActionType.EDIT_DOCUMENT,
        description = "编辑文档",
        targetDocumentId = "doc-1",
        content = content,
        baseContentHash = ContentHash.of(baseAtProposal)
    )

    private fun message() = Message(
        id = "m1",
        conversationId = "c1",
        role = MessageRole.ASSISTANT,
        content = "我改好了"
    )

    private fun document(id: String = "doc-1", content: String, wordCount: Int = 0) = Document(
        id = id,
        name = "Doc",
        content = content,
        folderId = null,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
        isFavorite = false,
        wordCount = wordCount,
        characterCount = content.length
    )
}
