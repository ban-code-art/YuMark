package com.yumark.app.presentation.filelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserAction
import com.yumark.app.core.util.onFailureReport
import com.yumark.app.core.validation.FileNameValidator
import com.yumark.app.core.validation.ValidationResult
import com.yumark.app.data.remote.UpdateChecker
import com.yumark.app.domain.model.*
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.FolderRepository
import com.yumark.app.domain.repository.WorkspaceRepository
import com.yumark.app.domain.usecase.CreateDocumentUseCase
import com.yumark.app.domain.usecase.DeleteDocumentUseCase
import com.yumark.app.domain.usecase.ManageFoldersUseCase
import com.yumark.app.domain.usecase.SearchDocumentsUseCase
import com.yumark.app.domain.usecase.GetFolderTreeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val updateChecker: UpdateChecker
) : ViewModel() {

    private val _currentFolderId = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _sortOption = MutableStateFlow(SortOption.DATE_NEWEST)
    private val _expandedFolders = MutableStateFlow<Set<String>>(emptySet())

    private val _uiState = MutableStateFlow<FileListUiState>(FileListUiState.Loading)
    val uiState: StateFlow<FileListUiState> = _uiState.asStateFlow()

    val expandedFolders: StateFlow<Set<String>> = _expandedFolders.asStateFlow()

    val workspace: StateFlow<Workspace?> = workspaceRepository.workspace

    /**
     * 工作区级错误（顶部错误条），与 [_actionError] 的一次性 Snackbar 分开。
     *
     * 类型是 [UiMessage] 而不是 String：产出侧是 core 的 [ErrorHandler] 与 repository，
     * 都拿不到 Context，解析要等到组合期。
     */
    private val _workspaceError = MutableStateFlow<UiMessage?>(null)
    val workspaceError: StateFlow<UiMessage?> = _workspaceError.asStateFlow()

    /** 当前 workspaceError 是否来自「默认目录恢复失败」——错误条按钮据此引导去设置页而非临时选文件夹 */
    private val _defaultDirRestoreFailed = MutableStateFlow(false)
    val defaultDirRestoreFailed: StateFlow<Boolean> = _defaultDirRestoreFailed.asStateFlow()

    private val _isWorkspaceLoading = MutableStateFlow(false)
    val isWorkspaceLoading: StateFlow<Boolean> = _isWorkspaceLoading.asStateFlow()

    // 文件夹树缓存：仅 docs/folders 实际变化时重建（搜索/排序不再触发 DB 查询）
    private var cachedTreeKey: Pair<Int, Int>? = null
    private var cachedTree: List<FolderTreeNode>? = null

    /**
     * 操作失败提示（Snackbar 一次性事件），不影响列表 uiState。
     *
     * 类型是 [UiMessage] 而不是 String：ViewModel 里没有 Context，查不了字符串资源，
     * 文案只能带着资源 id 传到组合期再由 resolveOrNull() 解析。
     */
    private val _actionError = MutableStateFlow<UiMessage?>(null)
    val actionError: StateFlow<UiMessage?> = _actionError.asStateFlow()

    /** 启动时自动检查更新的结果 */
    private val _autoUpdateInfo = MutableStateFlow<UpdateInfo?>(null)
    val autoUpdateInfo: StateFlow<UpdateInfo?> = _autoUpdateInfo.asStateFlow()

    init {
        // 启动时自动检查更新（静默，仅在有更新时弹窗）
        checkUpdateOnStartup()
    }

    /** 启动时检查更新（静默，不显示"检查中"或"无更新"状态） */
    private fun checkUpdateOnStartup() {
        viewModelScope.launch {
            try {
                val updateInfo = updateChecker.checkUpdate()
                if (updateInfo != null) {
                    _autoUpdateInfo.value = updateInfo
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                // 排在 Exception 前面：它是 IllegalStateException 的子类，被下面捕获就会
                // 把「页面已关闭」写成一条更新失败日志，同时吞掉协程取消。
                throw e
            } catch (e: Exception) {
                // 静默失败：离线启动时这里必然失败，不占崩溃日志配额，只留 logcat。
                android.util.Log.w("FileListViewModel", "启动时检查更新失败: ${e.message}")
            }
        }
    }

    /** 关闭自动更新弹窗 */
    fun dismissAutoUpdate() {
        _autoUpdateInfo.value = null
    }

    fun clearActionError() {
        _actionError.value = null
    }

    // 导入收纳库的整条流程已挪到 ImportViewModel / rememberImportFlow：编辑器侧栏也有同一个入口，
    // 而本类的 init 会发更新检查的网络请求，在编辑器里再实例化一份等于每次开文档多联一次网。

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
            }.collectLatest { data ->
                // 如果有搜索查询，执行搜索
                if (data.query.isNotBlank()) {
                    // 打分与摘要必须挪出主线程：SearchDocumentsUseCase 对每篇命中文档跑两遍正则
                    // （countMatches + extractSnippets），而命中上限是 200 篇**整文**。留在
                    // viewModelScope 默认的 Main.immediate 上，就是边打字边掉帧。
                    // 换 collectLatest 是配套的：下一个查询到来时这一次打分连带取消，
                    // 不会算完一整轮再把结果丢掉，也不会让旧结果后到覆盖新结果。
                    val results = withContext(Dispatchers.Default) { searchUseCase(data.query) }
                        // 失败得说出来。旧实现只有 onSuccess：索引损坏或读正文失败时界面停在
                        // 上一次的结果上，用户完全看不出这次搜索根本没成功。
                        // 带上动作标签：不然那一句只剩「无法读写文件」，指不出是搜索出的事。
                        .onFailureReport(UserAction.SEARCH) { setError(it) }
                        .getOrDefault(emptyList())
                    _uiState.value = FileListUiState.Success(
                        documents = emptyList(),
                        folders = data.folders,
                        folderTree = null,
                        currentFolderId = data.folderId,
                        searchResults = results,
                        isSearching = true,
                        sortOption = data.sort
                    )
                    return@collectLatest
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
            workspaceRepository.restoreOnLaunch()?.let {
                // repository 给的是带 @StringRes id 的 UiMessage，界面层解析 → 跟随系统语言
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
                .onFailureReport(UserAction.OPEN_FOLDER) { _workspaceError.value = it }
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
                .onFailureReport(UserAction.REFRESH) { _workspaceError.value = it }
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
                        .onFailureReport(UserAction.CREATE_DOCUMENT) { setError(it) }
                }
                is ValidationResult.Error -> setError(result.message)
            }
        }
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch {
            deleteDocumentUseCase(id)
                .onFailureReport(UserAction.DELETE_DOCUMENT) { setError(it) }
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
                        .onFailureReport(UserAction.CREATE_FOLDER) { setError(it) }
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
                        .onFailureReport(UserAction.CREATE_SUBFOLDER) { setError(it) }
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
                        .onFailureReport(UserAction.RENAME_FOLDER) { setError(it) }
                }
                is ValidationResult.Error -> setError(result.message)
            }
        }
    }

    fun deleteFolder(id: String, deleteContents: Boolean) {
        viewModelScope.launch {
            manageFoldersUseCase.deleteFolder(id, deleteContents)
                .onFailureReport(UserAction.DELETE_FOLDER) { setError(it) }
        }
    }

    /** 把文档移动到目标文件夹(null 为根目录)。 */
    fun moveDocument(docId: String, targetFolderId: String?) {
        viewModelScope.launch {
            documentRepository.moveDocument(docId, targetFolderId)
                .onFailureReport(UserAction.MOVE_DOCUMENT) { setError(it) }
        }
    }

    /** 把文件夹移动到目标父文件夹(null 为根目录);防环由仓库保证。 */
    fun moveFolder(folderId: String, targetParentId: String?) {
        viewModelScope.launch {
            manageFoldersUseCase.moveFolder(folderId, targetParentId)
                .onFailureReport(UserAction.MOVE_FOLDER) { setError(it) }
        }
    }

    /**
     * 改名。只发一条单字段 UPDATE，不读也不写正文。
     *
     * 从前是「读盘取正文 → `copy(name=…)` → `saveDocument` 整篇回写」：展开态双窗格下
     * （左列表 + 右编辑器，见 `AppShell`）右边正开着这篇文档且有未保存的修改时，
     * 读回来的旧正文会被当成新内容再落一次盘。见 [DocumentRepository.renameDocument]。
     */
    fun renameDocument(id: String, newName: String) {
        viewModelScope.launch {
            when (val result = FileNameValidator.validate(newName)) {
                is ValidationResult.Success ->
                    documentRepository.renameDocument(id, newName)
                        // 失败分两种：文档没了（并发删除）与重名。后者带 FriendlyValidationException
                        // 的文案，onFailureReport 会原样透出，所以这里不能再统一改写成
                        // 「文档不存在」——那会把「已有同名文档」说成删除。
                        .onFailureReport(UserAction.RENAME_DOCUMENT) { setError(it) }

                is ValidationResult.Error -> setError(result.message)
            }
        }
    }

    private fun setError(message: UiMessage) {
        // 操作失败不摧毁列表页，走 Snackbar 提示
        _actionError.value = message
    }
}

data class FilteredData(
    val docs: List<Document>,
    val folders: List<Folder>,
    val folderId: String?,
    val query: String,
    val sort: SortOption
)
