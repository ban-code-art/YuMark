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
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserAction
import com.yumark.app.core.util.onFailureReport
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.AgentStep
import com.yumark.app.domain.model.AgentStatusCode
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
import com.yumark.app.presentation.common.SnackbarEffect
import com.yumark.app.presentation.common.resolveOrNull
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppShapes
import com.yumark.app.presentation.theme.AppSpacing
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

    /**
     * 本轮 assistant 消息 id。[stop] 要靠它把内存里的步骤写回那一行。
     *
     * 流式期间落库的只有正文（`AgentUseCases` 那条 `assistant.copy(content = …)`），`steps`
     * 只在收尾的几处才写。用户点停止时协程被取消，一处收尾都到不了 —— 不在这里补写的话，
     * 刚才看着走完的整条工具时间线就永久没了。
     */
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

    /**
     * 错误/提示文案。用 [UiMessage] 而不是 String：来源（[AgentMessageState] 的 Error/Notice、
     * [onFailureReport] 回传）本身就是 UiMessage，解析统一留到 Composable 侧。
     */
    private val _error = MutableStateFlow<UiMessage?>(null)
    val error: StateFlow<UiMessage?> = _error.asStateFlow()

    /** 本轮 agent 执行步骤（内存态，不持久化；流式期间展示"正在调什么工具"） */
    private val _steps = MutableStateFlow<List<AgentStep>>(emptyList())
    val steps: StateFlow<List<AgentStep>> = _steps.asStateFlow()

    /** CREATE 操作成功后置为新文档 id，UI 据此导航。 */
    private val _createdDocumentId = MutableStateFlow<String?>(null)
    val createdDocumentId: StateFlow<String?> = _createdDocumentId.asStateFlow()

    /** 本轮待发送的图片附件（内存态，发送时才下采样落盘）。 */
    private val _attachments = MutableStateFlow<List<Uri>>(emptyList())
    val attachments: StateFlow<List<Uri>> = _attachments.asStateFlow()

    /**
     * 附件相关提示。用 [UiMessage] 而不是 String：上限提示是本模块可翻译文案（[UiMessage.Res]），
     * 而 [onFailureReport] 给回来的是 core 层产出的 [UiMessage]，两种来源都要能装。
     */
    private val _attachmentError = MutableStateFlow<UiMessage?>(null)
    val attachmentError: StateFlow<UiMessage?> = _attachmentError.asStateFlow()
    fun clearAttachmentError() { _attachmentError.value = null }

    fun addAttachment(uri: Uri) {
        if (_attachments.value.size >= 3) {
            _attachmentError.value = UiMessage.Res(R.string.agent_error_max_images, listOf(3))
            return
        }
        if (_attachments.value.contains(uri)) return
        viewModelScope.launch {
            imageProcessor.validate(uri)
                .onSuccess { _attachments.value = _attachments.value + uri }
                .onFailureReport(UserAction.ADD_IMAGE) { _attachmentError.value = it }
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
            streamingAssistantId = null
            // 处理附件：下采样 → 落盘 → 持久化引用（失败的图静默跳过，已在添加时校验过）
            val processed = atts.mapNotNull { uri ->
                imageProcessor.processForVision(uri).getOrNull()
                    ?.let { imageProcessor.save(it).getOrNull() }
            }
            _attachments.value = emptyList()
            sendAgentMessage(id, text, docId, docName, docContent, processed).collect { state ->
                when (state) {
                    is AgentMessageState.ActionProposed -> refreshBaseContent(state.action.targetDocumentId)
                    is AgentMessageState.AssistantMessageStarted -> streamingAssistantId = state.messageId
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

    /** 用户在思考过程中点击中断：取消本轮流式，把对话与任务状态复位。 */
    fun stop() {
        // 留住被取消的 job：cancel() 只打标记，正在 IO 上飞的那次 updateMessage 还是会落库。
        // 不 join 就补写，可能出现「补写先落地、迟到的 chunk 写入后落地」的顺序 ——
        // 而那笔迟到的写入是 assistant.copy(content = …)（AgentUseCases.kt:240-242 每个
        // chunk 都写），steps 是空的，于是刚补上的工具时间线当场被抹掉。
        val cancelled = streamingJob
        cancelled?.cancel()
        streamingJob = null
        _isStreaming.value = false
        // 清空前先拿走：这是本轮唯一一份步骤记录，落库全靠下面那次补写。
        val interruptedSteps = _steps.value
        val assistantId = streamingAssistantId
        _steps.value = emptyList()
        streamingAssistantId = null
        val convId = conversationId.value
        // 收尾在独立协程里跑：被取消的 job 不能再执行
        viewModelScope.launch {
            cancelled?.join()
            // 一次读库，补写与状态判定共用。不能用 messages.value：那是 Room flow 经 stateIn
            // 的快照，流式期间每个 chunk 都在写库，快照永远落后一拍；拿它 copy 回写等于把
            // 最后几个 chunk 的正文回滚掉（updateMessage 是整行覆盖，ConversationRepositoryImpl.kt:52）。
            val conv = convId?.let { conversationRepository.observeConversation(it).first() }
            // 补写被中断的那条 assistant 消息。流式期间落库的只有正文，steps 只在收尾几处写，
            // 而取消让那几处一处都到不了 —— 不补这一笔，用户刚看着走完的工具时间线就没了。
            // `isStreaming` 本身不落库（messages 表无此列），这里带上只是保持领域对象自洽。
            assistantId?.let { mid ->
                val row = conv?.messages?.firstOrNull { it.id == mid }
                    ?: messages.value.firstOrNull { it.id == mid }   // 读不到会话时退回快照
                row?.let { msg ->
                    conversationRepository.updateMessage(
                        msg.copy(
                            isStreaming = false,
                            // 内存里没步骤时不要把库里已有的抹掉（例如上一轮收尾写过的）
                            steps = interruptedSteps.ifEmpty { msg.steps }
                        )
                    )
                }
            }
            // 只收 WORKING，与 AgentUseCases 的兜底（AgentUseCases.kt:448-474）同一条规则。
            // 从前写的是 != IDLE：那会把一轮已经正常跑完、状态为 COMPLETED 的会话降级回 IDLE
            //（点停止时上一轮早已收尾、这一轮还没来得及置 WORKING，就会撞上这一支）。
            conv?.let {
                if (it.status == ConversationStatus.WORKING) {
                    conversationRepository.updateConversation(it.copy(status = ConversationStatus.IDLE))
                }
            }
            convId?.let { cid ->
                agentTaskRepository.getTaskByConversationId(cid)?.let { aggregate ->
                    val task = aggregate.task
                    // 除了三个进行中状态，还要接住 AgentUseCases 的 finally 抢先写下的
                    // FAILED + blocked.interrupted：那是它对「协程没了」的泛化判断，而用户点停止
                    // 是更准确的事实。两处谁先落库并不确定（那边在 NonCancellable 里，这边在
                    // join 之后），所以这里必须能覆盖它，否则历史里留下的是「执行失败」。
                    val interruptedByFlow = task.status == AgentTaskStatus.FAILED &&
                        task.blockingReason == AgentStatusCode.BLOCKED_INTERRUPTED.encode()
                    if (task.status == AgentTaskStatus.PLANNING ||
                        task.status == AgentTaskStatus.EXECUTING ||
                        task.status == AgentTaskStatus.REPLANNING ||
                        interruptedByFlow
                    ) {
                        // 先把还挂在 RUNNING 的步骤退回 PENDING，再改判任务本身。
                        //
                        // 不退回的后果不是「数据不干净」，而是界面自相矛盾：顶部状态胶囊已经是
                        // 「已由你中断」，而 [toUiStateOrNull] 的 activeStep 在 currentStepId 被
                        // 置空后正好退到「第一条 RUNNING 步骤」这一支，AgentTimeline 会继续按
                        // 进行中画脉冲、把标题高亮着——看上去像是停止没生效、还在跑。
                        //
                        // 冷启动那条复位路径救不回来：AgentTaskDao.resetRunningSteps 靠父任务仍在
                        // liveStatuses（PLANNING/EXECUTING/REPLANNING）里定位，任务一旦改判成
                        // BLOCKED，子查询就选不到它，那条 RUNNING 步骤会永久停在活动态。
                        //
                        // 顺序与 AgentTaskDao.reconcileInterrupted 一致（先步骤后任务），但理由不同：
                        // 那边是 SQL 谓词依赖父任务状态，这里按 step id 定位不受影响，真正怕的是两笔
                        // 写入之间进程被杀——先写终态就退化成上面那个救不回来的组合。
                        aggregate.steps
                            .filter { it.status == AgentTaskStepStatus.RUNNING }
                            .forEach {
                                agentTaskRepository.markStepStatus(it.id, AgentTaskStepStatus.PENDING)
                            }
                        agentTaskRepository.updateTask(
                            task.copy(
                                status = AgentTaskStatus.BLOCKED,
                                updatedAt = System.currentTimeMillis(),
                                currentStepId = null,
                                blockingReason = AgentStatusCode.BLOCKED_USER_STOPPED.encode()
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

    /**
     * 把 diff 闸门的 base 重新从库里读一遍。
     *
     * **不能沿用缓存里那一份**：`bind()` 会用编辑器传进来的内容给 base 打底，之后只有
     * 批准成功才更新它。用户在编辑器里改了几行、或同步拉回了远端版本，缓存就落后了——
     * 拿旧 base 算出的 diff，用户「只接受这几个 hunk」合成出来的正文里，恰好把那些改动
     * 还原成了旧样子，而界面上完全看不出来。基线校验在
     * [com.yumark.app.domain.usecase.ai.agent.ExecuteAgentActionUseCase] 里兜底（不一致直接拒），
     * 这里做的是让用户**看到**的就是当前正文。
     *
     * 仍然挡住并发重入：一次加载在飞就不再排一次，否则每次重组都会多打一发请求。
     * 读失败保留旧 base（并报错），执行侧的指纹校验保证不会因此写错内容。
     */
    fun refreshBaseContent(documentId: String?) {
        if (documentId == null || loadingBaseContent[documentId] == true) return
        loadingBaseContent[documentId] = true
        viewModelScope.launch {
            loadDocumentUseCase(documentId)
                .onSuccess { documentBaseContent[documentId] = it.content }
                .onFailureReport(UserAction.LOAD_TARGET_DOCUMENT) { _error.value = it }
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
                .onFailureReport(UserAction.APPLY_ACTION) { _error.value = it }
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
            modifier = Modifier.fillMaxWidth().padding(start = AppSpacing.Tight, end = AppSpacing.Cozy, top = AppSpacing.Tight, bottom = AppSpacing.Tight)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_agent_back))
            }
            Box(
                modifier = Modifier.size(AiDesign.GlyphSize).clip(CircleShape).background(cs.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(AppIconSize.Medium), tint = cs.onPrimaryContainer)
            }
            Spacer(Modifier.width(AgentChatMetrics.HeaderTitleGap))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.agent_header_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(
                        if (isStreaming) R.string.agent_header_thinking else R.string.agent_header_idle
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isStreaming) cs.primary else cs.onSurfaceVariant
                )
            }
        }
        if (documentName != null) {
            Surface(
                modifier = Modifier.padding(start = AiDesign.ScreenPadding, end = AiDesign.ScreenPadding, bottom = AppSpacing.Snug),
                shape = RoundedCornerShape(AiDesign.PillCorner),
                color = cs.surfaceVariant.copy(alpha = AiDesign.SoftFill)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = AgentChatMetrics.DocChipPaddingH, vertical = AppSpacing.Tight)
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(AgentChatMetrics.DocChipIconSize), tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(AgentChatMetrics.DocChipIconGap))
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
    // 组合期先解析成文本再交给下面的 LaunchedEffect：resolveOrNull() 是 @Composable，
    // 协程体里调不了，只能在这里拿到 String 让 effect 捕获。
    val errorText = error.resolveOrNull()
    val attachmentErrorText = attachmentError.resolveOrNull()
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
    // 「弹完再清」的取消语义收在 SnackbarEffect 里，见那里的注释。
    SnackbarEffect(errorText, snackbar) { viewModel.clearError() }
    SnackbarEffect(attachmentErrorText, snackbar) { viewModel.clearAttachmentError() }
    LaunchedEffect(createdDoc) {
        createdDoc?.let { onNavigateToDocument(it); viewModel.consumeCreatedDocument() }
    }

    Column(modifier = modifier.fillMaxWidth().testTag(AgentChatTestTags.ROOT)) {
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
                Modifier.fillMaxWidth().padding(horizontal = AiDesign.ScreenPadding, vertical = AppSpacing.Tight),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
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
                // weight 必须在 heightIn 之前：没有 weight 时这一列会按 max=460dp 自行占位，
                // 加上头部/任务面板/工具活动行（最多 4 行）之后总高可以超过 BottomSheet 的可用
                // 高度，而 Column 不滚动也不压缩——超出的部分直接被裁掉，最下面的输入栏就消失了，
                // 会话彻底没法继续。fill=false 保证消息少时仍按内容高度收缩，不留一大片空白。
                .weight(1f, fill = false)
                .heightIn(min = AgentChatMetrics.MessagesMinHeight, max = AgentChatMetrics.MessagesMaxHeight)
                .verticalScroll(scrollState)
                .padding(horizontal = AppSpacing.Cozy, vertical = AppSpacing.Default),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Default)
        ) {
            // 非懒列表：不回收条目，WebView 不被重建 → 无异步高度塌缩跳顶/白屏
            messages.forEach { msg ->
                key(msg.id) {
                MessageBubble(msg) {
                    Column {
                        if (msg.attachments.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Snug),
                                modifier = Modifier.padding(bottom = AppSpacing.Tight)
                            ) {
                                msg.attachments.forEach { att ->
                                    val model = File(context.filesDir, att.path)
                                    AsyncImage(
                                        model = model,
                                        contentDescription = stringResource(R.string.cd_agent_attachment),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(AgentChatMetrics.MessageAttachmentThumb)
                                            .clip(RoundedCornerShape(AppShapes.Small))
                                            .clickable { enlarged = model }
                                    )
                                }
                            }
                        }
                        if (msg.role == MessageRole.ASSISTANT && msg.steps.isNotEmpty()) {
                            var stepsExpanded by remember(msg.id) { mutableStateOf(false) }
                            val toolStepCount = msg.steps.count { it is AgentStep.ToolCalling }
                            TextButton(
                                onClick = { stepsExpanded = !stepsExpanded },
                                contentPadding = PaddingValues(AppSpacing.None)
                            ) {
                                Text(
                                    // count 传两次：一次选 quantity，一次做 %1$d 的实参
                                    if (stepsExpanded) stringResource(R.string.agent_steps_collapse)
                                    else pluralStringResource(
                                        R.plurals.agent_steps_expand,
                                        toolStepCount,
                                        toolStepCount
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            AnimatedVisibility(stepsExpanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
                                    msg.steps.forEach { step ->
                                        ToolActivityRow(step = step)
                                    }
                                }
                            }
                        }
                        val action = msg.agentAction
                        if (action != null && msg.role == MessageRole.ASSISTANT) {
                            val isPendingEdit =
                                action.type == com.yumark.app.domain.model.AgentActionType.EDIT_DOCUMENT &&
                                    action.targetDocumentId != null &&
                                    action.status == com.yumark.app.domain.model.AgentActionStatus.PENDING
                            // 待审批的编辑：卡片进入组合就把 base 重读一遍，而不是只在缓存为空时读。
                            // 缓存里那一份可能是 bind() 时编辑器给的旧内容——冷启动后重新水合出来的
                            // PENDING 提议一定走这条路——拿它算 diff，用户看不见自己后来的改动。
                            if (isPendingEdit) {
                                LaunchedEffect(msg.id, action.targetDocumentId) {
                                    viewModel.refreshBaseContent(action.targetDocumentId)
                                }
                            }
                            val base = if (action.type == com.yumark.app.domain.model.AgentActionType.EDIT_DOCUMENT) {
                                viewModel.baseContentFor(action.targetDocumentId)
                            } else null
                            val awaitingBase = isPendingEdit && base == null
                            if (awaitingBase) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.Default),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(Modifier.padding(AppSpacing.Cozy), verticalArrangement = Arrangement.spacedBy(AppSpacing.Snug)) {
                                        Text(
                                            stringResource(R.string.agent_diff_loading_base),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (viewModel.isBaseContentLoading(action.targetDocumentId)) {
                                            LinearProgressIndicator(Modifier.fillMaxWidth())
                                        }
                                        OutlinedButton(onClick = { viewModel.reject(msg, action) }) {
                                            Text(stringResource(R.string.agent_diff_cancel_edit))
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
        }

        SnackbarHost(snackbar)

        // 附件预览（横向滚动，点击放大，右上角删除）
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.Cozy, vertical = AppSpacing.Tight),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Default)
            ) {
                attachments.forEach { uri ->
                    Box {
                        AsyncImage(
                            model = uri,
                            contentDescription = stringResource(R.string.cd_agent_pending_image),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(AgentChatMetrics.PendingAttachmentThumb)
                                .clip(RoundedCornerShape(AppShapes.Small))
                                .clickable { enlarged = uri }
                        )
                        IconButton(
                            onClick = { viewModel.removeAttachment(uri) },
                            modifier = Modifier.align(Alignment.TopEnd).size(AgentChatMetrics.RemoveButtonSize)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                stringResource(R.string.cd_agent_remove_image),
                                Modifier.size(AppIconSize.Inline)
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.Cozy),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Default)
        ) {
            IconButton(
                onClick = {
                    pickMedia.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !isStreaming,
                modifier = Modifier.testTag(AgentChatTestTags.ADD_IMAGE)
            ) {
                Icon(Icons.Default.Image, stringResource(R.string.cd_agent_add_image))
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.agent_input_hint)) },
                modifier = Modifier.weight(1f).testTag(AgentChatTestTags.INPUT),
                maxLines = 4
            )
            if (isStreaming) {
                FilledIconButton(
                    onClick = { viewModel.stop() },
                    modifier = Modifier.testTag(AgentChatTestTags.STOP)
                ) {
                    Icon(Icons.Default.Stop, stringResource(R.string.cd_agent_stop))
                }
            } else {
                FilledIconButton(
                    onClick = {
                        autoScroll = true   // 发送新消息 → 恢复跟随，确保能看到回复
                        viewModel.send(input); input = ""
                    },
                    enabled = input.isNotBlank() || attachments.isNotEmpty(),
                    modifier = Modifier.testTag(AgentChatTestTags.SEND)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.cd_agent_send))
                }
            }
        }

        enlarged?.let { model ->
            Dialog(onDismissRequest = { enlarged = null }) {
                AsyncImage(
                    model = model,
                    contentDescription = stringResource(R.string.cd_agent_enlarged_image),
                    modifier = Modifier.fillMaxWidth().clickable { enlarged = null }
                )
            }
        }
    }
}

/** UI 测试锚点：文案会随语言变，测试只能靠稳定的英文 tag 定位。 */
private object AgentChatTestTags {
    const val ROOT = "agent_chat_root"
    const val INPUT = "agent_chat_input"
    const val SEND = "agent_chat_send"
    const val STOP = "agent_chat_stop"
    const val ADD_IMAGE = "agent_chat_add_image"
}

/**
 * 本 Agent 对话面板特有的度量，刻意不并入全局间距 / 图标标度：
 * - 头部字形徽标与标题列间距（10dp）、关联文档 chip 水平内边距（10dp）与图标-文字间距（5dp）、
 *   chip 内小文档图标（14dp，比最小图标标度 16 更紧）——都离散于 4/6/8/12 标度，硬凑会改像素。
 * - 消息滚动区高度上下限（200/460dp，布局约束）、消息/待发附件缩略图边长（72/64dp）、
 *   附件删除按钮容器（20dp，叠在 64dp 缩略图右上角，放大到 48 命中区会溢出缩略图，故保留）。
 * 离散于全局标度，保留原像素、不硬凑。按 FileListMetrics 先例落屏幕局部。
 */
private object AgentChatMetrics {
    /** 头部字形徽标与标题列的间距：10dp（off-grid，介于 Snug6/Cozy12）。 */
    val HeaderTitleGap = 10.dp
    /** 关联文档 chip 的水平内边距：10dp（off-grid）。 */
    val DocChipPaddingH = 10.dp
    /** chip 内文档图标与文件名的间距：5dp（off-grid，介于 Tight4/Snug6）。 */
    val DocChipIconGap = 5.dp
    /** chip 内小文档图标直径：14dp，比最小图标标度(16)略紧；off-grid。 */
    val DocChipIconSize = 14.dp
    /** 消息滚动区最小高度：200dp（布局约束，见 weight/heightIn 注释）。 */
    val MessagesMinHeight = 200.dp
    /** 消息滚动区最大高度：460dp（超出即滚动，避免挤掉底部输入栏）。 */
    val MessagesMaxHeight = 460.dp
    /** 消息内附件缩略图边长：72dp（可点放大）。 */
    val MessageAttachmentThumb = 72.dp
    /** 待发送附件预览缩略图边长：64dp。 */
    val PendingAttachmentThumb = 64.dp
    /** 附件删除按钮容器边长：20dp（叠在 64dp 缩略图右上角，off-grid）。 */
    val RemoveButtonSize = 20.dp
}
