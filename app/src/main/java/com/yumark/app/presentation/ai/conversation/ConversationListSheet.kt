package com.yumark.app.presentation.ai.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.domain.model.Conversation
import com.yumark.app.domain.model.ConversationType
import com.yumark.app.domain.usecase.ai.conversation.CreateConversationUseCase
import com.yumark.app.domain.usecase.ai.conversation.DeleteConversationUseCase
import com.yumark.app.domain.usecase.ai.conversation.GetAllConversationsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    getAllConversations: GetAllConversationsUseCase,
    private val createConversation: CreateConversationUseCase,
    private val deleteConversation: DeleteConversationUseCase
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> =
        getAllConversations().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun create(type: ConversationType, onCreated: (Conversation) -> Unit) {
        viewModelScope.launch {
            val title = if (type == ConversationType.AGENT) "新建 Agent 对话" else "新建对话"
            onCreated(createConversation(title, type))
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { deleteConversation(id) }
    }
}

@Composable
fun ConversationListContent(
    onOpen: (Conversation) -> Unit,
    onCreate: (ConversationType) -> Unit,
    viewModel: ConversationListViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsState()
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("AI 对话", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = { viewModel.create(ConversationType.CHAT) { onOpen(it) } },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建聊天")
            }
            FilledTonalButton(
                onClick = { viewModel.create(ConversationType.AGENT) { onOpen(it) } },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.SmartToy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建 Agent")
            }
        }

        Spacer(Modifier.height(8.dp))

        if (conversations.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("暂无对话，点击上方按钮开始", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(conversations, key = { it.id }) { conv ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                if (conv.type == ConversationType.AGENT) Icons.Default.SmartToy
                                else Icons.AutoMirrored.Filled.Chat,
                                null
                            )
                        },
                        headlineContent = { Text(conv.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(if (conv.type == ConversationType.AGENT) "Agent 对话" else "普通聊天")
                        },
                        trailingContent = {
                            IconButton(onClick = { pendingDelete = conv }) {
                                Icon(Icons.Default.Delete, "删除")
                            }
                        },
                        modifier = Modifier.clickable { onOpen(conv) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { conv ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除对话") },
            text = { Text("确定删除「${conv.title}」？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(conv.id); pendingDelete = null }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}
