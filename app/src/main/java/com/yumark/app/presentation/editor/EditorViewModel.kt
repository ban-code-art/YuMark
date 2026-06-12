package com.yumark.app.presentation.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.ExportFormat
import com.yumark.app.domain.model.ExportOptions
import com.yumark.app.domain.model.OutlineItem
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.repository.FolderRepository
import com.yumark.app.domain.repository.WorkspaceRepository
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import com.yumark.app.domain.usecase.SaveDocumentUseCase
import com.yumark.app.domain.usecase.LoadSettingsUseCase
import com.yumark.app.domain.usecase.export.ExportDocumentUseCase
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
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val documentId: String? = savedStateHandle["documentId"]
    private val docUri: String? = savedStateHandle["docUri"]

    /** 是否为外部工作区文档（不支持导出等依赖 Room 的功能） */
    val isExternal: Boolean get() = docUri != null

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
     */
    private suspend fun importLibraryImageResolver(doc: Document): ImageResolverConfig? {
        var folderId = doc.folderId ?: return null
        val names = ArrayDeque<String>()
        var guard = 0
        while (folderId != FolderRepository.IMPORT_LIBRARY_FOLDER_ID) {
            if (++guard > 64) return null
            val folder = folderRepository.getFolderById(folderId).getOrNull() ?: return null
            names.addFirst(folder.name)
            folderId = folder.parentId ?: return null
        }
        return ImageResolverConfig(
            prefix = android.net.Uri.fromFile(fileManager.getImportAssetsDir()).toString() + "/",
            base = names.joinToString("/"),
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

    /** 导出为 HTML（仅内部文档），成功后通过 exportedFile 通知 UI 弹分享 */
    fun exportAsHtml() {
        val id = documentId ?: return
        viewModelScope.launch {
            doSave()  // 导出读取的是仓库数据，先确保落盘
            withContext(Dispatchers.IO) {
                // 清理上次导出残留，避免私有目录无限累积
                fileManager.getExportsDir().listFiles()?.forEach { it.delete() }
            }
            exportDocumentUseCase(
                id,
                ExportFormat.HTML,
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
}

sealed class EditorUiState {
    data object Loading : EditorUiState()
    data class Success(val document: Document) : EditorUiState()
    data class Error(val message: String) : EditorUiState()
}

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
