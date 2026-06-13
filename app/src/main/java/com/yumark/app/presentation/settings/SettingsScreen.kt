package com.yumark.app.presentation.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.yumark.app.BuildConfig
import com.yumark.app.R
import com.yumark.app.core.update.ApkDownloader
import com.yumark.app.core.update.DownloadState
import com.yumark.app.core.util.SafLocations
import com.yumark.app.data.remote.UpdateChecker
import com.yumark.app.domain.model.CompressionQuality
import com.yumark.app.domain.model.UpdateInfo
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.repository.SettingsRepository
import com.yumark.app.domain.repository.WorkspaceRepository
import com.yumark.app.presentation.common.FolderConfirmDialog
import com.yumark.app.presentation.theme.AppThemes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val updateChecker: UpdateChecker
) : ViewModel() {
    private val _settings = MutableStateFlow(UserSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    /** 默认目录显示名，null 表示未设置 */
    private val _defaultDirName = MutableStateFlow<String?>(null)
    val defaultDirName: StateFlow<String?> = _defaultDirName.asStateFlow()

    /** 更新检查状态 */
    private val _updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateState: StateFlow<UpdateCheckState> = _updateState.asStateFlow()

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
        viewModelScope.launch { workspaceRepository.setDefaultDir(treeUri) }
    }

    fun clearDefaultDir() {
        viewModelScope.launch { workspaceRepository.clearDefaultDir() }
    }

    fun updateFontSize(s: Int) {
        viewModelScope.launch { repo.updateFontSize(s) }
    }

    fun updateAutoSave(on: Boolean, interval: Int) {
        viewModelScope.launch { repo.updateAutoSave(on, interval) }
    }

    fun updateCompression(autoCompress: Boolean, quality: CompressionQuality, maxWidth: Int) {
        viewModelScope.launch {
            repo.updateCompressionSettings(autoCompress, quality, maxWidth)
        }
    }

    fun updateDefaultPreviewMode(on: Boolean) {
        viewModelScope.launch {
            repo.updateSettings(settings.value.copy(defaultPreviewMode = on))
        }
    }

    fun updateThemeId(id: String) {
        viewModelScope.launch {
            repo.updateSettings(settings.value.copy(themeId = id))
        }
    }

    fun updateDarkMode(mode: String) {
        viewModelScope.launch {
            repo.updateSettings(settings.value.copy(darkMode = mode))
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repo.resetToDefaults() }
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
                _updateState.value = UpdateCheckState.Error(e.message ?: "检查更新失败")
            }
        }
    }

    /** 重置更新状态 */
    fun resetUpdateState() {
        _updateState.value = UpdateCheckState.Idle
    }
}

/** 更新检查状态 */
sealed class UpdateCheckState {
    object Idle : UpdateCheckState()
    object Checking : UpdateCheckState()
    data class Available(val updateInfo: UpdateInfo) : UpdateCheckState()
    object NoUpdate : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val defaultDirName by viewModel.defaultDirName.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
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
            Text(
                "主题",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            AppThemes.all.forEach { theme ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.updateThemeId(theme.id) }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.themeId == theme.id,
                        onClick = { viewModel.updateThemeId(theme.id) }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    // 主题色点预览
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(theme.light.primary)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(theme.label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            // 深色模式
            Text(
                "深色模式",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                    FilterChip(
                        selected = settings.darkMode == value,
                        onClick = { viewModel.updateDarkMode(value) },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            HorizontalDivider()

            // Font Size
            ListItem(
                headlineContent = { Text("字体大小") },
                supportingContent = { Text("${settings.fontSize} sp") },
                modifier = Modifier.clickable { }
            )
            Slider(
                value = settings.fontSize.toFloat(),
                onValueChange = { viewModel.updateFontSize(it.toInt()) },
                valueRange = 12f..24f,
                steps = 11,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            HorizontalDivider()

            // Auto Save
            ListItem(
                headlineContent = { Text("自动保存") },
                supportingContent = { Text(if (settings.autoSaveEnabled) "每 ${settings.autoSaveInterval} 秒" else "关闭") },
                trailingContent = {
                    Switch(
                        checked = settings.autoSaveEnabled,
                        onCheckedChange = { viewModel.updateAutoSave(it, settings.autoSaveInterval) }
                    )
                }
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("打开文档默认进入预览") },
                supportingContent = { Text(if (settings.defaultPreviewMode) "开启（空文档仍进入编辑）" else "关闭") },
                trailingContent = {
                    Switch(
                        checked = settings.defaultPreviewMode,
                        onCheckedChange = { viewModel.updateDefaultPreviewMode(it) }
                    )
                }
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
                        TextButton(onClick = { folderPicker.launch(SafLocations.storageRootHint()) }) {
                            Text(stringResource(R.string.default_dir_choose))
                        }
                        if (defaultDirName != null) {
                            TextButton(onClick = { viewModel.clearDefaultDir() }) {
                                Text(stringResource(R.string.default_dir_clear))
                            }
                        }
                    }
                }
            )

            HorizontalDivider()

            // 检查更新
            ListItem(
                headlineContent = { Text("检查更新") },
                supportingContent = { Text("当前版本: ${BuildConfig.VERSION_NAME}") },
                trailingContent = {
                    when (updateState) {
                        is UpdateCheckState.Checking -> {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                        is UpdateCheckState.NoUpdate -> {
                            Text("已是最新版本", style = MaterialTheme.typography.bodySmall)
                        }
                        is UpdateCheckState.Error -> {
                            Text(
                                (updateState as UpdateCheckState.Error).message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {
                            IconButton(onClick = { viewModel.checkUpdate() }) {
                                Icon(Icons.Default.SystemUpdate, "检查更新")
                            }
                        }
                    }
                },
                modifier = Modifier.clickable { viewModel.checkUpdate() }
            )

            HorizontalDivider()

            // About
            ListItem(
                headlineContent = { Text("关于") },
                supportingContent = { Text("YuMark - Markdown 编辑器") }
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
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            headlineContent()
            supportingContent()
        }
        trailingContent()
    }
}

/**
 * 更新对话框
 */
@Composable
private fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit,
    onUpdate: (UpdateInfo) -> Unit
) {
    var showFullChangelog by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // 标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "发现新版本",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "v${updateInfo.version}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // 文件信息
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "文件大小:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatFileSize(updateInfo.fileSize),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "发布日期:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatDate(updateInfo.publishDate),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 更新日志
                Text(
                    "更新内容:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (showFullChangelog) 300.dp else 120.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        updateInfo.changelog.take(if (showFullChangelog) Int.MAX_VALUE else 200),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (updateInfo.changelog.length > 200) {
                    TextButton(onClick = { showFullChangelog = !showFullChangelog }) {
                        Text(if (showFullChangelog) "收起" else "查看全部")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("稍后提醒")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onUpdate(updateInfo) }) {
                        Text("立即更新")
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
        else -> String.format("%.2f MB", bytes / (1024f * 1024f))
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
 * 下载对话框
 */
@Composable
private fun DownloadDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit,
    context: Context
) {
    val downloader = remember { ApkDownloader(context) }
    val downloadState by downloader.download(
        updateInfo.downloadUrl,
        updateInfo.version
    ).collectAsState(initial = DownloadState.Idle)

    // 下载成功后自动触发安装
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Success) {
            val filePath = (downloadState as DownloadState.Success).filePath
            kotlinx.coroutines.delay(1000) // 延迟1秒让用户看到完成状态
            downloader.installApk(filePath)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = { if (downloadState !is DownloadState.Downloading) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (downloadState) {
                    is DownloadState.Idle -> {
                        Text("准备下载...", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(16.dp))
                        CircularProgressIndicator()
                    }
                    is DownloadState.Downloading -> {
                        val progress = (downloadState as DownloadState.Downloading).progress
                        Text(
                            "正在下载更新",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "$progress%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    is DownloadState.Success -> {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "下载完成",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "即将开始安装...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is DownloadState.Failed -> {
                        val error = (downloadState as DownloadState.Failed).error
                        Text(
                            "下载失败",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onDismiss) {
                            Text("关闭")
                        }
                    }
                }
            }
        }
    }
}
