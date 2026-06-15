package com.yumark.app.presentation.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.StreamEvent
import com.yumark.app.domain.repository.AiConfigRepository
import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.presentation.ai.common.MessageBubble
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class QuickAiMode {
    AI_QUERY,    // 询问 AI（只读显示）
    AGENT_EDIT   // Agent 处理（可应用修改）
}

/**
 * 对话消息数据类
 */
data class ConversationMessage(
    val role: MessageRole,
    val content: String,
    val mode: QuickAiMode  // 记录发送时的模式
)

/**
 * 文本选择快捷 AI/Agent 对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiQuickDialog(
    selectedText: String,
    initialMode: QuickAiMode = QuickAiMode.AI_QUERY,
    onDismiss: () -> Unit,
    onApplyEdit: (String) -> Unit,
    allowEditSelectedText: Boolean = false,
    viewModel: AiQuickViewModel = hiltViewModel()
) {
    val userInput by viewModel.userInput.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()
    val conversationHistory by viewModel.conversationHistory.collectAsState()
    val hasMessages by viewModel.hasMessages.collectAsState()
    var editableSelectedText by remember { mutableStateOf(selectedText) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (viewModel.shouldRestore(selectedText)) {
            // 相同的选中文本，恢复之前的状态
        } else {
            // 不同的选中文本，重置并设置新的初始模式
            viewModel.reset()
            viewModel.setMode(initialMode)
        }
    }

    LaunchedEffect(editableSelectedText) {
        viewModel.setSelectedText(editableSelectedText)
    }

    // 自动滚动到最新消息
    LaunchedEffect(conversationHistory.size) {
        if (conversationHistory.isNotEmpty()) {
            listState.animateScrollToItem(conversationHistory.size - 1)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 处理返回按钮
    BackHandler(enabled = true) {
        if (userInput.isNotBlank() && !hasMessages && !isLoading) {
            showExitConfirmDialog = true
        } else {
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .navigationBarsPadding()
        ) {
            // 标题和模式切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "✨ AI 助手",
                    style = MaterialTheme.typography.titleLarge
                )

                // 模式切换按钮（加载时禁用，完成后可切换）
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = if (isLoading) 0.3f else 0.5f
                    )
                ) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        FilterChip(
                            selected = currentMode == QuickAiMode.AI_QUERY,
                            onClick = { viewModel.setMode(QuickAiMode.AI_QUERY) },
                            label = { Text("💬 询问", style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.height(32.dp),
                            enabled = !isLoading
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        FilterChip(
                            selected = currentMode == QuickAiMode.AGENT_EDIT,
                            onClick = { viewModel.setMode(QuickAiMode.AGENT_EDIT) },
                            label = { Text("🤖 处理", style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.height(32.dp),
                            enabled = !isLoading
                        )
                    }
                }
            }

            HorizontalDivider()

            // 对话区域
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                state = listState
            ) {
                // 显示选中的文本（始终显示在顶部）
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (currentMode == QuickAiMode.AI_QUERY) "关于：" else "选中的文本：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (allowEditSelectedText) {
                        OutlinedTextField(
                            value = editableSelectedText,
                            onValueChange = { editableSelectedText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            minLines = 3,
                            maxLines = 6,
                            placeholder = { Text("粘贴或输入要处理的文本...") }
                        )
                    } else {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = editableSelectedText,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    if (hasMessages) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }

                // 对话历史
                items(conversationHistory) { message ->
                    MessageBubble(
                        message = Message(
                            conversationId = "",
                            role = message.role,
                            content = message.content
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // 加载指示器
                if (isLoading) {
                    item {
                        Row(
                            modifier = Modifier.padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (currentMode == QuickAiMode.AI_QUERY) "AI 思考中..." else "Agent 处理中...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 错误提示
            error?.let {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            HorizontalDivider()

            // 底部输入框和按钮
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // 输入框
                OutlinedTextField(
                    value = userInput,
                    onValueChange = { viewModel.updateUserInput(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            if (currentMode == QuickAiMode.AI_QUERY) "输入你的问题..."
                            else "例如：改写成更专业的表达"
                        )
                    },
                    minLines = 1,
                    maxLines = 3,
                    enabled = !isLoading,
                    trailingIcon = {
                        if (!isLoading && userInput.isNotBlank()) {
                            IconButton(onClick = { viewModel.send() }) {
                                Icon(Icons.AutoMirrored.Filled.Send, "发送")
                            }
                        }
                    }
                )

                // Agent 模式下的"应用最新修改"按钮
                // 只显示最后一条 Agent 模式生成的消息
                if (currentMode == QuickAiMode.AGENT_EDIT && conversationHistory.isNotEmpty()) {
                    val lastAgentMessage = conversationHistory
                        .filter { it.role == MessageRole.ASSISTANT && it.mode == QuickAiMode.AGENT_EDIT }
                        .lastOrNull()

                    if (lastAgentMessage != null && !isLoading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                onApplyEdit(lastAgentMessage.content)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("应用最新修改")
                        }
                    }
                }
            }
        }
    }

    // 退出确认对话框
    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("确认退出") },
            text = { Text("你有未发送的内容，确定要退出吗？退出后内容将被保留，下次打开可以继续编辑。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirmDialog = false
                        onDismiss()
                    }
                ) {
                    Text("退出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmDialog = false }) {
                    Text("继续编辑")
                }
            }
        )
    }
}

@HiltViewModel
class AiQuickViewModel @Inject constructor(
    private val configRepository: AiConfigRepository,
    private val adapterFactory: AiAdapterFactory
) : ViewModel() {

    private val _userInput = MutableStateFlow("")
    val userInput: StateFlow<String> = _userInput.asStateFlow()

    private val _conversationHistory = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val conversationHistory: StateFlow<List<ConversationMessage>> = _conversationHistory.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentMode = MutableStateFlow(QuickAiMode.AI_QUERY)
    val currentMode: StateFlow<QuickAiMode> = _currentMode.asStateFlow()

    val hasMessages: StateFlow<Boolean> = MutableStateFlow(false).apply {
        viewModelScope.launch {
            _conversationHistory.collect { history ->
                value = history.isNotEmpty()
            }
        }
    }.asStateFlow()

    private var selectedText = ""
    private var lastSelectedText = ""

    fun setSelectedText(text: String) {
        selectedText = text
    }

    fun setMode(quickMode: QuickAiMode) {
        // 只要不在加载中就可以切换模式
        if (!_isLoading.value) {
            _currentMode.value = quickMode
        }
    }

    fun updateUserInput(input: String) {
        _userInput.value = input
    }

    fun shouldRestore(currentSelectedText: String): Boolean {
        return currentSelectedText == lastSelectedText && lastSelectedText.isNotEmpty()
    }

    fun reset() {
        lastSelectedText = selectedText
        _userInput.value = ""
        _conversationHistory.value = emptyList()
        _isLoading.value = false
        _error.value = null
    }

    fun send() {
        if (_userInput.value.isBlank() || _isLoading.value) return

        val userMessage = _userInput.value
        val currentModeSnapshot = _currentMode.value

        viewModelScope.launch {
            // 添加用户消息到历史记录
            _conversationHistory.value = _conversationHistory.value + ConversationMessage(
                role = MessageRole.USER,
                content = userMessage,
                mode = currentModeSnapshot
            )

            // 清空输入框
            _userInput.value = ""
            _isLoading.value = true
            _error.value = null

            try {
                val config = configRepository.observeConfig().first()
                if (config.apiKey.isBlank() || config.modelName.isBlank()) {
                    _error.value = "请先在设置中配置 API Key 和模型"
                    _isLoading.value = false
                    return@launch
                }

                val adapter = adapterFactory.createAdapter(config)

                // 构建消息
                val systemPrompt = buildSystemPrompt(currentModeSnapshot)
                val fullUserMessage = buildUserMessage(userMessage, currentModeSnapshot)

                val messages = listOf(
                    ChatMessage(role = "user", content = fullUserMessage)
                )

                // 流式接收回复
                val fullResponse = StringBuilder()
                adapter.sendChatStream(
                    messages,
                    AiRequestConfig(
                        model = config.modelName,
                        temperature = config.temperature,
                        maxTokens = config.maxTokens,
                        systemPrompt = systemPrompt
                    )
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            fullResponse.append(event.text)
                            // 临时更新最后一条消息（流式显示）
                            val currentHistory = _conversationHistory.value
                            val lastMessage = currentHistory.lastOrNull()
                            if (lastMessage?.role == MessageRole.ASSISTANT) {
                                // 更新现有的 AI 消息
                                _conversationHistory.value = currentHistory.dropLast(1) + ConversationMessage(
                                    role = MessageRole.ASSISTANT,
                                    content = fullResponse.toString(),
                                    mode = currentModeSnapshot
                                )
                            } else {
                                // 添加新的 AI 消息
                                _conversationHistory.value = currentHistory + ConversationMessage(
                                    role = MessageRole.ASSISTANT,
                                    content = fullResponse.toString(),
                                    mode = currentModeSnapshot
                                )
                            }
                        }
                        is StreamEvent.Done -> {
                            val finalText = event.fullText.ifBlank { fullResponse.toString() }
                            // 确保最后一条消息是完整的
                            val currentHistory = _conversationHistory.value
                            _conversationHistory.value = currentHistory.dropLast(1) + ConversationMessage(
                                role = MessageRole.ASSISTANT,
                                content = finalText,
                                mode = currentModeSnapshot
                            )
                            _isLoading.value = false
                        }
                        is StreamEvent.Error -> {
                            _error.value = event.message
                            _isLoading.value = false
                        }
                    }
                }
            } catch (e: Exception) {
                _error.value = "发生错误: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    private fun buildSystemPrompt(mode: QuickAiMode): String {
        return when (mode) {
            QuickAiMode.AI_QUERY -> """
                你是一个有帮助的 AI 助手。用户选中了一段文本并向你提问。
                请根据用户的问题，结合选中的文本内容，给出清晰、准确的回答。
                使用 Markdown 格式组织回复。
            """.trimIndent()

            QuickAiMode.AGENT_EDIT -> """
                你是一个文本编辑助手。用户选中了一段文本，并提出了修改需求。
                请根据用户的需求，对文本进行修改，只输出修改后的文本内容，不要有其他说明。
                保持原文的格式和风格，只按用户要求进行必要的修改。
            """.trimIndent()
        }
    }

    private fun buildUserMessage(userMessage: String, mode: QuickAiMode): String {
        return when (mode) {
            QuickAiMode.AI_QUERY -> """
                选中的文本：
                ```
                $selectedText
                ```

                我的问题：
                $userMessage
            """.trimIndent()

            QuickAiMode.AGENT_EDIT -> """
                选中的文本：
                ```
                $selectedText
                ```

                修改需求：
                $userMessage

                请直接输出修改后的文本，不要有其他说明。
            """.trimIndent()
        }
    }
}
