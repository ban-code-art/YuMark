package com.yumark.app.presentation.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.yumark.app.domain.model.SyncOutcome
import com.yumark.app.domain.model.WebDavConfig
import com.yumark.app.domain.usecase.sync.GetWebDavConfigUseCase
import com.yumark.app.domain.usecase.sync.ObserveLastSyncedAtUseCase
import com.yumark.app.domain.usecase.sync.SaveWebDavConfigUseCase
import com.yumark.app.domain.usecase.sync.SyncNowUseCase
import com.yumark.app.domain.usecase.sync.TestWebDavConnectionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class SyncUiState(
    val config: WebDavConfig = WebDavConfig(),
    val loaded: Boolean = false,
    val lastSyncedAt: Long? = null,
    val testing: Boolean = false,
    val syncing: Boolean = false,
    val message: String? = null,
    val lastOutcome: SyncOutcome? = null
)

@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    getConfig: GetWebDavConfigUseCase,
    observeLastSyncedAt: ObserveLastSyncedAtUseCase,
    private val saveConfig: SaveWebDavConfigUseCase,
    private val testConnection: TestWebDavConnectionUseCase,
    private val syncNow: SyncNowUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            getConfig().collect { cfg ->
                // 首次加载填充；之后以本地编辑为准
                if (!_state.value.loaded) {
                    _state.value = _state.value.copy(config = cfg, loaded = true)
                }
            }
        }
        viewModelScope.launch {
            observeLastSyncedAt().collect { ts ->
                _state.value = _state.value.copy(lastSyncedAt = ts)
            }
        }
    }

    private fun edit(transform: (WebDavConfig) -> WebDavConfig) {
        _state.value = _state.value.copy(config = transform(_state.value.config))
    }

    fun onEnabledChange(v: Boolean) = edit { it.copy(enabled = v) }
    fun onBaseUrlChange(v: String) = edit { it.copy(baseUrl = v) }
    fun onUsernameChange(v: String) = edit { it.copy(username = v) }
    fun onPasswordChange(v: String) = edit { it.copy(password = v) }
    fun onRemoteDirChange(v: String) = edit { it.copy(remoteDir = v) }

    fun save() {
        viewModelScope.launch {
            saveConfig(_state.value.config)
            _state.value = _state.value.copy(message = "已保存")
        }
    }

    fun test() {
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true)
            val result = testConnection(_state.value.config)
            _state.value = _state.value.copy(
                testing = false,
                message = result.fold({ "连接成功" }, { "连接失败：${it.message ?: "未知错误"}" })
            )
        }
    }

    fun sync() {
        viewModelScope.launch {
            // 先保存最新配置，确保 syncNow 用到当前输入
            saveConfig(_state.value.config)
            _state.value = _state.value.copy(syncing = true, lastOutcome = null)
            val result = syncNow()
            _state.value = _state.value.copy(
                syncing = false,
                lastOutcome = result.getOrNull(),
                message = result.fold(
                    { o ->
                        buildString {
                            append("同步完成：↑${o.uploaded} ↓${o.downloaded} 冲突${o.conflicts} 跳过${o.skipped}")
                            if (o.failed > 0) append(" 失败${o.failed}")
                        }
                    },
                    { "同步失败：${it.message ?: "未知错误"}" }
                )
            )
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    navController: NavController,
    viewModel: SyncSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config = state.config
    val snackbar = remember { SnackbarHostState() }
    val busy = state.testing || state.syncing

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("云端同步") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.save(); navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save() }) { Text("保存") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 总开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用 WebDAV 同步", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = config.enabled, onCheckedChange = viewModel::onEnabledChange)
            }

            Text(
                "支持 Nextcloud、坚果云、群晖等任意 WebDAV 服务（使用你自己的账号）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            OutlinedTextField(
                value = config.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text("服务器地址") },
                placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = config.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            var pwdVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = config.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("密码 / 应用授权码") },
                singleLine = true,
                visualTransformation = if (pwdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { pwdVisible = !pwdVisible }) {
                        Icon(
                            if (pwdVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            "切换显隐"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = config.remoteDir,
                onValueChange = viewModel::onRemoteDirChange,
                label = { Text("远端目录") },
                placeholder = { Text("YuMark") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            // 操作按钮
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = viewModel::test,
                    enabled = !busy && config.isValid,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("测试连接")
                    }
                }
                Button(
                    onClick = viewModel::sync,
                    enabled = !busy && config.enabled && config.isValid,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.syncing) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("立即同步")
                    }
                }
            }

            // 上次同步时间
            state.lastSyncedAt?.let { ts ->
                Text(
                    "上次同步：${formatTime(ts)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // P1 范围说明
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "当前为手动双向同步，仅同步库根目录下的文档正文（按文档名生成 .md 文件）。" +
                        "文件夹层级、图片附件、删除传播与后台自动同步将在后续版本支持。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
