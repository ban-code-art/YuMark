package com.yumark.app.presentation.ai.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.usecase.ai.chat.ChatMessageState
import com.yumark.app.domain.usecase.ai.chat.SendChatMessageUseCase
import com.yumark.app.domain.usecase.ai.conversation.GetConversationUseCase
import com.yumark.app.domain.repository.ConversationRepository
import com.yumark.app.presentation.ai.common.AiDesign
import com.yumark.app.presentation.ai.common.MessageBubble
import com.yumark.app.presentation.ai.common.StreamingIndicator
import com.yumark.app.presentation.common.SnackbarEffect
import com.yumark.app.presentation.common.resolveOrNull
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppMotion
import com.yumark.app.presentation.theme.AppSpacing
import com.yumark.app.presentation.theme.extendedColors
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
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiChatViewModel @Inject constructor(
    // 必须是属性而不是纯构造参数：stop() 的收尾要重新读一次会话（见那里的注释），
    // 而构造参数只在属性初始化式里可见。
    private val getConversation: GetConversationUseCase,
    private val sendChatMessage: SendChatMessageUseCase,
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    private val conversationId = MutableStateFlow<String?>(null)

    /**
     * 对话标题。取不到时留 null，由 UI 兜底成资源文案——ViewModel 没有 Resources，
     * 也不该把用户可见的默认标题硬编码在这里。
     */
    val title: StateFlow<String?> = conversationId
        .flatMapLatest { id -> if (id == null) flowOf(null) else getConversation(id) }
        .map { it?.title }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages: StateFlow<List<Message>> = conversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else getConversation(id).map { it?.messages.orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** 错误文案。[ChatMessageState.Error] 给的就是 [UiMessage]，解析留到 Composable 侧。 */
    private val _error = MutableStateFlow<UiMessage?>(null)
    val error: StateFlow<UiMessage?> = _error.asStateFlow()

    /** 本轮流式协程与对应助手消息 id；中断时据此取消并收尾。 */
    private var sendJob: Job? = null
    private var streamingAssistantId: String? = null

    fun bind(id: String) { conversationId.value = id }

    fun send(text: String) {
        val id = conversationId.value ?: return
        if (text.isBlank()) return
        sendJob = viewModelScope.launch {
            _isStreaming.value = true
            _error.value = null
            sendChatMessage(id, text).collect { state ->
                when (state) {
                    is ChatMessageState.AssistantMessageStarted -> streamingAssistantId = state.messageId
                    is ChatMessageState.Error -> { _error.value = state.message; _isStreaming.value = false }
                    // 截断提示走同一条横幅：它不是失败（正文照样留着），但同样必须让人看见
                    is ChatMessageState.Notice -> _error.value = state.message
                    is ChatMessageState.Completed -> _isStreaming.value = false
                    else -> Unit
                }
            }
            _isStreaming.value = false
        }
    }

    /** 用户在思考过程中点击中断：取消本轮流式，把半截助手消息收尾（空则删），状态复位。 */
    fun stop() {
        // cancel() 只打标记，正在 IO 上飞的那次 updateMessage 仍会落库；必须 join 等它真停下，
        // 否则「读到空 → 删行」之后那笔迟到的写入会把整行又写回来。
        val cancelled = sendJob
        cancelled?.cancel()
        sendJob = null
        _isStreaming.value = false
        val assistantId = streamingAssistantId
        streamingAssistantId = null
        val convId = conversationId.value
        // 收尾在独立协程里跑：被取消的 job 不能再执行 suspend
        viewModelScope.launch {
            cancelled?.join()
            val mid = assistantId ?: return@launch
            // 重新读库，不用 messages.value：那是 Room flow 经 stateIn 的快照，流式期间每个
            // chunk 都在写库，快照落后一拍 —— 拿它判空会把「其实已经收到正文」的那一行
            // 当成空占位删掉。
            val msg = convId?.let { cid ->
                getConversation(cid).first()?.messages?.firstOrNull { it.id == mid }
            } ?: return@launch
            // 只看正文，不再看 msg.isStreaming：那一位从不落库（messages 表无此列，
            // AiEntities.kt:33-42，AiMappers.kt 的 toDomain 也不填），从库里读回来恒为 false，
            // 于是这整段收尾从前是死代码 —— 首个 token 之前点停止，那条空的助手气泡就永久
            // 留在对话里（正常结束/报错时 SendChatMessageUseCase 会删掉它，取消时走不到那里）。
            if (msg.content.isBlank()) conversationRepository.deleteMessage(mid)
            // 非空则无需回写：isStreaming 不落库，库里那一行已经是最终状态。
        }
    }

    fun clearError() { _error.value = null }
}

@Composable
fun ChatContent(
    conversationId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AiChatViewModel = hiltViewModel()
) {
    LaunchedEffect(conversationId) { viewModel.bind(conversationId) }

    val title by viewModel.title.collectAsStateWithLifecycle()
    // 无条件解析默认标题，再用 elvis 兜底：避免把 @Composable 调用埋在条件分支里
    val defaultTitle = stringResource(R.string.ai_chat_default_title)
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    // 组合期先解析成文本：resolveOrNull() 是 @Composable，下面 effect 的协程体里调不了。
    val errorText = error.resolveOrNull()
    var input by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val snackbar = remember { SnackbarHostState() }

    // 自动跟随到底部：流式输出时持续滚到最新内容；用户上滑查看历史时停止跟随。
    var autoScroll by remember { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }

    // 非懒 Column + ScrollState：maxValue 即"可滚到底的距离"，不依赖 item 高度，
    // 也就没有 WebView 异步高度塌缩导致跳顶的问题。
    val bottomThreshold = 220
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress to (scrollState.value >= scrollState.maxValue - bottomThreshold) }
            .collect { (scrolling, nearBottom) ->
                if (programmaticScroll) return@collect
                when {
                    scrolling && !nearBottom -> autoScroll = false
                    !scrolling && nearBottom -> autoScroll = true
                }
            }
    }

    // autoScroll 必须进 key：它在这个 effect 外面被 snapshotFlow 那段改写，而 effect 体里读它
    // 不构成订阅。少了这个 key，用户上翻解除跟随、又静止回到底部（autoScroll 重新变 true）时，
    // 只要流已经结束、没有新 token 触发另外两个 key 变化，就永远不会重新贴到底部。
    // 与 AgentChatSheet 的同一个 effect 保持一致。
    LaunchedEffect(autoScroll, messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty() && autoScroll) {
            programmaticScroll = true
            try {
                scrollState.scrollTo(scrollState.maxValue)
            } finally {
                programmaticScroll = false
            }
        }
    }
    // 「弹完再清」的取消语义收在 SnackbarEffect 里，见那里的注释。
    SnackbarEffect(errorText, snackbar) { viewModel.clearError() }

    Column(modifier = modifier.fillMaxWidth().testTag(AiChatTestTags.ROOT)) {
        // 标题栏：返回 + 圆形字形徽标 + 标题/状态副标题（与 Agent 同款语言）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = AppSpacing.Tight, end = AppSpacing.Cozy, top = AppSpacing.Tight, bottom = AppSpacing.Tight)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_ai_chat_back))
            }
            Box(
                modifier = Modifier.size(AiDesign.GlyphSize).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(AppIconSize.Small),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.width(AiChatMetrics.TitleGlyphGap))
            Column(Modifier.weight(1f)) {
                Text(
                    title ?: defaultTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AnimatedVisibility(
                    visible = isStreaming,
                    enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
                    exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
                ) {
                    Text(
                        stringResource(R.string.ai_chat_replying),
                        style = MaterialTheme.typography.labelSmall,
                        color = extendedColors.primaryText
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = isStreaming,
            enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
            exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
        ) {
            StreamingIndicator()
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(min = AiChatMetrics.MessageListMinHeight, max = AiChatMetrics.MessageListMaxHeight)
                .verticalScroll(scrollState)
                .padding(horizontal = AppSpacing.Cozy, vertical = AppSpacing.Default),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Default)
        ) {
            // 非懒列表：不回收，WebView 不重建 → 无异步高度塌缩跳顶/白屏
            messages.forEach { msg -> MessageBubble(msg) }
        }

        SnackbarHost(snackbar)

        // 输入栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.Cozy),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Default)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.ai_chat_input_hint)) },
                modifier = Modifier.weight(1f).testTag(AiChatTestTags.INPUT),
                maxLines = 4
            )
            if (isStreaming) {
                FilledIconButton(
                    onClick = { viewModel.stop() },
                    modifier = Modifier.testTag(AiChatTestTags.STOP)
                ) {
                    Icon(Icons.Filled.Stop, stringResource(R.string.cd_ai_chat_stop))
                }
            } else {
                FilledIconButton(
                    onClick = {
                        autoScroll = true   // 发送新消息 → 恢复跟随，确保能看到回复
                        viewModel.send(input); input = ""
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.testTag(AiChatTestTags.SEND)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.cd_ai_chat_send))
                }
            }
        }
    }
}

/** UI 测试锚点：文案会随语言变，测试只能靠稳定的英文 tag 定位。 */
private object AiChatTestTags {
    const val ROOT = "ai_chat_root"
    const val INPUT = "ai_chat_input"
    const val SEND = "ai_chat_send"
    const val STOP = "ai_chat_stop"
}

/**
 * 本屏特有的 off-grid 布局度量，刻意不并入全局 [AppSpacing]：头像↔标题水平间隔与消息列表的
 * 高度上下界，均离散于 8dp 间距标度，保留原像素、不硬凑。按 FileListMetrics 先例落屏幕局部。
 */
private object AiChatMetrics {
    /** 圆形字形徽标↔标题列的水平间隔（10dp，介于 Default(8)/Cozy(12) 之间，off-grid）。 */
    val TitleGlyphGap = 10.dp
    /** 消息滚动区最小高度：内容少时也不塌成一条缝。 */
    val MessageListMinHeight = 200.dp
    /** 消息滚动区最大高度：超出内部滚动，避免把 sheet 顶满。 */
    val MessageListMaxHeight = 460.dp
}
