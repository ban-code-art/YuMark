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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
        // 外部变更观察（init 采集）：默认空流；外部变更用例各自覆写成 MutableStateFlow
        every { documentRepository.observeDocument(any()) } returns emptyFlow()
        // 外部冲突检测的默认桩：null = provider 不给 mtime，跳过检测（冲突用例各自覆写）
        coEvery { workspaceRepository.documentLastModified(any()) } returns null
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
    fun `外部文件被外部应用修改时先存冲突副本再写入`() = runTest {
        val uri = "content://test/doc.md"
        // readDocument：① 加载 ② 冲突检测时重读外部现值
        coEvery { workspaceRepository.readDocument(uri) } returnsMany listOf(
            Result.success("打开时的内容"),
            Result.success("外部应用改的内容"),
        )
        every { workspaceRepository.documentName(uri) } returns "doc"
        // documentLastModified：① 加载基线 100 ② 写盘前重查 200（≠基线 → 冲突）③ 写盘后刷新 300
        coEvery { workspaceRepository.documentLastModified(uri) } returnsMany listOf(100L, 200L, 300L)
        coEvery { workspaceRepository.writeDocument(uri, any()) } returns Result.success(Unit)
        coEvery { documentRepository.createDocument(any(), any()) } returns
            Result.success(Document.create("copy-1", "doc (冲突 120000)"))
        coEvery { saveDocumentUseCase(any()) } returns Result.success(Unit)

        val vm = externalVm(uri)
        advanceUntilIdle()
        vm.onContentChanged("用户的修改")
        vm.saveDocument()
        advanceUntilIdle()

        // 外部现值先备份（两个版本都不丢），用户的版本照常写入
        coVerify { documentRepository.createDocument(match { it.contains("冲突") }, any()) }
        coVerify { saveDocumentUseCase(match { it.id == "copy-1" && it.content == "外部应用改的内容" }) }
        coVerify { workspaceRepository.writeDocument(uri, "用户的修改") }
    }

    @Test
    fun `saveAndWait 返回保存结果供返回键门控`() = runTest {
        val doc = Document.create("doc-1", "笔记").copy(content = "# 标题")
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(doc)
        coEvery { saveDocumentUseCase(any()) } returns Result.failure(IOException("disk full"))

        val vm = internalVm()
        advanceUntilIdle()
        vm.onContentChanged("会丢的内容")
        // 失败：false → 返回键留在编辑器（修掉「保存失败仍无条件导航」的静默丢字路径）
        assertThat(vm.saveAndWait()).isFalse()

        coEvery { saveDocumentUseCase(any()) } returns Result.success(Unit)
        vm.onContentChanged("重试后的内容")
        // 成功：true → 返回键放行
        assertThat(vm.saveAndWait()).isTrue()
    }

    @Test
    fun `写盘挂起期间的输入不会被无条件清脏吞掉`() = runTest {
        val doc = Document.create("doc-1", "笔记").copy(content = "# 标题")
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(doc)
        coEvery { documentRepository.isTrashed(any()) } returns false
        // 写盘闸：保存协程停在 await 上，由测试在「挂起期间」敲字后放行
        val writeGate = CompletableDeferred<Result<Unit>>()
        coEvery { saveDocumentUseCase(any()) } coAnswers { writeGate.await() }

        val vm = internalVm()
        advanceUntilIdle()
        vm.onContentChanged("第一次编辑")
        val first = launch { vm.saveAndWait() }   // 显式进入 doSave（测试里自动保存未启用）
        advanceUntilIdle()                        // doSave 取完快照并挂在写盘闸上
        // 此刻模拟「写盘挂起期间的键入」：内容已进 _document 且置脏
        vm.onContentChanged("第一次编辑+挂起期间抢救的字")
        writeGate.complete(Result.success(Unit))
        first.join()                              // 第一轮保存完成：内容比对判出「有后续编辑」，脏标记必须保留

        // 第二轮保存必须发生——旧实现在这里被无条件清脏吞掉，直到下次输入前都不落盘
        vm.onContentChanged("第一次编辑+挂起期间抢救的字2")
        vm.saveDocument()
        advanceUntilIdle()

        coVerify(atLeast = 2) { saveDocumentUseCase(any()) }
        coVerify { saveDocumentUseCase(match { it.content == "第一次编辑+挂起期间抢救的字2" }) }
    }

    // ---- 外部变更感知（同步 vs 编辑器盲写互覆盖的修复）----

    @Test
    fun `外部修改落库且无未保存修改时自动采纳`() = runTest {
        val doc = Document.create("doc-1", "笔记").copy(content = "# 标题")
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(doc)
        val externalFlow = MutableStateFlow(doc)
        every { documentRepository.observeDocument("doc-1") } returns externalFlow

        val vm = internalVm()
        advanceUntilIdle()
        val events = mutableListOf<String>()
        val collector = launch { vm.contentReplaced.collect { events += it } }

        // 另一设备把新内容写进 DB（DownloadOverwrite 落库）
        externalFlow.value = doc.copy(content = "# 远端修改")
        advanceUntilIdle()

        // 无未保存修改：自动采纳 + 走 contentReplaced 热替换通道
        assertThat(vm.document.value?.content).isEqualTo("# 远端修改")
        assertThat(events).contains("# 远端修改")
        collector.cancel()
    }

    @Test
    fun `有未保存修改时外部版本存为冲突副本且不覆盖输入`() = runTest {
        val doc = Document.create("doc-1", "笔记").copy(content = "# 标题")
        coEvery { loadDocumentUseCase("doc-1") } returns Result.success(doc)
        val externalFlow = MutableStateFlow(doc)
        every { documentRepository.observeDocument("doc-1") } returns externalFlow
        coEvery { documentRepository.createDocument(any(), any()) } returns
            Result.success(Document.create("copy-1", "笔记 (冲突 120000)"))
        coEvery { saveDocumentUseCase(any()) } returns Result.success(Unit)

        val vm = internalVm()
        advanceUntilIdle()
        vm.onContentChanged("# 我的本地编辑")   // 置脏：有未保存修改

        externalFlow.value = doc.copy(content = "# 远端修改")
        advanceUntilIdle()

        // 远端版本不丢：存成冲突副本（名字沿用同步侧的「(冲突 …)」约定）
        coVerify {
            documentRepository.createDocument(match { it.contains("冲突") }, any())  // mockk 的 any() 匹配 null
        }
        coVerify { saveDocumentUseCase(match { it.id == "copy-1" && it.content == "# 远端修改" }) }
        // 用户正在输入的内容原样保留，绝不覆盖
        assertThat(vm.document.value?.content).isEqualTo("# 我的本地编辑")
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
