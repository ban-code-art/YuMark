package com.yumark.app.presentation.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.StreamEvent
import com.yumark.app.domain.repository.AiConfigRepository
import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.presentation.ai.agent.AgentActionCard
import com.yumark.app.presentation.ai.common.MessageBubble
import com.yumark.app.presentation.common.isNearBottom
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class QuickAiMode {
    AI_QUERY,    // 询问 AI（只读显示）
    AGENT_EDIT   // Agent 处理（可应用修改）
}

/** 注入 system prompt 的文档上下文字符预算。超过则截取并提示模型已截断。 */
private const val DOC_CONTEXT_CHAR_BUDGET = 12000

/**
 * 对话消息数据类
 */
data class ConversationMessage(
    val role: MessageRole,
    val content: String,
    val mode: QuickAiMode,  // 记录发送时的模式
    val editContent: String? = null,   // AI 提议的改写文本；仅当确实是「编辑」意图时非空
    val editStatus: AgentActionStatus? = null  // 「应用修改」状态
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
    onApplyEdit: (oldText: String, newText: String) -> Unit,
    allowEditSelectedText: Boolean = false,
    documentName: String? = null,
    documentContent: String? = null,
    viewModel: AiQuickViewModel = hiltViewModel()
) {
    val userInput by viewModel.userInput.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val currentMode by viewModel.currentMode.collectAsStateWithLifecycle()
    val conversationHistory by viewModel.conversationHistory.collectAsStateWithLifecycle()
    val hasMessages by viewModel.hasMessages.collectAsStateWithLifecycle()
    var editableSelectedText by remember { mutableStateOf(selectedText) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 打开（或换了新选区）时，由 ViewModel 原子决定恢复上次会话还是重置
    LaunchedEffect(selectedText) {
        viewModel.onOpen(selectedText, initialMode)
        editableSelectedText = selectedText
    }

    LaunchedEffect(editableSelectedText) {
        viewModel.setSelectedText(editableSelectedText)
    }

    // 把当前文档全文作为上下文同步给 ViewModel(询问/处理都会带上)
    LaunchedEffect(documentName, documentContent) {
        viewModel.setDocumentContext(documentName, documentContent)
    }

    // 自动滚动到最新消息。
    // LazyColumn 顶部有一个固定 item(选中文本块)占索引 0,消息从 1 起;加载时末项是指示器。
    // 用 scrollToItem(瞬时)而非 animateScrollToItem：流式每个 token 都会触发本 effect，
    // 动画会被不断取消重启、产生抖动；瞬时跳转更稳定。
    // 仅在「已接近底部」时跟随：用户向上翻阅历史时不被强行拽回底部。
    LaunchedEffect(conversationHistory.size, conversationHistory.lastOrNull()?.content, isLoading) {
        if (conversationHistory.isEmpty()) return@LaunchedEffect
        if (listState.isNearBottom()) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
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
                itemsIndexed(conversationHistory) { index, message ->
                    // 仅当 AI 确实给出改写(editContent 非空)时才挂「应用修改」卡片；
                    // 纯提问/总结不会有 editContent,因此不弹卡片。
                    val edit = message.editContent
                    val showEditCard = message.role == MessageRole.ASSISTANT &&
                        edit != null &&
                        !(index == conversationHistory.lastIndex && isLoading)

                    MessageBubble(
                        message = Message(
                            conversationId = "",
                            role = message.role,
                            content = message.content
                        ),
                        modifier = Modifier.padding(vertical = 4.dp),
                        extraContent = if (showEditCard && edit != null) {
                            {
                                AgentActionCard(
                                    action = AgentAction(
                                        type = AgentActionType.EDIT_DOCUMENT,
                                        description = "按你的要求改写选中文本",
                                        content = edit,
                                        status = message.editStatus ?: AgentActionStatus.PENDING
                                    ),
                                    baseContent = editableSelectedText,
                                    onApproveDiff = { finalContent ->
                                        onApplyEdit(editableSelectedText, finalContent)
                                        viewModel.setEditStatus(index, AgentActionStatus.EXECUTED)
                                    },
                                    onReject = {
                                        viewModel.setEditStatus(index, AgentActionStatus.REJECTED)
                                    }
                                )
                            }
                        } else null
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

    val hasMessages: StateFlow<Boolean> = _conversationHistory
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var selectedText = ""
    private var lastSelectedText = ""
    private var documentName: String? = null
    private var documentContent: String? = null

    fun setSelectedText(text: String) {
        selectedText = text
    }

    /** 注入当前文档全文,作为询问/处理的背景上下文。 */
    fun setDocumentContext(name: String?, content: String?) {
        documentName = name
        documentContent = content
    }

    /** 更新某条处理模式助手消息的「应用修改」状态(待确认 → 已执行/已拒绝)。 */
    fun setEditStatus(index: Int, status: AgentActionStatus) {
        val current = _conversationHistory.value
        if (index in current.indices) {
            _conversationHistory.value = current.toMutableList().also {
                it[index] = it[index].copy(editStatus = status)
            }
        }
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

    /**
     * 对话框打开（或换了新选区）时调用：相同选中文本则保留上次会话（历史+模式），
     * 否则按新选区重置。原子完成，避免分散在多个 LaunchedEffect 里因执行时序
     * 导致 lastSelectedText 记成上一次选区。
     */
    fun onOpen(currentSelected: String, initialMode: QuickAiMode) {
        selectedText = currentSelected
        val sameSelection = currentSelected == lastSelectedText && lastSelectedText.isNotEmpty()
        if (!sameSelection) {
            lastSelectedText = currentSelected
            _userInput.value = ""
            _conversationHistory.value = emptyList()
            _isLoading.value = false
            _error.value = null
            _currentMode.value = initialMode
        }
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
                            // 处理模式下流式显示时去掉 [[EDIT]] 标记,避免标记一闪而过
                            val display = if (currentModeSnapshot == QuickAiMode.AGENT_EDIT)
                                stripEditMarkers(fullResponse.toString())
                            else fullResponse.toString()
                            // 临时更新最后一条消息（流式显示）
                            val currentHistory = _conversationHistory.value
                            val lastMessage = currentHistory.lastOrNull()
                            if (lastMessage?.role == MessageRole.ASSISTANT) {
                                // 更新现有的 AI 消息
                                _conversationHistory.value = currentHistory.dropLast(1) + ConversationMessage(
                                    role = MessageRole.ASSISTANT,
                                    content = display,
                                    mode = currentModeSnapshot
                                )
                            } else {
                                // 添加新的 AI 消息
                                _conversationHistory.value = currentHistory + ConversationMessage(
                                    role = MessageRole.ASSISTANT,
                                    content = display,
                                    mode = currentModeSnapshot
                                )
                            }
                        }
                        is StreamEvent.ToolCallDelta -> Unit  // Quick Dialog暂不使用工具调用
                        is StreamEvent.ToolCallComplete -> Unit
                        is StreamEvent.Done -> {
                            val finalText = event.fullText.ifBlank { fullResponse.toString() }
                            // 处理模式:解析 AI 是否给出改写。
                            // 优先按 [[EDIT]] 标记解析(compliant 模型)；
                            // 无标记时走兜底启发式:回复「看起来像改写」则整段作为 editContent(救援忽略标记的弱模型)，
                            // 否则按普通问答/总结处理(不挂卡片)。纯问答/总结不挂卡片。
                            val edit = if (currentModeSnapshot == QuickAiMode.AGENT_EDIT)
                                resolveEdit(finalText, selectedText) else null
                            val display = when {
                                edit != null -> edit
                                currentModeSnapshot == QuickAiMode.AGENT_EDIT -> stripEditMarkers(finalText)
                                else -> finalText
                            }
                            // 确保最后一条消息是完整的
                            val currentHistory = _conversationHistory.value
                            _conversationHistory.value = currentHistory.dropLast(1) + ConversationMessage(
                                role = MessageRole.ASSISTANT,
                                content = display,
                                mode = currentModeSnapshot,
                                editContent = edit,
                                editStatus = if (edit != null) AgentActionStatus.PENDING else null
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
        // 当前文档全文作为背景上下文；过长则按字符预算截取，避免撑爆模型上下文窗口。
        val docContext = documentContent?.takeIf { it.isNotBlank() }?.let { content ->
            val truncated = content.take(DOC_CONTEXT_CHAR_BUDGET)
            val truncatedNote =
                if (truncated.length < content.length)
                    "\n（注意：文档过长，以上仅截取前 ${DOC_CONTEXT_CHAR_BUDGET} 字作为上下文参考。）"
                else ""
            val nameLine = documentName?.takeIf { it.isNotBlank() }?.let { "文档名称：$it\n" } ?: ""
            "\n\n以下是用户当前文档的完整内容，作为回答/修改的背景参考：\n" +
                "$nameLine```\n$truncated\n```$truncatedNote"
        } ?: ""

        return when (mode) {
            QuickAiMode.AI_QUERY -> """
                你是一个有帮助的 AI 助手。用户选中了一段文本并向你提问。
                请根据用户的问题，结合选中的文本内容以及下方提供的文档全文，给出清晰、准确的回答。
                使用 Markdown 格式组织回复。
            """.trimIndent() + docContext

            QuickAiMode.AGENT_EDIT -> """
                你是一个文本编辑助手。用户选中了一段文本（见对话），可能要你「修改/改写/润色/翻译/扩写/精简」它，也可能只是「提问/总结/解释」。请先判断用户意图：

                - 若用户要求**修改选中文本**：只输出修改后的【完整文本】，并严格用如下标记包裹（标记各占一行，标记外不要写任何解释，也不要用代码块包裹）：
                [[EDIT]]
                （这里放改写后的完整文本）
                [[/EDIT]]
                - 若用户只是**提问/总结/解释**、并不需要替换选中文本：正常用 Markdown 回答，**不要**输出 [[EDIT]] 标记。

                保持原文的格式与风格，只按用户要求做必要修改。
            """.trimIndent() + docContext
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

                我的需求：
                $userMessage
            """.trimIndent()
        }
    }

    /** 去掉处理模式回复里的 [[EDIT]]/[[/EDIT]] 标记,用于气泡展示。 */
    private fun stripEditMarkers(text: String): String =
        text.replace("[[EDIT]]", "").replace("[[/EDIT]]", "").trim()

    /** 解析 [[EDIT]]...[[/EDIT]] 包裹的改写文本;无标记返回 null(表示这是普通问答/总结,不挂卡片)。 */
    private fun parseEditContent(text: String): String? {
        val start = text.indexOf("[[EDIT]]")
        if (start < 0) return null
        val afterStart = start + "[[EDIT]]".length
        val end = text.indexOf("[[/EDIT]]", afterStart)
        val inner = if (end >= 0) text.substring(afterStart, end) else text.substring(afterStart)
        return inner.trim().ifBlank { null }
    }

    /**
     * 处理模式下决定回复是否应作为「可应用改写」。
     *
     * 1) 优先按 [[EDIT]] 标记解析(compliant 模型)。
     * 2) 无标记时走兜底启发式:仅当回复「看起来像改写」才把整段作为 editContent
     *    ——救援忽略标记指令的弱模型;其余(明显是问答/总结/解释)按普通消息处理,不挂卡片。
     */
    private fun resolveEdit(text: String, selected: String): String? {
        parseEditContent(text)?.let { return it }
        val reply = text.trim()
        if (!looksLikeRewrite(reply, selected.trim())) return null
        return reply.ifBlank { null }
    }

    /**
     * 兜底判定:回复是否「看起来像直接改写」而非问答/总结。
     *
     * - 含明显解释性结构(标题行、解释性引导语)→ 视为问答/总结,抑制。
     * - 长度远超选区(>2.5× 且选区非平凡)→ 视为扩写型解释,抑制。
     * - 其余视为改写。
     *
     * 偏向保守:拿不准时倾向当作改写,以救援弱模型的改写;但因有结构/长度双闸,
     * 典型的长篇总结仍不会误弹卡片。
     */
    private fun looksLikeRewrite(reply: String, selected: String): Boolean {
        if (reply.isBlank()) return false
        if (hasExplanationStructure(reply)) return false
        if (selected.length > 16 && reply.length > selected.length * 2.5f) return false
        return true
    }

    /** 高精度识别「明显是解释/总结而非改写」的结构信号。 */
    private fun hasExplanationStructure(reply: String): Boolean {
        val firstLine = reply.lineSequence().firstOrNull()?.trim().orEmpty()
        // 以 Markdown 标题开头 → 几乎不是直接改写
        if (firstLine.startsWith("#")) return true
        // 解释性引导语
        val cues = listOf(
            "以下是", "建议如下", "总结一下", "总结：", "总结:", "原因如下", "修改建议",
            "这段话", "这段文字", "这段文本", "选中的文本", "选中的文本",
            "我建议", "可以这样修改", "作为一个", "作为一"
        )
        if (cues.any { reply.contains(it) }) return true
        return false
    }
}
