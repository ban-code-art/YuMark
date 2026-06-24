package com.yumark.app.presentation.ai.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiProvider
import com.yumark.app.domain.model.ModelTestResult
import com.yumark.app.domain.model.WebSearchProvider
import com.yumark.app.domain.model.defaultBaseUrl
import com.yumark.app.domain.usecase.ai.FetchAvailableModelsUseCase
import com.yumark.app.domain.usecase.ai.GetAiConfigUseCase
import com.yumark.app.domain.usecase.ai.TestAiConnectionUseCase
import com.yumark.app.domain.usecase.ai.UpdateAiConfigUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val message: String? = null,
    val loaded: Boolean = false
)

@HiltViewModel
class AiConfigViewModel @Inject constructor(
    private val getAiConfig: GetAiConfigUseCase,
    private val updateAiConfig: UpdateAiConfigUseCase,
    private val testConnection: TestAiConnectionUseCase,
    private val fetchModels: FetchAvailableModelsUseCase
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
    fun onTemperatureChange(v: Float) = edit { it.copy(temperature = v) }
    fun onMaxTokensChange(v: Int) = edit { it.copy(maxTokens = v) }
    fun onStreamChange(v: Boolean) = edit { it.copy(streamEnabled = v) }
    fun onWebSearchToggle(v: Boolean) = edit { it.copy(webSearchEnabled = v) }
    fun onWebSearchProviderChange(p: WebSearchProvider) = edit { it.copy(webSearchProvider = p) }
    fun onWebSearchApiKeyChange(v: String) = edit { it.copy(webSearchApiKey = v) }
    fun onWebSearchCustomUrlChange(v: String) = edit { it.copy(webSearchCustomUrl = v) }

    fun save() {
        viewModelScope.launch {
            updateAiConfig(_state.value.config)
            _state.value = _state.value.copy(message = "已保存")
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
                    message = "获取到 ${models.size} 个模型"
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    isFetchingModels = false,
                    message = "获取失败：${it.message}"
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
                title = { Text("AI 助手") },
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
                Text("启用 AI 助手", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = config.enabled, onCheckedChange = viewModel::onToggleEnabled)
            }

            HorizontalDivider()

            // Provider
            Text("API 提供商", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AiProvider.values().forEach { p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(selected = config.provider == p, onClick = { viewModel.onProviderChange(p) })
                        Spacer(Modifier.width(8.dp))
                        Text(p.displayName())
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
                            "切换显隐"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // 模型
            OutlinedTextField(
                value = config.modelName,
                onValueChange = viewModel::onModelChange,
                label = { Text("模型名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { viewModel.fetchModelList() }, enabled = !state.isFetchingModels) {
                    if (state.isFetchingModels) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("获取模型列表")
                }
            }
            if (config.availableModels.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    config.availableModels.take(20).forEach { m ->
                        SuggestionChip(onClick = { viewModel.onModelChange(m) }, label = { Text(m) })
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
                Text("流式输出")
                Spacer(Modifier.weight(1f))
                Switch(checked = config.streamEnabled, onCheckedChange = viewModel::onStreamChange)
            }

            HorizontalDivider()

            // 知识库（RAG）embedding 模型
            Text("知识库 (RAG)", style = MaterialTheme.typography.titleMedium)
            Text(
                "复用上方 Base URL 与 API Key，走 OpenAI 兼容 /embeddings。留空则不建立向量索引（仅文档全文检索可用）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = config.embeddingModel,
                onValueChange = viewModel::onEmbeddingModelChange,
                label = { Text("Embedding 模型（如 text-embedding-3-small）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            // 网络搜索
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("网络搜索", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = config.webSearchEnabled, onCheckedChange = viewModel::onWebSearchToggle)
            }
            if (config.webSearchEnabled) {
                Text("搜索引擎", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WebSearchProvider.values().forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(selected = config.webSearchProvider == p, onClick = { viewModel.onWebSearchProviderChange(p) })
                            Spacer(Modifier.width(8.dp))
                            Text(p.webSearchDisplayName())
                        }
                    }
                }
                if (config.webSearchProvider != WebSearchProvider.DUCKDUCKGO) {
                    OutlinedTextField(
                        value = config.webSearchApiKey,
                        onValueChange = viewModel::onWebSearchApiKeyChange,
                        label = { Text("搜索 API Key（DuckDuckGo 不需要）") },
                        singleLine = true,
                        visualTransformation = if (webKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { webKeyVisible = !webKeyVisible }) {
                                Icon(
                                    if (webKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    "切换显隐"
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
                        label = { Text("自定义搜索 URL") },
                        placeholder = { Text("https://your-search-endpoint/search") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            HorizontalDivider()

            // 测试连接
            Button(onClick = { viewModel.save(); viewModel.test() }, enabled = !state.isTesting, modifier = Modifier.fillMaxWidth()) {
                if (state.isTesting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("测试连接")
            }
            state.testResult?.let { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (r.success) "✓ 连接成功" else "✗ 连接失败",
                            color = if (r.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleSmall)
                        if (r.success) {
                            Text("总耗时: ${r.responseTime} ms")
                            Text("首 token 延迟: ${r.firstTokenLatency} ms")
                            Text("流式: ${if (r.streamingWorks) "可用" else "不可用"}")
                        } else {
                            Text(r.errorMessage ?: "未知错误", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun AiProvider.displayName(): String = when (this) {
    AiProvider.OPENAI -> "OpenAI 官方"
    AiProvider.OPENAI_COMPATIBLE -> "OpenAI 兼容 (Ollama / DeepSeek 等)"
    AiProvider.CLAUDE -> "Anthropic Claude"
    AiProvider.GEMINI -> "Google Gemini"
}

private fun WebSearchProvider.webSearchDisplayName(): String = when (this) {
    WebSearchProvider.DUCKDUCKGO -> "DuckDuckGo（免 Key）"
    WebSearchProvider.TAVILY -> "Tavily"
    WebSearchProvider.SERPER -> "Serper（Google）"
    WebSearchProvider.BRAVE -> "Brave"
    WebSearchProvider.CUSTOM -> "自定义"
}
