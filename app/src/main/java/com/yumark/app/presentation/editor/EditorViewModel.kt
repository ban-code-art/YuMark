package com.yumark.app.presentation.editor

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.OutlineItem
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.repository.WorkspaceRepository
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import com.yumark.app.domain.usecase.SaveDocumentUseCase
import com.yumark.app.domain.usecase.LoadSettingsUseCase
import com.yumark.app.presentation.theme.AppThemes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val loadDocumentUseCase: LoadDocumentUseCase,
    private val saveDocumentUseCase: SaveDocumentUseCase,
    private val loadSettingsUseCase: LoadSettingsUseCase,
    private val workspaceRepository: WorkspaceRepository,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val documentId: String? = savedStateHandle["documentId"]
    private val docUri: String? = savedStateHandle["docUri"]

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _document = MutableStateFlow<Document?>(null)
    val document: StateFlow<Document?> = _document.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isPreviewMode = MutableStateFlow(false)  // 默认编辑模式
    val isPreviewMode: StateFlow<Boolean> = _isPreviewMode.asStateFlow()

    private val _cursorPosition = MutableStateFlow(0)

    private val _outline = MutableStateFlow<List<OutlineItem>>(emptyList())
    val outline: StateFlow<List<OutlineItem>> = _outline.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    /** 当前主题 id（驱动预览区深色配色） */
    val themeId: StateFlow<String> = loadSettingsUseCase.observe()
        .map { it.themeId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppThemes.DEFAULT_ID)

    private var autoSaveJob: Job? = null

    // 添加 Mutex 保护共享状态，防止并发修改导致数据竞争
    private val stateMutex = Mutex()

    // 标记文档是否已被用户修改（脏数据标记）
    private var isDocumentDirty = false

    init {
        require(documentId != null || docUri != null) { "documentId or docUri required" }

        // 首次加载文档（内部 Room 文档 或 外部 SAF 文档）
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
                    applyDefaultPreview(settings, content)
                }.onFailure { e ->
                    _uiState.value = EditorUiState.Error(e.message ?: "无法读取文件")
                }
            } else {
                loadDocumentUseCase(documentId!!).onSuccess { doc ->
                    _document.value = doc
                    _uiState.value = EditorUiState.Success(doc)
                    isDocumentDirty = false
                    applyDefaultPreview(settings, doc.content)
                }.onFailure { e ->
                    _uiState.value = EditorUiState.Error(e.message ?: "Document not found")
                }
            }
        }

        // 监听设置变化（自动保存）
        viewModelScope.launch {
            loadSettingsUseCase.observe().collect { settings ->
                if (settings.autoSaveEnabled) startAutoSave(settings.autoSaveInterval)
                else stopAutoSave()
            }
        }
    }

    /** 默认预览：设置开启且文档非空才进预览（空文档直接编辑，避免空白预览） */
    private fun applyDefaultPreview(settings: UserSettings, content: String) {
        if (settings.defaultPreviewMode && content.isNotBlank()) {
            _isPreviewMode.value = true
        }
    }

    fun onContentChanged(newContent: String) {
        // 输入热路径：直通更新，不开协程不抢锁（StateFlow.update 本身原子）
        _document.update { it?.copy(content = newContent) }
        isDocumentDirty = true
    }

    fun onCursorPositionChanged(position: Int) {
        _cursorPosition.value = position
    }

    fun saveDocument() {
        viewModelScope.launch {
            stateMutex.withLock {
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
    }

    fun clearSaveError() {
        _saveError.value = null
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

    fun onInsertImageClick() {
        // TODO: 打开图片选择器
        // 当前仅作占位，实际需要 Activity Result API
    }

    fun insertImage(markdownImageSyntax: String) {
        viewModelScope.launch {
            stateMutex.withLock {
                _document.value?.let { doc ->
                    val newContent = doc.content + "\n" + markdownImageSyntax
                    _document.value = doc.copy(content = newContent)
                }
            }
        }
    }

    fun insertSyntax(syntax: String) {
        viewModelScope.launch {
            stateMutex.withLock {
                _document.value?.let { doc ->
                    val cursor = _cursorPosition.value
                    val before = doc.content.substring(0, cursor.coerceAtMost(doc.content.length))
                    val after = doc.content.substring(cursor.coerceAtMost(doc.content.length))

                    // 计算新的光标位置（对于包裹型语法，将光标放在中间）
                    val newCursor = when {
                        syntax.contains("****") -> cursor + 2  // 粗体
                        syntax.contains("**") -> cursor + 1    // 斜体
                        syntax.contains("``") -> cursor + 1    // 代码
                        syntax.contains("[](") -> cursor + 1   // 链接
                        else -> cursor + syntax.length
                    }

                    val newContent = before + syntax + after
                    _document.value = doc.copy(content = newContent)
                    _cursorPosition.value = newCursor
                }
            }
        }
    }

    private fun startAutoSave(intervalSec: Int) {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            while (isActive) { delay(intervalSec * 1000L); saveDocument() }
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

@Serializable
private data class OutlineItemDto(val level: Int, val text: String, val id: String)

private val outlineJson = Json { ignoreUnknownKeys = true }
