package com.yumark.app.presentation.editor

import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.R
import com.yumark.app.core.coroutines.AppScope
import com.yumark.app.core.export.ExportImageResolver
import com.yumark.app.core.text.SelectionLocator
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserAction
import com.yumark.app.core.util.onFailureReport
import com.yumark.app.core.validation.FileNameValidator
import com.yumark.app.core.validation.ValidationResult
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.ExportFormat
import com.yumark.app.domain.model.ExportOptions
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.FolderTreeNode
import com.yumark.app.domain.model.OutlineItem
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.model.Workspace
import com.yumark.app.domain.model.withFreshCounts
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.FolderRepository
import com.yumark.app.domain.repository.WorkspaceRepository
import com.yumark.app.domain.usecase.GetFolderTreeUseCase
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import com.yumark.app.domain.usecase.SaveDocumentUseCase
import com.yumark.app.domain.usecase.LoadSettingsUseCase
import com.yumark.app.domain.usecase.export.ExportDocumentUseCase
import com.yumark.app.domain.usecase.image.ProcessImageUseCase
import com.yumark.app.domain.usecase.ai.GetAiConfigUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kotlin.coroutines.cancellation.CancellationException

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
    private val processImage: ProcessImageUseCase,
    private val documentVersionRepository: com.yumark.app.domain.repository.DocumentVersionRepository,
    private val ragPipeline: com.yumark.app.data.ai.rag.RagPipeline,
    getAiConfig: GetAiConfigUseCase,
    savedStateHandle: SavedStateHandle,
    // 退出时保存要用的应用级作用域：onCleared 触发时 viewModelScope 已被取消，
    // 用它启动的协程会立刻死掉，脏内容一个字节都写不出去（见 onCleared）。
    // 带默认值只为让 JVM 单元测试能直接 new 出 VM 而不必造一个作用域；
    // 生产路径一律由 Hilt 注入，见 di/AppScopeModule。
    @AppScope private val appScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    /** 当前文档的历史版本（仅内部文档；按时间倒序）。 */
    val versions: StateFlow<List<com.yumark.app.domain.model.DocumentVersion>> =
        (documentId?.let { documentVersionRepository.observeVersions(it) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    private val _saveError = MutableStateFlow<UiMessage?>(null)
    val saveError: StateFlow<UiMessage?> = _saveError.asStateFlow()

    /** Agent 应用修改失败提示（无法在原文中定位选中文本时），UI 收到后弹 Snackbar */
    private val _applyError = MutableStateFlow<UiMessage?>(null)
    val applyError: StateFlow<UiMessage?> = _applyError.asStateFlow()

    /**
     * 「正文被整体换掉了，编辑框必须跟上」的一次性事件。
     *
     * 为什么不能只靠 [document] 这条 StateFlow：编辑框里的 `TextFieldValue` 是屏幕自己的
     * `remember` 状态，屏幕靠比对「新内容 != 当前输入框内容」来决定是否覆写——而**恢复历史版本
     * 恰好可能恢复成和当前一模一样的内容**（改了几个字又撤回、或恢复到打开时那一版），比对
     * 结果是「没变化」，于是文件已经被写成旧版、输入框仍是新版，用户下一次敲键就把恢复
     * 覆盖回去，界面上看是「点了恢复没反应」。AI 划词替换成相同文本同理。
     *
     * 事件流没有「当前值」，所以不存在这种比对，屏幕收到就无条件覆写。用 SharedFlow 而不是
     * StateFlow 正是为此：同一份内容连续恢复两次也要各触发一次。
     *
     * `extraBufferCapacity = 1` + 默认的 SUSPEND 策略：屏幕不在前台（无订阅者）时事件会被丢弃，
     * 这是对的——那时编辑框状态也随组合一起没了，下次进入会从仓库重新读。
     */
    private val _contentReplaced = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val contentReplaced: SharedFlow<String> = _contentReplaced.asSharedFlow()

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

    /**
     * 预览图片解析基址。
     *
     * 两类基址合在一个对象里：`prefix`/`base` 是**文档所在目录**（导入库镜像目录 / 外部工作区
     * 目录），推不出来时为空串；`appPrefix` 是**应用私有 images/ 目录**，与文档在哪儿无关，
     * 恒有值。所以这条流在文档加载后一定非 null——从前只有前一类，普通库文档拿不到任何基址，
     * 工具栏刚插进去的 `images/<uuid>.jpg` 在预览里必然是个碎图标。
     */
    private val _imageResolver = MutableStateFlow<ImageResolverConfig?>(null)
    val imageResolver: StateFlow<ImageResolverConfig?> = _imageResolver.asStateFlow()

    /**
     * `file://<应用私有根>/` —— 应用自管图片的绝对前缀。
     *
     * 拼到根而不是到 `images/`：正文里的引用本身带 `images/` 这一段，renderer.js 直接拼接，
     * 两边各留一半必然对不上。用 [android.net.Uri.fromFile] 而不是手拼 `"file://" + path`：
     * 路径里有空格或中文时手拼出来的不是合法 URI。
     *
     * runCatching 兜住的是**测试环境**：JVM 单测里 `android.net.Uri` 是 android.jar 的空壳，
     * `isReturnDefaultValues = true` 让 `fromFile` 返回 null，紧接着的 `.toString()` 就是一个
     * NPE——而它落在 [loadDocument] 的成功分支上，会把「加载文档」整条路径炸成 Error 态。
     * 拿不到前缀时退成空串：JS 侧 `if (cfg.appPrefix && …)` 把空串当假值跳过（见
     * renderer.js:135），只是解析不出应用私有图片，不影响其他任何一步。
     */
    private val appImagesPrefix: String by lazy {
        runCatching { android.net.Uri.fromFile(fileManager.getFilesRootDir()).toString() + "/" }
            .getOrDefault("")
    }

    /**
     * 给文档目录基址补上应用图片基址；文档目录推不出来时给一个只含 [appImagesPrefix] 的配置。
     *
     * 空 prefix 是 JS 侧「没有文档目录可解析」的约定信号（`if (!cfg.prefix) continue`），
     * 而不是拼出一个 `file:///` 开头的无效 URL 去撞 404。
     */
    private fun withAppImages(config: ImageResolverConfig?): ImageResolverConfig =
        (config ?: ImageResolverConfig(prefix = "", base = "", encodeAll = false))
            // ifEmpty → null：空串和 null 在 JS 侧等价，但 null 是 @Serializable 的默认值，
            // 默认 Json 的 encodeDefaults = false 会把这个字段整个省掉，不往导出件里塞一个
            // 恒为假的 "appPrefix": ""。
            .copy(appPrefix = appImagesPrefix.ifEmpty { null })

    /**
     * 「相册选的图已落盘，把引用插进正文」的一次性事件，负载是相对路径（`images/<uuid>.<ext>`）。
     *
     * 与 [contentReplaced] 同样用 SharedFlow 而不是 StateFlow：连续插两张一模一样的图
     * （同一张图选两次会得到两个不同 uuid，但万一路径相同）也要各触发一次；且真正的文本改动
     * 必须发生在屏幕侧——`editValue` 与撤销栈都是 Compose 的 remember 状态，VM 碰不到。
     */
    private val _insertedImagePath = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val insertedImagePath: SharedFlow<String> = _insertedImagePath.asSharedFlow()

    /** 图片落盘失败提示（读不到图/解码失败/磁盘满），UI 收到后弹 Snackbar */
    private val _imageError = MutableStateFlow<UiMessage?>(null)
    val imageError: StateFlow<UiMessage?> = _imageError.asStateFlow()

    fun clearImageError() {
        _imageError.value = null
    }

    /**
     * 能否插入本地图片：只有内部库文档能。
     *
     * `images.document_id` 是指向 `documents.id` 的外键，而外部工作区文档是内存里造的
     * `Document.create(id = "external", …)`，数据库里没有对应行——真去插会撞外键约束失败，
     * 用户看到的是一句无从下手的「保存图片失败」。所以入口层面就不给这个选项。
     */
    val canInsertLocalImage: Boolean get() = documentId != null

    /**
     * 把相册选中的图片存进应用私有 images/，成功后发一次 [insertedImagePath]。
     *
     * 压缩策略由 [ProcessImageUseCase] 按设置决定，这里不再硬写 `compress = true`。
     * 手机拍的原图动辄 4000px 宽、几 MB，默认压是对的（`autoCompressImages` 默认开），但
     * 用户把它关掉是明确表态：从前这里硬传 true，仓库照旧按 `imageCompressionQuality` 重编码，
     * 关掉开关也照压——JPEG 白掉一轮画质，GIF 更是被压成单帧 PNG（只有 `compress = false`
     * 才走原样复制那条路，多帧才留得住）。
     */
    fun insertLocalImage(uri: android.net.Uri) {
        val id = documentId ?: return
        viewModelScope.launch {
            processImage(id, uri)
                .onSuccess { _insertedImagePath.emit(it.filePath) }
                .onFailureReport(UserAction.ADD_IMAGE) { _imageError.value = it }
        }
    }

    /** 编辑/预览字号（设置驱动） */
    val editorFontSize: StateFlow<Int> = loadSettingsUseCase.observe()
        .map { it.fontSize }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 16)

    private var autoSaveJob: Job? = null

    /**
     * 停手即存的防抖 job，独立于 [autoSaveJob]。
     *
     * 只有固定间隔轮询时，用户打完字停手最坏要等一整个间隔（默认 30s）才落盘，
     * 这段窗口里退出或被系统杀掉就丢。轮询保留作兜底：连续输入不会触发防抖。
     */
    private var debounceSaveJob: Job? = null

    /**
     * 自动保存总开关的内存镜像。
     *
     * 防抖保存也归它管：否则用户在设置里关掉自动保存后，停手 1.8s 照样落盘，设置项等于失效。
     */
    private var autoSaveEnabled = false

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
                    _imageResolver.value = withAppImages(externalImageResolver(docUri))
                    applyDefaultPreview(settings, content)
                }.onFailure { e ->
                    // 不透出 e.message：外部文档走 SAF，失败原文里是 content:// URI 和内部路径。
                    _uiState.value = EditorUiState.Error(ErrorHandler.report(e, UserAction.READ_FILE))
                }
            } else {
                loadDocumentUseCase(documentId!!).onSuccess { doc ->
                    _document.value = doc
                    _uiState.value = EditorUiState.Success(doc)
                    isDocumentDirty = false
                    _imageResolver.value = withAppImages(importLibraryImageResolver(doc))
                    applyDefaultPreview(settings, doc.content)
                }.onFailure { e ->
                    _uiState.value = EditorUiState.Error(ErrorHandler.report(e, UserAction.OPEN_DOCUMENT))
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
        val docId = android.provider.DocumentsContract.getDocumentId(docUri.toUri())
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
        // 防抖：这里只 cancel + launch 一个纯 delay 协程（无锁无 IO），
        // 代价远小于一次写盘，换来的是「停手即存」而不是最坏等满一个轮询周期。
        if (autoSaveEnabled) scheduleDebouncedSave()
    }

    fun saveDocument() {
        viewModelScope.launch { doSave() }
    }

    /** 恢复到某历史版本：先把当前内容入历史，再写回该版本内容并保存。 */
    fun restoreVersion(version: com.yumark.app.domain.model.DocumentVersion) {
        viewModelScope.launch {
            doSave()                          // 当前内容先落历史（若有改动）
            onContentChanged(version.content) // 写回历史内容
            // 必须显式通知屏幕换正文：onContentChanged 只改 VM 状态，而编辑框是屏幕自己的
            // remember 状态，且屏幕的兜底比对在「恢复成与当前相同的内容」时判定为无变化。
            // 见 [contentReplaced]。
            _contentReplaced.emit(version.content)
            doSave()                          // 保存并记一条「恢复后」版本
        }
    }

    /** 供返回键等需要等待保存落盘后再继续的场景（在调用方协程内执行，不会被 VM 销毁取消） */
    suspend fun saveAndWait() = doSave()

    private suspend fun doSave() {
        stateMutex.withLock {
            // 不脏不写盘：避免自动保存每 30s 重写外部原文件
            if (!isDocumentDirty) return
            // 字数/字符数在这里重算并写回内存，而不是留给仓库层在自己的副本上算：
            // 下面落历史快照用的就是这个 doc.wordCount，内存不刷新的话每条历史记录
            // 显示的字数都属于打开文档那一刻的版本。见 [withFreshCounts]。
            //
            // 用 updateAndGet 而不是「读出来 → 改 → 写回」：onContentChanged 是输入热路径，
            // 它不拿这把锁（拿了就要在每次按键时排队），所以写回必须是原子的 CAS，
            // 否则一次「保存中恰好又敲了一个字」就会把新字符回退掉。
            val doc = _document.updateAndGet { it?.withFreshCounts() } ?: return
            _isSaving.value = true
            try {
                val result = if (docUri != null) {
                    workspaceRepository.writeDocument(docUri, doc.content)
                } else {
                    saveDocumentUseCase(doc)
                }
                result.onSuccess {
                    isDocumentDirty = false
                    // 内部文档保存成功后落历史版本快照（内容变化才记，best-effort，失败不影响保存）
                    val internalId = documentId
                    if (docUri == null && internalId != null) {
                        // 两个后置任务各自独立 try/catch，不合并：前者失败不该连带跳过后者。
                        // 不用 runCatching——它捕获 Throwable，会把 CancellationException 一起吞掉
                        // （退出时保存走的是 appScope，进程收尾阶段确实会被取消），
                        // 取消信号断在这里，调用方就以为任务是正常跑完的。
                        // 也不再静默：原先只 runCatching 不记日志，快照/索引持续失败装机后无从取证。
                        try {
                            documentVersionRepository.snapshotIfChanged(internalId, doc.content, doc.wordCount)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ErrorHandler.report(e, UserAction.VERSION_SNAPSHOT)
                        }
                        try {
                            ragPipeline.enqueueIndex(internalId, doc.name, doc.content)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ErrorHandler.report(e, UserAction.RAG_ENQUEUE)
                        }
                    }
                }.onFailure { e ->
                    // 保存失败不改变整页状态，编辑内容保留在内存
                    _saveError.value = ErrorHandler.report(e, UserAction.SAVE)
                }
            } finally {
                // 必须放 finally：仓库层抛异常（而非返回失败 Result）或协程在写盘中途被取消时，
                // 漏掉这一句会让界面上的保存指示灯永久亮着。
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
                // Agent 直接写盘后走这条路刷新，编辑框必须跟着换。这里是唯一「磁盘内容才是
                // 真相」的入口，所以无条件发事件——即便重读回来的内容与编辑框当前一致，
                // 重放一次也只是把相同文本写回去，代价是零。见 [contentReplaced]。
                _contentReplaced.emit(doc.content)
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
     * 分支保持四条不合并：虽然 [SelectionLocator.replace] 的 range 提示 + 全文兜底看着能吃掉
     * 整个 `when`，但它的区间校验只要求「空白差异内相等」，比分支 1 的完全相等松；
     * 更关键的是它不做语法吞噬，而**只有预览模式**才该吞语法符号（见
     * [replaceWithSyntaxSwallow]）——合并会让编辑模式的兜底路径连带吃掉两侧的 `**`。
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

            // 2. 预览模式（range==null）：渲染选区无源码区间，直接走语法感知匹配。
            //    不先走 contains/trim——它们对 **bold**→bold 的单 token 会命中子串却留下 **** 残留。
            range == null ->
                replaceWithSyntaxSwallow(content, oldText, newText)

            // 3. 编辑模式 range 失效兜底：源码精确/trim 匹配（oldText 是源码选区，无语法差异）。
            //    这里刻意**不**吞语法符号：编辑模式下选中的就是源码本身，
            //    选了 **bold** 里的 bold 还顺手吃掉两侧的 ** 就是改错内容。
            oldText.isNotEmpty() && content.contains(oldText) ->
                content.replaceFirst(oldText, newText)

            else -> {
                val trimmed = oldText.trim()
                if (trimmed.isNotEmpty() && content.contains(trimmed))
                    content.replaceFirst(trimmed, newText)
                else replaceWithSyntaxSwallow(content, oldText, newText)
            }
        }

        if (newContent == null || newContent == content) {
            _applyError.value = UiMessage.Res(R.string.editor_error_locate_selection)
            return false
        }

        _document.value = doc.copy(content = newContent)
        isDocumentDirty = true
        // 与 restoreVersion 同一个理由：编辑框是屏幕自己的 remember 状态，只改 _document
        // 在「新旧内容碰巧相同」之外的情况下虽然也能被屏幕的兜底比对捞到，但那条比对是
        // 尽力而为的兜底，不是契约。整体换正文的路径一律显式发事件。见 [contentReplaced]。
        viewModelScope.launch { _contentReplaced.emit(newContent) }
        // 触发自动保存
        saveDocument()
        return true
    }

    /**
     * 预览模式兜底：渲染选区是纯文本（Markdown 语法已被去掉），与源码逐字比对会被语法符号打断——
     * `| a | b |` 渲染成 "a b"、`[文本](url)` 渲染成 "文本"、`# 标题` 渲染成 "标题"、
     * `hello **world**` 渲染成 "hello world"。
     *
     * 定位改由 [SelectionLocator] 负责。这里原来自己拼正则，用的是**无界**量词
     * `[^\p{L}\p{N}_]*` 且词元数量不设上限，一段长文本会串起上百个无界量词，
     * 在不匹配的输入上灾难性回溯——表现为编辑器整个卡死（主线程调用）。
     * [SelectionLocator] 的量词有界、词元数有上限，且要求模糊命中全文唯一：
     * 有歧义时宁可报「无法定位」，也不赌一个位置去改错地方。
     *
     * 不能直接用 [SelectionLocator.replace]：它只替换命中区间本身，而预览选区必须连带吞掉
     * 紧邻的语法符号——选中渲染后的 "bold"，源码是 `**bold**`，只换 bold 会留下 `****` 残留。
     * 所以拿回区间后在这里做前后扩展。不吞空白，避免跨段误删。
     *
     * **区间约定**：SelectionLocator 返回的 MatchRange 是半开区间 `[start, end)`，`end` 独占；
     * 而原实现用的是 `MatchResult.range.last`（闭区间，含末字符）。因此向后扩展检查的是
     * `content[endExclusive]` 而非 `content[endExclusive + 1]`，拼接尾巴也直接从 `endExclusive`
     * 起而非 `+ 1`——这里差一就会多吃或少吃一个字符。
     */
    private fun replaceWithSyntaxSwallow(content: String, oldText: String, newText: String): String? {
        if (oldText.isBlank()) return null
        val match = SelectionLocator.locate(content, oldText) ?: return null

        fun isSyntax(c: Char) = !c.isLetterOrDigit() && c != '_' && !c.isWhitespace()

        var start = match.start           // 含
        var endExclusive = match.end      // 不含
        // 向前/后吞紧邻的语法符号（**bold** 的 **、| a | 的 |），不吞空白避免跨段
        while (start - 1 >= 0 && isSyntax(content[start - 1])) start--
        while (endExclusive < content.length && isSyntax(content[endExclusive])) endExclusive++
        return content.substring(0, start) + newText + content.substring(endExclusive)
    }

    /** 导出为指定格式（仅内部文档），成功后通过 exportedFile 通知 UI 弹分享 */
    fun exportAs(format: ExportFormat) {
        val id = documentId ?: return
        viewModelScope.launch {
            doSave()  // 导出读取的是仓库数据，先确保落盘
            // 清理旧导出，但保留宽限期内的：上一份可能刚分享出去、对方还没读完（见 ExportRetention）
            fileManager.pruneExports()
            exportDocumentUseCase(
                id,
                format,
                ExportOptions(outputDir = fileManager.getExportsDir()),
                // 预览用的那份解析基址也交给导出：不传，正文里 `![](images/a.png)` 这种相对引用
                // 会被离屏 WebView 按 `file:///android_asset/` 解析，PDF/长图/富 HTML 里那几张图是空的。
                exportImageResolver()
            ).onSuccess { file ->
                _exportedFile.value = file
            }.onFailureReport(UserAction.EXPORT) { message ->
                // 原先只有一行 Log.e，装机后没法取证；report 会把带堆栈的记录落进崩溃日志。
                _saveError.value = message
            }
        }
    }

    fun clearExportedFile() {
        _exportedFile.value = null
    }

    /**
     * 预览用的 [ImageResolverConfig] → 导出用的 [ExportImageResolver]。
     *
     * 四个字段同形却不复用同一个类：`core` 不能反向依赖 `presentation`，
     * 于是搬运这一次留在调用方（见 `ExportImageInline.kt` 的说明）。
     * `appPrefix` 一起搬过去，导出的 PDF / 长图 / HTML 才认得工具栏插进来的图——
     * 那一步之后 `inlineLocalImages` 会把它们转成 base64 内联进导出件。
     */
    private fun exportImageResolver(): ExportImageResolver? =
        _imageResolver.value?.let {
            ExportImageResolver(it.prefix, it.base, it.encodeAll, it.appPrefix)
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
        autoSaveEnabled = true
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            while (isActive) { delay(intervalSec * 1000L); doSave() }
        }
        // 刻意不动 debounceSaveJob：设置流每次发射都会走到这里（改字号、换主题都算），
        // 顺手取消会把用户正在等的那次防抖保存打掉。
    }

    private fun stopAutoSave() {
        autoSaveEnabled = false
        autoSaveJob?.cancel(); autoSaveJob = null
        debounceSaveJob?.cancel(); debounceSaveJob = null
    }

    /**
     * 重排防抖定时器：只有「最后一次输入」之后的那个 delay 能活到 doSave()。
     *
     * 与固定间隔轮询共用 [doSave]——它自带脏检查与 [stateMutex]，两条路径撞在一起也安全，
     * 顶多是后到的那次看到不脏直接返回。
     */
    private fun scheduleDebouncedSave() {
        debounceSaveJob?.cancel()
        debounceSaveJob = viewModelScope.launch {
            delay(DEBOUNCE_SAVE_DELAY_MS)
            doSave()
        }
    }

    override fun onCleared() {
        stopAutoSave()
        // 这里是脏内容的最后一次机会：返回键、进程内存回收、配置变更走掉都落到 onCleared，
        // 而此时 viewModelScope **已经被取消**——用它 launch 出去的协程会立刻死掉，
        // 一个字节都写不出去，未保存的编辑就此永久丢失。所以必须挂在与进程同寿的 appScope 上。
        //
        // 不在这里重复判 isDocumentDirty：脏检查在 doSave() 的互斥锁内，
        // 外面再判一次只会和进行中的那次保存抢读同一个非 volatile 字段。
        appScope.launch {
            try {
                doSave()
            } catch (e: CancellationException) {
                // 必须排在 Exception 之前：CancellationException 是 IllegalStateException 的子类，
                // 吞掉它就破坏了结构化并发（appScope 会以为子任务正常结束了）。
                throw e
            } catch (e: Exception) {
                // 这条路径上界面已经没了，_saveError 没人看；只能落一条带堆栈的非致命记录留证，
                // 否则「退出时保存失败」在装机后完全无法取证。
                ErrorHandler.report(e, UserAction.SAVE_ON_EXIT)
            }
        }
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
                _saveError.value = UiMessage.Res(R.string.editor_error_create_document)
            }
            selectedFolderId = null
        }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            folderRepository.createFolder(name, null).onFailure {
                // 复用 strings.xml 里侧栏已有的同文案键，不在编辑器分片里重造一个
                _saveError.value = UiMessage.Res(R.string.error_create_folder)
            }
        }
    }

    fun createSubfolder(name: String, parentId: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            folderRepository.createFolder(name, parentId).onFailure {
                _saveError.value = UiMessage.Res(R.string.editor_error_create_subfolder)
            }
        }
    }

    fun renameFolder(folderId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            folderRepository.renameFolder(folderId, newName)
                .onFailureReport(UserAction.RENAME_FOLDER) { _saveError.value = it }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            folderRepository.deleteFolder(folderId, deleteContents = true)
                .onFailureReport(UserAction.DELETE_FOLDER) { _saveError.value = it }
        }
    }

    /**
     * 给当前库里的某篇文档改名（编辑器侧栏的改名入口）。
     *
     * **不再经过正文**。旧实现是「`getDocumentById` 读盘取正文 → `copy(name=…)` →
     * `saveDocument` 整篇回写 → `_document.value = renamed`」，三处都在丢数据：
     *  - 读回来的正文是**磁盘上那份**，编辑器里未保存的修改不在其中；
     *  - 整篇回写把这份旧正文按新名字又落了一次盘；
     *  - 最后 `_document.value = renamed` 把内存里的正文也换成旧的，且 `isDocumentDirty`
     *    仍是 true，下一次自动保存再把旧正文钉死。用户正在写的段落就这样没了，全程无提示。
     *
     * 现在只发一条单字段 UPDATE（[DocumentRepository.renameDocument]），内存侧也只改 `name`
     * 一个字段。用 `update` 而不是 `value =`：[onContentChanged] 是不拿锁的输入热路径，
     * 读出来改完写回去的话，改名与「改名瞬间正好敲进一个字」会互相覆盖。
     *
     * 校验补齐到与文件列表页同一套 [FileNameValidator]：从前这里只挡空白名，于是同一个
     * 改名动作在列表页会被拒（含 `/`、`:`、超 255 字、Windows 保留名…），在侧栏却放行，
     * 而落盘用的导出/同步文件名正是它。
     */
    fun renameDocument(docId: String, newName: String) {
        viewModelScope.launch {
            when (val validation = FileNameValidator.validate(newName)) {
                is ValidationResult.Success ->
                    documentRepository.renameDocument(docId, newName)
                        .onSuccess {
                            if (docId == documentId) {
                                _document.update { it?.copy(name = newName) }
                            }
                        }
                        .onFailureReport(UserAction.RENAME_DOCUMENT) { _saveError.value = it }

                is ValidationResult.Error -> _saveError.value = validation.message
            }
        }
    }

    fun deleteDocument(docId: String) {
        viewModelScope.launch {
            documentRepository.deleteDocument(docId)
                .onFailureReport(UserAction.DELETE_DOCUMENT) { _saveError.value = it }
        }
    }

    /** 把文档移动到目标文件夹(null 为根目录)。 */
    fun moveDocument(docId: String, targetFolderId: String?) {
        viewModelScope.launch {
            documentRepository.moveDocument(docId, targetFolderId)
                .onFailureReport(UserAction.MOVE_DOCUMENT) { _saveError.value = it }
        }
    }

    /** 把文件夹移动到目标父文件夹(null 为根目录);防环由仓库保证。 */
    fun moveFolder(folderId: String, targetParentId: String?) {
        viewModelScope.launch {
            folderRepository.moveFolder(folderId, targetParentId)
                .onFailureReport(UserAction.MOVE_FOLDER) { _saveError.value = it }
        }
    }

    private companion object {
        /**
         * 停手多久后落盘。
         *
         * 1.8s 是两头夹出来的：再短会在词与词的停顿之间反复写盘（每次都是一次 fsync），
         * 再长就接近默认 30s 的轮询间隔、失去「停手即存」的意义。
         */
        const val DEBOUNCE_SAVE_DELAY_MS = 1_800L
    }
}

sealed class EditorUiState {
    data object Loading : EditorUiState()
    data class Success(val document: Document) : EditorUiState()

    /**
     * 加载失败。[message] 是 [UiMessage] 而不是 String：产出侧是 core 的 ErrorHandler，
     * 那里拿不到 Context，解析要等到组合期（`presentation.common.resolve`）。
     */
    data class Error(val message: UiMessage) : EditorUiState()
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
 *
 * 两条解析路径，JS 侧按顺序试：
 * 1. [appPrefix] + 引用本身 —— 工具栏「从相册选择」存进应用私有 `images/` 的图，形态固定为
 *    `images/<uuid>.<ext>`，与文档在哪个目录无关；
 * 2. [prefix] + encode(join([base], 引用)) —— 文档所在目录里的资源；[encodeAll] 区分
 *    SAF docId 整体编码与文件路径逐段编码。[prefix] 为空串表示这条路不可用（普通库文档
 *    的正文存在数据库里，没有"所在目录"）。
 */
@Serializable
data class ImageResolverConfig(
    val prefix: String,
    val base: String,
    val encodeAll: Boolean,
    val appPrefix: String? = null
)

@Serializable
private data class OutlineItemDto(val level: Int, val text: String, val id: String)

private val outlineJson = Json { ignoreUnknownKeys = true }
