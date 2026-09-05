package com.yumark.app.presentation.settings

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.yumark.app.BuildConfig
import com.yumark.app.R
import com.yumark.app.core.update.ApkDownloader
import com.yumark.app.core.crash.CrashReporter
import com.yumark.app.core.update.DownloadState
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.SafLocations
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserAction
import com.yumark.app.data.remote.UpdateChecker
import com.yumark.app.domain.model.CompressionQuality
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.ConfigBackup
import com.yumark.app.domain.model.ConfigImportSummary
import com.yumark.app.domain.model.UpdateInfo
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.repository.SettingsRepository
import com.yumark.app.domain.repository.WorkspaceRepository
import com.yumark.app.domain.usecase.ai.GetAiConfigUseCase
import com.yumark.app.domain.usecase.config.ExportConfigUseCase
import com.yumark.app.domain.usecase.config.ImportConfigUseCase
import com.yumark.app.domain.usecase.crash.ExportCrashLogUseCase
import com.yumark.app.presentation.common.FolderConfirmDialog
import com.yumark.app.presentation.common.SnackbarEffect
import com.yumark.app.presentation.common.resolve
import com.yumark.app.presentation.navigation.Screen
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppMotion
import com.yumark.app.presentation.theme.AppSpacing
import com.yumark.app.presentation.theme.AppThemes
import com.yumark.app.presentation.theme.appColorScheme
import com.yumark.app.presentation.theme.extendedColors
import com.yumark.app.presentation.theme.toCssHex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val updateChecker: UpdateChecker,
    private val exportConfig: ExportConfigUseCase,
    private val importConfig: ImportConfigUseCase,
    private val crashReporter: CrashReporter,
    private val exportCrashLog: ExportCrashLogUseCase,
    private val fileManager: com.yumark.app.data.local.file.FileManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    getAiConfig: GetAiConfigUseCase
) : ViewModel() {
    private val _settings = MutableStateFlow(UserSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    /** AI 配置（用于设置项显示启用状态与 Provider） */
    val aiConfig: StateFlow<AiConfig> =
        getAiConfig().stateIn(viewModelScope, SharingStarted.Eagerly, AiConfig())

    /** 默认目录显示名，null 表示未设置 */
    private val _defaultDirName = MutableStateFlow<String?>(null)
    val defaultDirName: StateFlow<String?> = _defaultDirName.asStateFlow()

    /** 更新检查状态 */
    private val _updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateState: StateFlow<UpdateCheckState> = _updateState.asStateFlow()

    /** 配置导出/导入状态 */
    private val _configState = MutableStateFlow<ConfigTransferState>(ConfigTransferState.Idle)
    val configState: StateFlow<ConfigTransferState> = _configState.asStateFlow()

    // 崩溃日志相关状态。刷新时机交给界面（进入设置页时拉一次），
    // 这样非致命错误在别处刚被记下，回到这一页就能看到新的条数。

    /** 记录条数。只有设置页用，不值得做成 Flow；查看/导出/清除后各自刷新。 */
    private val _crashLogCount = MutableStateFlow(0)
    val crashLogCount: StateFlow<Int> = _crashLogCount.asStateFlow()

    /** 正在查看的记录正文，null = 没打开查看框 */
    private val _crashLogText = MutableStateFlow<String?>(null)
    val crashLogText: StateFlow<String?> = _crashLogText.asStateFlow()

    private val _crashExportState = MutableStateFlow<CrashExportState>(CrashExportState.Idle)
    val crashExportState: StateFlow<CrashExportState> = _crashExportState.asStateFlow()

    /**
     * 设置项写盘失败的一次性提示（弹完由界面调 [consumeActionError] 清掉）。
     *
     * 补这条通道的原因：下面那批 mutator 全是 `viewModelScope.launch { repo.updateX(...) }`，
     * 而每个 `updateX` 都返回 `Result`——之前一个都没看。DataStore 写失败（磁盘满、
     * 存储被回收、SAF 授权失效）时开关照旧弹回去、页面一声不响，用户以为改好了，
     * 下次进来发现全是旧值。同一个文件里配置导入导出早就是 `fold(onFailure = …)`，这里补齐。
     */
    private val _actionError = MutableStateFlow<UiMessage?>(null)
    val actionError: StateFlow<UiMessage?> = _actionError.asStateFlow()

    fun consumeActionError() {
        _actionError.value = null
    }

    /** 把失败记进非致命日志并转成给用户看的一句话。[ErrorHandler.report] 自带脱敏与截断。 */
    private fun reportAction(t: Throwable, action: UserAction) {
        _actionError.value = ErrorHandler.report(t, action)
    }

    init {
        viewModelScope.launch { repo.observeSettings().collect { _settings.value = it } }
        // 默认目录 URI 变化时刷新显示名
        viewModelScope.launch {
            workspaceRepository.defaultDirUri.collect {
                _defaultDirName.value = if (it == null) null else workspaceRepository.defaultDirName()
            }
        }
    }

    /** 设为默认目录（UI 已完成持久授权）；同时立即打开为当前工作区 */
    fun setDefaultDir(treeUri: String) {
        viewModelScope.launch {
            // 打不开就不该留下「默认目录」的假象（仓库层已改成成功才落库），这里把失败说出来。
            workspaceRepository.setDefaultDir(treeUri)
                .onFailure { reportAction(it, UserAction.OPEN_FOLDER) }
        }
    }

    fun clearDefaultDir() {
        viewModelScope.launch {
            runCatching { workspaceRepository.clearDefaultDir() }
                .onFailure { reportAction(it, UserAction.SAVE_SETTINGS) }
        }
    }

    fun updateFontSize(s: Int) {
        viewModelScope.launch {
            repo.updateFontSize(s).onFailure { reportAction(it, UserAction.SAVE_SETTINGS) }
        }
    }

    fun updateAutoSave(on: Boolean, interval: Int) {
        viewModelScope.launch {
            repo.updateAutoSave(on, interval).onFailure { reportAction(it, UserAction.SAVE_SETTINGS) }
        }
    }

    fun updateCompression(autoCompress: Boolean, quality: CompressionQuality, maxWidth: Int) {
        viewModelScope.launch {
            repo.updateCompressionSettings(autoCompress, quality, maxWidth)
                .onFailure { reportAction(it, UserAction.SAVE_SETTINGS) }
        }
    }

    fun updateDefaultPreviewMode(on: Boolean) {
        viewModelScope.launch {
            repo.updateSettings(settings.value.copy(defaultPreviewMode = on))
                .onFailure { reportAction(it, UserAction.SAVE_SETTINGS) }
        }
    }

    fun updateThemeId(id: String) {
        viewModelScope.launch {
            repo.updateSettings(settings.value.copy(themeId = id))
                .onFailure { reportAction(it, UserAction.SAVE_SETTINGS) }
        }
    }

    fun updateDarkMode(mode: String) {
        viewModelScope.launch {
            repo.updateSettings(settings.value.copy(darkMode = mode))
                .onFailure { reportAction(it, UserAction.SAVE_SETTINGS) }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            repo.resetToDefaults().onFailure { reportAction(it, UserAction.SAVE_SETTINGS) }
        }
    }

    /** 检查更新 */
    fun checkUpdate() {
        viewModelScope.launch {
            _updateState.value = UpdateCheckState.Checking
            try {
                val updateInfo = updateChecker.checkUpdate()
                _updateState.value = if (updateInfo != null) {
                    UpdateCheckState.Available(updateInfo)
                } else {
                    UpdateCheckState.NoUpdate
                }
            } catch (e: Exception) {
                // 用 message 而不是 report：离线启动时这里天天失败，记进崩溃日志会把
                // 20 条的配额挤满，真正的崩溃反而留不下来。取消也不再被当成错误弹出。
                _updateState.value = UpdateCheckState.Error(ErrorHandler.message(e, UserAction.CHECK_UPDATE))
            }
        }
    }

    /** 重置更新状态 */
    fun resetUpdateState() {
        _updateState.value = UpdateCheckState.Idle
    }

    /** 导出配置文件建议名（交给 SAF 的 CreateDocument 作为初始文件名） */
    fun suggestedConfigFileName(): String = exportConfig.suggestFileName()

    fun exportConfigTo(uri: android.net.Uri, includeSecrets: Boolean) {
        viewModelScope.launch {
            _configState.value = ConfigTransferState.Running
            _configState.value = exportConfig(uri, includeSecrets).fold(
                onSuccess = { ConfigTransferState.Exported(includeSecrets) },
                onFailure = { ConfigTransferState.Failed(ErrorHandler.report(it, UserAction.EXPORT_CONFIG)) }
            )
        }
    }

    fun importConfigFrom(uri: android.net.Uri) {
        viewModelScope.launch {
            _configState.value = ConfigTransferState.Running
            _configState.value = importConfig(uri).fold(
                onSuccess = { ConfigTransferState.Imported(it) },
                onFailure = { ConfigTransferState.Failed(ErrorHandler.report(it, UserAction.IMPORT_CONFIG)) }
            )
        }
    }

    fun resetConfigState() {
        _configState.value = ConfigTransferState.Idle
    }

    // ---- 崩溃日志 ----

    /** count() 要列目录，一律走 IO。 */
    fun refreshCrashLogCount() {
        viewModelScope.launch {
            _crashLogCount.value = withContext(Dispatchers.IO) { crashReporter.count() }
        }
    }

    fun openLatestCrashLog() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { crashReporter.latestText() }
            // 读不出来就什么都不弹：空白对话框比没有对话框更让人困惑
            if (!text.isNullOrBlank()) _crashLogText.value = text
        }
    }

    fun closeCrashLog() {
        _crashLogText.value = null
    }

    fun suggestedCrashLogFileName(): String = exportCrashLog.suggestFileName()

    fun exportCrashLogTo(uri: android.net.Uri) {
        viewModelScope.launch {
            _crashExportState.value = exportCrashLog(uri).fold(
                onSuccess = { CrashExportState.Done(it) },
                onFailure = { CrashExportState.Failed(ErrorHandler.report(it, UserAction.EXPORT_CRASH_LOGS)) }
            )
        }
    }

    fun resetCrashExportState() {
        _crashExportState.value = CrashExportState.Idle
    }

    fun clearCrashLogs() {
        viewModelScope.launch {
            // 删完重新数一遍而不是直接置 0：删除可能部分失败，界面不该谎报清空
            _crashLogCount.value = withContext(Dispatchers.IO) {
                crashReporter.clear()
                crashReporter.count()
            }
            _crashLogText.value = null
        }
    }

    // ---- 同步删除的正文备份（sync_trash）----

    /** 备份额。查看/导出/清除后各自刷新，与崩溃日志同款。 */
    private val _syncTrashCount = MutableStateFlow(0)
    val syncTrashCount: StateFlow<Int> = _syncTrashCount.asStateFlow()

    /** 正在查看的备份正文，null = 没打开查看框。最新的在最前（导出拼接顺序）。 */
    private val _syncTrashText = MutableStateFlow<String?>(null)
    val syncTrashText: StateFlow<String?> = _syncTrashText.asStateFlow()

    /** 导出结果回显。独立于 [crashExportState]：两个入口的文案不同，复用会把「记录」错说成「份」。 */
    private val _syncTrashExportState = MutableStateFlow<CrashExportState>(CrashExportState.Idle)
    val syncTrashExportState: StateFlow<CrashExportState> = _syncTrashExportState.asStateFlow()

    /** list() 列目录，一律走 IO。 */
    fun refreshSyncTrashCount() {
        viewModelScope.launch {
            _syncTrashCount.value = withContext(Dispatchers.IO) { fileManager.syncTrashCount() }
        }
    }

    fun openSyncTrash() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { fileManager.syncTrashExportText() }
            // 读不出来就什么都不弹：空白对话框比没有对话框更让人困惑（同 crashLog 的取舍）
            if (!text.isBlank()) _syncTrashText.value = text
        }
    }

    fun closeSyncTrash() {
        _syncTrashText.value = null
    }

    fun suggestedSyncTrashFileName(): String {
        val stamp = java.text.SimpleDateFormat(
            com.yumark.app.data.local.file.SyncTrashStore.STAMP_FORMAT,
            java.util.Locale.US
        ).format(java.util.Date())
        return "yumark-sync-trash-$stamp.md"
    }

    fun exportSyncTrashTo(uri: android.net.Uri) {
        viewModelScope.launch {
            _syncTrashExportState.value = runCatching {
                withContext(Dispatchers.IO) {
                    val count = fileManager.syncTrashCount()
                    val text = fileManager.syncTrashExportText()
                    val stream = appContext.contentResolver.openOutputStream(uri, "wt")
                        ?: throw com.yumark.app.core.util.FriendlyIOException(
                            com.yumark.app.core.util.UiMessage.Res(com.yumark.app.R.string.saf_error_write_target)
                        )
                    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    count
                }
            }.fold(
                onSuccess = { CrashExportState.Done(it) },
                onFailure = { CrashExportState.Failed(ErrorHandler.report(it, UserAction.EXPORT_CRASH_LOGS)) }
            )
        }
    }

    fun resetSyncTrashExportState() {
        _syncTrashExportState.value = CrashExportState.Idle
    }

    fun clearSyncTrash() {
        viewModelScope.launch {
            _syncTrashCount.value = withContext(Dispatchers.IO) {
                fileManager.syncTrashClear()
                fileManager.syncTrashCount()
            }
            _syncTrashText.value = null
        }
    }
}

/** 配置导出/导入状态 */
sealed interface ConfigTransferState {
    object Idle : ConfigTransferState
    object Running : ConfigTransferState
    data class Exported(val includedSecrets: Boolean) : ConfigTransferState
    data class Imported(val summary: ConfigImportSummary) : ConfigTransferState
    data class Failed(val message: UiMessage) : ConfigTransferState
}

/**
 * 导入时接受的 MIME 类型。
 * 只放 application/json 会在部分文件管理器/网盘里把我们自己导出的文件灰掉——
 * 它们按扩展名猜不出类型时报 text/plain 或 octet-stream。内容合法性由魔数与 schema 校验兜底。
 */
private val CONFIG_IMPORT_MIME_TYPES = arrayOf(
    ConfigBackup.MIME_TYPE,
    "text/plain",
    "application/octet-stream"
)

/** 崩溃日志导出的 MIME：纯文本，任何设备上都能直接打开，也方便贴进工单 */
private const val CRASH_LOG_MIME_TYPE = "text/plain"

/** 崩溃日志导出状态 */
sealed interface CrashExportState {
    object Idle : CrashExportState
    data class Done(val count: Int) : CrashExportState
    data class Failed(val message: UiMessage) : CrashExportState
}

/** 更新检查状态 */
sealed class UpdateCheckState {
    object Idle : UpdateCheckState()
    object Checking : UpdateCheckState()
    data class Available(val updateInfo: UpdateInfo) : UpdateCheckState()
    object NoUpdate : UpdateCheckState()
    data class Error(val message: UiMessage) : UpdateCheckState()
}

/**
 * 本屏的组件尺寸与布局常量。
 *
 * 与 `AppSpacing` / `AppIconSize` 分工明确：那两个是**跨屏复用的角色令牌**（间距该多大、
 * 图标该多大），这里是**只有这一屏才有的具体物件该多大**——一个主题色板圆点、一个对话框
 * 的滚动上限。把它们塞进令牌表会让令牌变成杂物抽屉，而继续写成裸字面量则没人知道
 * `300.dp` 和 `320.dp` 是两个不同的东西还是同一个东西写错了。
 *
 * 尤其是 [ThemeSwatchSize]：它恰好等于 `AppIconSize.Inline`，但它量的是一个**色块**不是图标，
 * 哪天行内图标要调大，色板不该跟着变。
 */
private val ThemeSwatchSize = 16.dp
private val DialogScrollMaxHeight = 360.dp
private val ImportSummaryMaxHeight = 320.dp
private val ChangelogCollapsedHeight = 120.dp
private val ChangelogExpandedHeight = 300.dp
private val DownloadBarHeight = 8.dp

/**
 * UI 测试用的稳定锚点。集中放在这里而不是散在各控件旁边：
 * 标签是测试与界面之间的契约，改文案不该改标签，改标签必须一眼看到全部受影响项。
 *
 * 主题项与深色模式项用「前缀 + 取值」拼出标签，因为它们是循环渲染的，
 * 一个固定标签会在同一棵树里出现多次，测试无法定位到具体某一项。
 */
private object SettingsTestTags {
    const val ROOT = "settings_screen"
    const val BACK = "settings_back_button"
    const val THEME_OPTION_PREFIX = "settings_theme_option_"
    const val DARK_MODE_CHIP_PREFIX = "settings_dark_mode_chip_"
    const val FONT_SIZE_SLIDER = "settings_font_size_slider"
    const val AUTO_SAVE_SWITCH = "settings_auto_save_switch"
    const val AUTO_SAVE_INTERVAL_CHIP_PREFIX = "settings_auto_save_interval_chip_"
    const val AUTO_COMPRESS_SWITCH = "settings_auto_compress_switch"
    const val COMPRESSION_QUALITY_CHIP_PREFIX = "settings_compression_quality_chip_"
    const val MAX_IMAGE_WIDTH_SLIDER = "settings_max_image_width_slider"
    const val DEFAULT_PREVIEW_SWITCH = "settings_default_preview_switch"
    const val DEFAULT_DIR_CHOOSE = "settings_default_dir_choose"
    const val DEFAULT_DIR_CLEAR = "settings_default_dir_clear"
    const val AI_ROW = "settings_ai_row"
    const val SYNC_ROW = "settings_sync_row"
    const val CONFIG_EXPORT = "settings_config_export"
    const val CONFIG_IMPORT = "settings_config_import"
    const val CRASH_LOGS_ROW = "settings_crash_logs_row"
    const val CRASH_LOGS_EXPORT = "settings_crash_logs_export"
    const val CRASH_LOGS_CLEAR = "settings_crash_logs_clear"
    const val SYNC_TRASH_ROW = "settings_sync_trash_row"
    const val SYNC_TRASH_EXPORT = "settings_sync_trash_export"
    const val SYNC_TRASH_CLEAR = "settings_sync_trash_clear"
    const val SYNC_TRASH_VIEW_CLOSE = "settings_sync_trash_view_close"
    const val SYNC_TRASH_CLEAR_CONFIRM = "settings_sync_trash_clear_confirm"
    const val SYNC_TRASH_CLEAR_CANCEL = "settings_sync_trash_clear_cancel"
    const val RESET_ROW = "settings_reset_row"
    const val RESET_BUTTON = "settings_reset_button"
    const val CHECK_UPDATE_ROW = "settings_check_update_row"
    const val CHECK_UPDATE_BUTTON = "settings_check_update_button"

    // 对话框按钮
    const val EXPORT_SECRETS_CONFIRM = "settings_export_with_secrets"
    const val EXPORT_SECRETS_DISMISS = "settings_export_without_secrets"
    const val IMPORT_CONFIRM = "settings_import_confirm"
    const val IMPORT_CANCEL = "settings_import_cancel"
    const val CRASH_VIEW_CLOSE = "settings_crash_view_close"
    const val CRASH_CLEAR_CONFIRM = "settings_crash_clear_confirm"
    const val CRASH_CLEAR_CANCEL = "settings_crash_clear_cancel"
    const val RESET_CONFIRM = "settings_reset_confirm"
    const val RESET_CANCEL = "settings_reset_cancel"
    // 结果对话框的关闭键。三/两个分支共用一个标签是安全的：分支由同一个 state 决定，
    // 任一时刻只可能渲染其中一个，树里不会出现重复标签。
    const val CONFIG_RESULT_CLOSE = "settings_config_result_close"
    const val CRASH_RESULT_CLOSE = "settings_crash_result_close"
    const val UPDATE_CHANGELOG_TOGGLE = "settings_update_changelog_toggle"
    const val UPDATE_LATER = "settings_update_later_button"
    const val UPDATE_NOW = "settings_update_now_button"
    const val DOWNLOAD_CLOSE = "settings_download_close_button"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val defaultDirName by viewModel.defaultDirName.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val aiConfig by viewModel.aiConfig.collectAsStateWithLifecycle()
    val configState by viewModel.configState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 方案 A：系统选择器返回后，先回显名称让用户确认，确认后才持久授权 + 设为默认目录
    var pendingDir by remember { mutableStateOf<android.net.Uri?>(null) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) pendingDir = uri }

    pendingDir?.let { uri ->
        FolderConfirmDialog(
            uri = uri,
            titleRes = R.string.default_dir_confirm_title,
            messageRes = R.string.default_dir_confirm_message,
            onConfirm = {
                // 用户确认后才持久授权，避免误选目录也占用授权配额
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.setDefaultDir(uri.toString())
                pendingDir = null
            },
            onDismiss = { pendingDir = null }
        )
    }

    // 更新对话框
    var downloadingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

    if (updateState is UpdateCheckState.Available) {
        UpdateDialog(
            updateInfo = (updateState as UpdateCheckState.Available).updateInfo,
            onDismiss = { viewModel.resetUpdateState() },
            onUpdate = { updateInfo ->
                downloadingUpdate = updateInfo
                viewModel.resetUpdateState()
            }
        )
    }

    // 下载对话框
    downloadingUpdate?.let { updateInfo ->
        DownloadDialog(
            updateInfo = updateInfo,
            onDismiss = { downloadingUpdate = null },
            context = context
        )
    }

    // ---- 配置导出/导入 ----
    // 「是否含密钥」在打开 SAF 之前问：选完保存位置再追问一个安全问题，用户已经没有上下文了。
    // rememberSaveable：SAF 期间进程被回收时若丢了这个答案，回调只能静默什么都不做。
    var exportWithSecrets by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var askExportSecrets by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val configExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ConfigBackup.MIME_TYPE)
    ) { uri ->
        val includeSecrets = exportWithSecrets
        exportWithSecrets = null
        if (uri != null && includeSecrets != null) {
            viewModel.exportConfigTo(uri, includeSecrets)
        }
    }
    val configImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingImport = uri }

    if (askExportSecrets) {
        val launchExport: (Boolean) -> Unit = { includeSecrets ->
            exportWithSecrets = includeSecrets
            askExportSecrets = false
            configExporter.launch(viewModel.suggestedConfigFileName())
        }
        AlertDialog(
            onDismissRequest = { askExportSecrets = false },
            title = { Text(stringResource(R.string.config_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Default)) {
                    Text(stringResource(R.string.config_export_message))
                    Text(
                        stringResource(R.string.config_export_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { launchExport(true) },
                    modifier = Modifier.testTag(SettingsTestTags.EXPORT_SECRETS_CONFIRM)
                ) {
                    Text(stringResource(R.string.config_export_with_secrets))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { launchExport(false) },
                    modifier = Modifier.testTag(SettingsTestTags.EXPORT_SECRETS_DISMISS)
                ) {
                    Text(stringResource(R.string.config_export_without_secrets))
                }
            }
        )
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.config_import_title)) },
            text = { Text(stringResource(R.string.config_import_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        viewModel.importConfigFrom(uri)
                    },
                    modifier = Modifier.testTag(SettingsTestTags.IMPORT_CONFIRM)
                ) { Text(stringResource(R.string.config_import_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingImport = null },
                    modifier = Modifier.testTag(SettingsTestTags.IMPORT_CANCEL)
                ) {
                    Text(stringResource(R.string.config_dialog_cancel))
                }
            }
        )
    }

    ConfigTransferDialog(state = configState, onDismiss = { viewModel.resetConfigState() })

    // ---- 崩溃日志 ----
    val crashLogCount by viewModel.crashLogCount.collectAsStateWithLifecycle()
    val crashLogText by viewModel.crashLogText.collectAsStateWithLifecycle()
    val crashExportState by viewModel.crashExportState.collectAsStateWithLifecycle()
    var askClearCrashLogs by remember { mutableStateOf(false) }
    var askResetSettings by remember { mutableStateOf(false) }

    // ---- 同步删除的正文备份 ----
    val syncTrashCount by viewModel.syncTrashCount.collectAsStateWithLifecycle()
    val syncTrashText by viewModel.syncTrashText.collectAsStateWithLifecycle()
    var askClearSyncTrash by remember { mutableStateOf(false) }

    // 设置项写盘失败的提示。用 Snackbar 而不是对话框：这类失败不需要用户决策，
    // 只需要让他知道刚才那一下没存上（否则开关弹回去看着像自己手滑）。
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarEffect(actionError?.resolve(), snackbarHostState) { viewModel.consumeActionError() }

    // 进入这一页时数一次：ViewModel 常被返回栈复用，只在 init 里数会一直显示旧条数
    LaunchedEffect(Unit) { viewModel.refreshCrashLogCount() }
    LaunchedEffect(Unit) { viewModel.refreshSyncTrashCount() }

    val crashLogExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CRASH_LOG_MIME_TYPE)
    ) { uri -> if (uri != null) viewModel.exportCrashLogTo(uri) }

    val syncTrashExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CRASH_LOG_MIME_TYPE)
    ) { uri -> if (uri != null) viewModel.exportSyncTrashTo(uri) }

    crashLogText?.let { text ->
        AlertDialog(
            onDismissRequest = { viewModel.closeCrashLog() },
            title = { Text(stringResource(R.string.crash_view_title)) },
            text = {
                // 等宽 + 可选中：堆栈缩进要对齐才看得清，而用户通常还要复制出去贴给开发者
                SelectionContainer {
                    Text(
                        text = text,
                        modifier = Modifier
                            .heightIn(max = DialogScrollMaxHeight)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.closeCrashLog() },
                    modifier = Modifier.testTag(SettingsTestTags.CRASH_VIEW_CLOSE)
                ) {
                    Text(stringResource(R.string.config_dialog_close))
                }
            }
        )
    }

    if (askClearCrashLogs) {
        AlertDialog(
            onDismissRequest = { askClearCrashLogs = false },
            title = { Text(stringResource(R.string.crash_clear_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.crash_clear_message,
                        crashLogCount,
                        crashLogCount
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askClearCrashLogs = false
                        viewModel.clearCrashLogs()
                    },
                    modifier = Modifier.testTag(SettingsTestTags.CRASH_CLEAR_CONFIRM)
                ) { Text(stringResource(R.string.crash_clear_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { askClearCrashLogs = false },
                    modifier = Modifier.testTag(SettingsTestTags.CRASH_CLEAR_CANCEL)
                ) {
                    Text(stringResource(R.string.config_dialog_cancel))
                }
            }
        )
    }

    // ---- 同步删除的正文备份：查看框 ----
    syncTrashText?.let { text ->
        AlertDialog(
            onDismissRequest = { viewModel.closeSyncTrash() },
            title = { Text(stringResource(R.string.sync_trash_view_title)) },
            text = {
                SelectionContainer {
                    Text(
                        text = text,
                        modifier = Modifier
                            .heightIn(max = DialogScrollMaxHeight)
                            .verticalScroll(rememberScrollState()),
                        // 等宽与崩溃日志同款：正文缩进对齐 + 用户要复制出去恢复
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.closeSyncTrash() },
                    modifier = Modifier.testTag(SettingsTestTags.SYNC_TRASH_VIEW_CLOSE)
                ) {
                    Text(stringResource(R.string.config_dialog_close))
                }
            }
        )
    }

    // ---- 同步删除的正文备份：清除确认 ----
    if (askClearSyncTrash) {
        AlertDialog(
            onDismissRequest = { askClearSyncTrash = false },
            title = { Text(stringResource(R.string.sync_trash_clear_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.sync_trash_clear_message,
                        syncTrashCount,
                        syncTrashCount
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askClearSyncTrash = false
                        viewModel.clearSyncTrash()
                    },
                    modifier = Modifier.testTag(SettingsTestTags.SYNC_TRASH_CLEAR_CONFIRM)
                ) { Text(stringResource(R.string.sync_trash_clear_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { askClearSyncTrash = false },
                    modifier = Modifier.testTag(SettingsTestTags.SYNC_TRASH_CLEAR_CANCEL)
                ) {
                    Text(stringResource(R.string.config_dialog_cancel))
                }
            }
        )
    }

    if (askResetSettings) {
        AlertDialog(
            onDismissRequest = { askResetSettings = false },
            title = { Text(stringResource(R.string.reset_settings)) },
            text = { Text(stringResource(R.string.reset_settings_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askResetSettings = false
                        viewModel.resetToDefaults()
                    },
                    modifier = Modifier.testTag(SettingsTestTags.RESET_CONFIRM)
                ) { Text(stringResource(R.string.reset_settings)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { askResetSettings = false },
                    modifier = Modifier.testTag(SettingsTestTags.RESET_CANCEL)
                ) { Text(stringResource(R.string.config_dialog_cancel)) }
            }
        )
    }

    CrashExportResultDialog(
        state = crashExportState,
        onDismiss = { viewModel.resetCrashExportState() }
    )

    val syncTrashExportState by viewModel.syncTrashExportState.collectAsStateWithLifecycle()
    SyncTrashExportResultDialog(
        state = syncTrashExportState,
        onDismiss = { viewModel.resetSyncTrashExportState() }
    )

    Scaffold(
        modifier = Modifier.testTag(SettingsTestTags.ROOT),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.navigateUp() },
                        modifier = Modifier.testTag(SettingsTestTags.BACK)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.cd_settings_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // 主题
            // 分组标题用 extendedColors.primaryText 而不是 colorScheme.primary：primary 是「品牌
            // 色块该填什么」，当文字用时在 Claude 主题下只有 3.12:1，不到正文要求的 4.5:1。
            // primaryText 是从同一个 primary 现算出的达标版本，色相饱和度不偏。本屏五个分组
            // 标题、版本号、两处图标着色同理。
            Text(
                stringResource(R.string.theme),
                style = MaterialTheme.typography.labelLarge,
                color = extendedColors.primaryText,
                modifier = Modifier.padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Default)
            )
            // 先 resolve 一遍再比：DataStore 里可能躺着本机不支持的 "dynamic"（配置从新手机搬
            // 过来的），拿 settings.themeId 直接比会让所有单选圈一个都不选中，用户看到的是
            // 「我没选主题」。resolve 把它折叠成实际生效的默认主题，选中的就是默认那一行。
            val selectedThemeId = AppThemes.resolve(settings.themeId).id
            AppThemes.selectable().forEach { theme ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // selectable 而不是「Row 上 clickable + RadioButton 上 onClick」：后者在
                        // 无障碍树里是两个可激活节点（读屏会把同一个选项念两遍），而且行本身
                        // 不带单选语义、也不报「已选中」。selectable 把整行合成一个 RadioButton
                        // 角色的节点，RadioButton 就退化成纯视觉指示器（onClick = null）。
                        .selectable(
                            selected = selectedThemeId == theme.id,
                            onClick = { viewModel.updateThemeId(theme.id) },
                            role = Role.RadioButton
                        )
                        .padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Tight)
                        .testTag(SettingsTestTags.THEME_OPTION_PREFIX + theme.id),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedThemeId == theme.id,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.Tight))
                    // 主题色点预览。走 appColorScheme 而不是 theme.light.primary：动态取色的配色
                    // 编译期不存在，读 light 拿到的是兜底的默认主题灰蓝，那颗点永远不跟壁纸变。
                    // 固定取浅色那套（与改造前一致）：色点是「这个主题长什么样」的示意，
                    // 跟着当前深浅切换会让两行点在深色下糊成一片。
                    Box(
                        modifier = Modifier
                            .size(ThemeSwatchSize)
                            .clip(CircleShape)
                            .background(appColorScheme(theme, darkTheme = false).primary)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.Default))
                    Text(stringResource(theme.labelRes), style = MaterialTheme.typography.bodyLarge)
                }
            }

            // 深色模式
            Text(
                stringResource(R.string.settings_dark_mode),
                style = MaterialTheme.typography.labelLarge,
                color = extendedColors.primaryText,
                modifier = Modifier.padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Default)
            )
            Row(
                modifier = Modifier.padding(horizontal = AppSpacing.Screen),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Default)
            ) {
                // 标签在这里就取好而不是传资源 id 进循环：destructure 出来的 Int 丢了
                // @StringRes 标注，lint 的 ResourceType 检查看不出它是字符串资源。
                listOf(
                    "system" to stringResource(R.string.settings_dark_mode_system),
                    "light" to stringResource(R.string.settings_dark_mode_light),
                    "dark" to stringResource(R.string.settings_dark_mode_dark)
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = settings.darkMode == value,
                        onClick = { viewModel.updateDarkMode(value) },
                        label = { Text(label) },
                        modifier = Modifier.testTag(SettingsTestTags.DARK_MODE_CHIP_PREFIX + value)
                    )
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.Default))

            HorizontalDivider()

            // Font Size
            // 这里不挂 clickable：空 lambda 会在无障碍树里多出一个「按钮」节点，读屏念完却什么
            // 都不会发生。真正的控件是下面那个 Slider，它自己带语义。
            ListItem(
                headlineContent = { Text(stringResource(R.string.font_size)) },
                supportingContent = { Text("${settings.fontSize} sp") }
            )
            // 标签在 semantics 之外先取好：那个 lambda 不是 @Composable，里面调不了
            // stringResource；而读屏只报得出「滑块」，不把当前字号念进文案就无从知道调到了几号。
            val fontSizeLabel = stringResource(R.string.cd_font_size_slider, settings.fontSize)
            Slider(
                value = settings.fontSize.toFloat(),
                onValueChange = { viewModel.updateFontSize(it.toInt()) },
                valueRange = 12f..24f,
                steps = 11,
                modifier = Modifier
                    .padding(horizontal = AppSpacing.Screen)
                    .semantics { contentDescription = fontSizeLabel }
                    .testTag(SettingsTestTags.FONT_SIZE_SLIDER)
            )

            HorizontalDivider()

            // Auto Save
            ListItem(
                headlineContent = { Text(stringResource(R.string.auto_save)) },
                supportingContent = {
                    Text(
                        if (settings.autoSaveEnabled) {
                            // count 传两次：第一个选 quantity 分支，第二个才是 %1$d 的实参
                            pluralStringResource(
                                R.plurals.settings_auto_save_every_seconds,
                                settings.autoSaveInterval,
                                settings.autoSaveInterval
                            )
                        } else {
                            stringResource(R.string.settings_switch_off)
                        }
                    )
                },
                trailingContent = {
                    Switch(
                        checked = settings.autoSaveEnabled,
                        onCheckedChange = { viewModel.updateAutoSave(it, settings.autoSaveInterval) },
                        modifier = Modifier.testTag(SettingsTestTags.AUTO_SAVE_SWITCH)
                    )
                },
                // 合并整行语义：不合并时标题与副标题各成一个节点，读屏要走三站才拼得出
                // 「自动保存 / 每 30 秒 / 开关」，中途听到的开关不知道管什么。
                modifier = Modifier.semantics(mergeDescendants = true) {}
            )

            // 自动保存间隔。只在开关打开时露出来：关掉自动保存以后这几个档位调了也不生效，
            // 一直摆着只会让人以为「明明设了 5 秒怎么没存」。
            //
            // 用 AnimatedVisibility 而不是裸 if：硬切时这一整块凭空出现，用户读不出「是我刚才
            // 拨的那一下让它出来的」，下面的分隔线和图片压缩整段也会瞬移。展开配 200ms、收起
            // 配 150ms（出场比入场快，见 AppMotion）。内层要套一个 Column：
            // AnimatedVisibility 的 content 是 AnimatedVisibilityScope 而不是 ColumnScope，
            // 三个子项直接摞进去会重叠。
            AnimatedVisibility(
                visible = settings.autoSaveEnabled,
                enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
                exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.auto_save_interval),
                        style = MaterialTheme.typography.labelLarge,
                        color = extendedColors.primaryText,
                        modifier = Modifier.padding(
                            horizontal = AppSpacing.Screen,
                            vertical = AppSpacing.Default
                        )
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = AppSpacing.Screen),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Default)
                    ) {
                        // 档位全部落在 5..3600 之内（ConfigBackupCodec 的 AUTO_SAVE_INTERVAL 校验
                        // 区间），导出配置再导进来数值一个不变。做成离散档而不是滑块的理由见
                        // strings.xml 里 settings_seconds_short 的注释。
                        listOf(5, 15, 30, 60, 300).forEach { sec ->
                            FilterChip(
                                selected = settings.autoSaveInterval == sec,
                                onClick = { viewModel.updateAutoSave(true, sec) },
                                label = { Text(stringResource(R.string.settings_seconds_short, sec)) },
                                modifier = Modifier.testTag(
                                    SettingsTestTags.AUTO_SAVE_INTERVAL_CHIP_PREFIX + sec
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.Default))
                }
            }
            HorizontalDivider()

            // 图片压缩。这三项一直躺在 UserSettings 里，也一直被 ImageRepositoryImpl 真的读着
            // （autoCompressImages 决定要不要缩，imageCompressionQuality 是 Bitmap.compress 的
            // quality 实参，maxImageWidth 是缩到多宽），配置备份也把它们导出导入 —— 唯独设置页
            // 从来没给过入口：updateCompression 的调用点数量一直是 0，值永远停在默认的
            // 「开 / 中(80) / 1920」。这一段就是那个缺掉的入口。
            Text(
                stringResource(R.string.image_compression),
                style = MaterialTheme.typography.labelLarge,
                color = extendedColors.primaryText,
                modifier = Modifier.padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Default)
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.auto_compress_images)) },
                supportingContent = {
                    // 开着时报当前生效的两个值而不是一句「开启」：这一行是插图前唯一能看出
                    // 「照片会被压成什么样」的地方。关掉时下面两个控件都会隐藏，只剩这句「关闭」。
                    if (settings.autoCompressImages) {
                        Text(
                            "${settings.imageCompressionQuality.localizedLabel()} · " +
                                "${settings.maxImageWidth} px"
                        )
                    } else {
                        Text(stringResource(R.string.settings_switch_off))
                    }
                },
                trailingContent = {
                    Switch(
                        checked = settings.autoCompressImages,
                        onCheckedChange = {
                            viewModel.updateCompression(
                                it,
                                settings.imageCompressionQuality,
                                settings.maxImageWidth
                            )
                        },
                        modifier = Modifier.testTag(SettingsTestTags.AUTO_COMPRESS_SWITCH)
                    )
                },
                modifier = Modifier.semantics(mergeDescendants = true) {}
            )
            // 同上：这两项在自动压缩关掉后调了也不生效，展开 / 收起走 AppMotion 的两档时长。
            AnimatedVisibility(
                visible = settings.autoCompressImages,
                enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
                exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.compression_quality),
                        style = MaterialTheme.typography.labelLarge,
                        color = extendedColors.primaryText,
                        modifier = Modifier.padding(
                            horizontal = AppSpacing.Screen,
                            vertical = AppSpacing.Default
                        )
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = AppSpacing.Screen),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Default)
                    ) {
                        CompressionQuality.entries.forEach { q ->
                            FilterChip(
                                selected = settings.imageCompressionQuality == q,
                                onClick = {
                                    viewModel.updateCompression(true, q, settings.maxImageWidth)
                                },
                                label = { Text(q.localizedLabel()) },
                                modifier = Modifier.testTag(
                                    SettingsTestTags.COMPRESSION_QUALITY_CHIP_PREFIX + q.name
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.Default))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.max_image_width)) },
                        supportingContent = { Text("${settings.maxImageWidth} px") }
                    )
                    // 同字号滑块：semantics 的 lambda 不是 @Composable，文案得先在外面取好，
                    // 否则读屏只念得出「滑块」，不知道管什么、也不知道停在哪一档。
                    val maxWidthLabel =
                        stringResource(R.string.cd_max_image_width_slider, settings.maxImageWidth)
                    Slider(
                        // 640..3840 步长 160（steps = 19）：区间与步长都落在 ConfigBackupCodec 的
                        // MAX_IMAGE_WIDTH = 320..8192 之内，导出再导入不会被校验改值。不把滑块开到
                        // 8192：那是校验上限而不是「合理值」，何况 ImageRepositoryImpl 只按这个宽度
                        // 下采样，给一个比原图还宽的值等于不压。
                        //
                        // coerceIn 兜住导入进来的越界值（比如别人的备份里写着 8192）：Slider 自己会
                        // 夹，但显式写出来才对得上下面那句「拖一下就会把值改成 3840」的实际行为。
                        value = settings.maxImageWidth.toFloat().coerceIn(640f, 3840f),
                        onValueChange = {
                            viewModel.updateCompression(
                                true,
                                settings.imageCompressionQuality,
                                it.toInt()
                            )
                        },
                        valueRange = 640f..3840f,
                        steps = 19,
                        modifier = Modifier
                            .padding(horizontal = AppSpacing.Screen)
                            .semantics { contentDescription = maxWidthLabel }
                            .testTag(SettingsTestTags.MAX_IMAGE_WIDTH_SLIDER)
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.Default))
                }
            }

            HorizontalDivider()

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_default_preview)) },
                supportingContent = {
                    Text(
                        if (settings.defaultPreviewMode) stringResource(R.string.settings_default_preview_on)
                        else stringResource(R.string.settings_switch_off)
                    )
                },
                trailingContent = {
                    Switch(
                        checked = settings.defaultPreviewMode,
                        onCheckedChange = { viewModel.updateDefaultPreviewMode(it) },
                        modifier = Modifier.testTag(SettingsTestTags.DEFAULT_PREVIEW_SWITCH)
                    )
                },
                // 同上。合并只加在带开关的行上：其余行的尾部是按钮或空的，没有「孤立控件」问题，
                // 无谓合并只会把它们的可点区域与朗读顺序也一起改掉。
                modifier = Modifier.semantics(mergeDescendants = true) {}
            )

            HorizontalDivider()

            // 默认目录：选择后启动 App 自动加载该目录文件树到侧栏
            ListItem(
                headlineContent = { Text(stringResource(R.string.default_dir)) },
                supportingContent = {
                    Text(defaultDirName ?: stringResource(R.string.default_dir_unset))
                },
                trailingContent = {
                    Row {
                        TextButton(
                            onClick = { folderPicker.launch(SafLocations.storageRootHint()) },
                            modifier = Modifier.testTag(SettingsTestTags.DEFAULT_DIR_CHOOSE)
                        ) {
                            Text(stringResource(R.string.default_dir_choose))
                        }
                        if (defaultDirName != null) {
                            TextButton(
                                onClick = { viewModel.clearDefaultDir() },
                                modifier = Modifier.testTag(SettingsTestTags.DEFAULT_DIR_CLEAR)
                            ) {
                                Text(stringResource(R.string.default_dir_clear))
                            }
                        }
                    }
                }
            )

            HorizontalDivider()

            // AI 助手
            ListItem(
                headlineContent = { Text(stringResource(R.string.editor_ai_assistant)) },
                supportingContent = {
                    Text(
                        if (aiConfig.enabled) {
                            stringResource(R.string.settings_ai_enabled, aiConfig.provider.name)
                        } else {
                            stringResource(R.string.settings_ai_disabled)
                        }
                    )
                },
                modifier = Modifier
                    .clickable { navController.navigate(Screen.AiConfig.route) }
                    .testTag(SettingsTestTags.AI_ROW)
            )

            HorizontalDivider()

            // 云端同步
            ListItem(
                headlineContent = { Text(stringResource(R.string.sync_title)) },
                supportingContent = { Text(stringResource(R.string.settings_sync_desc)) },
                modifier = Modifier
                    .clickable { navController.navigate(Screen.Sync.route) }
                    .testTag(SettingsTestTags.SYNC_ROW)
            )

            HorizontalDivider()

            // 配置备份：把设置项本身导出/导入（文档正文不在其中）
            ListItem(
                headlineContent = { Text(stringResource(R.string.config_backup)) },
                supportingContent = { Text(stringResource(R.string.config_backup_desc)) },
                trailingContent = {
                    Row {
                        TextButton(
                            onClick = { askExportSecrets = true },
                            modifier = Modifier.testTag(SettingsTestTags.CONFIG_EXPORT)
                        ) {
                            Text(stringResource(R.string.config_backup_export))
                        }
                        TextButton(
                            onClick = { configImporter.launch(CONFIG_IMPORT_MIME_TYPES) },
                            modifier = Modifier.testTag(SettingsTestTags.CONFIG_IMPORT)
                        ) {
                            Text(stringResource(R.string.config_backup_import))
                        }
                    }
                }
            )

            HorizontalDivider()

            // 崩溃日志：点行看最近一条，右侧导出/清除。没有记录时只留说明，不给按钮
            ListItem(
                headlineContent = { Text(stringResource(R.string.crash_logs)) },
                supportingContent = {
                    Text(
                        if (crashLogCount == 0) {
                            stringResource(R.string.crash_logs_empty)
                        } else {
                            // count 传两次：第一个选 quantity 分支，第二个才是 %1$d 的实参
                            pluralStringResource(
                                R.plurals.crash_logs_count,
                                crashLogCount,
                                crashLogCount
                            )
                        }
                    )
                    Text(
                        stringResource(R.string.crash_logs_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingContent = {
                    if (crashLogCount > 0) {
                        Row {
                            TextButton(
                                onClick = {
                                    crashLogExporter.launch(viewModel.suggestedCrashLogFileName())
                                },
                                modifier = Modifier.testTag(SettingsTestTags.CRASH_LOGS_EXPORT)
                            ) {
                                Text(stringResource(R.string.crash_logs_export))
                            }
                            TextButton(
                                onClick = { askClearCrashLogs = true },
                                modifier = Modifier.testTag(SettingsTestTags.CRASH_LOGS_CLEAR)
                            ) {
                                Text(stringResource(R.string.crash_logs_clear))
                            }
                        }
                    }
                },
                // 没有记录时整行不可点，所以 testTag 挂在条件之外：
                // 标签本身与可点击性无关，测试要能定位到这一行才好断言「按钮不存在」。
                // 括号是必须的——少了它 .testTag 只会接在 else 分支上。
                modifier = (
                    if (crashLogCount > 0) {
                        Modifier.clickable { viewModel.openLatestCrashLog() }
                    } else {
                        Modifier
                    }
                    ).testTag(SettingsTestTags.CRASH_LOGS_ROW)
            )

            HorizontalDivider()

            // 同步删除的正文备份：远端删除传播到本机前，正文先落一份救援副本（SyncTrashStore）。
            // 与崩溃日志行同款结构：点行查看、右侧导出/清除、空时只留说明。
            ListItem(
                headlineContent = { Text(stringResource(R.string.sync_trash)) },
                supportingContent = {
                    Text(
                        if (syncTrashCount == 0) {
                            stringResource(R.string.sync_trash_empty)
                        } else {
                            pluralStringResource(
                                R.plurals.sync_trash_count,
                                syncTrashCount,
                                syncTrashCount
                            )
                        }
                    )
                    Text(
                        stringResource(R.string.sync_trash_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingContent = {
                    if (syncTrashCount > 0) {
                        Row {
                            TextButton(
                                onClick = { syncTrashExporter.launch(viewModel.suggestedSyncTrashFileName()) },
                                modifier = Modifier.testTag(SettingsTestTags.SYNC_TRASH_EXPORT)
                            ) {
                                Text(stringResource(R.string.sync_trash_export))
                            }
                            TextButton(
                                onClick = { askClearSyncTrash = true },
                                modifier = Modifier.testTag(SettingsTestTags.SYNC_TRASH_CLEAR)
                            ) {
                                Text(stringResource(R.string.sync_trash_clear))
                            }
                        }
                    }
                },
                // 与崩溃日志行同款：标签挂在条件之外，空列表时测试仍能定位这一行
                modifier = (
                    if (syncTrashCount > 0) {
                        Modifier.clickable { viewModel.openSyncTrash() }
                    } else {
                        Modifier
                    }
                    ).testTag(SettingsTestTags.SYNC_TRASH_ROW)
            )

            HorizontalDivider()

            // 重置设置：`resetToDefaults()` 与这两条文案早就写好了，却一直没有入口 ——
            // 用户改坏了设置只能清应用数据（连文档索引一起没了）。二次确认沿用清崩溃日志那套。
            ListItem(
                headlineContent = { Text(stringResource(R.string.reset_settings)) },
                supportingContent = { Text(stringResource(R.string.reset_settings_confirm)) },
                trailingContent = {
                    TextButton(
                        onClick = { askResetSettings = true },
                        modifier = Modifier.testTag(SettingsTestTags.RESET_BUTTON),
                        // 危险操作用 error 色，和上面几行普通设置区分开
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text(stringResource(R.string.reset_settings)) }
                },
                modifier = Modifier.testTag(SettingsTestTags.RESET_ROW)
            )

            HorizontalDivider()

            // 检查更新
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_check_update)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_current_version, BuildConfig.VERSION_NAME))
                },
                trailingContent = {
                    when (updateState) {
                        is UpdateCheckState.Checking -> {
                            CircularProgressIndicator(modifier = Modifier.size(AppIconSize.Large))
                        }
                        is UpdateCheckState.NoUpdate -> {
                            Text(
                                stringResource(R.string.settings_update_none),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        is UpdateCheckState.Error -> {
                            Text(
                                (updateState as UpdateCheckState.Error).message.resolve(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {
                            IconButton(
                                onClick = { viewModel.checkUpdate() },
                                modifier = Modifier.testTag(SettingsTestTags.CHECK_UPDATE_BUTTON)
                            ) {
                                // 图标与整行是同一个动作，contentDescription 复用行标题的键，
                                // 另建一个 cd_ 键只会让两处文案各自漂移
                                Icon(
                                    Icons.Default.SystemUpdate,
                                    stringResource(R.string.settings_check_update)
                                )
                            }
                        }
                    }
                },
                modifier = Modifier
                    .clickable { viewModel.checkUpdate() }
                    .testTag(SettingsTestTags.CHECK_UPDATE_ROW)
            )

            HorizontalDivider()

            // About
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_about)) },
                supportingContent = { Text(stringResource(R.string.settings_about_desc)) }
            )
        }
    }
}

@Composable
private fun ListItem(
    headlineContent: @Composable () -> Unit,
    supportingContent: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Cozy),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            headlineContent()
            supportingContent()
        }
        trailingContent()
    }
}

/** 配置导出/导入的进度与结果回显。[ConfigTransferState.Idle] 时不渲染任何东西。 */
@Composable
private fun ConfigTransferDialog(
    state: ConfigTransferState,
    onDismiss: () -> Unit
) {
    when (state) {
        is ConfigTransferState.Idle -> Unit

        is ConfigTransferState.Running -> AlertDialog(
            // 不给关闭途径：SAF 读写已在进行，中途撤掉 UI 只会让用户以为失败了
            onDismissRequest = {},
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Screen)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(AppIconSize.Large))
                    Text(stringResource(R.string.config_running))
                }
            },
            confirmButton = {}
        )

        is ConfigTransferState.Exported -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.config_export_done_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Default)) {
                    Text(stringResource(R.string.config_export_done_message))
                    if (state.includedSecrets) {
                        Text(
                            stringResource(R.string.config_export_done_secrets),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(SettingsTestTags.CONFIG_RESULT_CLOSE)
                ) { Text(stringResource(R.string.config_dialog_close)) }
            }
        )

        is ConfigTransferState.Imported -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.config_import_done_title)) },
            text = { ImportSummaryBody(state.summary) },
            confirmButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(SettingsTestTags.CONFIG_RESULT_CLOSE)
                ) { Text(stringResource(R.string.config_dialog_close)) }
            }
        )

        is ConfigTransferState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.config_failed_title)) },
            text = { Text(state.message.resolve()) },
            confirmButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(SettingsTestTags.CONFIG_RESULT_CLOSE)
                ) { Text(stringResource(R.string.config_dialog_close)) }
            }
        )
    }
}

/** 崩溃日志导出结果回显。[CrashExportState.Idle] 时不渲染任何东西。 */
@Composable
private fun CrashExportResultDialog(
    state: CrashExportState,
    onDismiss: () -> Unit
) {
    ExportResultDialog(
        state = state,
        onDismiss = onDismiss,
        doneMessage = { c ->
            pluralStringResource(R.plurals.crash_export_done_message, c, c)
        }
    )
}

/** 正文备份导出结果回显。与崩溃日志共用骨架，只换完成分支的 plurals 文案。 */
@Composable
private fun SyncTrashExportResultDialog(
    state: CrashExportState,
    onDismiss: () -> Unit
) {
    ExportResultDialog(
        state = state,
        onDismiss = onDismiss,
        doneMessage = { c ->
            pluralStringResource(R.plurals.sync_trash_export_done_message, c, c)
        }
    )
}

/** 两个导出入口共用的结果骨架：完成分支的正文由调用方按各自的 plurals 资源给。 */
@Composable
private fun ExportResultDialog(
    state: CrashExportState,
    onDismiss: () -> Unit,
    doneMessage: @Composable (Int) -> String
) {
    when (state) {
        is CrashExportState.Idle -> Unit

        is CrashExportState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.crash_export_done_title)) },
            text = { Text(doneMessage(state.count)) },
            confirmButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(SettingsTestTags.CRASH_RESULT_CLOSE)
                ) { Text(stringResource(R.string.config_dialog_close)) }
            }
        )

        is CrashExportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.crash_export_failed_title)) },
            text = { Text(state.message.resolve()) },
            confirmButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(SettingsTestTags.CRASH_RESULT_CLOSE)
                ) { Text(stringResource(R.string.config_dialog_close)) }
            }
        )
    }
}

@Composable
private fun ImportSummaryBody(summary: ConfigImportSummary) {
    // 三个标签无条件取出：stringResource 放在 if 里会让「应用了哪几段」的文案随重组条件漂移
    val settingsLabel = stringResource(R.string.config_section_settings)
    val aiLabel = stringResource(R.string.config_section_ai)
    val syncLabel = stringResource(R.string.config_section_sync)
    // 连接符也得跟着语区变：中文用顿号，英文要逗号加空格，写死在这里等于漏翻一处
    val separator = stringResource(R.string.settings_list_separator)
    val applied = listOfNotNull(
        settingsLabel.takeIf { summary.settingsApplied },
        aiLabel.takeIf { summary.aiApplied },
        syncLabel.takeIf { summary.syncApplied }
    )

    Column(
        modifier = Modifier
            .heightIn(max = ImportSummaryMaxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Default)
    ) {
        Text(
            if (applied.isEmpty()) stringResource(R.string.config_import_nothing)
            else stringResource(R.string.config_import_applied, applied.joinToString(separator))
        )
        Text(
            if (summary.secretsApplied) stringResource(R.string.config_import_secrets_applied)
            else stringResource(R.string.config_import_secrets_kept),
            style = MaterialTheme.typography.bodySmall
        )
        if (summary.warnings.isNotEmpty()) {
            Text(
                stringResource(R.string.config_import_warnings_title),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            summary.warnings.forEach { warning ->
                Text(
                    "· $warning",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 更新对话框（公开，可在其他界面复用）
 */
@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit,
    onUpdate: (UpdateInfo) -> Unit
) {
    var showFullChangelog by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Screen),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Group)
            ) {
                // 标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // contentDescription = null 是有意的：紧邻的标题「发现新版本」已经把这个
                    // 图标要传达的信息说完了，再给它一个标签只会让读屏把同一件事念两遍。
                    Icon(
                        Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = extendedColors.primaryText,
                        modifier = Modifier.size(AppIconSize.Avatar)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.Cozy))
                    Column {
                        Text(
                            stringResource(R.string.settings_update_available_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "v${updateInfo.version}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = extendedColors.primaryText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.Screen))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(AppSpacing.Screen))

                // 文件信息
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.settings_update_file_size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatFileSize(updateInfo.fileSize),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.Default))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.settings_update_publish_date),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatDate(updateInfo.publishDate),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.Screen))

                // 更新日志
                Text(
                    stringResource(R.string.settings_update_changelog),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(AppSpacing.Default))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 「全部显示 / 收起」换的是高度上限。硬切时这张卡瞬间长高 180dp、下面两个
                        // 按钮跟着跳一下，用户看不出是自己点的那一下造成的。animateContentSize
                        // 放在 heightIn 之前：它测量的是「已经被 heightIn 夹过的孩子」，上限一变
                        // 就有新尺寸可动。
                        .animateContentSize(AppMotion.enter())
                        .heightIn(
                            max = if (showFullChangelog) ChangelogExpandedHeight
                            else ChangelogCollapsedHeight
                        )
                ) {
                    ChangelogMarkdownView(
                        markdown = if (showFullChangelog) updateInfo.changelog else updateInfo.changelog.take(200),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (updateInfo.changelog.length > 200) {
                    TextButton(
                        onClick = { showFullChangelog = !showFullChangelog },
                        modifier = Modifier.testTag(SettingsTestTags.UPDATE_CHANGELOG_TOGGLE)
                    ) {
                        Text(
                            if (showFullChangelog) stringResource(R.string.settings_update_collapse)
                            else stringResource(R.string.settings_update_show_all)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.Screen))

                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag(SettingsTestTags.UPDATE_LATER)
                    ) {
                        Text(stringResource(R.string.settings_update_later))
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.Default))
                    Button(
                        onClick = { onUpdate(updateInfo) },
                        modifier = Modifier.testTag(SettingsTestTags.UPDATE_NOW)
                    ) {
                        Text(stringResource(R.string.settings_update_now))
                    }
                }
            }
        }
    }
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        // 显式传 Locale.US：不传时用系统默认区域，阿拉伯语/波斯语环境下 %.2f 会输出
        // 阿拉伯-印度数字（٣٫١٤），而这里的 "MB" 是 ASCII，混排出来既难读也不是本意。
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
    }
}

/**
 * 格式化日期
 */
private fun formatDate(isoDate: String): String {
    return try {
        // 简单提取日期部分 "2024-01-15T10:30:00Z" -> "2024-01-15"
        isoDate.substringBefore('T')
    } catch (e: Exception) {
        isoDate
    }
}

/**
 * 下载对话框（公开，可在其他界面复用）
 */
@Composable
fun DownloadDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit,
    context: Context
) {
    val downloader = remember { ApkDownloader(context) }

    // 只启动一次下载。摘要两路一起递进去：sha256 是 asset 自带的 digest（有就直接比），
    // sha256Url 是 *.apk.sha256 旁 asset 的地址（digest 缺失时才会真去请求）。
    // 两者都为 null 时下载链路只做包名/签名校验，见 ApkDownloader.download。
    val downloadFlow = remember(updateInfo.downloadUrl) {
        downloader.download(
            url = updateInfo.downloadUrl,
            version = updateInfo.version,
            expectedSha256 = updateInfo.sha256,
            sidecarUrl = updateInfo.sha256Url
        )
    }

    val downloadState by downloadFlow.collectAsState(initial = DownloadState.Idle)

    /**
     * 「已经拉起过安装器」的一次性闩。刻意**不是** `mutableStateOf`。
     *
     * 从前是 `var installTriggered by remember { mutableStateOf(false) }`，而且被放进了下面
     * 那段 effect 的 key 里。于是这段 effect 一进来就先改自己的 key：写 State → 触发重组 →
     * Compose 认定 key 变了 → **把正在运行的这段协程取消**、带着新 key 重启一次；
     * 重启后 `installTriggered` 已经是 true，块体直接跳过，什么都不做。
     *
     * 取消落在哪里是确定的：`installApk` 的第一个挂起点就是
     * `withContext(Dispatchers.IO) { checkInstallable(file) }`（ApkDownloader.kt:401），
     * 它把 CancellationException 抛出来，`installApk` 的 catch 又原样重抛
     * （ApkDownloader.kt:422-425），于是 `startActivity`（同文件 :419）永远执行不到——
     * 应用内更新从来没有真正拉起过系统安装器。而且连一句错都看不到：`installError`
     * 也在同一段协程里赋值，一起被取消掉，对话框就永远停在「下载完成 / 正在安装…」。
     *
     * 换成 AtomicBoolean 一并解决两件事：写它不进快照系统、不触发重组，也不能当 key；
     * compareAndSet 仍然保证「只拉起一次」，重组多少次都一样。
     */
    val installLatch = remember { AtomicBoolean(false) }

    /**
     * 安装被拒的原因。下载成功之后才可能出现，所以不能塞回 [DownloadState]。
     *
     * 旧实现直接丢弃 `installApk` 的返回值：校验被拒时安装器根本没拉起，对话框却照旧
     * 静静关掉，用户以为在装、其实什么都没发生，连一句原因都看不到。
     */
    var installError by remember { mutableStateOf<UiMessage?>(null) }

    // key 只取「成功之后的那个文件路径」，不是整个 downloadState：Downloading(progress)
    // 每涨一个百分点就是一个新值，拿它当 key 等于下载全程反复取消重启这段 effect
    // （那时块体确实什么都不做，但仍是白开销）。而 Success 是 callbackFlow 的终态
    // （download 里 trySend(Success) 紧跟 return@launch，finally 里 close()），
    // 所以这个 key 只会从 null 变成路径一次。
    val successPath = (downloadState as? DownloadState.Success)?.filePath
    LaunchedEffect(successPath) {
        val path = successPath ?: return@LaunchedEffect
        if (!installLatch.compareAndSet(false, true)) return@LaunchedEffect
        android.util.Log.d("SettingsScreen", "下载成功，准备安装: $path")
        // installApk 是 suspend：文件检查与安装包解析在它内部走 Dispatchers.IO，
        // 这里本来就是协程，直接挂起等它即可（旧的同步版本在主线程解析几十 MB 的包）。
        val error = downloader.installApk(path)
        if (error != null) {
            // 停在对话框里把原因说出来，不自动关闭：这一步失败往往要用户自己再来一次
            installError = error
            return@LaunchedEffect
        }
        // 留一点时间等安装界面弹出后再关闭对话框
        kotlinx.coroutines.delay(1500)
        onDismiss()
    }

    Dialog(onDismissRequest = { if (downloadState !is DownloadState.Downloading) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Screen),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Group)
                    // 四个阶段的高度差很大（一行「准备中」→ 进度条 → 48dp 图标加两行字），
                    // 硬切时整个对话框跳一下。这里只动尺寸、不做内容交叉淡入是有意的：
                    // Crossfade 会让淡出中的旧分支继续读当前 downloadState，
                    // `as DownloadState.Downloading` 在状态已经走到 Success 时直接抛
                    // ClassCastException。
                    .animateContentSize(AppMotion.enter()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 安装被拒与下载失败共用同一套失败呈现，只有标题不同。
                // installError 优先：它发生在下载已经成功之后。
                val failure = installError ?: (downloadState as? DownloadState.Failed)?.error
                when {
                    failure != null -> {
                        Text(
                            stringResource(
                                if (installError != null) R.string.settings_install_failed
                                else R.string.settings_download_failed
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Default))
                        Text(
                            failure.resolve(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Screen))
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.testTag(SettingsTestTags.DOWNLOAD_CLOSE)
                        ) {
                            Text(stringResource(R.string.close))
                        }
                    }
                    downloadState is DownloadState.Downloading -> {
                        val progress = (downloadState as DownloadState.Downloading).progress
                        Text(
                            stringResource(R.string.settings_download_running),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Screen))
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(DownloadBarHeight)
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Default))
                        Text(
                            "$progress%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    downloadState is DownloadState.Success -> {
                        // contentDescription = null 是有意的：紧接着的「下载完成」把这个图标要说
                        // 的话说完了，给它加标签只会让读屏念两遍同一件事。
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = extendedColors.primaryText,
                            modifier = Modifier.size(AppIconSize.Hero)
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Screen))
                        Text(
                            stringResource(R.string.settings_download_done),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Default))
                        Text(
                            stringResource(R.string.settings_download_installing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        // Idle。Failed 到不了这里——上面的 failure 分支已经把它接住了。
                        Text(
                            stringResource(R.string.settings_download_preparing),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Screen))
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

/**
 * 更新日志 Markdown 渲染视图
 */
@Composable
private fun ChangelogMarkdownView(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 颜色一律现取再转 CSS，不写死十六进制。从前 code / pre 的底色与前景是四个硬编码值
    // （#2E2E2C / #F0F0F0 / #E0DED6 / #333333）按 isDarkMode 二选一：只认「深还是浅」，
    // 三套主题共用同一对灰，Claude 主题下这块代码底色和周围的暖色调对不上。
    //
    // 用 toCssHex() 而不是 "#%02X%02X%02X".format(…)：java.util.Formatter 的整数转换按默认
    // Locale 挑数字字形，阿拉伯语等语区下会吐出非 ASCII 数字，那串东西 CSS 不认。
    val bgColorHex = MaterialTheme.colorScheme.surface.toCssHex()
    val textColorHex = MaterialTheme.colorScheme.onSurface.toCssHex()
    // surfaceContainerHighest 是 M3 里「嵌在页面里的代码块」该用的那一档容器色，配
    // onSurfaceVariant 正是 Round 2 求解到 4.50–4.52:1 的那一对，深浅三套主题都达标。
    val codeBgHex = MaterialTheme.colorScheme.surfaceContainerHighest.toCssHex()
    val codeFgHex = MaterialTheme.colorScheme.onSurfaceVariant.toCssHex()

    // Base64 编码 Markdown 内容
    val markdownBase64 = android.util.Base64.encodeToString(
        markdown.toByteArray(Charsets.UTF_8),
        android.util.Base64.NO_WRAP
    )

    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <!--
              更新日志正文来自 GitHub Release body（远端不可信输入）。
              CSP 无 'unsafe-inline'/'unsafe-eval'，且 connect-src 'none' 断掉外传通道；
              渲染逻辑必须留在 raw/static-md.js。style-src 需 'unsafe-inline'（下方内联主题色）。
            -->
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src file:; style-src file: 'unsafe-inline'; img-src file: blob: data: https: http:; font-src file: data:; connect-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'">
            <meta name="referrer" content="no-referrer">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <!-- purify → ym-sanitize → marked 顺序固定：净化器必须先于渲染逻辑就绪 -->
            <script src="file:///android_asset/raw/purify.js"></script>
            <script src="file:///android_asset/raw/ym-sanitize.js"></script>
            <script src="file:///android_asset/raw/markedjs.js"></script>
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                    font-size: 14px;
                    line-height: 1.6;
                    color: $textColorHex;
                    background: $bgColorHex;
                    padding: 8px;
                }
                h1, h2, h3, h4 { margin: 0.5em 0 0.3em 0; font-weight: 600; }
                h1 { font-size: 1.3em; }
                h2 { font-size: 1.2em; }
                h3 { font-size: 1.1em; }
                h4 { font-size: 1.05em; }
                p { margin: 0.5em 0; }
                ul, ol { margin: 0.3em 0; padding-left: 1.5em; }
                li { margin: 0.2em 0; }
                strong { font-weight: 600; }
                code {
                    font-family: 'Courier New', monospace;
                    background: $codeBgHex;
                    color: $codeFgHex;
                    padding: 1px 4px;
                    border-radius: 3px;
                    font-size: 0.9em;
                }
                pre {
                    background: $codeBgHex;
                    padding: 8px;
                    border-radius: 4px;
                    overflow-x: auto;
                    margin: 0.5em 0;
                }
                pre code { background: transparent; padding: 0; }
            </style>
        </head>
        <body>
            <!-- 内容以 base64 走 data 属性传入，由 raw/static-md.js 解码 → 净化 → 渲染 -->
            <div id="content" data-ym-md="$markdownBase64"></div>
            <script src="file:///android_asset/raw/static-md.js"></script>
        </body>
        </html>
    """.trimIndent()

    // 实例代号：渲染进程消失后这个 WebView 永久不可用，自增此值让 key() 重建整块 AndroidView。
    // 更新日志是纯静态内容，重建零代价，不需要用户点重试。
    var webViewGeneration by remember { mutableIntStateOf(0) }

    key(webViewGeneration) {
    AndroidView(
        factory = {
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                settings.javaScriptEnabled = true
                // 只渲染 android_asset 下的模板与本地库。allowFileAccess=false 仍允许
                // file:///android_asset 与 file:///android_res，故模板与 raw/*.js 不受影响，
                // 但远端更新日志无法再通过 file:// 读取应用私有目录。
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                // 无 WebViewClient 时更新日志里的链接会就地导航。http/https 交系统浏览器，其余拦下。
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = handleChangelogUrl(context, request?.url?.toString())

                    @Deprecated("兼容 API < 24")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                        handleChangelogUrl(context, url)

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?
                    ): Boolean {
                        // 返回 false（默认）= 框架杀掉整个应用进程。一次"看更新日志"绝不能变成闪退。
                        webViewGeneration++
                        return true
                    }
                }

                loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
            }
        },
        onRelease = { view ->
            // 原来没有 onRelease，这个 WebView 从进对话框起就常驻内存直到进程退出。
            // 键重建时也依赖它销毁旧实例，否则每次渲染进程回收都会漏一个 WebView。
            view.destroy()
        },
        modifier = modifier
    )
    }
}

/**
 * 更新日志内链接的统一出口。
 *
 * 返回 true 表示「本 WebView 不加载它」。仅 http/https 交给系统处理，
 * 其余（intent:、file:、content:、javascript: 等）直接丢弃——正文来自远端，不可信。
 */
private fun handleChangelogUrl(context: Context, url: String?): Boolean {
    val target = url?.trim().orEmpty()
    if (target.isEmpty()) return true
    val scheme = target.toUri().scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return true
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, target.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: android.content.ActivityNotFoundException) {
        android.util.Log.d("SettingsScreen", "no handler for link: ${e.message}")
    }
    return true
}
