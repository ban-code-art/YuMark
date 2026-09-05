package com.yumark.app.presentation.filelist

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yumark.app.R
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.SafLocations
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserAction
import com.yumark.app.core.util.onFailureReport
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.repository.FolderRepository
import com.yumark.app.domain.usecase.importing.ImportCandidate
import com.yumark.app.domain.usecase.importing.ImportDocumentUseCase
import com.yumark.app.domain.usecase.importing.ImportFolderUseCase
import com.yumark.app.domain.usecase.importing.ImportStats
import com.yumark.app.presentation.common.SnackbarEffect
import com.yumark.app.presentation.common.resolveOrNull
import com.yumark.app.presentation.theme.AppSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「导入到库」整条流程的状态机：选文件 → 确认位置 → 复制；选文件夹 → 浏览 → 扫描 → 勾选 → 复制。
 *
 * 单独成一个 ViewModel，而不是继续挂在 [FileListViewModel] 上：编辑器侧栏也有「导入到库」入口
 * （从前那两个菜单项点了没反应，只有一行 TODO），而 [FileListViewModel] 的 init 会发一次更新检查
 * 的网络请求、还持有整份文件列表的筛选/搜索管道——为了两个菜单项在编辑器里再造一个它，
 * 等于每次打开编辑器多一次联网、多一份列表状态，还可能在编辑器里弹出「发现新版本」。
 *
 * 反过来把导入逻辑抄一份进 EditorViewModel 也不行：导入的目标文件夹解析（「导入库」哨兵 id 的
 * 惰性创建）两份实现一旦漂移，两个入口就会把文件导进不同地方。整条流程只留这一份实现。
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val folderRepository: FolderRepository,
    private val importDocumentUseCase: ImportDocumentUseCase,
    private val importFolderUseCase: ImportFolderUseCase
) : ViewModel() {

    /**
     * 上一次导入的成败计数（一次性事件），UI 收到后弹 Snackbar。
     *
     * 存计数而不是拼好的句子：英文的 "1 file" / "2 files" 只有 <plurals> 分得开，
     * 而读 plurals 的 pluralStringResource 是 @Composable，ViewModel 里调不了。
     */
    private val _importStats = MutableStateFlow<ImportStats?>(null)
    val importStats: StateFlow<ImportStats?> = _importStats.asStateFlow()

    fun clearImportStats() {
        _importStats.value = null
    }

    /** 导入过程中的错误（一次性 Snackbar）。 */
    private val _error = MutableStateFlow<UiMessage?>(null)
    val error: StateFlow<UiMessage?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    /** 导入文件夹的待选项（扫描结果），非空时 UI 显示勾选对话框。 */
    private val _importCandidates = MutableStateFlow<List<ImportCandidate>?>(null)
    val importCandidates: StateFlow<List<ImportCandidate>?> = _importCandidates.asStateFlow()

    /** 与本次扫描配套的图片资产（不进勾选列表，随勾选文档自动复制）。 */
    private var pendingImportImages: List<ImportCandidate> = emptyList()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    /**
     * 导入选中的单个/多个文件到指定位置。
     * @param targetFolderId 导入位置：默认导入库根（惰性创建）；可传任意文件夹 id，null 为根目录
     */
    fun importFiles(
        uris: List<Uri>,
        targetFolderId: String? = FolderRepository.IMPORT_LIBRARY_FOLDER_ID
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isImporting.value = true
            val resolved = resolveImportTarget(targetFolderId).getOrElse {
                _error.value = ErrorHandler.report(it, UserAction.CREATE_IMPORT_LIBRARY)
                _isImporting.value = false
                return@launch
            }
            var ok = 0
            uris.forEach { uri ->
                importDocumentUseCase(uri, resolved)
                    .onSuccess { ok++ }
                    .onFailureReport(UserAction.IMPORT) { _error.value = it }
            }
            _isImporting.value = false
            // 逐个文件的失败已经各自弹过 _error，这里只汇报成功数（failed = 0）
            if (ok > 0) _importStats.value = ImportStats(imported = ok, failed = 0)
        }
    }

    /** 把「导入库」哨兵 id 解析成真实文件夹 id（惰性创建）；其余 id 或 null（根目录）原样返回。 */
    private suspend fun resolveImportTarget(targetFolderId: String?): Result<String?> =
        if (targetFolderId == FolderRepository.IMPORT_LIBRARY_FOLDER_ID) {
            folderRepository.ensureImportLibraryFolder().map { it.id }
        } else {
            Result.success(targetFolderId)
        }

    /** 扫描待导入文件夹（根授权 + 应用内浏览选定的子路径），得到候选列表供勾选（默认全不选）。 */
    fun scanImportFolder(treeUri: String, relativePath: List<String> = emptyList()) {
        viewModelScope.launch {
            _isImporting.value = true
            importFolderUseCase.scan(treeUri, relativePath)
                .onSuccess { result ->
                    if (result.documents.isEmpty()) {
                        _error.value = UiMessage.Res(R.string.filelist_import_no_markdown)
                    } else {
                        pendingImportImages = result.images
                        _importCandidates.value = result.documents
                    }
                }
                .onFailureReport(UserAction.SCAN_FOLDER) { _error.value = it }
            _isImporting.value = false
        }
    }

    fun cancelImportFolder() {
        _importCandidates.value = null
        pendingImportImages = emptyList()
    }

    /** 确认导入勾选的候选文件，复制进所选位置（图片资产一并复制）；逐文件容错并汇报成败数。 */
    fun confirmImportFolder(
        selected: List<ImportCandidate>,
        targetFolderId: String? = FolderRepository.IMPORT_LIBRARY_FOLDER_ID
    ) {
        _importCandidates.value = null
        val images = pendingImportImages
        pendingImportImages = emptyList()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            _isImporting.value = true
            importFolderUseCase(selected, images, targetFolderId)
                // 成败计数原样交给界面层：三种文案（全成/全败/部分成）都要按数字选单复数
                .onSuccess { stats -> _importStats.value = stats }
                .onFailureReport(UserAction.IMPORT_FOLDER) { _error.value = it }
            _isImporting.value = false
        }
    }
}

/** [rememberImportFlow] 交给调用方的两个入口；除此之外整条流程不需要外部配合。 */
class ImportFlowHandle internal constructor(
    /** 拉起系统多选文件选择器。 */
    val pickFiles: () -> Unit,
    /** 拉起系统目录授权选择器（之后是应用内浏览 + 勾选）。 */
    val pickFolder: () -> Unit
)

/**
 * 「导入到库」流程的界面侧：**这个函数会自己发射对话框和 Snackbar**，调用方只拿两个 lambda。
 *
 * 之所以做成一个 @Composable 而不是一组可复用的对话框零件：整条流程有 5 个 UI 状态
 * （待确认的文件、待浏览的目录、勾选列表、导入中、结果提示），任何一处漏接都是一个静默失败——
 * 文件列表页曾经把这 5 个状态一个个手写在页面里，编辑器侧栏要复现就得再抄一遍。
 * 把状态和它们的对话框一起封在这里，接入点只剩「点菜单调 pickFiles/pickFolder」。
 *
 * 必须在会一直参与组合的位置调用（页面顶层），不能塞进 `if (menuOpen) { }`：
 * 菜单一收，导入中的进度对话框和结果 Snackbar 就跟着组合一起消失了。
 *
 * @param folders 导入位置选择器要列出的文件夹；编辑器侧传 `viewModel.folders` 即可
 */
@Composable
fun rememberImportFlow(
    folders: List<Folder>,
    snackbarHostState: SnackbarHostState,
    viewModel: ImportViewModel = hiltViewModel()
): ImportFlowHandle {
    val context = LocalContext.current

    // 导入结果提示：ViewModel 只给成败计数，单复数分支在这里选
    // （pluralStringResource 是 @Composable，ViewModel 里读不到）。
    val importStats by viewModel.importStats.collectAsStateWithLifecycle()
    val importMessage = importStats?.let { stats ->
        when {
            stats.failed == 0 ->
                pluralStringResource(R.plurals.filelist_import_done, stats.imported, stats.imported)
            stats.imported == 0 ->
                pluralStringResource(R.plurals.filelist_import_failed, stats.failed, stats.failed)
            // 部分成功：quantity 按成功数选，失败数只是第二个实参
            else -> pluralStringResource(
                R.plurals.filelist_import_partial,
                stats.imported,
                stats.imported,
                stats.failed
            )
        }
    }
    SnackbarEffect(importMessage, snackbarHostState) { viewModel.clearImportStats() }

    val error by viewModel.error.collectAsStateWithLifecycle()
    SnackbarEffect(error.resolveOrNull(), snackbarHostState) { viewModel.clearError() }

    // 导入文件：系统多选选择器，仅勾选项被导入（手动选择，非自动导入）；
    // 选完先弹确认对话框回显文件数与导入位置（默认导入库，可自定义）
    var pendingFiles by remember { mutableStateOf<List<Uri>?>(null) }
    val filesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // 持久读取授权对每个文件单独生效（复制读取需要）
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            pendingFiles = uris
        }
    }

    pendingFiles?.let { uris ->
        ImportFilesDialog(
            fileCount = uris.size,
            folders = folders,
            onConfirm = { targetFolderId ->
                viewModel.importFiles(uris, targetFolderId)
                pendingFiles = null
            },
            onDismiss = { pendingFiles = null }
        )
    }

    // 导入文件夹：系统选择器只负责授权入口文件夹（部分 ROM 点进文件夹即返回，
    // 选不到深层），之后弹应用内浏览器逐层进入，点「导入此文件夹」才扫描
    var pendingDir by remember { mutableStateOf<Uri?>(null) }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 浏览即需读权限，拿到结果立即持久授权
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pendingDir = uri
        }
    }

    pendingDir?.let { uri ->
        ImportFolderBrowserDialog(
            treeUri = uri,
            onConfirm = { relativePath ->
                viewModel.scanImportFolder(uri.toString(), relativePath)
                pendingDir = null
            },
            onDismiss = { pendingDir = null }
        )
    }

    // 文件夹导入勾选对话框（默认全不选，手动勾；可自定义导入位置）
    val candidates by viewModel.importCandidates.collectAsStateWithLifecycle()
    candidates?.let { list ->
        ImportSelectionDialog(
            candidates = list,
            folders = folders,
            onConfirm = { selected, targetFolderId ->
                viewModel.confirmImportFolder(selected, targetFolderId)
            },
            onDismiss = { viewModel.cancelImportFolder() }
        )
    }

    // 导入中状态：模态进度提示（扫描/复制期间均显示）
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    if (isImporting) {
        AlertDialog(
            onDismissRequest = { /* 导入中不可取消 */ },
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ImportFlowMetrics.SpinnerSize),
                        strokeWidth = ImportFlowMetrics.SpinnerStroke
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.Screen))
                    Text(stringResource(R.string.import_in_progress))
                }
            }
        )
    }

    return remember(filesLauncher, folderLauncher) {
        ImportFlowHandle(
            pickFiles = {
                // 三种 mime：不少文件管理器把 .md 报成 application/octet-stream，
                // 只写 text/markdown 会让目标文件在选择器里变灰选不中。
                filesLauncher.launch(
                    arrayOf("text/*", "text/markdown", "application/octet-stream")
                )
            },
            pickFolder = { folderLauncher.launch(SafLocations.storageRootHint()) }
        )
    }
}

/**
 * 导入进度对话框特有的度量，刻意不并入全局标度：环形进度指示器直径（28dp）与描边宽度（3dp，
 * 绘制轴）。指示器不可点、非触控目标，无需满足 48dp 命中区；离散于全局标度，保留原像素、不硬凑。
 * 按 FileListMetrics 先例落屏幕局部。
 */
private object ImportFlowMetrics {
    /** 导入中进度环直径：28dp（非交互指示器，非触控目标）；off-grid。 */
    val SpinnerSize = 28.dp
    /** 进度环描边宽度：绘制轴，非间距。 */
    val SpinnerStroke = 3.dp
}
