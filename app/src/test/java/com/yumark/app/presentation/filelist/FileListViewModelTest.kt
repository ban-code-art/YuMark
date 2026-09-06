package com.yumark.app.presentation.filelist

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.SortOption
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.FolderRepository
import com.yumark.app.data.remote.UpdateChecker
import com.yumark.app.domain.repository.WorkspaceRepository
import com.yumark.app.domain.usecase.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileListViewModelTest {

    private lateinit var viewModel: FileListViewModel
    private val documentRepository: DocumentRepository = mockk()
    private val folderRepository: FolderRepository = mockk()
    private val createDocumentUseCase: CreateDocumentUseCase = mockk()
    private val deleteDocumentUseCase: DeleteDocumentUseCase = mockk()
    private val searchUseCase: SearchDocumentsUseCase = mockk()
    private val manageFoldersUseCase: ManageFoldersUseCase = mockk()
    private val getFolderTreeUseCase: GetFolderTreeUseCase = mockk()
    private val workspaceRepository: WorkspaceRepository = mockk(relaxed = true)
    private val updateChecker: UpdateChecker = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        every { documentRepository.observeAllDocuments() } returns flowOf(emptyList())
        every { documentRepository.observeTrashCount() } returns flowOf(0)
        every { folderRepository.observeFolders() } returns flowOf(emptyList())
        coEvery { getFolderTreeUseCase() } returns Result.success(mockk())
        every { workspaceRepository.workspace } returns MutableStateFlow(null)

        viewModel = createViewModel()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // 导入相关的依赖已挪到 ImportViewModel，这里不再需要（见 ImportFlow.kt）
    private fun createViewModel() = FileListViewModel(
        documentRepository, folderRepository, createDocumentUseCase,
        deleteDocumentUseCase, searchUseCase, manageFoldersUseCase, getFolderTreeUseCase,
        workspaceRepository, updateChecker,
        // 启动检查更新的开关读取用；本类不覆盖更新检查，默认开（getSettings 返回 relaxed mock 的默认值会抛，
        // 显式钉成 UserSettings() —— updateCheckEnabled = true）
        run {
            val repo = mockk<com.yumark.app.domain.repository.SettingsRepository>()
            coEvery { repo.getSettings() } returns com.yumark.app.domain.model.UserSettings()
            repo
        }
    )

    @Test
    fun `uiState emits Success when documents loaded`() = runTest {
        val docs = listOf(Document.create("1", "Test"))
        every { documentRepository.observeAllDocuments() } returns flowOf(docs)
        // 重新 stub 后必须重建 ViewModel，旧实例已经在收集旧的流
        viewModel = createViewModel()

        // 推进虚拟时间，越过搜索 debounce(300) 让 combine 产出第一帧
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(FileListUiState.Success::class.java)
        val success = state as FileListUiState.Success
        assertThat(success.documents).hasSize(1)
    }

    @Test
    fun `onSortOptionChanged updates sort option`() = runTest {
        viewModel.onSortOptionChanged(SortOption.NAME_ASC)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem() as FileListUiState.Success
            assertThat(state.sortOption).isEqualTo(SortOption.NAME_ASC)
        }
    }

    // ---- 删除撤销：状态生命周期 ----

    @Test
    fun `删除成功后挂可撤销项并超时自清`() = runTest {
        coEvery { deleteDocumentUseCase("d1") } returns Result.success(Unit)

        viewModel.deleteDocument("d1")
        runCurrent()
        assertThat(viewModel.trashUndo.value?.id).isEqualTo("d1")
        // 越过 11s 的对账窗口：撤销项自动清空（清理由超时任务按 id 对账完成，
        // 不挂在 Snackbar 的 consume 上——advanceUntilIdle 会把 11s 一口气快进完，
        // 所以这里必须用 advanceTimeBy 分段控时；advanceTimeBy 不跑恰好落在
        // 目标时刻的任务，尾部要跟 runCurrent()）
        advanceTimeBy(11_000)
        runCurrent()
        assertThat(viewModel.trashUndo.value).isNull()
    }

    @Test
    fun `连续删除时新撤销项不会被旧提示的清理吞掉`() = runTest {
        coEvery { deleteDocumentUseCase("a") } returns Result.success(Unit)
        coEvery { deleteDocumentUseCase("b") } returns Result.success(Unit)

        viewModel.deleteDocument("a")
        runCurrent()                    // a 的清理任务定在 t≈11000
        advanceTimeBy(5_000)            // t=5000，a 的撤销窗还剩 6s
        viewModel.deleteDocument("b")   // b 顶掉 a，a 的超时任务被取消，b 的定在 t≈16000
        runCurrent()
        assertThat(viewModel.trashUndo.value?.id).isEqualTo("b")

        advanceTimeBy(6_000)            // t=11000：若 a 的清理未被取消，此刻会误清 b
        runCurrent()
        assertThat(viewModel.trashUndo.value?.id).isEqualTo("b")

        advanceTimeBy(5_000)            // t=16000：b 正常过期
        runCurrent()
        assertThat(viewModel.trashUndo.value).isNull()
    }
}
