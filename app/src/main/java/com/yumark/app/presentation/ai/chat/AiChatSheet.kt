package com.yumark.app.presentation.ai.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.usecase.ai.chat.ChatMessageState
import com.yumark.app.domain.usecase.ai.chat.SendChatMessageUseCase
import com.yumark.app.domain.usecase.ai.conversation.GetConversationUseCase
import com.yumark.app.domain.repository.ConversationRepository
import com.yumark.app.presentation.ai.common.AiDesign
import com.yumark.app.presentation.ai.common.MessageBubble
import com.yumark.app.presentation.ai.common.StreamingIndicator
import com.yumark.app.presentation.common.isNearBottom
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiChatViewModel @Inject constructor(
    getConversation: GetConversationUseCase,
    private val sendChatMessage: SendChatMessageUseCase,
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    private val conversationId = MutableStateFlow<String?>(null)

    val title: StateFlow<String> = conversationId
        .flatMapLatest { id -> if (id == null) flowOf(null) else getConversation(id) }
        .map { it?.title ?: "对话" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "对话")

    val messages: StateFlow<List<Message>> = conversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else getConversation(id).map { it?.messages.orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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
                    is ChatMessageState.Completed -> _isStreaming.value = false
                    else -> Unit
                }
            }
            _isStreaming.value = false
        }
    }

    /** 用户在思考过程中点击中断：取消本轮流式，把半截助手消息收尾（空则删、非空则置非流式），状态复位。 */
    fun stop() {
        sendJob?.cancel()
        sendJob = null
        _isStreaming.value = false
        val assistantId = streamingAssistantId
        streamingAssistantId = null
        // 收尾在独立协程里跑：被取消的 job 不能再执行 suspend
        viewModelScope.launch {
            assistantId?.let { mid ->
                val msg = messages.value.firstOrNull { it.id == mid }
                if (msg != null && msg.isStreaming) {
                    if (msg.content.isBlank()) {
                        conversationRepository.deleteMessage(mid)
                    } else {
                        conversationRepository.updateMessage(msg.copy(isStreaming = false))
                    }
                }
            }
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
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
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

    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty() && autoScroll) {
            programmaticScroll = true
            try {
                scrollState.scrollTo(scrollState.maxValue)
            } finally {
                programmaticScroll = false
            }
        }
    }
    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 标题栏：返回 + 圆形字形徽标 + 标题/状态副标题（与 Agent 同款语言）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Box(
                modifier = Modifier.size(AiDesign.GlyphSize).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isStreaming) {
                    Text("正在回复…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (isStreaming) StreamingIndicator()
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(min = 200.dp, max = 460.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 非懒列表：不回收，WebView 不重建 → 无异步高度塌缩跳顶/白屏
            messages.forEach { msg -> MessageBubble(msg) }
        }

        SnackbarHost(snackbar)

        // 输入栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("输入消息…") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            if (isStreaming) {
                FilledIconButton(onClick = { viewModel.stop() }) {
                    Icon(Icons.Filled.Stop, "停止")
                }
            } else {
                FilledIconButton(
                    onClick = {
                        autoScroll = true   // 发送新消息 → 恢复跟随，确保能看到回复
                        viewModel.send(input); input = ""
                    },
                    enabled = input.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送")
                }
            }
        }
    }
}
