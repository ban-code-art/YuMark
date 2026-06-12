package com.yumark.app.presentation.filelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.core.validation.FileNameValidator
import com.yumark.app.core.validation.ValidationResult
import com.yumark.app.domain.model.*
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.FolderRepository
import com.yumark.app.domain.repository.WorkspaceRepository
import com.yumark.app.domain.usecase.CreateDocumentUseCase
import com.yumark.app.domain.usecase.DeleteDocumentUseCase
import com.yumark.app.domain.usecase.ManageFoldersUseCase
import com.yumark.app.domain.usecase.SearchDocumentsUseCase
import com.yumark.app.domain.usecase.GetFolderTreeUseCase
import com.yumark.app.domain.usecase.importing.ImportCandidate
import com.yumark.app.domain.usecase.importing.ImportDocumentUseCase
import com.yumark.app.domain.usecase.importing.ImportFolderUseCase
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class FileListViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val folderRepository: FolderRepository,
    private val createDocumentUseCase: CreateDocumentUseCase,
    private val deleteDocumentUseCase: DeleteDocumentUseCase,
    private val searchUseCase: SearchDocumentsUseCase,
    private val manageFoldersUseCase: ManageFoldersUseCase,
    private val getFolderTreeUseCase: GetFolderTreeUseCase,
    private val workspaceRepository: WorkspaceRepository,
    private val importDocumentUseCase: ImportDocumentUseCase,
    private val importFolderUseCase: ImportFolderUseCase
) : ViewModel() {

    private val _currentFolderId = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _sortOption = MutableStateFlow(SortOption.DATE_NEWEST)
    private val _expandedFolders = MutableStateFlow<Set<String>>(emptySet())

    private val _uiState = MutableStateFlow<FileListUiState>(FileListUiState.Loading)
    val uiState: StateFlow<FileListUiState> = _uiState.asStateFlow()

    val expandedFolders: StateFlow<Set<String>> = _expandedFolders.asStateFlow()

    val workspace: StateFlow<Workspace?> = workspaceRepository.workspace

    private val _workspaceError = MutableStateFlow<String?>(null)
    val workspaceError: StateFlow<String?> = _workspaceError.asStateFlow()

    /** 当前 workspaceError 是否来自「默认目录恢复失败」——错误条按钮据此引导去设置页而非临时选文件夹 */
    private val _defaultDirRestoreFailed = MutableStateFlow(false)
    val defaultDirRestoreFailed: StateFlow<Boolean> = _defaultDirRestoreFailed.asStateFlow()

    private val _isWorkspaceLoading = MutableStateFlow(false)
    val isWorkspaceLoading: StateFlow<Boolean> = _isWorkspaceLoading.asStateFlow()

    // 文件夹树缓存：仅 docs/folders 实际变化时重建（搜索/排序不再触发 DB 查询）
    private var cachedTreeKey: Pair<Int, Int>? = null
    private var cachedTree: List<FolderTreeNode>? = null

    /** 操作失败提示（Snackbar 一次性事件），不影响列表 uiState */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    fun clearActionError() {
        _actionError.value = null
    }

    // ===== 导入收纳库 =====

    /** 导入成功提示（一次性事件），UI 收到后弹 Snackbar */
    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    fun clearImportMessage() {
        _importMessage.value = null
    }

    /** 导入文件夹的待选项（扫描结果），非空时 UI 显示勾选对话框 */
    private val _importCandidates = MutableStateFlow<List<ImportCandidate>?>(null)
    val importCandidates: StateFlow<List<ImportCandidate>?> = _importCandidates.asStateFlow()

    /** 与本次扫描配套的图片资产（不进勾选列表，随勾选文档自动复制） */
    private var pendingImportImages: List<ImportCandidate> = emptyList()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    /**
     * 导入选中的单个/多个文件到导入库根。
     * 走 ImportDocumentUseCase（已有），targetFolderId 传导入库根 ID。
     */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isImporting.value = true
            val importRoot = folderRepository.ensureImportLibraryFolder().getOrNull()
            if (importRoot == null) {
                _actionError.value = "无法创建导入库"
                _isImporting.value = false
                return@launch
            }
            var ok = 0
            uris.forEach { uri ->
                importDocumentUseCase(uri, importRoot.id)
                    .onSuccess { ok++ }
                    .onFailure { _actionError.value = it.message ?: "导入失败" }
            }
            _isImporting.value = false
            if (ok > 0) _importMessage.value = "已导入 $ok 个文件"
        }
    }

    /** 扫描待导入文件夹（根授权 + 应用内浏览选定的子路径），得到候选列表供勾选（默认全不选） */
    fun scanImportFolder(treeUri: String, relativePath: List<String> = emptyList()) {
        viewModelScope.launch {
            _isImporting.value = true
            importFolderUseCase.scan(treeUri, relativePath)
                .onSuccess { result ->
                    if (result.documents.isEmpty()) {
                        _actionError.value = "该文件夹中没有可导入的 Markdown/文本文件"
                    } else {
                        pendingImportImages = result.images
                        _importCandidates.value = result.documents
                    }
                }
                .onFailure { _actionError.value = it.message ?: "扫描文件夹失败" }
            _isImporting.value = false
        }
    }

    fun cancelImportFolder() {
        _importCandidates.value = null
        pendingImportImages = emptyList()
    }

    /** 确认导入勾选的候选文件，复制进导入库（图片资产一并复制）；逐文件容错并汇报成败数 */
    fun confirmImportFolder(selected: List<ImportCandidate>) {
        _importCandidates.value = null
        val images = pendingImportImages
        pendingImportImages = emptyList()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            _isImporting.value = true
            importFolderUseCase(selected, images)
                .onSuccess { stats ->
                    _importMessage.value = when {
                        stats.failed == 0 -> "已导入 ${stats.imported} 个文件"
                        stats.imported == 0 -> "导入失败（${stats.failed} 个文件无法读取）"
                        else -> "已导入 ${stats.imported} 个文件，${stats.failed} 个失败"
                    }
                }
                .onFailure { _actionError.value = it.message ?: "导入文件夹失败" }
            _isImporting.value = false
        }
    }

    init {
        viewModelScope.launch {
            combine(
                documentRepository.observeAllDocuments(),
                folderRepository.observeFolders(),
                _currentFolderId,
                _searchQuery.debounce(300),
                _sortOption
            ) { docs, folders, folderId, query, sort ->
                FilteredData(docs, folders, folderId, query, sort)
            }.collect { data ->
                // 如果有搜索查询，执行搜索
                if (data.query.isNotBlank()) {
                    searchUseCase(data.query).onSuccess { results ->
                        _uiState.value = FileListUiState.Success(
                            documents = emptyList(),
                            folders = data.folders,
                            folderTree = null,
                            currentFolderId = data.folderId,
                            searchResults = results,
                            isSearching = true,
                            sortOption = data.sort
                        )
                    }
                    return@collect
                }

                // 正常文件夹视图
                val filteredDocs = data.docs.filter { it.folderId == data.folderId }
                val sortedDocs = when (data.sort) {
                    SortOption.NAME_ASC -> filteredDocs.sortedBy { it.name }
                    SortOption.NAME_DESC -> filteredDocs.sortedByDescending { it.name }
                    SortOption.DATE_NEWEST -> filteredDocs.sortedByDescending { it.updatedAt }
                    SortOption.DATE_OLDEST -> filteredDocs.sortedBy { it.updatedAt }
                    SortOption.WORD_COUNT_ASC -> filteredDocs.sortedBy { it.wordCount }
                    SortOption.WORD_COUNT_DESC -> filteredDocs.sortedByDescending { it.wordCount }
                }
                val treeKey = data.docs.hashCode() to data.folders.hashCode()
                if (treeKey != cachedTreeKey) {
                    cachedTree = getFolderTreeUseCase().getOrNull()
                    cachedTreeKey = treeKey
                }
                val tree = cachedTree
                _uiState.value = FileListUiState.Success(
                    documents = sortedDocs,
                    folders = data.folders,
                    folderTree = tree,
                    currentFolderId = data.folderId,
                    searchResults = emptyList(),
                    isSearching = false,
                    sortOption = data.sort
                )
            }
        }

        // 启动时恢复默认目录/上次工作区；失败时在侧栏错误条提示（不再静默）
        viewModelScope.launch {
            workspaceRepository.restoreOnLaunch()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    _workspaceError.value = it
                    // restoreOnLaunch 仅在默认目录失效时返回错误
                    _defaultDirRestoreFailed.value = true
                }
        }
    }

    fun onFolderSelected(id: String?) { _currentFolderId.value = id }
    fun onSearchQueryChanged(q: String) { _searchQuery.value = q }
    fun onSortOptionChanged(o: SortOption) { _sortOption.value = o }

    fun onFolderExpand(id: String) {
        _expandedFolders.value = _expandedFolders.value + id
    }

    fun onFolderCollapse(id: String) {
        _expandedFolders.value = _expandedFolders.value - id
    }

    fun openWorkspace(treeUri: String) {
        viewModelScope.launch {
            _isWorkspaceLoading.value = true
            workspaceRepository.openWorkspace(treeUri)
                .onFailure { _workspaceError.value = it.message ?: "打开文件夹失败" }
            _isWorkspaceLoading.value = false
        }
    }

    fun closeWorkspace() {
        viewModelScope.launch { workspaceRepository.closeWorkspace() }
    }

    fun rescanWorkspace() {
        viewModelScope.launch {
            _isWorkspaceLoading.value = true
            workspaceRepository.rescan()
                .onFailure { _workspaceError.value = it.message ?: "刷新失败" }
            _isWorkspaceLoading.value = false
        }
    }

    fun clearWorkspaceError() {
        _workspaceError.value = null
        _defaultDirRestoreFailed.value = false
    }

    /** 工作区文件夹展开/收起（与内部文件夹共用 _expandedFolders，键为 uri） */
    fun onWorkspaceFolderToggle(uri: String) {
        _expandedFolders.value = if (uri in _expandedFolders.value) {
            _expandedFolders.value - uri
        } else {
            _expandedFolders.value + uri
        }
    }

    fun createDocument(name: String) {
        viewModelScope.launch {
            when (val result = FileNameValidator.validate(name)) {
                is ValidationResult.Success -> {
                    createDocumentUseCase(name, _currentFolderId.value)
                        .onFailure { setError(it.message ?: "创建文档失败") }
                }
                is ValidationResult.Error -> setError(result.message)
            }
        }
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch {
            deleteDocumentUseCase(id)
                .onFailure { setError(it.message ?: "Delete failed") }
        }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch { documentRepository.toggleFavorite(id) }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            when (val result = FileNameValidator.validate(name)) {
                is ValidationResult.Success -> {
                    manageFoldersUseCase.createFolder(name, _currentFolderId.value)
                        .onFailure { setError(it.message ?: "创建文件夹失败") }
                }
                is ValidationResult.Error -> setError(result.message)
            }
        }
    }

    fun createSubfolder(name: String, parentId: String?) {
        viewModelScope.launch {
            when (val result = FileNameValidator.validate(name)) {
                is ValidationResult.Success -> {
                    manageFoldersUseCase.createFolder(name, parentId)
                        .onFailure { setError(it.message ?: "创建子文件夹失败") }
                }
                is ValidationResult.Error -> setError(result.message)
            }
        }
    }

    fun renameFolder(id: String, newName: String) {
        viewModelScope.launch {
            when (val result = FileNameValidator.validate(newName)) {
                is ValidationResult.Success -> {
                    folderRepository.renameFolder(id, newName)
                        .onFailure { setError(it.message ?: "重命名文件夹失败") }
                }
                is ValidationResult.Error -> setError(result.message)
            }
        }
    }

    fun deleteFolder(id: String, deleteContents: Boolean) {
        viewModelScope.launch {
            manageFoldersUseCase.deleteFolder(id, deleteContents)
                .onFailure { setError(it.message ?: "Delete folder failed") }
        }
    }

    fun renameDocument(id: String, newName: String) {
        viewModelScope.launch {
            when (val result = FileNameValidator.validate(newName)) {
                is ValidationResult.Success -> {
                    documentRepository.getDocumentById(id)
                        .onSuccess { doc ->
                            val renamed = doc.copy(name = newName)
                            documentRepository.saveDocument(renamed)
                                .onFailure { setError(it.message ?: "重命名文档失败") }
                        }
                        .onFailure { setError(it.message ?: "文档不存在") }
                }
                is ValidationResult.Error -> setError(result.message)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun exportDocument(id: String, format: ExportFormat) {
        viewModelScope.launch { /* TODO */ }
    }

    private fun setError(msg: String) {
        // 操作失败不摧毁列表页，走 Snackbar 提示
        _actionError.value = msg
    }
}

data class FilteredData(
    val docs: List<Document>,
    val folders: List<Folder>,
    val folderId: String?,
    val query: String,
    val sort: SortOption
)
