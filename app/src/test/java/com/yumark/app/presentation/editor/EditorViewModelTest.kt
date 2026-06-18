package com.yumark.app.presentation.editor

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.repository.WorkspaceRepository
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import com.yumark.app.domain.usecase.LoadSettingsUseCase
import com.yumark.app.domain.usecase.SaveDocumentUseCase
import com.yumark.app.domain.usecase.export.ExportDocumentUseCase
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private val loadDocumentUseCase: LoadDocumentUseCase = mockk()
    private val saveDocumentUseCase: SaveDocumentUseCase = mockk()
    private val loadSettingsUseCase: LoadSettingsUseCase = mockk()
    private val workspaceRepository: WorkspaceRepository = mockk()
    private val exportDocumentUseCase: ExportDocumentUseCase = mockk()
    private val fileManager: FileManager = mockk()
    private val folderRepository: com.yumark.app.domain.repository.FolderRepository = mockk()
    private val getFolderTreeUseCase: com.yumark.app.domain.usecase.GetFolderTreeUseCase = mockk()
    private val documentRepository: com.yumark.app.domain.repository.DocumentRepository = mockk()
    private val getAiConfigUseCase: com.yumark.app.domain.usecase.ai.GetAiConfigUseCase = mockk()

    private val testDispatcher = StandardTestDispatcher()

    // autoSaveEnabled 必须为 false：自动保存的无限 delay 循环会让 advanceUntilIdle 永不结束
    private val settings = UserSettings(autoSaveEnabled = false)

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { loadSettingsUseCase.observe() } returns flowOf(settings)
        coEvery { loadSettingsUseCase() } returns settings
        every { getAiConfigUseCase() } returns flowOf(com.yumark.app.domain.model.AiConfig())
        every { folderRepository.observeFolders() } returns flowOf(emptyList())
        every { documentRepository.observeAllDocuments() } returns flowOf(emptyList())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun internalVm(docId: String = "doc-1") = EditorViewModel(
        loadDocumentUseCase, saveDocumentUseCase, loadSettingsUseCase,
        workspaceRepository, exportDocumentUseCase, fileManager, folderRepository,
        getFolderTreeUseCase, documentRepository, getAiConfigUseCase,
        SavedStateHandle(mapOf("documentId" to docId))
    )

    private fun externalVm(uri: String = "content://test/doc.md") = EditorViewModel(
        loadDocumentUseCase, saveDocumentUseCase, loadSettingsUseCase,
        workspaceRepository, exportDocumentUseCase, fileManager, folderRepository,
        getFolderTreeUseCase, documentRepository, getAiConfigUseCase,
        SavedStateHandle(mapOf("docUri" to uri))
    )

    @Test
    fun `内部文档加载成功且默认进入预览`() = runTest {
        val doc = Document.create("doc-1", "笔记").copy(content = "# 标题")
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(doc)

        val vm = internalVm()
        advanceUntilIdle()

        assertThat(vm.uiState.value).isInstanceOf(EditorUiState.Success::class.java)
        assertThat(vm.isPreviewMode.value).isTrue()
    }

    @Test
    fun `空文档不进入预览`() = runTest {
        val doc = Document.create("doc-1", "新文档")  // content 为空
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(doc)

        val vm = internalVm()
        advanceUntilIdle()

        assertThat(vm.uiState.value).isInstanceOf(EditorUiState.Success::class.java)
        assertThat(vm.isPreviewMode.value).isFalse()
    }

    @Test
    fun `设置关闭默认预览时保持编辑模式`() = runTest {
        coEvery { loadSettingsUseCase() } returns settings.copy(defaultPreviewMode = false)
        val doc = Document.create("doc-1", "笔记").copy(content = "# 标题")
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(doc)

        val vm = internalVm()
        advanceUntilIdle()

        assertThat(vm.isPreviewMode.value).isFalse()
    }

    @Test
    fun `外部文档经 WorkspaceRepository 加载`() = runTest {
        val uri = "content://test/doc.md"
        coEvery { workspaceRepository.readDocument(uri) } returns Result.success("hello world")
        every { workspaceRepository.documentName(uri) } returns "doc"

        val vm = externalVm(uri)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state).isInstanceOf(EditorUiState.Success::class.java)
        assertThat(vm.document.value?.content).isEqualTo("hello world")
        assertThat(vm.document.value?.name).isEqualTo("doc")
        coVerify(exactly = 0) { loadDocumentUseCase(any()) }
    }

    @Test
    fun `加载失败进入错误态`() = runTest {
        coEvery { loadDocumentUseCase("doc-1") } returns Result.failure(Exception("not found"))

        val vm = internalVm()
        advanceUntilIdle()

        assertThat(vm.uiState.value).isInstanceOf(EditorUiState.Error::class.java)
    }

    @Test
    fun `保存失败走 saveError 且不破坏页面状态`() = runTest {
        val doc = Document.create("doc-1", "笔记").copy(content = "# 标题")
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(doc)
        coEvery { saveDocumentUseCase(any()) } returns Result.failure(Exception("磁盘已满"))

        val vm = internalVm()
        advanceUntilIdle()
        vm.onContentChanged("新内容")
        vm.saveDocument()
        advanceUntilIdle()

        assertThat(vm.saveError.value).isEqualTo("磁盘已满")
        assertThat(vm.uiState.value).isInstanceOf(EditorUiState.Success::class.java)
    }

    @Test
    fun `未修改时保存不写盘`() = runTest {
        val doc = Document.create("doc-1", "笔记").copy(content = "# 标题")
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(doc)

        val vm = internalVm()
        advanceUntilIdle()
        vm.saveDocument()  // 未做任何修改
        advanceUntilIdle()

        coVerify(exactly = 0) { saveDocumentUseCase(any()) }
    }

    @Test
    fun `外部文档保存写回原文件`() = runTest {
        val uri = "content://test/doc.md"
        coEvery { workspaceRepository.readDocument(uri) } returns Result.success("old")
        every { workspaceRepository.documentName(uri) } returns "doc"
        coEvery { workspaceRepository.writeDocument(uri, any()) } returns Result.success(Unit)

        val vm = externalVm(uri)
        advanceUntilIdle()
        vm.onContentChanged("new content")
        vm.saveDocument()
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepository.writeDocument(uri, "new content") }
        coVerify(exactly = 0) { saveDocumentUseCase(any()) }
    }

    @Test
    fun `大纲 JSON 正常解析`() = runTest {
        val doc = Document.create("doc-1", "笔记").copy(content = "# 标题")
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(doc)
        val vm = internalVm()
        advanceUntilIdle()

        vm.onOutlineReceived("""[{"level":1,"text":"标题","id":"yumark-h-0"},{"level":2,"text":"小节","id":"yumark-h-1"}]""")

        assertThat(vm.outline.value).hasSize(2)
        assertThat(vm.outline.value[0].anchorId).isEqualTo("yumark-h-0")
    }

    @Test
    fun `大纲 JSON 损坏时保持原值不崩溃`() = runTest {
        val doc = Document.create("doc-1", "笔记").copy(content = "# 标题")
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(doc)
        val vm = internalVm()
        advanceUntilIdle()
        vm.onOutlineReceived("""[{"level":1,"text":"标题","id":"yumark-h-0"}]""")

        vm.onOutlineReceived("not a json")

        assertThat(vm.outline.value).hasSize(1)
    }
}
