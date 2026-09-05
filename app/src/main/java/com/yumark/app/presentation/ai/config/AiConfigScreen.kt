package com.yumark.app.presentation.ai.config

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.yumark.app.R
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserAction
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiProvider
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.WebSearchProvider
import com.yumark.app.domain.model.defaultBaseUrl
import com.yumark.app.domain.usecase.ai.FetchAvailableModelsUseCase
import com.yumark.app.domain.usecase.ai.FetchRagModelsUseCase
import com.yumark.app.domain.usecase.ai.GetAiConfigUseCase
import com.yumark.app.domain.usecase.ai.TestAiConnectionUseCase
import com.yumark.app.domain.usecase.ai.UpdateAiConfigUseCase
import com.yumark.app.presentation.common.SnackbarEffect
import com.yumark.app.presentation.common.resolve
import com.yumark.app.presentation.common.resolveOrNull
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppMotion
import com.yumark.app.presentation.theme.AppSpacing
import com.yumark.app.presentation.theme.extendedColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiConfigUiState(
    val config: AiConfig = AiConfig(),
    val isTesting: Boolean = false,
    val testResult: ModelTestResult? = null,
    val isFetchingModels: Boolean = false,
    val isFetchingRagModels: Boolean = false,
    // ViewModel 里没有 Context 也不该有，一次性提示改走 UiMessage：
    // Res 携带 @StringRes id + 实参，到组合期才解析；ErrorHandler 返回的也是 UiMessage
    // （「<动作>失败：<原因>」两段都是资源 id），所以这里不需要再包一层。
    val message: UiMessage? = null,
    val loaded: Boolean = false
)

@HiltViewModel
class AiConfigViewModel @Inject constructor(
    private val getAiConfig: GetAiConfigUseCase,
    private val updateAiConfig: UpdateAiConfigUseCase,
    private val testConnection: TestAiConnectionUseCase,
    private val fetchModels: FetchAvailableModelsUseCase,
    private val fetchRagModels: FetchRagModelsUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(AiConfigUiState())
    val state: StateFlow<AiConfigUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            getAiConfig().collect { config ->
                // 首次加载后以本地编辑为准，仅同步未编辑过的字段：这里简单地在未加载时填充
                if (!_state.value.loaded) {
                    _state.value = _state.value.copy(config = config, loaded = true)
                }
            }
        }
    }

    private fun edit(transform: (AiConfig) -> AiConfig) {
        _state.value = _state.value.copy(config = transform(_state.value.config), testResult = null)
    }

    fun onToggleEnabled(v: Boolean) = edit { it.copy(enabled = v) }
    fun onProviderChange(p: AiProvider) = edit { it.copy(provider = p, baseUrl = "") }
    fun onApiKeyChange(v: String) = edit { it.copy(apiKey = v) }
    fun onBaseUrlChange(v: String) = edit { it.copy(baseUrl = v) }
    fun onModelChange(v: String) = edit { it.copy(modelName = v) }
    fun onEmbeddingModelChange(v: String) = edit { it.copy(embeddingModel = v) }
    fun onRagUseMainEndpointChange(v: Boolean) = edit { it.copy(ragUseMainEndpoint = v) }
    fun onRagBaseUrlChange(v: String) = edit { it.copy(ragBaseUrl = v) }
    fun onRagApiKeyChange(v: String) = edit { it.copy(ragApiKey = v) }
    fun onTemperatureChange(v: Float) = edit { it.copy(temperature = v) }
    fun onMaxTokensChange(v: Int) = edit { it.copy(maxTokens = v) }
    fun onStreamChange(v: Boolean) = edit { it.copy(streamEnabled = v) }
    fun onWebSearchToggle(v: Boolean) = edit { it.copy(webSearchEnabled = v) }
    fun onWebSearchProviderChange(p: WebSearchProvider) = edit { it.copy(webSearchProvider = p) }
    fun onWebSearchApiKeyChange(v: String) = edit { it.copy(webSearchApiKey = v) }
    fun onWebSearchCustomUrlChange(v: String) = edit { it.copy(webSearchCustomUrl = v) }

    /**
     * 没加载完不许写盘。
     *
     * 首次发射之前 `_state.value.config` 是 `AiConfig()` —— apiKey 空串、enabled 假。而返回键
     * 的实现是「顺手保存再退出」（见 [AiConfigScreen] 的 navigationIcon），于是「进配置页随即
     * 退出」这条再普通不过的操作，就是拿一个空配置去覆盖用户存着的 API Key：下次进来看到
     * 「AI 未启用」+ 空密钥，且没有任何提示说明刚刚发生了什么。
     *
     * DataStore 首帧一般几十毫秒内到，所以这是一个窗口很窄但**必然可触发**的竞态（进程被杀后
     * 恢复现场时窗口更宽）—— 而它的后果是用户凭据丢失，不是一次失败的写入。
     *
     * 未加载完时静默返回而不是弹提示：走到这条路径的用户压根没在保存（他在退出），提示只是
     * 噪声；主动点「保存」的入口另有 `enabled = state.loaded` 拦着，按钮是灰的。
     *
     * 写盘本身必须兜住：密文那半边失败时 `AiConfigDataStore.updateConfig` 会抛
     * [com.yumark.app.core.util.FriendlyIOException]，而这里是 `viewModelScope`——抛出去就是崩进程。
     * `CancellationException` 单独重抛：它是「界面已经离开」的正常信号，吞掉会让协程接着往
     * 一个已清理的 ViewModel 里写状态，也会把取消当成一次失败报给用户。
     */
    fun save() {
        viewModelScope.launch {
            if (!_state.value.loaded) return@launch
            val message = try {
                updateAiConfig(_state.value.config)
                // 复用全局的 R.string.saved（已保存），不另建同义键
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
            _state.value = _state.value.copy(isTesting = true, testResult = null)
            val result = testConnection(_state.value.config)
            _state.value = _state.value.copy(isTesting = false, testResult = result)
        }
    }

    fun fetchModelList() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isFetchingModels = true)
            val result = fetchModels(_state.value.config)
            result.onSuccess { models ->
                _state.value = _state.value.copy(
                    config = _state.value.config.copy(availableModels = models.map { it.id }),
                    isFetchingModels = false,
                    message = UiMessage.Res(R.string.ai_config_models_fetched, listOf(models.size))
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    isFetchingModels = false,
                    // 适配器现在会在 HTTP 失败时抛 FriendlyIOException（「API Key 无效…」），
                    // 不再静默返回空列表让界面报「获取到 0 个模型」。
                    // report() 直接返回 UiMessage，动作标签由 UserAction 枚举带上资源 id。
                    message = ErrorHandler.report(it, UserAction.FETCH_MODELS)
                )
            }
        }
    }

    /**
     * 拉取 embedding 端点的模型列表。与 [fetchModelList] 同构，区别只在于打哪个地址、
     * 用哪把密钥、把结果写进 [AiConfig.ragAvailableModels]——这三点都封装在
     * [FetchRagModelsUseCase] 里。地址为空的拦截也在用例里（发请求之前），
     * 这里拿到的是一条友好的「请先填写 Base URL」。
     */
    fun fetchRagModelList() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isFetchingRagModels = true)
            val result = fetchRagModels(_state.value.config)
            result.onSuccess { models ->
                _state.value = _state.value.copy(
                    config = _state.value.config.copy(ragAvailableModels = models.map { it.id }),
                    isFetchingRagModels = false,
                    message = UiMessage.Res(R.string.ai_config_models_fetched, listOf(models.size))
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    isFetchingRagModels = false,
                    message = ErrorHandler.report(it, UserAction.FETCH_MODELS)
                )
            }
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiConfigScreen(
    navController: NavController,
    viewModel: AiConfigViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config = state.config
    val snackbar = remember { SnackbarHostState() }

    // resolveOrNull() 是 @Composable，协程体里调不了，所以先在组合期解析成 String 再交给
    // SnackbarEffect——它也正是以这段文案为 key。
    val messageText = state.message.resolveOrNull()
    SnackbarEffect(messageText, snackbar) { viewModel.consumeMessage() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_config_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.save(); navController.navigateUp() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.cd_ai_config_back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save() },
                        // 未加载完时置灰：ViewModel 里的 loaded 闸门会让这次点击变成空操作，
                        // 按钮却还是可点的话就成了「点了保存、提示也没出、其实什么都没发生」。
                        enabled = state.loaded,
                        modifier = Modifier.testTag(AiConfigTestTags.SAVE)
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
                Text(stringResource(R.string.ai_config_enable), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = config.enabled, onCheckedChange = viewModel::onToggleEnabled)
            }

            HorizontalDivider()

            // Provider
            Text(
                stringResource(R.string.ai_config_provider),
                style = MaterialTheme.typography.labelLarge,
                color = extendedColors.primaryText
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Tight),
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
            ) {
                AiProvider.values().forEach { p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(selected = config.provider == p, onClick = { viewModel.onProviderChange(p) })
                        Spacer(Modifier.width(AppSpacing.Default))
                        Text(stringResource(p.displayNameRes()))
                    }
                }
            }

            // Base URL（OpenAI 兼容必填，其他可选覆盖）
            OutlinedTextField(
                value = config.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text("Base URL") },
                placeholder = { Text(config.provider.defaultBaseUrl.ifBlank { "https://..." }) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // API Key
            var keyVisible by remember { mutableStateOf(false) }
            var webKeyVisible by remember { mutableStateOf(false) }
            var ragKeyVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = config.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            stringResource(R.string.cd_ai_config_toggle_key_visibility)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // 模型
            OutlinedTextField(
                value = config.modelName,
                onValueChange = viewModel::onModelChange,
                label = { Text(stringResource(R.string.ai_config_model_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Default), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { viewModel.fetchModelList() },
                    enabled = !state.isFetchingModels,
                    modifier = Modifier.testTag(AiConfigTestTags.FETCH_MODELS)
                ) {
                    if (state.isFetchingModels) {
                        CircularProgressIndicator(Modifier.size(AiConfigMetrics.ButtonSpinner), strokeWidth = AiConfigMetrics.ButtonSpinnerStroke)
                        Spacer(Modifier.width(AppSpacing.Default))
                    }
                    Text(stringResource(R.string.ai_config_fetch_models))
                }
            }
            if (config.availableModels.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
                    config.availableModels.take(MaxModelChips).forEach { m ->
                        SuggestionChip(
                            onClick = { viewModel.onModelChange(m) },
                            label = { Text(m) },
                            // 同一棵树里会出现多个候选模型芯片，固定 tag 会让测试定位不到具体某个，
                            // 所以拼上模型 id（参照 SettingsTestTags 的 *_PREFIX 写法）
                            modifier = Modifier.testTag(AiConfigTestTags.MODEL_CHOICE_PREFIX + m)
                        )
                    }
                }
            }

            HorizontalDivider()

            // Temperature
            Text("Temperature: ${"%.2f".format(config.temperature)}")
            Slider(
                value = config.temperature,
                onValueChange = viewModel::onTemperatureChange,
                valueRange = 0f..1f,
                steps = 9
            )

            // Max tokens
            OutlinedTextField(
                value = config.maxTokens.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let(viewModel::onMaxTokensChange) },
                label = { Text("Max Tokens") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 流式开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_config_stream))
                Spacer(Modifier.weight(1f))
                Switch(checked = config.streamEnabled, onCheckedChange = viewModel::onStreamChange)
            }

            HorizontalDivider()

            // 知识库（RAG）embedding 模型
            Text(stringResource(R.string.ai_config_rag), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.ai_config_rag_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Embedding 端点：复用上方，或单独配置
            Text(
                stringResource(R.string.ai_config_rag_endpoint),
                style = MaterialTheme.typography.labelLarge,
                color = extendedColors.primaryText
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Tight),
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = config.ragUseMainEndpoint,
                        onClick = { viewModel.onRagUseMainEndpointChange(true) }
                    )
                    Spacer(Modifier.width(AppSpacing.Default))
                    Text(stringResource(R.string.ai_config_rag_reuse_main))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = !config.ragUseMainEndpoint,
                        onClick = { viewModel.onRagUseMainEndpointChange(false) }
                    )
                    Spacer(Modifier.width(AppSpacing.Default))
                    Text(stringResource(R.string.ai_config_rag_separate))
                }
            }

            // 单独配置时才露出这一组输入框；展开/收起走标准 M3 动效
            AnimatedVisibility(
                visible = !config.ragUseMainEndpoint,
                enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
                exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Default)
                ) {
                    Text(
                        stringResource(R.string.ai_config_rag_separate_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = config.ragBaseUrl,
                        onValueChange = viewModel::onRagBaseUrlChange,
                        label = { Text(stringResource(R.string.ai_config_rag_base_url)) },
                        placeholder = { Text("https://...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AiConfigTestTags.RAG_BASE_URL)
                    )
                    OutlinedTextField(
                        value = config.ragApiKey,
                        onValueChange = viewModel::onRagApiKeyChange,
                        label = { Text(stringResource(R.string.ai_config_rag_api_key)) },
                        singleLine = true,
                        visualTransformation = if (ragKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { ragKeyVisible = !ragKeyVisible }) {
                                Icon(
                                    if (ragKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    stringResource(R.string.cd_ai_config_toggle_key_visibility)
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AiConfigTestTags.RAG_API_KEY)
                    )
                }
            }

            OutlinedTextField(
                value = config.embeddingModel,
                onValueChange = viewModel::onEmbeddingModelChange,
                label = { Text(stringResource(R.string.ai_config_embedding_model)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            // Embedding 模型列表来自 RAG 端点（复用模式下即上方那个端点）的 /models
            OutlinedButton(
                onClick = { viewModel.fetchRagModelList() },
                enabled = !state.isFetchingRagModels,
                modifier = Modifier.testTag(AiConfigTestTags.FETCH_RAG_MODELS)
            ) {
                if (state.isFetchingRagModels) {
                    CircularProgressIndicator(Modifier.size(AiConfigMetrics.ButtonSpinner), strokeWidth = AiConfigMetrics.ButtonSpinnerStroke)
                    Spacer(Modifier.width(AppSpacing.Default))
                }
                Text(stringResource(R.string.ai_config_rag_fetch_models))
            }
            if (config.ragAvailableModels.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
                    config.ragAvailableModels.take(MaxModelChips).forEach { m ->
                        SuggestionChip(
                            onClick = { viewModel.onEmbeddingModelChange(m) },
                            label = { Text(m) },
                            modifier = Modifier.testTag(AiConfigTestTags.RAG_MODEL_CHOICE_PREFIX + m)
                        )
                    }
                }
            }

            HorizontalDivider()

            // 网络搜索
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_config_web_search), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = config.webSearchEnabled, onCheckedChange = viewModel::onWebSearchToggle)
            }
            if (config.webSearchEnabled) {
                Text(
                    stringResource(R.string.ai_config_search_engine),
                    style = MaterialTheme.typography.labelLarge,
                    color = extendedColors.primaryText
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Tight),
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup()
                ) {
                    WebSearchProvider.values().forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(selected = config.webSearchProvider == p, onClick = { viewModel.onWebSearchProviderChange(p) })
                            Spacer(Modifier.width(AppSpacing.Default))
                            Text(stringResource(p.webSearchDisplayNameRes()))
                        }
                    }
                }
                if (config.webSearchProvider != WebSearchProvider.DUCKDUCKGO) {
                    OutlinedTextField(
                        value = config.webSearchApiKey,
                        onValueChange = viewModel::onWebSearchApiKeyChange,
                        label = { Text(stringResource(R.string.ai_config_search_api_key)) },
                        singleLine = true,
                        visualTransformation = if (webKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { webKeyVisible = !webKeyVisible }) {
                                Icon(
                                    if (webKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    stringResource(R.string.cd_ai_config_toggle_key_visibility)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (config.webSearchProvider == WebSearchProvider.CUSTOM) {
                    OutlinedTextField(
                        value = config.webSearchCustomUrl,
                        onValueChange = viewModel::onWebSearchCustomUrlChange,
                        label = { Text(stringResource(R.string.ai_config_custom_search_url)) },
                        placeholder = { Text("https://your-search-endpoint/search") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            HorizontalDivider()

            // 测试连接
            Button(
                onClick = { viewModel.save(); viewModel.test() },
                enabled = !state.isTesting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AiConfigTestTags.TEST)
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(Modifier.size(AiConfigMetrics.ButtonSpinner), strokeWidth = AiConfigMetrics.ButtonSpinnerStroke)
                    Spacer(Modifier.width(AppSpacing.Default))
                }
                Text(stringResource(R.string.ai_config_test))
            }
            state.testResult?.let { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(AppSpacing.Cozy), verticalArrangement = Arrangement.spacedBy(AppSpacing.Tight)) {
                        Text(
                            stringResource(
                                if (r.success) R.string.ai_config_test_success else R.string.ai_config_test_failed
                            ),
                            color = if (r.success) extendedColors.success else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleSmall)
                        if (r.success) {
                            Text(stringResource(R.string.ai_config_test_total_time, r.responseTime))
                            Text(stringResource(R.string.ai_config_test_first_token, r.firstTokenLatency))
                            Text(
                                stringResource(
                                    R.string.ai_config_test_streaming,
                                    stringResource(
                                        if (r.streamingWorks) R.string.ai_config_streaming_available
                                        else R.string.ai_config_streaming_unavailable
                                    )
                                )
                            )
                        } else {
                            Text(
                                r.errorMessage?.resolve()
                                    ?: stringResource(R.string.ai_config_unknown_error),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.Group))
        }
    }
}

// 这两个原先直接返回中文字符串，现在返回 @StringRes id：它们是普通（非 @Composable）
// 扩展函数，读不了 stringResource，改成返回 id 后由上面的 forEach 里解析。
// 品牌名不翻，只有限定语（官方 / 兼容 / 免 Key / 自定义）进了资源。
@StringRes
private fun AiProvider.displayNameRes(): Int = when (this) {
    AiProvider.OPENAI -> R.string.ai_config_provider_openai
    AiProvider.OPENAI_COMPATIBLE -> R.string.ai_config_provider_openai_compatible
    AiProvider.CLAUDE -> R.string.ai_config_provider_claude
    AiProvider.GEMINI -> R.string.ai_config_provider_gemini
}

@StringRes
private fun WebSearchProvider.webSearchDisplayNameRes(): Int = when (this) {
    WebSearchProvider.DUCKDUCKGO -> R.string.ai_config_search_duckduckgo
    WebSearchProvider.TAVILY -> R.string.ai_config_search_tavily
    WebSearchProvider.SERPER -> R.string.ai_config_search_serper
    WebSearchProvider.BRAVE -> R.string.ai_config_search_brave
    WebSearchProvider.CUSTOM -> R.string.ai_config_search_custom
}

// 稳定的英文 snake_case tag，集中放一处：散在调用点上很容易两处写成同一个值，
// 同一棵树里出现重复 tag 会让 UI 测试定位不到具体节点（参照 SettingsTestTags）。
private object AiConfigTestTags {
    const val SAVE = "ai_config_save"
    const val FETCH_MODELS = "ai_config_fetch_models"
    const val TEST = "ai_config_test"
    const val MODEL_CHOICE_PREFIX = "ai_config_model_choice_"
    const val RAG_BASE_URL = "ai_config_rag_base_url"
    const val RAG_API_KEY = "ai_config_rag_api_key"
    const val FETCH_RAG_MODELS = "ai_config_fetch_rag_models"
    const val RAG_MODEL_CHOICE_PREFIX = "ai_config_rag_model_choice_"
}

/**
 * 本屏的布局常量。角色说不清的数字不进 [com.yumark.app.presentation.theme.AppSpacing]：
 * 「按钮里 spinner 的直径」和「屏幕边距」是两件事，前者变了不该牵动后者。
 */
private object AiConfigMetrics {
    /** 按钮内嵌 CircularProgressIndicator 的直径——贴着按钮文字高度，不是通用间距档 */
    val ButtonSpinner = AppIconSize.Inline

    /** 同一个 spinner 的描边宽度。2dp 是视觉值，走这里是为了和直径同处一改 */
    val ButtonSpinnerStroke = 2.dp
}

/** 候选模型芯片最多显示多少个（chat 与 embedding 各一份列表，共用这个上限）。 */
private const val MaxModelChips = 20
