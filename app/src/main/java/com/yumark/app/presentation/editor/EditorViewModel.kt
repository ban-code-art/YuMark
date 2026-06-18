package com.yumark.app.presentation.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.ExportFormat
import com.yumark.app.domain.model.ExportOptions
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.FolderTreeNode
import com.yumark.app.domain.model.OutlineItem
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.model.Workspace
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.FolderRepository
import com.yumark.app.domain.repository.WorkspaceRepository
import com.yumark.app.domain.usecase.GetFolderTreeUseCase
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import com.yumark.app.domain.usecase.SaveDocumentUseCase
import com.yumark.app.domain.usecase.LoadSettingsUseCase
import com.yumark.app.domain.usecase.export.ExportDocumentUseCase
import com.yumark.app.domain.usecase.ai.GetAiConfigUseCase
import com.yumark.app.presentation.theme.AppThemes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val loadDocumentUseCase: LoadDocumentUseCase,
    private val saveDocumentUseCase: SaveDocumentUseCase,
    private val loadSettingsUseCase: LoadSettingsUseCase,
    private val workspaceRepository: WorkspaceRepository,
    private val exportDocumentUseCase: ExportDocumentUseCase,
    private val fileManager: FileManager,
    private val folderRepository: FolderRepository,
    private val getFolderTreeUseCase: GetFolderTreeUseCase,
    private val documentRepository: DocumentRepository,
    getAiConfig: GetAiConfigUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val documentId: String? = savedStateHandle["documentId"]
    private val docUri: String? = savedStateHandle["docUri"]

    /** AI 助手是否启用（控制编辑器 AI 按钮显示） */
    val aiEnabled: StateFlow<Boolean> = getAiConfig()
        .map { it.enabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 是否为外部工作区文档（不支持导出等依赖 Room 的功能） */
    val isExternal: Boolean get() = docUri != null

    /** 当前内部文档 id（侧栏高亮/防重复打开用），外部文档为 null */
    val currentDocumentId: String? get() = documentId

    /** 当前外部文档 URI（侧栏防重复打开用），内部文档为 null */
    val currentDocUri: String? get() = docUri

    /** 当前外部工作区（编辑器侧栏文件树用） */
    val workspace: StateFlow<Workspace?> get() = workspaceRepository.workspace

    /**
     * 编辑器侧栏的内部库文件树；文档/文件夹变化时自动重建。
     * Lazily + flow 包裹：侧栏首次展开才开始观察，构造 VM 时不触碰仓库
     */
    val folderTree: StateFlow<List<FolderTreeNode>?> = flow {
        emitAll(
            combine(
                documentRepository.observeAllDocuments(),
                folderRepository.observeFolders()
            ) { _, _ -> }
        )
    }
        .map { getFolderTreeUseCase().getOrNull() }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** 扁平内部文件夹列表（给「移动到…」选择器用） */
    val folders: StateFlow<List<Folder>> = folderRepository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _document = MutableStateFlow<Document?>(null)
    val document: StateFlow<Document?> = _document.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isPreviewMode = MutableStateFlow(false)  // 默认编辑模式
    val isPreviewMode: StateFlow<Boolean> = _isPreviewMode.asStateFlow()

    private val _outline = MutableStateFlow<List<OutlineItem>>(emptyList())
    val outline: StateFlow<List<OutlineItem>> = _outline.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    /** Agent 应用修改失败提示（无法在原文中定位选中文本时），UI 收到后弹 Snackbar */
    private val _applyError = MutableStateFlow<String?>(null)
    val applyError: StateFlow<String?> = _applyError.asStateFlow()

    private val _scrollState = MutableStateFlow(EditorScrollState())
    val scrollState: StateFlow<EditorScrollState> = _scrollState.asStateFlow()

    fun saveEditScrollPosition(position: Int) {
        _scrollState.update { it.copy(editScrollPosition = position) }
    }

    fun savePreviewScrollRatio(ratio: Float) {
        _scrollState.update { it.copy(previewScrollRatio = ratio) }
    }

    /** 导出成功的文件（UI 收到后弹分享） */
    private val _exportedFile = MutableStateFlow<File?>(null)
    val exportedFile: StateFlow<File?> = _exportedFile.asStateFlow()

    /** 预览图片相对路径解析基址（导入库文档/外部工作区文档），null 表示无法解析 */
    private val _imageResolver = MutableStateFlow<ImageResolverConfig?>(null)
    val imageResolver: StateFlow<ImageResolverConfig?> = _imageResolver.asStateFlow()

    /** 当前主题 id（驱动预览区深色配色） */
    val themeId: StateFlow<String> = loadSettingsUseCase.observe()
        .map { it.themeId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppThemes.DEFAULT_ID)

    /** 编辑/预览字号（设置驱动） */
    val editorFontSize: StateFlow<Int> = loadSettingsUseCase.observe()
        .map { it.fontSize }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 16)

    private var autoSaveJob: Job? = null

    // 保护保存的读-改-写竞态
    private val stateMutex = Mutex()

    // 标记文档是否已被用户修改（脏数据标记）
    private var isDocumentDirty = false

    init {
        require(documentId != null || docUri != null) { "documentId or docUri required" }

        loadDocument()

        // 监听设置变化（自动保存）
        viewModelScope.launch {
            loadSettingsUseCase.observe().collect { settings ->
                if (settings.autoSaveEnabled) startAutoSave(settings.autoSaveInterval)
                else stopAutoSave()
            }
        }
    }

    /** 加载文档（内部 Room 文档 或 外部 SAF 文档）；错误态 Retry 也走这里 */
    fun loadDocument() {
        _uiState.value = EditorUiState.Loading
        // 清除旧文档的滚动状态，避免不同文档间串用滚动位置
        _scrollState.value = EditorScrollState()
        viewModelScope.launch {
            val settings = loadSettingsUseCase()
            if (docUri != null) {
                workspaceRepository.readDocument(docUri).onSuccess { content ->
                    val doc = Document.create(
                        id = "external",
                        name = workspaceRepository.documentName(docUri)
                    ).copy(content = content)
                    _document.value = doc
                    _uiState.value = EditorUiState.Success(doc)
                    isDocumentDirty = false
                    _imageResolver.value = externalImageResolver(docUri)
                    applyDefaultPreview(settings, content)
                }.onFailure { e ->
                    _uiState.value = EditorUiState.Error(e.message ?: "无法读取文件")
                }
            } else {
                loadDocumentUseCase(documentId!!).onSuccess { doc ->
                    _document.value = doc
                    _uiState.value = EditorUiState.Success(doc)
                    isDocumentDirty = false
                    _imageResolver.value = importLibraryImageResolver(doc)
                    applyDefaultPreview(settings, doc.content)
                }.onFailure { e ->
                    _uiState.value = EditorUiState.Error(e.message ?: "Document not found")
                }
            }
        }
    }

    /** 默认预览：设置开启且文档非空才进预览（空文档直接编辑，避免空白预览） */
    private fun applyDefaultPreview(settings: UserSettings, content: String) {
        if (settings.defaultPreviewMode && content.isNotBlank()) {
            _isPreviewMode.value = true
        }
    }

    /**
     * 外部工作区文档：相对图片引用解析到原文件夹（SAF content URI）。
     * 文档 URI 形如 content://.../tree/<treeId>/document/<docId>，docId 内含原始路径；
     * 取父目录 docId 作为 base，JS 侧拼好完整 docId 后整体编码回 /document/ 前缀。
     */
    private fun externalImageResolver(docUri: String): ImageResolverConfig? = runCatching {
        val docId = android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(docUri))
        // 仅处理 "root:path/to/doc" 形态的 docId（externalstorage 等标准提供器）；
        // 无冒号的第三方提供器无法推断父目录，返回 null 让图片保持原样而不是拼出无效 URI
        if (':' !in docId) return@runCatching null
        val parentId = if ('/' in docId) docId.substringBeforeLast('/')
        else docId.substringBefore(':') + ":"
        val marker = "/document/"
        val idx = docUri.lastIndexOf(marker)
        if (idx < 0) return@runCatching null
        ImageResolverConfig(
            prefix = docUri.substring(0, idx + marker.length),
            base = parentId,
            encodeAll = true
        )
    }.getOrNull()

    /**
     * 导入库文档：相对图片引用解析到 filesDir/import_assets/ 下的镜像目录。
     * base 为文档在导入库内的文件夹名称链（与导入时复制图片的相对路径一致）。
     * 自定义导入位置（不在导入库下）的文档：镜像目录仍按「所选文件夹名起始」存放，
     * 取名称链中与镜像目录能对上的最长后缀作为 base。
     */
    private suspend fun importLibraryImageResolver(doc: Document): ImageResolverConfig? {
        var folderId = doc.folderId ?: return null
        val names = ArrayDeque<String>()
        var guard = 0
        var reachedImportRoot = false
        while (true) {
            if (folderId == FolderRepository.IMPORT_LIBRARY_FOLDER_ID) {
                reachedImportRoot = true
                break
            }
            if (++guard > 64) return null
            val folder = folderRepository.getFolderById(folderId).getOrNull() ?: return null
            names.addFirst(folder.name)
            folderId = folder.parentId ?: break
        }
        val assetsDir = fileManager.getImportAssetsDir()
        val base = if (reachedImportRoot) {
            names.joinToString("/")
        } else {
            withContext(Dispatchers.IO) {
                names.indices.asSequence()
                    .map { i -> names.drop(i).joinToString("/") }
                    .firstOrNull { it.isNotEmpty() && File(assetsDir, it).isDirectory }
            } ?: return null
        }
        return ImageResolverConfig(
            prefix = android.net.Uri.fromFile(assetsDir).toString() + "/",
            base = base,
            encodeAll = false
        )
    }

    fun onContentChanged(newContent: String) {
        // 输入热路径：直通更新，不开协程不抢锁（StateFlow.update 本身原子）
        _document.update { it?.copy(content = newContent) }
        isDocumentDirty = true
    }

    fun saveDocument() {
        viewModelScope.launch { doSave() }
    }

    /** 供返回键等需要等待保存落盘后再继续的场景（在调用方协程内执行，不会被 VM 销毁取消） */
    suspend fun saveAndWait() = doSave()

    private suspend fun doSave() {
        stateMutex.withLock {
            // 不脏不写盘：避免自动保存每 30s 重写外部原文件
            if (!isDocumentDirty) return
            _document.value?.let { doc ->
                _isSaving.value = true
                val result = if (docUri != null) {
                    workspaceRepository.writeDocument(docUri, doc.content)
                } else {
                    saveDocumentUseCase(doc)
                }
                result.onSuccess {
                    isDocumentDirty = false
                }.onFailure { e ->
                    // 保存失败不改变整页状态，编辑内容保留在内存
                    _saveError.value = e.message ?: "保存失败"
                }
                _isSaving.value = false
            }
        }
    }

    fun clearSaveError() {
        _saveError.value = null
    }

    fun clearApplyError() {
        _applyError.value = null
    }

    /**
     * 重新加载当前文档（用于外部修改后刷新，如 AI 编辑完成）。
     * 仅内部文档支持（外部文档需要走 SAF 重读，暂不支持）。
     */
    fun reloadDocumentFromRepository() {
        val id = documentId ?: return  // 外部文档不支持
        viewModelScope.launch {
            loadDocumentUseCase(id).onSuccess { doc ->
                _document.value = doc
                _uiState.value = EditorUiState.Success(doc)
                isDocumentDirty = false
            }
        }
    }

    /**
     * 替换选中的文本（用于 Agent 快捷编辑）。
     *
     * 选区文本可能来自渲染后的预览（去 Markdown 语法、被 trim、甚至用户手动改过），
     * 直接 replaceFirst 源码常匹配不上而静默失效。这里按可靠度依次尝试：
     *  1. 编辑模式传入的精确选区 [start,end)（校验区间内容与 oldText 一致，最可靠）；
     *  2. 源码中精确匹配第一处；
     *  3. trim 后再匹配（兜底预览选区的首尾空白差异）。
     * 全部失败时写入 applyError 并返回 false——不再静默丢弃。
     *
     * @param range 编辑模式选区的半开区间 (start, end)，预览模式传 null。
     */
    fun replaceSelectedText(oldText: String, newText: String, range: Pair<Int, Int>? = null): Boolean {
        val doc = _document.value ?: return false
        val content = doc.content

        val newContent: String? = when {
            // 1. 编辑模式：精确选区替换（防止文本已变动导致错位）
            range != null &&
                range.first in 0..content.length &&
                range.second in range.first..content.length &&
                content.substring(range.first, range.second) == oldText ->
                content.substring(0, range.first) + newText + content.substring(range.second)

            // 2. 源码精确匹配第一处
            oldText.isNotEmpty() && content.contains(oldText) ->
                content.replaceFirst(oldText, newText)

            // 3. 兜底：trim 后匹配
            else -> {
                val trimmed = oldText.trim()
                if (trimmed.isNotEmpty() && content.contains(trimmed))
                    content.replaceFirst(trimmed, newText)
                else null
            }
        }

        if (newContent == null || newContent == content) {
            _applyError.value = "无法在原文中定位选中文本，请切换到编辑模式后再用 Agent 修改"
            return false
        }

        _document.value = doc.copy(content = newContent)
        isDocumentDirty = true
        // 触发自动保存
        saveDocument()
        return true
    }

    /** 导出为指定格式（仅内部文档），成功后通过 exportedFile 通知 UI 弹分享 */
    fun exportAs(format: ExportFormat) {
        val id = documentId ?: return
        viewModelScope.launch {
            doSave()  // 导出读取的是仓库数据，先确保落盘
            withContext(Dispatchers.IO) {
                // 清理上次导出残留，避免私有目录无限累积
                fileManager.getExportsDir().listFiles()?.forEach { it.delete() }
            }
            exportDocumentUseCase(
                id,
                format,
                ExportOptions(outputDir = fileManager.getExportsDir())
            ).onSuccess { file ->
                _exportedFile.value = file
            }.onFailure { e ->
                _saveError.value = e.message ?: "导出失败"
            }
        }
    }

    fun clearExportedFile() {
        _exportedFile.value = null
    }

    /** WebView JS 渲染完成后回传的大纲（JSON 数组） */
    fun onOutlineReceived(json: String) {
        runCatching {
            outlineJson.decodeFromString<List<OutlineItemDto>>(json)
        }.onSuccess { items ->
            _outline.value = items.map { OutlineItem(it.level, it.text, it.id) }
        }
    }

    fun togglePreviewMode() {
        // 切换到预览模式前先保存文档
        if (!_isPreviewMode.value && isDocumentDirty) {
            saveDocument()
        }
        _isPreviewMode.value = !_isPreviewMode.value
    }

    private fun startAutoSave(intervalSec: Int) {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            while (isActive) { delay(intervalSec * 1000L); doSave() }
        }
    }

    private fun stopAutoSave() { autoSaveJob?.cancel(); autoSaveJob = null }

    override fun onCleared() {
        stopAutoSave()
        super.onCleared()
    }

    // ===== 侧边栏文件夹/文档操作 =====
    private var selectedFolderId: String? = null

    fun selectFolderForNewDoc(folderId: String) {
        selectedFolderId = folderId
    }

    fun createDocument(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            documentRepository.createDocument(name, selectedFolderId).onFailure {
                _saveError.value = "创建文档失败"
            }
            selectedFolderId = null
        }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            folderRepository.createFolder(name, null).onFailure {
                _saveError.value = "创建文件夹失败"
            }
        }
    }

    fun createSubfolder(name: String, parentId: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            folderRepository.createFolder(name, parentId).onFailure {
                _saveError.value = "创建子文件夹失败"
            }
        }
    }

    fun renameFolder(folderId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            folderRepository.renameFolder(folderId, newName).onFailure {
                _saveError.value = "重命名文件夹失败"
            }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            folderRepository.deleteFolder(folderId, deleteContents = true).onFailure {
                _saveError.value = "删除文件夹失败"
            }
        }
    }

    fun renameDocument(docId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            documentRepository.getDocumentById(docId)
                .onSuccess { doc ->
                    val renamed = doc.copy(name = newName)
                    documentRepository.saveDocument(renamed).onSuccess {
                        // 如果重命名的是当前文档，更新显示
                        if (docId == documentId) {
                            _document.value = renamed
                        }
                    }.onFailure {
                        _saveError.value = "重命名文档失败"
                    }
                }
                .onFailure {
                    _saveError.value = "重命名文档失败"
                }
        }
    }

    fun deleteDocument(docId: String) {
        viewModelScope.launch {
            documentRepository.deleteDocument(docId).onFailure {
                _saveError.value = "删除文档失败"
            }
        }
    }

    /** 把文档移动到目标文件夹(null 为根目录)。 */
    fun moveDocument(docId: String, targetFolderId: String?) {
        viewModelScope.launch {
            documentRepository.moveDocument(docId, targetFolderId).onFailure {
                _saveError.value = it.message ?: "移动文档失败"
            }
        }
    }

    /** 把文件夹移动到目标父文件夹(null 为根目录);防环由仓库保证。 */
    fun moveFolder(folderId: String, targetParentId: String?) {
        viewModelScope.launch {
            folderRepository.moveFolder(folderId, targetParentId).onFailure {
                _saveError.value = it.message ?: "移动文件夹失败"
            }
        }
    }
}

sealed class EditorUiState {
    data object Loading : EditorUiState()
    data class Success(val document: Document) : EditorUiState()
    data class Error(val message: String) : EditorUiState()
}

/**
 * 编辑器滚动状态（用于保持跨页面导航的滚动位置）
 */
data class EditorScrollState(
    val editScrollPosition: Int = 0,      // 编辑器滚动位置（像素）
    val previewScrollRatio: Float = 0f    // 预览滚动比例（0-1）
)

/**
 * 预览图片相对路径解析配置（序列化成 JSON 传给 renderer.html 的 setImageResolver）。
 * 最终 src = prefix + encode(join(base, 相对引用))；encodeAll 区分 SAF docId 整体编码与文件路径逐段编码。
 */
@Serializable
data class ImageResolverConfig(
    val prefix: String,
    val base: String,
    val encodeAll: Boolean
)

@Serializable
private data class OutlineItemDto(val level: Int, val text: String, val id: String)

private val outlineJson = Json { ignoreUnknownKeys = true }
