package com.yumark.app.presentation.ai.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yumark.app.domain.model.Conversation
import com.yumark.app.domain.model.ConversationStatus
import com.yumark.app.domain.model.ConversationType
import com.yumark.app.domain.repository.ConversationRepository
import com.yumark.app.domain.usecase.ai.conversation.CreateConversationUseCase
import com.yumark.app.domain.usecase.ai.conversation.DeleteConversationUseCase
import com.yumark.app.domain.usecase.ai.conversation.GetAllConversationsUseCase
import com.yumark.app.presentation.ai.common.AgentStatusIndicator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    getAllConversations: GetAllConversationsUseCase,
    private val createConversation: CreateConversationUseCase,
    private val deleteConversation: DeleteConversationUseCase,
    private val conversationRepository: ConversationRepository
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

    fun rename(id: String, newTitle: String) {
        viewModelScope.launch {
            conversationRepository.observeConversation(id).first()?.let { conversation ->
                conversationRepository.updateConversation(
                    conversation.copy(title = newTitle)
                )
            }
        }
    }
}

@Composable
fun ConversationListContent(
    onOpen: (Conversation) -> Unit,
    @Suppress("UNUSED_PARAMETER")
    onCreate: (ConversationType) -> Unit,
    viewModel: ConversationListViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var pendingRename by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showMenuForConv by remember { mutableStateOf<String?>(null) }  // 显示菜单的对话 ID

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
                            // Agent 对话显示状态指示器，普通聊天显示普通图标
                            if (conv.type == ConversationType.AGENT) {
                                AgentStatusIndicator(
                                    status = conv.status,
                                    size = 40.dp
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Chat, null)
                            }
                        },
                        headlineContent = { Text(conv.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                // 关联文档信息
                                if (conv.relatedDocumentName != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Description,
                                            null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            conv.relatedDocumentName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // 上次使用时间
                                Text(
                                    formatRelativeTime(conv.updatedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { showMenuForConv = conv.id }) {
                                    Icon(Icons.Default.MoreVert, "更多选项")
                                }

                                DropdownMenu(
                                    expanded = showMenuForConv == conv.id,
                                    onDismissRequest = { showMenuForConv = null }
                                ) {
                                    // 重命名
                                    DropdownMenuItem(
                                        text = { Text("重命名") },
                                        onClick = {
                                            pendingRename = conv
                                            renameText = conv.title
                                            showMenuForConv = null
                                        },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) }
                                    )

                                    // 删除
                                    DropdownMenuItem(
                                        text = { Text("删除") },
                                        onClick = {
                                            pendingDelete = conv
                                            showMenuForConv = null
                                        },
                                        leadingIcon = { Icon(Icons.Default.Delete, null) }
                                    )
                                }
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

    pendingRename?.let { conv ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("重命名对话") },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    placeholder = { Text("输入新名称") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            viewModel.rename(conv.id, renameText.trim())
                            pendingRename = null
                        }
                    },
                    enabled = renameText.isNotBlank()
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 格式化相对时间
 * - 今天：显示具体时间（如 "14:30"）
 * - 昨天：显示 "昨天"
 * - 本周：显示星期（如 "周一"）
 * - 更早：显示日期（如 "06/13"）
 */
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val nowCalendar = Calendar.getInstance()

    return when {
        // 今天
        calendar.get(Calendar.YEAR) == nowCalendar.get(Calendar.YEAR) &&
        calendar.get(Calendar.DAY_OF_YEAR) == nowCalendar.get(Calendar.DAY_OF_YEAR) -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        // 昨天
        calendar.get(Calendar.YEAR) == nowCalendar.get(Calendar.YEAR) &&
        calendar.get(Calendar.DAY_OF_YEAR) == nowCalendar.get(Calendar.DAY_OF_YEAR) - 1 -> {
            "昨天"
        }
        // 本周
        diff < 7 * 24 * 60 * 60 * 1000 -> {
            val weekdays = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
            weekdays[calendar.get(Calendar.DAY_OF_WEEK) - 1]
        }
        // 更早
        else -> {
            SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
