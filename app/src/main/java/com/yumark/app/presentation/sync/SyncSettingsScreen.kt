package com.yumark.app.presentation.sync

import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.yumark.app.R
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserAction
import com.yumark.app.domain.model.SyncOutcome
import com.yumark.app.domain.model.WebDavConfig
import com.yumark.app.domain.usecase.sync.GetWebDavConfigUseCase
import com.yumark.app.domain.usecase.sync.ObserveLastSyncedAtUseCase
import com.yumark.app.domain.usecase.sync.SaveWebDavConfigUseCase
import com.yumark.app.domain.usecase.sync.SyncNowUseCase
import com.yumark.app.domain.usecase.sync.TestWebDavConnectionUseCase
import com.yumark.app.presentation.common.SnackbarEffect
import com.yumark.app.presentation.common.resolveOrNull
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppMotion
import com.yumark.app.presentation.theme.AppSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    // UiMessage 而不是 String：ViewModel 里拿不到 Context，成句的文案只能推到界面侧解析。
    // Res 走资源 id；ErrorHandler 返回的也是 UiMessage，直接放进来即可。
    val message: UiMessage? = null,
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

    /**
     * 没加载完不许写盘。理由与 `AiConfigViewModel.save()` 同：首次发射之前
     * `_state.value.config` 是 `WebDavConfig()`（baseUrl / username / password 全空），而返回键
     * 是「顺手保存再退出」，于是「进同步设置页随即退出」就把用户存着的 WebDAV 密码覆盖成空串。
     *
     * 密码丢了不只是要重填：[WebDavConfig.isValid] 随即为假，同步静静地停在「未配置」，
     * 而用户以为它还在后台跑。
     *
     * 写盘本身必须兜住：密文那半边失败时 `SyncConfigDataStore.updateConfig` 会抛
     * [com.yumark.app.core.util.FriendlyIOException]，而这里是 `viewModelScope`——抛出去就是崩进程。
     * `CancellationException` 单独重抛：它是「界面已经离开」的正常信号，吞掉会把取消当成一次
     * 失败报给用户，还会往已清理的 ViewModel 里接着写状态。
     */
    fun save() {
        viewModelScope.launch {
            if (!_state.value.loaded) return@launch
            val message = try {
                saveConfig(_state.value.config)
                UiMessage.Res(R.string.saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorHandler.report(e, UserAction.SAVE_SETTINGS)
            }
            _state.value = _state.value.copy(message = message)
        }
    }

    fun test() {
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true)
            val result = testConnection(_state.value.config)
            _state.value = _state.value.copy(
                testing = false,
                // 失败文案交给 ErrorHandler：WebDavClient 抛的 FriendlyIOException 会原样透出
                // （「账号或密码不正确（HTTP 401）」），其余异常不会把带内嵌密码的 URL 带上界面。
                // report() 返回的就是 UiMessage，动作标签走 UserAction 的资源 id。
                message = result.fold(
                    { UiMessage.Res(R.string.sync_test_success) },
                    { ErrorHandler.report(it, UserAction.CONNECT) }
                )
            )
        }
    }

    fun sync() {
        viewModelScope.launch {
            // 同 save()：这里的第一件事就是写盘，未加载完时写进去的是一个空配置，
            // 覆盖掉密码之后紧跟着的 syncNow() 还会拿这个空配置去撞服务器。
            if (!_state.value.loaded) return@launch
            // 先保存最新配置，确保 syncNow 用到当前输入
            val saveError = try {
                saveConfig(_state.value.config)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorHandler.report(e, UserAction.SAVE_SETTINGS)
            }
            // 存不进去就别往下撞服务器：syncNow() 是自己去存储读配置的，拿到的会是旧密码，
            // 于是一条 401 盖住真正的原因；SyncConfigDataStore.readSecret 的说明里还提到
            // 部分 WebDAV 实现会累计失败次数并锁账号。
            if (saveError != null) {
                _state.value = _state.value.copy(message = saveError)
                return@launch
            }
            _state.value = _state.value.copy(syncing = true, lastOutcome = null)
            val result = syncNow()
            _state.value = _state.value.copy(
                syncing = false,
                lastOutcome = result.getOrNull(),
                // 有无失败数走两个不同的键，而不是在这里拼「基础句 + 失败段」：
                // 拼接要先取到字符串，而 ViewModel 里没有 Context。
                message = result.fold(
                    { o ->
                        if (o.failed > 0) {
                            UiMessage.Res(
                                R.string.sync_result_with_failed,
                                listOf(o.uploaded, o.downloaded, o.deleted, o.conflicts, o.skipped, o.failed)
                            )
                        } else {
                            UiMessage.Res(
                                R.string.sync_result,
                                listOf(o.uploaded, o.downloaded, o.deleted, o.conflicts, o.skipped)
                            )
                        }
                    },
                    { ErrorHandler.report(it, UserAction.SYNC) }
                )
            )
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

/**
 * UI 测试用的稳定锚点。集中放在这里而不是散在各控件旁边：
 * 标签是测试与界面之间的契约，改文案不该改标签，改标签必须一眼看到全部受影响项。
 */
private object SyncTestTags {
    const val ROOT = "sync_settings_screen"
    const val BACK = "sync_back_button"
    const val SAVE = "sync_save_button"
    const val ENABLE_SWITCH = "sync_enable_switch"
    const val BASE_URL_FIELD = "sync_base_url_field"
    const val USERNAME_FIELD = "sync_username_field"
    const val PASSWORD_FIELD = "sync_password_field"
    const val PASSWORD_TOGGLE = "sync_password_toggle"
    const val REMOTE_DIR_FIELD = "sync_remote_dir_field"
    const val TEST_BUTTON = "sync_test_button"
    const val SYNC_BUTTON = "sync_now_button"
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
    // 在组合期解析：resolveOrNull() 是 @Composable，协程体里拿不到资源上下文
    val message = state.message.resolveOrNull()
    SnackbarEffect(message, snackbar) { viewModel.consumeMessage() }

    Scaffold(
        modifier = Modifier.testTag(SyncTestTags.ROOT),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.save(); navController.navigateUp() },
                        modifier = Modifier.testTag(SyncTestTags.BACK)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.cd_sync_back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save() },
                        // 未加载完时置灰：ViewModel 里的 loaded 闸门会把这次点击变成空操作，
                        // 按钮还亮着就成了「点了保存、也没提示、其实什么都没发生」。
                        enabled = state.loaded,
                        modifier = Modifier.testTag(SyncTestTags.SAVE)
                    ) { Text(stringResource(R.string.save)) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.Screen),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Screen)
        ) {
            // 总开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sync_enable),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = config.enabled,
                    onCheckedChange = viewModel::onEnabledChange,
                    modifier = Modifier.testTag(SyncTestTags.ENABLE_SWITCH)
                )
            }

            Text(
                stringResource(R.string.sync_providers_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            OutlinedTextField(
                value = config.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text(stringResource(R.string.sync_server_url)) },
                placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SyncTestTags.BASE_URL_FIELD)
            )

            OutlinedTextField(
                value = config.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text(stringResource(R.string.sync_username)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SyncTestTags.USERNAME_FIELD)
            )

            var pwdVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = config.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text(stringResource(R.string.sync_password)) },
                singleLine = true,
                visualTransformation = if (pwdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(
                        onClick = { pwdVisible = !pwdVisible },
                        modifier = Modifier.testTag(SyncTestTags.PASSWORD_TOGGLE)
                    ) {
                        Icon(
                            if (pwdVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            stringResource(R.string.cd_sync_toggle_password)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SyncTestTags.PASSWORD_FIELD)
            )

            OutlinedTextField(
                value = config.remoteDir,
                onValueChange = viewModel::onRemoteDirChange,
                label = { Text(stringResource(R.string.sync_remote_dir)) },
                placeholder = { Text("YuMark") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SyncTestTags.REMOTE_DIR_FIELD)
            )

            HorizontalDivider()

            // 操作按钮
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Cozy)) {
                OutlinedButton(
                    onClick = viewModel::test,
                    enabled = !busy && config.isValid,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SyncTestTags.TEST_BUTTON)
                ) {
                    Crossfade(
                        targetState = state.testing,
                        animationSpec = AppMotion.enter(AppMotion.CrossfadeMs),
                        label = "sync_test_btn"
                    ) { testing ->
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(AppIconSize.Small), strokeWidth = SyncMetrics.ProgressStroke)
                        } else {
                            Text(stringResource(R.string.sync_test))
                        }
                    }
                }
                Button(
                    onClick = viewModel::sync,
                    enabled = !busy && config.enabled && config.isValid,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SyncTestTags.SYNC_BUTTON)
                ) {
                    Crossfade(
                        targetState = state.syncing,
                        animationSpec = AppMotion.enter(AppMotion.CrossfadeMs),
                        label = "sync_now_btn"
                    ) { syncing ->
                        if (syncing) {
                            CircularProgressIndicator(
                                Modifier.size(AppIconSize.Small),
                                strokeWidth = SyncMetrics.ProgressStroke,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(stringResource(R.string.sync_now))
                        }
                    }
                }
            }

            // 上次同步时间
            state.lastSyncedAt?.let { ts ->
                Text(
                    stringResource(R.string.sync_last_synced, formatTime(ts)),
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
                    stringResource(R.string.sync_scope_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(AppSpacing.Cozy)
                )
            }
        }
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

/**
 * 本屏特有的绘制度量，刻意不并入全局 [AppSpacing]：按钮内联加载圈的描边宽度是线宽（绘制轴），
 * 既非间距节奏也非图标标度，保留原像素。按 FileListMetrics 先例落屏幕局部。
 */
private object SyncMetrics {
    /** 测试/同步按钮内联 [CircularProgressIndicator] 的描边宽度：与 18dp 直径成比例的细环。 */
    val ProgressStroke = 2.dp
}
