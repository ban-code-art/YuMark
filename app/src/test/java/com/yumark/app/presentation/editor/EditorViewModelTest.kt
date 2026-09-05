package com.yumark.app.presentation.editor

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.ErrorMessages
import com.yumark.app.core.util.UiMessage
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.ExportFormat
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
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

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
    private val processImage: com.yumark.app.domain.usecase.image.ProcessImageUseCase = mockk(relaxed = true)
    private val documentVersionRepository: com.yumark.app.domain.repository.DocumentVersionRepository = mockk(relaxed = true)
    private val ragPipeline: com.yumark.app.data.ai.rag.RagPipeline = mockk(relaxed = true)
    private val getAiConfigUseCase: com.yumark.app.domain.usecase.ai.GetAiConfigUseCase = mockk()

    private val testDispatcher = StandardTestDispatcher()

    /** 导出用例要一个真实存在的输出目录，用临时目录顶上；每个用例一份，互不干扰。 */
    @TempDir
    lateinit var tempRoot: File

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
        // 预览图片基址要拿应用私有根目录（EditorViewModel.appImagesPrefix）。这里给临时目录
        // 而不是靠 relaxed 返回 null：null 会让那行退到 runCatching 的兜底分支，等于测不到
        // 「有根目录时也照样能加载文档」这半边。
        every { fileManager.getFilesRootDir() } returns tempRoot
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
        // ErrorHandler 是单例：这里不拆，装过 sink 的用例会串到别的测试类里
        ErrorHandler.reset()
    }

    private fun internalVm(docId: String = "doc-1") = EditorViewModel(
        loadDocumentUseCase, saveDocumentUseCase, loadSettingsUseCase,
        workspaceRepository, exportDocumentUseCase, fileManager, folderRepository,
        getFolderTreeUseCase, documentRepository, processImage, documentVersionRepository, ragPipeline, getAiConfigUseCase,
        SavedStateHandle(mapOf("documentId" to docId))
    )

    private fun externalVm(uri: String = "content://test/doc.md") = EditorViewModel(
        loadDocumentUseCase, saveDocumentUseCase, loadSettingsUseCase,
        workspaceRepository, exportDocumentUseCase, fileManager, folderRepository,
        getFolderTreeUseCase, documentRepository, processImage, documentVersionRepository, ragPipeline, getAiConfigUseCase,
        SavedStateHandle(mapOf("docUri" to uri))
    )

    /**
     * 「<动作>失败：<原因>」的期望结构（[com.yumark.app.core.util.ErrorHandler] 的拼法）。
     *
     * 动作标签写字面量 `R.string.action_*` 而不是 `UserAction.X.labelRes`：后者会让 ViewModel
     * 传错动作、或枚举项指错资源的改动照样通过。
     */
    private fun actionFailed(labelRes: Int, reason: UiMessage): UiMessage =
        UiMessage.of(R.string.error_action_failed, UiMessage.Res(labelRes), reason)

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
        // 真实的失败长这样：异常原文里带内部绝对路径
        coEvery { saveDocumentUseCase(any()) } returns Result.failure(
            IOException("No space left on device: /data/user/0/com.yumark.app/files/documents/doc-1.md")
        )

        val vm = internalVm()
        advanceUntilIdle()
        vm.onContentChanged("新内容")
        vm.saveDocument()
        advanceUntilIdle()

        // saveError 是 core.util.UiMessage：这条路径的文案来自 ErrorHandler.report，它把动作与
        // 原因拼成一条嵌套 Res（模板 error_action_failed），两段都是资源 id，解析留给界面层。
        // 断言写成结构相等，顺带钉住「别把它退回成运行期拼好的 Raw 字符串」。
        val error = vm.saveError.value
        assertThat(error).isEqualTo(actionFailed(R.string.action_save, ErrorMessages.STORAGE))
        // 路径绝不能出现在界面文案里：整条文案只由资源 id 组成，没有 String 实参可夹带异常原文
        assertThat((error as UiMessage.Res).args.filterIsInstance<String>()).isEmpty()
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
    fun `导出前先清理旧导出再写新文件`() = runTest {
        val doc = Document.create("doc-1", "笔记").copy(content = "# 标题")
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(doc)
        val exportsDir = File(tempRoot, "exports").apply { mkdirs() }
        val produced = File(exportsDir, "笔记.pdf").apply { writeText("pdf") }
        coEvery { fileManager.pruneExports() } returns 0
        every { fileManager.getExportsDir() } returns exportsDir
        // 第四个实参是图片解析基址（相对路径图片用）：本用例的文档不在导入库下，VM 会传 null，
        // 用 any() 而不是省略，是为了让「以后改成传非 null」不至于把桩打飞、报成导出失败
        coEvery { exportDocumentUseCase("doc-1", ExportFormat.PDF, any(), any()) } returns
            Result.success(produced)

        val vm = internalVm()
        advanceUntilIdle()
        vm.exportAs(ExportFormat.PDF)
        advanceUntilIdle()

        // 顺序是这条设计的全部意义：清理必须发生在写新文件之前。反过来的话，
        // 清理逻辑就得额外认出「刚写出的这份别删」，多一处能写错的特例。
        coVerifyOrder {
            fileManager.pruneExports()
            exportDocumentUseCase("doc-1", ExportFormat.PDF, any(), any())
        }
        assertThat(vm.exportedFile.value).isEqualTo(produced)
    }

    @Test
    fun `导出失败走 saveError 且带动作前缀`() = runTest {
        val doc = Document.create("doc-1", "笔记").copy(content = "# 标题")
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(doc)
        coEvery { fileManager.pruneExports() } returns 0
        every { fileManager.getExportsDir() } returns File(tempRoot, "exports")
        coEvery { exportDocumentUseCase(any(), any(), any(), any()) } returns Result.failure(
            IOException("No space left on device: /data/user/0/com.yumark.app/files/exports/笔记.pdf")
        )

        val vm = internalVm()
        advanceUntilIdle()
        vm.exportAs(ExportFormat.PDF)
        advanceUntilIdle()

        val error = vm.saveError.value
        assertThat(error).isEqualTo(actionFailed(R.string.action_export, ErrorMessages.STORAGE))
        assertThat((error as UiMessage.Res).args.filterIsInstance<String>()).isEmpty()
        assertThat(vm.exportedFile.value).isNull()
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
