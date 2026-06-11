package com.yumark.app.presentation.filelist

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.SortOption
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.FolderRepository
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

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        every { documentRepository.observeAllDocuments() } returns flowOf(emptyList())
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

    private fun createViewModel() = FileListViewModel(
        documentRepository, folderRepository, createDocumentUseCase,
        deleteDocumentUseCase, searchUseCase, manageFoldersUseCase, getFolderTreeUseCase,
        workspaceRepository
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
}
