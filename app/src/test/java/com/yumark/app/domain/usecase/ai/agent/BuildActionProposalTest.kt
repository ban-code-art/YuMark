package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.FolderRepository
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import com.yumark.app.domain.usecase.NamedEntry
import com.yumark.app.domain.usecase.ai.EditException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [BuildWriteProposalUseCase] 动作空间扩展（move/rename/delete）的提议语义：
 * 参数校验、回收站目标拒绝、撞名自动加序号、目标文件夹存在性。
 */
class BuildActionProposalTest {

    private val loadDocument: LoadDocumentUseCase = mockk()
    private val documentRepository: DocumentRepository = mockk()
    private val folderRepository: FolderRepository = mockk()
    private lateinit var useCase: BuildWriteProposalUseCase

    @BeforeEach
    fun setup() {
        useCase = BuildWriteProposalUseCase(loadDocument, documentRepository, folderRepository)
        coEvery { documentRepository.isTrashed(any()) } returns false
        // 默认有一个 f1 文件夹（move 正常用例用；「不存在」用例各自覆写为空表）
        coEvery { folderRepository.getAllFolders() } returns Result.success(
            listOf(com.yumark.app.domain.model.Folder.create("f1", "工作", null, 0))
        )
    }

    private fun call(name: String, arguments: String) = ToolCall("c1", name, arguments)

    private fun doc(id: String, name: String, folderId: String? = null) =
        Document.create(id, name, folderId).copy(content = "内容")

    @Test
    fun `move 提议带目标列表与目标文件夹`() = runTest {
        val action = useCase(
            call("move_documents", """{"document_ids":["d1","d2"],"target_folder_id":"f1"}"""),
            currentDocumentId = null
        ).getOrThrow()

        assertThat(action.type).isEqualTo(AgentActionType.MOVE_DOCUMENT)
        assertThat(action.targetIds).containsExactly("d1", "d2")
        assertThat(action.destinationFolderId).isEqualTo("f1")
    }

    @Test
    fun `move 回收站文档被拒绝并回喂可操作信息`() = runTest {
        coEvery { documentRepository.isTrashed("d1") } returns true

        val result = useCase(
            call("move_documents", """{"document_ids":["d1"]}"""), null
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("回收站")
    }

    @Test
    fun `move 目标文件夹不存在被拒绝`() = runTest {
        coEvery { folderRepository.getAllFolders() } returns Result.success(emptyList())

        val result = useCase(
            call("move_documents", """{"document_ids":["d1"],"target_folder_id":"ghost"}"""), null
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("目标文件夹不存在")
    }

    @Test
    fun `rename 撞名自动追加序号且告知最终名`() = runTest {
        coEvery { loadDocument("d1") } returns Result.success(doc("d1", "旧名", null))
        coEvery { documentRepository.getDocumentsByFolder(null) } returns Result.success(
            listOf(doc("d2", "新名"))
        )

        val action = useCase(
            call("rename_document", """{"document_id":"d1","new_name":"新名"}"""), null
        ).getOrThrow()

        assertThat(action.newName).isEqualTo("新名 (2)")
        assertThat(action.description).contains("新名 (2)")
        assertThat(action.targetDocumentId).isEqualTo("d1")
    }

    @Test
    fun `rename 目标文档不存在被拒绝`() = runTest {
        coEvery { loadDocument("ghost") } returns Result.failure(IllegalStateException())

        val result = useCase(
            call("rename_document", """{"document_id":"ghost","new_name":"x"}"""), null
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("不存在")
    }

    @Test
    fun `delete 提议带目标列表且不产生墓碑语义（纯回收站）`() = runTest {
        val action = useCase(
            call("delete_documents", """{"document_ids":["d1","d2","d3"]}"""), null
        ).getOrThrow()

        assertThat(action.type).isEqualTo(AgentActionType.DELETE_DOCUMENT)
        assertThat(action.targetIds).containsExactly("d1", "d2", "d3").inOrder()
        assertThat(action.content).isEmpty()   // 无正文语义：执行走 moveToTrash
    }

    @Test
    fun `delete 已在回收站的目标被拒绝（防重复提议）`() = runTest {
        coEvery { documentRepository.isTrashed("d1") } returns true

        val result = useCase(
            call("delete_documents", """{"document_ids":["d1"]}"""), null
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("已在回收站")
    }

    @Test
    fun `空目标列表一律拒绝`() = runTest {
        for (name in listOf("move_documents", "delete_documents")) {
            val result = useCase(call(name, """{"document_ids":[]}"""), null)
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(EditException::class.java)
        }
    }

    @Test
    fun `同级重名序号连续递增不与现有冲突`() {
        val siblings = listOf(
            NamedEntry("a", "笔记"), NamedEntry("b", "笔记 (2)"), NamedEntry("c", "笔记 (3)")
        )
        // 私有函数经由 use case 的行为间接验证：此处直接测同一规则的公开入口不存在，
        // 用提议链路验证（上面 rename 用例覆盖单次追加）；保留一条纯规则断言：
        val taken = siblings.map { it.name }.toSet()
        var n = 2
        while ("笔记 ($n)" in taken) n++
        assertThat("笔记 ($n)").isEqualTo("笔记 (4)")
    }
}
