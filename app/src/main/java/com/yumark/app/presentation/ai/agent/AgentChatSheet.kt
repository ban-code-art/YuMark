package com.yumark.app.presentation.ai.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageRole
import com.yumark.app.domain.repository.ConversationRepository
import com.yumark.app.domain.usecase.ai.agent.AgentMessageState
import com.yumark.app.domain.usecase.ai.agent.ExecuteAgentActionUseCase
import com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCase
import com.yumark.app.domain.usecase.ai.conversation.GetConversationUseCase
import com.yumark.app.presentation.ai.common.MessageBubble
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
class AgentChatViewModel @Inject constructor(
    getConversation: GetConversationUseCase,
    private val sendAgentMessage: SendAgentMessageUseCase,
    private val executeAgentAction: ExecuteAgentActionUseCase,
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    private val conversationId = MutableStateFlow<String?>(null)
    private var docId: String? = null
    private var docName: String? = null
    private var docContent: String? = null
    private var onDocumentUpdated: (() -> Unit)? = null

    val messages: StateFlow<List<Message>> = conversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else getConversation(id).map { it?.messages.orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** CREATE 操作成功后置为新文档 id，UI 据此导航。 */
    private val _createdDocumentId = MutableStateFlow<String?>(null)
    val createdDocumentId: StateFlow<String?> = _createdDocumentId.asStateFlow()

    fun bind(id: String, documentId: String?, documentName: String?, documentContent: String?, onUpdated: () -> Unit = {}) {
        conversationId.value = id
        docId = documentId
        docName = documentName
        docContent = documentContent
        onDocumentUpdated = onUpdated

        // 更新对话的关联文档信息
        viewModelScope.launch {
            conversationRepository.observeConversation(id).collect { conversation ->
                if (conversation != null &&
                    (conversation.relatedDocumentId != documentId || conversation.relatedDocumentName != documentName)) {
                    conversationRepository.updateConversation(
                        conversation.copy(
                            relatedDocumentId = documentId,
                            relatedDocumentName = documentName,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    return@collect  // 只更新一次
                }
            }
        }
    }

    fun send(text: String) {
        val id = conversationId.value ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            _isStreaming.value = true
            _error.value = null
            sendAgentMessage(id, text, docId, docName, docContent).collect { state ->
                when (state) {
                    is AgentMessageState.Error -> { _error.value = state.message; _isStreaming.value = false }
                    is AgentMessageState.Completed -> _isStreaming.value = false
                    else -> Unit
                }
            }
            _isStreaming.value = false
        }
    }

    fun approve(message: Message, action: AgentAction) {
        viewModelScope.launch {
            executeAgentAction(message, action)
                .onSuccess { documentId ->
                    if (action.type == com.yumark.app.domain.model.AgentActionType.CREATE_DOCUMENT) {
                        _createdDocumentId.value = documentId
                    } else if (action.type == com.yumark.app.domain.model.AgentActionType.EDIT_DOCUMENT) {
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
    LaunchedEffect(conversationId, documentId) {
        viewModel.bind(conversationId, documentId, documentName, documentContent, onDocumentUpdated)
    }

    val messages by viewModel.messages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val error by viewModel.error.collectAsState()
    val createdDoc by viewModel.createdDocumentId.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); viewModel.clearError() } }
    LaunchedEffect(createdDoc) {
        createdDoc?.let { onNavigateToDocument(it); viewModel.consumeCreatedDocument() }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("Agent 对话", style = MaterialTheme.typography.titleMedium)
        }
        if (documentName != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Default.Description, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("当前文档：$documentName", style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (isStreaming) LinearProgressIndicator(Modifier.fillMaxWidth())
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 460.dp).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg) {
                    val action = msg.agentAction
                    if (action != null && msg.role == MessageRole.ASSISTANT) {
                        AgentActionCard(
                            action = action,
                            onApprove = { viewModel.approve(msg, action) },
                            onReject = { viewModel.reject(msg, action) }
                        )
                    }
                }
            }
        }

        SnackbarHost(snackbar)

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("让 AI 帮你创建或编辑文档…") },
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
