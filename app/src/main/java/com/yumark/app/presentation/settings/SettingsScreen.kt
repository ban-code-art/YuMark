package com.yumark.app.presentation.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.core.util.SafLocations
import com.yumark.app.domain.model.CompressionQuality
import com.yumark.app.presentation.common.FolderConfirmDialog
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.repository.SettingsRepository
import com.yumark.app.domain.repository.WorkspaceRepository
import com.yumark.app.presentation.theme.AppThemes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.hilt.navigation.compose.hiltViewModel

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {
    private val _settings = MutableStateFlow(UserSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    /** 默认目录显示名，null 表示未设置 */
    private val _defaultDirName = MutableStateFlow<String?>(null)
    val defaultDirName: StateFlow<String?> = _defaultDirName.asStateFlow()

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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val defaultDirName by viewModel.defaultDirName.collectAsState()
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
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

            // About
            ListItem(
                headlineContent = { Text("关于") },
                supportingContent = { Text("YuMark v1.0.0") }
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
