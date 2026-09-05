package com.yumark.app.presentation.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.R
import com.yumark.app.core.util.AiErrorMapper
import com.yumark.app.core.util.UiMessage
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.AiRequestConfig
import com.yumark.app.domain.model.AiTool
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.StreamEvent
import com.yumark.app.domain.repository.AiConfigRepository
import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.presentation.ai.agent.AgentActionCard
import com.yumark.app.presentation.ai.common.MessageBubble
import com.yumark.app.presentation.common.resolveOrNull
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppShapes
import com.yumark.app.presentation.theme.AppSpacing
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

enum class QuickAiMode {
    AI_QUERY,    // 询问 AI（只读显示）
    AGENT_EDIT   // Agent 处理（可应用修改）
}

/** 注入 system prompt 的文档上下文字符预算。超过则截取并提示模型已截断。 */
private const val DOC_CONTEXT_CHAR_BUDGET = 12000

/**
 * 修改通道的触发词：独立成词的 `yy`（大小写不敏感）。
 *
 * 授权判定与「剥离后交给模型的指令」**必须共用这一个 Regex**。从前授权用
 * `lowercase().contains("yy")`、剥离用 `Regex("(?i)yy")` 全局替换，两套规则各说一套：
 * 「把日期格式改成 yyyy-MM-dd」既会被误判成授权修改，还会被剥成「把日期格式改成 -MM-dd」——
 * 模型收到的需求已经被改坏，输出必然是错的，而用户完全看不出发生了什么。
 *
 * 前后各一个拉丁字母的否定环视，是为了只放行「yy」这个独立触发词：`yyyy`、`yyyy-MM-dd`、
 * `myyy` 都不匹配，而中文紧贴着写的「这段yy改写」照样匹配（CJK 不是 `[a-z]`），
 * 因为用户就是这么打字的。`(?i)` 让环视里的 `[a-z]` 同时挡住大写。
 */
private val EDIT_TRIGGER = Regex("(?i)(?<![a-z])yy(?![a-z])")

/**
 * 回放给模型的历史条数上限（不含本轮新消息）。
 *
 * 划词对话是「就这一段文本来回几轮」的场景，10 条足够覆盖真实追问深度；再往上加只是把
 * 早已被后续轮次覆盖掉的内容重新塞进上下文窗口，挤掉 system prompt 里的文档全文。
 */
private const val HISTORY_MAX_MESSAGES = 10

/**
 * 回放历史的字符预算。
 *
 * 与 [DOC_CONTEXT_CHAR_BUDGET] 分开算：文档全文在 system prompt 里，历史在 messages 里，
 * 两者加起来才是一次请求的体积。6000 字约等于 3–4k token，留给文档 12000 字之后仍有余量。
 * 超预算时从**最旧**的一端丢（见 [AiQuickViewModel.buildRequestMessages]），因为最近的追问
 * 才是模型必须看懂的那部分。
 */
private const val HISTORY_CHAR_BUDGET = 6000

/**
 * 划词编辑工具：用新文本替换用户选中的文本。与主 Agent 的 edit_document 一致走函数调用，
 * 比纯文本协议（[[EDIT]] 标记）可靠——删除传空串、润色直接给新文本，模型结构化返回。
 * 弱端点不支持函数调用时，仍回退到 [[EDIT]] 文本协议。
 */
private val APPLY_EDIT_TOOL = AiTool(
    name = "apply_edit",
    description = "用新文本替换用户选中的文本。用户要求修改/改写/润色/翻译/扩写/精简/删除选中文本时调用。" +
        "new_text 给出改写后的完整文本；删除选中文本则 new_text 传空字符串。" +
        "不要在回复正文里重复输出改后的文本——调用本工具即可。",
    parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "new_text" to mapOf(
                "type" to "string",
                "description" to "替换选中文本的新文本；删除时传空字符串"
            ),
            "summary" to mapOf(
                "type" to "string",
                "description" to "一句话说明改了什么（可选，仅展示用）"
            )
        ),
        "required" to listOf("new_text")
    )
)

/**
 * 对话消息数据类
 */
data class ConversationMessage(
    val id: String = java.util.UUID.randomUUID().toString(),  // 稳定身份：流式更新时保留，供列表 key 与高度缓存命中
    val role: MessageRole,
    val content: String,
    val mode: QuickAiMode,  // 记录发送时的模式
    val editContent: String? = null,   // AI 提议的改写文本；仅当确实是「编辑」意图时非空
    val editStatus: AgentActionStatus? = null  // 「应用修改」状态
)

/**
 * 本屏特有的两处组件尺寸，刻意不并入全局 [AppSpacing] / [AppIconSize] 标度：它们是「组件自身
 * 尺寸」而非「间距」——分段控件的紧凑行高、小尺寸转圈的描边宽，语义上与间距标度是两回事，
 * 按 FileListMetrics / AiConfigMetrics 的先例落在屏幕局部。圆角（20 / 8dp）则留给跨屏统一的
 * 形状 token 一次性收敛，不在此就地命名，免得那趟又得把它们搬出去。
 */
private object AiQuickMetrics {
    /** 模式切换分段控件的紧凑行高（与 M3 FilterChip 默认一致，显式钉住避免被父布局拉伸）。 */
    val ModeSwitchChipHeight = 32.dp

    /** 思考中小转圈的描边宽（20dp 小尺寸 spinner，比 M3 默认 4dp 细才协调）。 */
    val ThinkingStrokeWidth = 2.dp
}

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
    val scrollState = rememberScrollState()
    // 自动跟随到底部：流式输出时持续滚到最新内容；用户上滑查看历史时停止跟随。
    var autoScroll by remember { mutableStateOf(true) }
    // 标记「程序化滚动」，屏蔽其间的滚动检测，避免误判为用户上滑而关闭跟随。
    var programmaticScroll by remember { mutableStateOf(false) }

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

    // 仅在「用户发起的滚动」时切换跟随状态。
    // 非懒 Column + ScrollState：maxValue 即"可滚到底的距离"，不依赖 item 高度，
    // 也就没有 WebView 异步高度塌缩导致跳顶的问题。nearBottom = 已贴近 maxValue。
    val bottomThreshold = 220
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress to (scrollState.value >= scrollState.maxValue - bottomThreshold) }
            .collect { (scrolling, nearBottom) ->
                if (programmaticScroll) return@collect
                when {
                    scrolling && !nearBottom -> autoScroll = false   // 用户向上拖离底部
                    !scrolling && nearBottom -> autoScroll = true    // 静止且回到底部
                }
            }
    }

    // 跟随最新内容：直接滚到底（maxValue）。无需 scrollToItem/overflow——ScrollState 的 maxValue
    // 反映全部已组合内容的高度，无回收、无异步重测，不会跳顶。
    LaunchedEffect(conversationHistory.size, conversationHistory.lastOrNull()?.content, isLoading) {
        if (conversationHistory.isEmpty() && !isLoading) return@LaunchedEffect
        if (!autoScroll) return@LaunchedEffect
        programmaticScroll = true
        try {
            scrollState.scrollTo(scrollState.maxValue)
        } finally {
            programmaticScroll = false
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
                    .padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Cozy),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.ai_quick_title),
                    style = MaterialTheme.typography.titleLarge
                )

                // 模式切换按钮（加载时禁用，完成后可切换）
                Surface(
                    shape = RoundedCornerShape(AppShapes.Large),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = if (isLoading) 0.3f else 0.5f
                    )
                ) {
                    Row(modifier = Modifier.padding(AppSpacing.Tight)) {
                        FilterChip(
                            selected = currentMode == QuickAiMode.AI_QUERY,
                            onClick = { viewModel.setMode(QuickAiMode.AI_QUERY) },
                            label = {
                                Text(
                                    stringResource(R.string.ai_quick_mode_ask),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            modifier = Modifier.height(AiQuickMetrics.ModeSwitchChipHeight),
                            enabled = !isLoading
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Tight))
                        FilterChip(
                            selected = currentMode == QuickAiMode.AGENT_EDIT,
                            onClick = { viewModel.setMode(QuickAiMode.AGENT_EDIT) },
                            label = {
                                Text(
                                    stringResource(R.string.ai_quick_mode_agent),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            modifier = Modifier.height(AiQuickMetrics.ModeSwitchChipHeight),
                            enabled = !isLoading
                        )
                    }
                }
            }

            // 处理模式说明：仅输入含 yy 才改写选中文本，否则仅作答
            if (currentMode == QuickAiMode.AGENT_EDIT) {
                Text(
                    text = stringResource(R.string.ai_quick_agent_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Tight)
                )
            }

            HorizontalDivider()

            // 对话区域
            // 非懒 Column + verticalScroll：不回收条目，WebView 不被重建 → 无异步高度塌缩跳顶、无白屏。
            // 划词对话消息数有限，全量组合的内存代价可接受。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = AppSpacing.Screen)
            ) {
                // 显示选中的文本（始终显示在顶部）
                Spacer(modifier = Modifier.height(AppSpacing.Screen))
                Text(
                    text = if (currentMode == QuickAiMode.AI_QUERY) {
                        stringResource(R.string.ai_quick_about_label)
                    } else {
                        stringResource(R.string.ai_quick_selection_label)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (allowEditSelectedText) {
                    OutlinedTextField(
                        value = editableSelectedText,
                        onValueChange = { editableSelectedText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppSpacing.Default),
                        minLines = 3,
                        maxLines = 6,
                        placeholder = { Text(stringResource(R.string.ai_quick_selection_placeholder)) }
                    )
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppSpacing.Default),
                        shape = RoundedCornerShape(AppShapes.Small),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = editableSelectedText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(AppSpacing.Cozy)
                        )
                    }
                }

                if (hasMessages) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = AppSpacing.Default))
                }

                // 对话历史
                // 卡片说明文案在循环外先取好：省掉每条消息重复查一次资源，
                // 也不必依赖 extraContent lambda 的 @Composable 作用域。
                val editCardDescription = stringResource(R.string.ai_quick_edit_action_desc)
                conversationHistory.forEachIndexed { index, message ->
                    // 仅当 AI 确实给出改写(editContent 非空)时才挂「应用修改」卡片；
                    // 纯提问/总结不会有 editContent,因此不弹卡片。
                    val edit = message.editContent
                    val showEditCard = message.role == MessageRole.ASSISTANT &&
                        edit != null &&
                        !(index == conversationHistory.lastIndex && isLoading)

                    MessageBubble(
                        message = Message(
                            id = message.id,
                            conversationId = "",
                            role = message.role,
                            content = message.content
                        ),
                        modifier = Modifier.padding(vertical = AppSpacing.Tight),
                        extraContent = if (showEditCard) {
                            {
                                AgentActionCard(
                                    action = AgentAction(
                                        type = AgentActionType.EDIT_DOCUMENT,
                                        description = editCardDescription,
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
                    Row(
                        modifier = Modifier.padding(vertical = AppSpacing.Screen),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(AppIconSize.Medium),
                            strokeWidth = AiQuickMetrics.ThinkingStrokeWidth
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Cozy))
                        Text(
                            text = if (currentMode == QuickAiMode.AI_QUERY) {
                                stringResource(R.string.ai_quick_thinking)
                            } else {
                                stringResource(R.string.ai_quick_agent_working)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 错误提示：ViewModel 里调不了 stringResource，本模块自己的文案与 core/domain 层
            // 给过来的 UiMessage 都是延迟解析的，统一在这里落成字符串。
            val errorText = error.resolveOrNull()
            errorText?.let {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Default),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(AppShapes.Small)
                ) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(AppSpacing.Cozy)
                    )
                }
            }

            HorizontalDivider()

            // 底部输入框和按钮
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Screen)
            ) {
                // 输入框
                OutlinedTextField(
                    value = userInput,
                    onValueChange = { viewModel.updateUserInput(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            if (currentMode == QuickAiMode.AI_QUERY) {
                                stringResource(R.string.ai_quick_input_hint_ask)
                            } else {
                                // 提示里的 yy 是功能触发词，任何语言下都保持原样，不要翻译
                                stringResource(R.string.ai_quick_input_hint_agent)
                            }
                        )
                    },
                    minLines = 1,
                    maxLines = 3,
                    trailingIcon = {
                        if (isLoading) {
                            // 思考中可手动中断，与外部 AI/Agent 一致
                            IconButton(onClick = { viewModel.stop() }) {
                                Icon(Icons.Filled.Stop, stringResource(R.string.cd_ai_quick_stop))
                            }
                        } else if (userInput.isNotBlank()) {
                            IconButton(onClick = {
                                autoScroll = true   // 发送新消息 → 恢复跟随，确保能看到回复
                                viewModel.send()
                            }) {
                                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.cd_ai_quick_send))
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
            title = { Text(stringResource(R.string.ai_quick_exit_title)) },
            text = { Text(stringResource(R.string.ai_quick_exit_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirmDialog = false
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.ai_quick_exit_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmDialog = false }) {
                    Text(stringResource(R.string.ai_quick_exit_dismiss))
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

    // 错误来源有三种：本模块自己的文案、[AiErrorMapper] 的映射结果、[StreamEvent.Error] 透传的
    // 适配层文案——三者现在都是 [UiMessage]（带资源 id 或已成句的 Raw），本类不再自己包壳。
    // ViewModel 里拿不到 Context 也不该拿，解析统一放在 Composable 侧。
    private val _error = MutableStateFlow<UiMessage?>(null)
    val error: StateFlow<UiMessage?> = _error.asStateFlow()

    private val _currentMode = MutableStateFlow(QuickAiMode.AI_QUERY)
    val currentMode: StateFlow<QuickAiMode> = _currentMode.asStateFlow()

    val hasMessages: StateFlow<Boolean> = _conversationHistory
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var selectedText = ""
    private var lastSelectedText = ""
    private var documentName: String? = null
    private var documentContent: String? = null

    /** 当前流式协程；用户中断时据此取消。 */
    private var sendJob: Job? = null

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

    /** 用户在思考过程中点击中断：取消本轮流式，半截回复保留在历史里，状态复位。 */
    fun stop() {
        sendJob?.cancel()
        sendJob = null
        _isLoading.value = false
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
        // 本轮之前的对话，必须在追加这条用户消息**之前**抓快照：下面 launch 里第一件事就是把
        // 新消息塞进 _conversationHistory，之后再读就会把本轮消息当成历史发两遍。
        val priorHistory = _conversationHistory.value
        // 仅「处理」模式 + 输入含独立的 yy（大小写不敏感）才授权修改通道：下发 apply_edit 工具、
        // 挂「应用修改」卡片。其余情况纯文本输出，不调工具、不修改选中文本。
        val editAuthorized = currentModeSnapshot == QuickAiMode.AGENT_EDIT &&
            EDIT_TRIGGER.containsMatchIn(userMessage)
        // 剥离 yy，得到传给 AI 的干净指令；剥离后为空（用户只输入了 yy）则用占位。
        // 与授权判定共用 [EDIT_TRIGGER]，两套规则不会再走岔。
        val instructionForAi = userMessage.replace(EDIT_TRIGGER, "").trim()
            .ifBlank { "请改写选中文本" }

        sendJob = viewModelScope.launch {
            // 添加用户消息到历史记录（显示原始输入，含 yy）
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
                    _error.value = UiMessage.Res(R.string.ai_quick_error_no_config)
                    _isLoading.value = false
                    return@launch
                }

                val adapter = adapterFactory.createAdapter(config)

                // 构建消息：传给 AI 的是剥离 yy 后的指令
                val systemPrompt = buildSystemPrompt(currentModeSnapshot)
                val fullUserMessage = buildUserMessage(instructionForAi, currentModeSnapshot)

                // 带上前几轮对话：从前这里只发本轮一条，界面上是多轮、模型看到的永远是单轮，
                // 于是「再短一点」「换个说法」这类纯追问在模型侧没有指代对象，只能瞎猜。
                val messages = buildRequestMessages(priorHistory) +
                    ChatMessage(role = "user", content = fullUserMessage)

                // 流式接收回复
                val fullResponse = StringBuilder()
                // 仅授权时下发 apply_edit；未授权时 AI 拿不到写工具，纯文本回复。
                val tools = if (editAuthorized) listOf(APPLY_EDIT_TOOL) else emptyList()
                var pendingEditNewText: String? = null
                adapter.sendChatStream(
                    messages,
                    AiRequestConfig(
                        model = config.modelName,
                        temperature = config.temperature,
                        maxTokens = config.maxTokens,
                        systemPrompt = systemPrompt
                    ),
                    tools = tools
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            fullResponse.append(event.text)
                            // 处理模式下流式显示时去掉 [[EDIT]] 标记,避免标记一闪而过
                            val display = if (currentModeSnapshot == QuickAiMode.AGENT_EDIT)
                                QuickEditHeuristics.stripEditMarkers(fullResponse.toString())
                            else fullResponse.toString()
                            // 临时更新最后一条消息（流式显示）
                            val currentHistory = _conversationHistory.value
                            val lastMessage = currentHistory.lastOrNull()
                            if (lastMessage != null && lastMessage.role == MessageRole.ASSISTANT) {
                                // 更新现有的 AI 消息（保留 id：流式期间身份稳定，列表 key 与高度缓存才能命中）
                                _conversationHistory.value = currentHistory.dropLast(1) + ConversationMessage(
                                    id = lastMessage.id,
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
                        is StreamEvent.ToolCallDelta -> Unit
                        is StreamEvent.ToolCallComplete -> {
                            // apply_edit 工具调用：取 new_text 作为改写结果（空串=删除）。
                            if (editAuthorized && pendingEditNewText == null) {
                                val call = event.calls.firstOrNull { it.name == "apply_edit" }
                                if (call != null) {
                                    QuickEditHeuristics.parseApplyEditArgs(call.arguments)?.let { pendingEditNewText = it }
                                }
                            }
                            android.util.Log.d("YuMarkQuick",
                                "ToolCallComplete mode=$currentModeSnapshot editAuthorized=$editAuthorized calls=${event.calls.map { it.name }} " +
                                    "applyEditNewText=${pendingEditNewText?.let { "len=${it.length}" }}")
                        }
                        is StreamEvent.Done -> {
                            val finalText = event.fullText.ifBlank { fullResponse.toString() }
                            // 仅授权时才可能产生 edit（工具调用 → [[EDIT]] → 兜底）；未授权恒 null，不挂卡片。
                            val parsedEdit = if (editAuthorized)
                                pendingEditNewText ?: QuickEditHeuristics.resolveEdit(finalText, selectedText, instructionForAi) else null
                            // 被输出上限砍断的回复绝不当成可应用的改写。这一路自己挡不住截断：
                            // [[EDIT]] 缺收尾标记时 parseEditContent 会一路 substring 到末尾
                            // （QuickEditHeuristics.kt:36），兜底启发式更是只看长度与结构——于是半截
                            // 改写照样挂上 PENDING 卡片，用户点「应用」就把选中的正文换成写到一半的
                            // 版本，后半截当场消失（卡片预览长了根本读不完，跟审批等于没审）。
                            // 只压卡片、不动气泡：那段半截文本仍留在对话里，用户想自取还能取。
                            val edit = if (event.truncated) null else parsedEdit
                            android.util.Log.d("YuMarkQuick",
                                "Done mode=$currentModeSnapshot editAuthorized=$editAuthorized finalTextLen=${finalText.length} " +
                                    "truncated=${event.truncated} " +
                                    "edit=${edit?.let { "len=${it.length}" } ?: "null"} " +
                                    "parsedEdit=${parsedEdit?.let { "len=${it.length}" } ?: "null"} " +
                                    "toolUsed=${pendingEditNewText != null} userMsg=${userMessage.take(40)}")
                            // 截断本身也要让人看见：正文停在半句上，不说一声用户只会以为模型就这水平。
                            if (event.truncated) {
                                _error.value = UiMessage.Res(R.string.ai_notice_response_truncated)
                            }
                            // 气泡正文与 edit 是两回事，删除时必须分开取。
                            //
                            // 约定「空串 = 删除选中内容」（见 [QuickEditHeuristics]）。直接令
                            // display = edit 时，删除这一路的 display 恒为空串，被下面的空正文守卫
                            // 判成「模型什么都没说」→ 气泡收掉、报 ai_error_empty_response、return，
                            // 于是**「应用修改」卡片永远挂不上，删除永远无法应用**，模型同轮的解释
                            // 正文也一起丢。所以空/纯空白的 edit 走解释文本；解释也没有时留空气泡
                            // ——卡片在 extraContent 里（`MessageBubble.kt:95` 在正文判空之外），
                            // 空正文照样渲染，而 `AgentActionCard` 拿空 content 出的正是「整段删掉」
                            // 的 diff，语义刚好对上。
                            val explanation = QuickEditHeuristics.stripEditMarkers(finalText)
                            val display = when {
                                edit == null ->
                                    if (currentModeSnapshot == QuickAiMode.AGENT_EDIT) explanation else finalText
                                edit.isNotBlank() -> edit
                                else -> explanation
                            }
                            // 确保最后一条消息是完整的。
                            // 纯工具调用（无正文流式）时 Content 分支不会插入助手消息，
                            // 此刻末条仍是用户消息——需追加而非替换，否则会丢掉用户消息。
                            val currentHistory = _conversationHistory.value
                            val lastAssistant = currentHistory.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
                            val lastIsAssistant = lastAssistant != null
                            if (display.isBlank() && edit == null) {
                                // 空正文的 Done 是真实结果而非异常：适配层把「重试若干次仍是空补全」
                                // 也收敛成 Done("")。挂一个空气泡等于什么都没说，用户只能自己猜是不是
                                // 模型选错了 —— 按错误提示，并且把这一轮的空气泡收掉。
                                //
                                // 必须同时要求 edit == null：edit 非 null 意味着这一轮**有**结果
                                // （空串就是「删除」），此时正文空只是模型没多说一句话，不是空回复。
                                _conversationHistory.value =
                                    if (lastIsAssistant) currentHistory.dropLast(1) else currentHistory
                                // 截断已经给过更准确的提示，且它才是这里空正文的真正成因（纯工具调用
                                // 被砍断时正文本来就是空的），不要覆盖成「AI 没有返回任何内容」——
                                // 那会把用户推去换模型，而该做的是调大 max tokens。
                                if (!event.truncated) {
                                    _error.value = UiMessage.Res(R.string.ai_error_empty_response)
                                }
                                _isLoading.value = false
                                return@collect
                            }
                            // 替换末条助手消息时保留其 id（流式期间同一轮回复身份不变，缓存/动画才连贯）
                            _conversationHistory.value =
                                (if (lastIsAssistant) currentHistory.dropLast(1) else currentHistory) + ConversationMessage(
                                    id = lastAssistant?.id ?: java.util.UUID.randomUUID().toString(),
                                    role = MessageRole.ASSISTANT,
                                    content = display,
                                    mode = currentModeSnapshot,
                                    editContent = edit,
                                    editStatus = if (edit != null) AgentActionStatus.PENDING else null
                                )
                            _isLoading.value = false
                        }
                        is StreamEvent.Error -> {
                            // 适配层给的已经是 UiMessage，原样透出即可
                            _error.value = event.message
                            _isLoading.value = false
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 必须排在 Exception 之前：CancellationException 是 IllegalStateException 的子类，
                // 被下面那条捕获后，用户点「停止」就会看到「发生错误: StandaloneCoroutine was cancelled」。
                // stop() 已经复位过 isLoading，这里只需把取消原样抛回给协程框架。
                throw e
            } catch (e: Exception) {
                // 不拼 e.message：Ktor 的异常消息里带完整请求 URL（Gemini 的密钥就在 ?key= 里）。
                _error.value = AiErrorMapper.mapException(e)
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
                你是一个文本编辑助手。用户选中了一段文本（见对话），可能要你「修改/改写/润色/翻译/扩写/精简/删除」它，也可能只是「提问/总结/解释」。请先判断用户意图：

                - 若用户要求**修改选中文本**：调用 apply_edit 工具，在 new_text 里给出改写后的完整文本（删除选中文本则 new_text 传空字符串）。可在 summary 里一句话说明改了什么。**不要在回复正文里重复输出改后的文本**——调用工具即可。
                  若所用端点不支持函数调用，改为用标记包裹（标记外不写解释，也不用代码块）：
                  [[EDIT]]
                  （改写后的完整文本；删除则留空）
                  [[/EDIT]]
                - 若用户只是**提问/总结/解释**、并不需要替换选中文本：正常用 Markdown 回答，**不要**调用 apply_edit、也不要输出 [[EDIT]] 标记。

                保持原文的格式与风格，只按用户要求做必要修改。
            """.trimIndent() + docContext
        }
    }

    /**
     * 把已有对话折成请求消息（本轮那条由调用方追加在后面）。
     *
     * 三条约束决定了这段的写法：
     * - **倒着挑、正着发**：预算不够时必须丢最旧的，留最近的——追问的指代对象在近处。
     *   所以从末尾往前累加，再翻回时间正序（模型只认时间顺序）。
     * - **首条必须是 user**：Claude 与 Gemini 都要求消息以 user 开头、user/assistant 交替。
     *   预算恰好在一条 assistant 上截断时它会成为首条，必须丢掉，否则整个请求被端点拒绝。
     * - **不回放历史里的选中文本**：本轮消息由 [buildUserMessage] 重新嵌一份最新的选区，
     *   历史里再嵌一遍只会让模型在多份「选中的文本」之间挑错对象（用户可能已经改过选区）。
     */
    private fun buildRequestMessages(history: List<ConversationMessage>): List<ChatMessage> {
        val picked = ArrayDeque<ChatMessage>()
        var budget = HISTORY_CHAR_BUDGET
        for (msg in history.asReversed().take(HISTORY_MAX_MESSAGES)) {
            val text = historyTextFor(msg)
            if (text.isBlank()) continue
            // 超预算就停，不是跳过：跳过会让更旧的短消息越过一条长消息挤进来，
            // 上下文变成跳跃的碎片，比少几轮更难读懂。
            if (text.length > budget) break
            budget -= text.length
            val role = if (msg.role == MessageRole.USER) "user" else "assistant"
            picked.addFirst(ChatMessage(role = role, content = text))
        }
        while (picked.firstOrNull()?.role == "assistant") picked.removeFirst()
        return picked.toList()
    }

    /**
     * 一条历史消息回放给模型时的文本。
     *
     * 带 [ConversationMessage.editContent] 的助手消息要特殊处理：它是上一轮提议的改写正文，
     * 展示时 `content` 已经就是这段正文（见 send 里的 `display`）。不加说明地回放，模型会把它
     * 当成自己的普通回答，「再润色一次」就无从对照；而 `[[EDIT]]` 标记不能带上——那是给解析器
     * 看的，回放进上下文会诱导模型下一轮继续输出标记而不是调工具。
     */
    private fun historyTextFor(msg: ConversationMessage): String {
        val edit = msg.editContent ?: return msg.content
        val prefix = "（上一轮提议的改写结果）\n"
        return if (msg.content == edit) prefix + edit
        else msg.content + "\n\n" + prefix + edit
    }

    private fun buildUserMessage(userMessage: String, mode: QuickAiMode): String =
        buildQuickUserMessage(selectedText, userMessage, mode)
}
