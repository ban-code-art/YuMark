package com.yumark.app.presentation.ai.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.usecase.ai.chat.ChatMessageState
import com.yumark.app.domain.usecase.ai.chat.SendChatMessageUseCase
import com.yumark.app.domain.usecase.ai.conversation.GetConversationUseCase
import com.yumark.app.presentation.ai.common.MessageBubble
import com.yumark.app.presentation.common.isNearBottom
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val sendChatMessage: SendChatMessageUseCase
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

    fun bind(id: String) { conversationId.value = id }

    fun send(text: String) {
        val id = conversationId.value ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            _isStreaming.value = true
            _error.value = null
            sendChatMessage(id, text).collect { state ->
                when (state) {
                    is ChatMessageState.Error -> { _error.value = state.message; _isStreaming.value = false }
                    is ChatMessageState.Completed -> _isStreaming.value = false
                    else -> Unit
                }
            }
            _isStreaming.value = false
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

    val title by viewModel.title.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val error by viewModel.error.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        // 瞬时滚动(避免流式每个 token 重启动画)+ 仅在底部附近跟随(用户向上翻阅时不被拽回)
        if (messages.isNotEmpty() && listState.isNearBottom()) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }
    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 标题栏
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isStreaming) LinearProgressIndicator(Modifier.fillMaxWidth())
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 200.dp, max = 460.dp).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
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
            FilledIconButton(
                onClick = { viewModel.send(input); input = "" },
                enabled = !isStreaming && input.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送")
            }
        }
    }
}
