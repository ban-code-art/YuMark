package com.yumark.app.presentation.ai.agent

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.AgentStep
import com.yumark.app.domain.model.AgentTaskAggregate
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.domain.model.ConversationStatus
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
import com.yumark.app.domain.repository.AgentTaskRepository
import com.yumark.app.domain.repository.ConversationRepository
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import com.yumark.app.domain.usecase.ai.agent.AgentMessageState
import com.yumark.app.domain.usecase.ai.agent.ExecuteAgentActionUseCase
import com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCase
import com.yumark.app.domain.usecase.ai.conversation.GetConversationUseCase
import com.yumark.app.presentation.ai.common.AiDesign
import com.yumark.app.presentation.ai.common.MessageBubble
import com.yumark.app.presentation.ai.common.StreamingIndicator
import com.yumark.app.presentation.ai.common.ToolActivityRow
import com.yumark.app.presentation.common.isNearBottom
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class AgentChatViewModel @Inject constructor(
    getConversation: GetConversationUseCase,
    private val sendAgentMessage: SendAgentMessageUseCase,
    private val executeAgentAction: ExecuteAgentActionUseCase,
    private val loadDocumentUseCase: LoadDocumentUseCase,
    private val conversationRepository: ConversationRepository,
    private val agentTaskRepository: AgentTaskRepository,
    private val imageProcessor: com.yumark.app.core.image.ImageProcessor,
    private val agentUiPrefs: com.yumark.app.data.local.prefs.AgentUiPrefsDataStore
) : ViewModel() {

    private val conversationId = MutableStateFlow<String?>(null)
    private var docId: String? = null
    private var docName: String? = null
    private var docContent: String? = null
    private var onDocumentUpdated: (() -> Unit)? = null
    private var conversationBindJob: Job? = null
    private val documentBaseContent = mutableStateMapOf<String, String>()
    private val loadingBaseContent = mutableStateMapOf<String, Boolean>()

    /** 本轮流式协程与对应 assistant 消息 id；中断时据此取消并收尾。 */
    private var streamingJob: Job? = null
    private var streamingAssistantId: String? = null

    val messages: StateFlow<List<Message>> = conversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else getConversation(id).map { it?.messages.orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val taskProgress: StateFlow<TaskProgressUiState?> = conversationId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else agentTaskRepository.observeTaskByConversation(id).map { it?.toUiStateOrNull() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 任务执行流程面板是否收起（持久化偏好，跨会话/跨新 Agent 保持）。 */
    val taskPanelCollapsed: StateFlow<Boolean> = agentUiPrefs.taskPanelCollapsedFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setTaskPanelCollapsed(collapsed: Boolean) {
        viewModelScope.launch { agentUiPrefs.setTaskPanelCollapsed(collapsed) }
    }

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 本轮 agent 执行步骤（内存态，不持久化；流式期间展示"正在调什么工具"） */
    private val _steps = MutableStateFlow<List<AgentStep>>(emptyList())
    val steps: StateFlow<List<AgentStep>> = _steps.asStateFlow()

    /** CREATE 操作成功后置为新文档 id，UI 据此导航。 */
    private val _createdDocumentId = MutableStateFlow<String?>(null)
    val createdDocumentId: StateFlow<String?> = _createdDocumentId.asStateFlow()

    /** 本轮待发送的图片附件（内存态，发送时才下采样落盘）。 */
    private val _attachments = MutableStateFlow<List<Uri>>(emptyList())
    val attachments: StateFlow<List<Uri>> = _attachments.asStateFlow()

    private val _attachmentError = MutableStateFlow<String?>(null)
    val attachmentError: StateFlow<String?> = _attachmentError.asStateFlow()
    fun clearAttachmentError() { _attachmentError.value = null }

    fun addAttachment(uri: Uri) {
        if (_attachments.value.size >= 3) { _attachmentError.value = "最多只能添加 3 张图片"; return }
        if (_attachments.value.contains(uri)) return
        viewModelScope.launch {
            imageProcessor.validate(uri)
                .onSuccess { _attachments.value = _attachments.value + uri }
                .onFailure { _attachmentError.value = it.message ?: "无法添加该图片" }
        }
    }

    fun removeAttachment(uri: Uri) { _attachments.value = _attachments.value - uri }

    fun bind(id: String, documentId: String?, documentName: String?, documentContent: String?, onUpdated: () -> Unit = {}) {
        conversationBindJob?.cancel()
        conversationBindJob = null
        conversationId.value = id
        docId = documentId
        docName = documentName
        docContent = documentContent
        onDocumentUpdated = onUpdated
        if (documentId != null && documentContent != null) {
            documentBaseContent[documentId] = documentContent
        }

        // 更新对话的关联文档信息
        conversationBindJob = viewModelScope.launch {
            conversationRepository.observeConversation(id).collectLatest { conversation ->
                if (conversation != null &&
                    (conversation.relatedDocumentId != documentId || conversation.relatedDocumentName != documentName)) {
                    conversationRepository.updateConversation(
                        conversation.copy(
                            relatedDocumentId = documentId,
                            relatedDocumentName = documentName,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    return@collectLatest  // 只更新一次
                }
            }
        }
    }

    fun send(text: String) {
        val id = conversationId.value ?: return
        val atts = _attachments.value
        if (text.isBlank() && atts.isEmpty()) return
        streamingJob = viewModelScope.launch {
            _isStreaming.value = true
            _error.value = null
            _steps.value = emptyList()
            // 处理附件：下采样 → 落盘 → 持久化引用（失败的图静默跳过，已在添加时校验过）
            val processed = atts.mapNotNull { uri ->
                imageProcessor.processForVision(uri).getOrNull()
                    ?.let { imageProcessor.save(it).getOrNull() }
            }
            _attachments.value = emptyList()
            sendAgentMessage(id, text, docId, docName, docContent, processed).collect { state ->
                when (state) {
                    is AgentMessageState.AssistantMessageStarted -> streamingAssistantId = state.messageId
                    is AgentMessageState.ActionProposed -> ensureBaseContent(state.action.targetDocumentId)
                    is AgentMessageState.ToolStep -> _steps.value = _steps.value + state.step
                    is AgentMessageState.Error -> { _error.value = state.message; _isStreaming.value = false }
                    is AgentMessageState.Notice -> _error.value = state.message
                    is AgentMessageState.Completed -> _isStreaming.value = false
                    else -> Unit
                }
            }
            _isStreaming.value = false
        }
    }

    /** 用户在思考过程中点击中断：取消本轮流式，把半截消息收尾、对话状态复位 IDLE。 */
    fun stop() {
        streamingJob?.cancel()
        streamingJob = null
        _isStreaming.value = false
        _steps.value = emptyList()
        val assistantId = streamingAssistantId
        val convId = conversationId.value
        streamingAssistantId = null
        // 收尾在独立协程里跑：被取消的 job 不能再执行
        viewModelScope.launch {
            assistantId?.let { mid ->
                messages.value.firstOrNull { it.id == mid }?.takeIf { it.isStreaming }?.let { msg ->
                    conversationRepository.updateMessage(msg.copy(isStreaming = false))
                }
            }
            convId?.let { cid ->
                conversationRepository.observeConversation(cid).first()?.let { conv ->
                    if (conv.status != ConversationStatus.IDLE) {
                        conversationRepository.updateConversation(conv.copy(status = ConversationStatus.IDLE))
                    }
                }
                agentTaskRepository.getTaskByConversationId(cid)?.task?.let { task ->
                    if (task.status == AgentTaskStatus.PLANNING ||
                        task.status == AgentTaskStatus.EXECUTING ||
                        task.status == AgentTaskStatus.REPLANNING
                    ) {
                        agentTaskRepository.updateTask(
                            task.copy(
                                status = AgentTaskStatus.BLOCKED,
                                updatedAt = System.currentTimeMillis(),
                                currentStepId = null,
                                blockingReason = "用户已停止本轮 Agent 执行"
                            )
                        )
                    }
                }
            }
        }
    }

    /** 当前关联文档内容：EDIT diff 闸门的 base（用户所见原文）。 */
    fun currentDocumentContent(): String? = docContent

    /** 当前关联文档 ID：diff base 仅在编辑目标 == 当前文档时才成立。 */
    fun currentDocumentId(): String? = docId

    fun baseContentFor(documentId: String?): String? = documentId?.let { documentBaseContent[it] }

    fun isBaseContentLoading(documentId: String?): Boolean =
        documentId != null && loadingBaseContent[documentId] == true

    fun ensureBaseContent(documentId: String?) {
        if (documentId == null || documentBaseContent.containsKey(documentId) || loadingBaseContent[documentId] == true) {
            return
        }
        loadingBaseContent[documentId] = true
        viewModelScope.launch {
            loadDocumentUseCase(documentId)
                .onSuccess { documentBaseContent[documentId] = it.content }
                .onFailure { _error.value = "无法加载目标文档内容：${it.message}" }
            loadingBaseContent.remove(documentId)
        }
    }

    fun approve(message: Message, action: AgentAction, finalContent: String? = null) {
        viewModelScope.launch {
            executeAgentAction(message, action, finalContent)
                .onSuccess { documentId ->
                    if (action.type == com.yumark.app.domain.model.AgentActionType.CREATE_DOCUMENT) {
                        _createdDocumentId.value = documentId
                    } else if (action.type == com.yumark.app.domain.model.AgentActionType.EDIT_DOCUMENT) {
                        documentBaseContent[documentId] = finalContent ?: action.content
                        // 编辑文档完成，触发热更新
                        onDocumentUpdated?.invoke()
                    }
                }
                .onFailure { _error.value = "操作失败：${it.message}" }
        }
    }

    fun reject(message: Message, action: AgentAction) {
        viewModelScope.launch {
            conversationRepository.updateMessage(
                message.copy(agentAction = action.copy(status = AgentActionStatus.REJECTED))
            )
        }
    }

    fun clearError() { _error.value = null }
    fun consumeCreatedDocument() { _createdDocumentId.value = null }
}

data class TaskProgressUiState(
    val goal: String,
    val status: AgentTaskStatus,
    val steps: List<TaskProgressStepUiState>,
    val activeStepTitle: String?,
    val blockingReason: String?,
    val finalSummary: String?
)

data class TaskProgressStepUiState(
    val title: String,
    val status: AgentTaskStepStatus,
    val order: Int
)

private fun AgentTaskAggregate.toUiStateOrNull(): TaskProgressUiState? {
    // 已完成也生成面板（默认收起、仅显示结果摘要），让用户可回看；折叠头由 UI 控制。
    val orderedSteps = steps.sortedBy { it.order }
    val activeStep = task.currentStepId?.let { id -> orderedSteps.firstOrNull { it.id == id } }
        ?: orderedSteps.firstOrNull { it.status == AgentTaskStepStatus.RUNNING }
        ?: if (task.status == AgentTaskStatus.EXECUTING || task.status == AgentTaskStatus.REPLANNING) {
            orderedSteps.firstOrNull { it.status == AgentTaskStepStatus.PENDING }
        } else {
            null
        }

    return TaskProgressUiState(
        goal = task.goal,
        status = task.status,
        steps = if (task.status == AgentTaskStatus.EXECUTING || task.status == AgentTaskStatus.REPLANNING) {
            orderedSteps
        } else {
            orderedSteps.filter { it.status != AgentTaskStepStatus.PENDING }
        }.map { step ->
            TaskProgressStepUiState(
                title = step.title,
                status = step.status,
                order = step.order
            )
        },
        activeStepTitle = activeStep?.title,
        blockingReason = task.blockingReason,
        finalSummary = task.finalSummary
    )
}

/** Agent 对话顶部栏：返回 + 圆形字形徽标 + 标题/状态副标题，下方挂关联文档 chip。 */
@Composable
private fun AgentHeader(
    documentName: String?,
    isStreaming: Boolean,
    onBack: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Box(
                modifier = Modifier.size(AiDesign.GlyphSize).clip(CircleShape).background(cs.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(20.dp), tint = cs.onPrimaryContainer)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Agent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (isStreaming) "正在思考…" else "随时待命",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isStreaming) cs.primary else cs.onSurfaceVariant
                )
            }
        }
        if (documentName != null) {
            Surface(
                modifier = Modifier.padding(start = AiDesign.ScreenPadding, end = AiDesign.ScreenPadding, bottom = 6.dp),
                shape = RoundedCornerShape(AiDesign.PillCorner),
                color = cs.surfaceVariant.copy(alpha = AiDesign.SoftFill)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(14.dp), tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        documentName,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun AgentContent(
    conversationId: String,
    documentId: String?,
    documentName: String?,
    documentContent: String?,
    onBack: () -> Unit,
    onNavigateToDocument: (String) -> Unit,
    onDocumentUpdated: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AgentChatViewModel = hiltViewModel()
) {
    LaunchedEffect(conversationId, documentId, documentName, documentContent, onDocumentUpdated) {
        viewModel.bind(conversationId, documentId, documentName, documentContent, onDocumentUpdated)
    }

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val createdDoc by viewModel.createdDocumentId.collectAsStateWithLifecycle()
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val taskProgress by viewModel.taskProgress.collectAsStateWithLifecycle()
    val taskPanelCollapsed by viewModel.taskPanelCollapsed.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val attachmentError by viewModel.attachmentError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var enlarged by remember { mutableStateOf<Any?>(null) }
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(3)
    ) { uris -> uris.forEach { viewModel.addAttachment(it) } }
    var input by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val snackbar = remember { SnackbarHostState() }

    // 自动跟随到底部：流式输出时持续把窗口滚到最新内容；用户上滑查看历史时停止跟随。
    var autoScroll by remember { mutableStateOf(true) }
    // 标记「程序化滚动」，屏蔽其间的滚动检测，避免误判为用户上滑而错误关闭跟随。
    var programmaticScroll by remember { mutableStateOf(false) }

    // 仅在「用户发起的滚动」时切换跟随状态。
    // 非懒 Column + ScrollState：maxValue 即"可滚到底的距离"，不依赖 item 高度，
    // 也就没有 WebView 异步高度塌缩导致跳顶的问题。
    val bottomThreshold = 220
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress to (scrollState.value >= scrollState.maxValue - bottomThreshold) }
            .collect { (scrolling, nearBottom) ->
                if (programmaticScroll) return@collect
                when {
                    scrolling && !nearBottom -> autoScroll = false   // 用户向上拖/惯性滚离底部
                    !scrolling && nearBottom -> autoScroll = true    // 静止且回到底部
                }
            }
    }

    // 跟随最新内容：直接滚到底（maxValue）。无需 scrollToItem/overflow——ScrollState 的 maxValue
    // 反映全部已组合内容的高度，无回收、无异步重测，不会跳顶。
    LaunchedEffect(autoScroll, messages.size, messages.lastOrNull()?.content) {
        if (!autoScroll || messages.isEmpty()) return@LaunchedEffect
        programmaticScroll = true
        try {
            scrollState.scrollTo(scrollState.maxValue)
        } finally {
            programmaticScroll = false
        }
    }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); viewModel.clearError() } }
    LaunchedEffect(attachmentError) { attachmentError?.let { snackbar.showSnackbar(it); viewModel.clearAttachmentError() } }
    LaunchedEffect(createdDoc) {
        createdDoc?.let { onNavigateToDocument(it); viewModel.consumeCreatedDocument() }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AgentHeader(documentName = documentName, isStreaming = isStreaming, onBack = onBack)
        if (isStreaming) StreamingIndicator()
        taskProgress?.let { progress ->
            AgentTimeline(
                progress = progress,
                // 已完成/失败等终态默认收起（仅显示结果摘要），进行中沿用用户偏好
                collapsed = if (progress.status == AgentTaskStatus.EXECUTING ||
                    progress.status == AgentTaskStatus.REPLANNING ||
                    progress.status == AgentTaskStatus.PLANNING
                ) taskPanelCollapsed else true,
                onToggleCollapse = { viewModel.setTaskPanelCollapsed(!taskPanelCollapsed) }
            )
        }
        if (isStreaming && steps.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = AiDesign.ScreenPadding, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val recent = steps.takeLast(4)
                recent.forEachIndexed { index, step ->
                    // 最后一行若仍是「调用中」，视为活跃行做 shimmer。
                    val active = index == recent.lastIndex && step is AgentStep.ToolCalling
                    ToolActivityRow(step = step, active = active)
                }
            }
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 460.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 非懒列表：不回收条目，WebView 不被重建 → 无异步高度塌缩跳顶/白屏
            messages.forEach { msg ->
                MessageBubble(msg) {
                    Column {
                        if (msg.attachments.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(bottom = 4.dp)
                            ) {
                                msg.attachments.forEach { att ->
                                    val model = File(context.filesDir, att.path)
                                    AsyncImage(
                                        model = model,
                                        contentDescription = "图片附件",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { enlarged = model }
                                    )
                                }
                            }
                        }
                        if (msg.role == MessageRole.ASSISTANT && msg.steps.isNotEmpty()) {
                            var stepsExpanded by remember(msg.id) { mutableStateOf(false) }
                            TextButton(
                                onClick = { stepsExpanded = !stepsExpanded },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    if (stepsExpanded) "收起执行过程"
                                    else "执行过程（${msg.steps.count { it is AgentStep.ToolCalling }} 步）",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            AnimatedVisibility(stepsExpanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    msg.steps.forEach { step ->
                                        ToolActivityRow(step = step)
                                    }
                                }
                            }
                        }
                        val action = msg.agentAction
                        if (action != null && msg.role == MessageRole.ASSISTANT) {
                            val base = if (action.type == com.yumark.app.domain.model.AgentActionType.EDIT_DOCUMENT) {
                                viewModel.baseContentFor(action.targetDocumentId)
                            } else null
                            val awaitingBase = action.type == com.yumark.app.domain.model.AgentActionType.EDIT_DOCUMENT &&
                                action.targetDocumentId != null &&
                                base == null
                            if (awaitingBase) {
                                LaunchedEffect(msg.id, action.targetDocumentId) {
                                    viewModel.ensureBaseContent(action.targetDocumentId)
                                }
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("正在加载目标文档内容，以生成可审阅的 diff。", style = MaterialTheme.typography.bodySmall)
                                        if (viewModel.isBaseContentLoading(action.targetDocumentId)) {
                                            LinearProgressIndicator(Modifier.fillMaxWidth())
                                        }
                                        OutlinedButton(onClick = { viewModel.reject(msg, action) }) {
                                            Text("取消此次修改")
                                        }
                                    }
                                }
                            } else {
                                AgentActionCard(
                                    action = action,
                                    baseContent = base,
                                    onApproveDiff = { finalContent -> viewModel.approve(msg, action, finalContent) },
                                    onApprove = { viewModel.approve(msg, action) },
                                    onReject = { viewModel.reject(msg, action) }
                                )
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(snackbar)

        // 附件预览（横向滚动，点击放大，右上角删除）
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEach { uri ->
                    Box {
                        AsyncImage(
                            model = uri,
                            contentDescription = "待发送图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { enlarged = uri }
                        )
                        IconButton(
                            onClick = { viewModel.removeAttachment(uri) },
                            modifier = Modifier.align(Alignment.TopEnd).size(20.dp)
                        ) {
                            Icon(Icons.Default.Close, "移除", Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = {
                    pickMedia.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !isStreaming
            ) {
                Icon(Icons.Default.Image, "添加图片")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("让 AI 帮你创建或编辑文档…") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            if (isStreaming) {
                FilledIconButton(onClick = { viewModel.stop() }) {
                    Icon(Icons.Default.Stop, "停止")
                }
            } else {
                FilledIconButton(
                    onClick = {
                        autoScroll = true   // 发送新消息 → 恢复跟随，确保能看到回复
                        viewModel.send(input); input = ""
                    },
                    enabled = input.isNotBlank() || attachments.isNotEmpty()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送")
                }
            }
        }

        enlarged?.let { model ->
            Dialog(onDismissRequest = { enlarged = null }) {
                AsyncImage(
                    model = model,
                    contentDescription = "查看大图",
                    modifier = Modifier.fillMaxWidth().clickable { enlarged = null }
                )
            }
        }
    }
}
