package com.yumark.app.presentation.ai.conversation

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
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
import com.yumark.app.domain.model.Conversation
import com.yumark.app.domain.model.ConversationStatus
import com.yumark.app.domain.model.ConversationType
import com.yumark.app.domain.repository.ConversationRepository
import com.yumark.app.domain.usecase.ai.conversation.CreateConversationUseCase
import com.yumark.app.domain.usecase.ai.conversation.DeleteConversationUseCase
import com.yumark.app.domain.usecase.ai.conversation.GetAllConversationsUseCase
import com.yumark.app.presentation.ai.common.AgentStatusIndicator
import com.yumark.app.presentation.ai.common.AiDesign
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppSpacing
import com.yumark.app.presentation.theme.extendedColors
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

    /**
     * 新建对话。[defaultTitle] 由界面按当前语言解析后传进来——ViewModel 没有 Resources，
     * 默认标题又是要写进库、之后一直显示给用户的文案，不能在这里硬编码。
     */
    fun create(type: ConversationType, defaultTitle: String, onCreated: (Conversation) -> Unit) {
        viewModelScope.launch {
            onCreated(createConversation(defaultTitle, type))
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
    // 新建对话的默认标题：组合期解析，跟随系统语言；也是传给 ViewModel 的入参
    val defaultChatTitle = stringResource(R.string.ai_conversation_default_title_chat)
    val defaultAgentTitle = stringResource(R.string.ai_conversation_default_title_agent)
    // formatTimeBucket 是普通函数（不是 @Composable），只能把 Resources 传进去；
    // 用 LocalResources 而不是 LocalContext.current.getString——后者被 lint 判 error，
    // 且配置变更后拿到的可能是旧语言。
    val resources = LocalResources.current

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = AiDesign.ScreenPadding).testTag(ConversationListTestTags.ROOT)) {
        Text(
            stringResource(R.string.ai_conversation_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = AppSpacing.Default)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Default), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = { viewModel.create(ConversationType.CHAT, defaultChatTitle) { onOpen(it) } },
                modifier = Modifier.weight(1f).testTag(ConversationListTestTags.NEW_CHAT)
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(AppIconSize.Small))
                Spacer(Modifier.width(AppSpacing.Snug))
                Text(stringResource(R.string.ai_conversation_new_chat))
            }
            Button(
                onClick = { viewModel.create(ConversationType.AGENT, defaultAgentTitle) { onOpen(it) } },
                modifier = Modifier.weight(1f).testTag(ConversationListTestTags.NEW_AGENT)
            ) {
                Icon(Icons.Default.SmartToy, null, Modifier.size(AppIconSize.Small))
                Spacer(Modifier.width(AppSpacing.Snug))
                Text(stringResource(R.string.ai_conversation_new_agent))
            }
        }

        Spacer(Modifier.height(AppSpacing.Default))

        if (conversations.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(AppSpacing.Section), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.ai_conversation_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = ConversationListMetrics.ListMaxHeight),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Snug),
                contentPadding = PaddingValues(vertical = AppSpacing.Tight)
            ) {
                items(conversations, key = { it.id }) { conv ->
                    Surface(
                        shape = RoundedCornerShape(AiDesign.CardCorner),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AiDesign.SoftFill),
                        modifier = Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .clickable { onOpen(conv) }
                            .testTag(ConversationListTestTags.ITEM_PREFIX + conv.id)
                    ) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = {
                            // Agent 对话显示状态指示器，普通聊天显示普通图标
                            if (conv.type == ConversationType.AGENT) {
                                AgentStatusIndicator(
                                    status = conv.status,
                                    size = ConversationListMetrics.LeadingAvatar
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Chat, null)
                            }
                        },
                        headlineContent = { Text(conv.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
                                // 关联文档信息
                                if (conv.relatedDocumentName != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Tight)
                                    ) {
                                        Icon(
                                            Icons.Default.Description,
                                            null,
                                            modifier = Modifier.size(ConversationListMetrics.RelatedDocIcon),
                                            tint = extendedColors.primaryText
                                        )
                                        Text(
                                            conv.relatedDocumentName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = extendedColors.primaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // 上次使用时间
                                Text(
                                    formatTimeBucket(conv.updatedAt, resources),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { showMenuForConv = conv.id }) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.cd_more_options))
                                }

                                DropdownMenu(
                                    expanded = showMenuForConv == conv.id,
                                    onDismissRequest = { showMenuForConv = null }
                                ) {
                                    // 重命名
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rename)) },
                                        onClick = {
                                            pendingRename = conv
                                            renameText = conv.title
                                            showMenuForConv = null
                                        },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) }
                                    )

                                    // 删除
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.delete)) },
                                        onClick = {
                                            pendingDelete = conv
                                            showMenuForConv = null
                                        },
                                        leadingIcon = { Icon(Icons.Default.Delete, null) }
                                    )
                                }
                            }
                        },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { conv ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.ai_conversation_delete_title)) },
            text = { Text(stringResource(R.string.ai_conversation_delete_message, conv.title)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(conv.id); pendingDelete = null }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    pendingRename?.let { conv ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.ai_conversation_rename_title)) },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.ai_conversation_rename_hint)) }
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
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/** UI 测试锚点：文案会随语言变，测试只能靠稳定的英文 tag 定位。 */
private object ConversationListTestTags {
    const val ROOT = "ai_conversation_root"
    const val NEW_CHAT = "ai_conversation_new_chat"
    const val NEW_AGENT = "ai_conversation_new_agent"
    /** 每个对话条目一个 tag：拼对话 id 保证同一棵树里唯一。 */
    const val ITEM_PREFIX = "ai_conversation_item_"
}

/**
 * 按日历分档格式化时间戳
 * - 今天：显示具体时间（如 "14:30"）
 * - 昨天：显示 "昨天"
 * - 本周：显示星期（如 "周一"）
 * - 更早：显示日期（如 "06/13"）
 *
 * [resources] 由调用方从 `LocalResources.current` 传入：这个函数不是 @Composable，
 * 拿不到组合期的资源上下文，而"昨天"和星期名必须随语言变。
 *
 * 叫 formatTimeBucket 而不是 formatRelativeTime：FileListScreen 里另有一个曾经同名的函数，
 * 两者**不合并**。那个算已流逝时长（"5 分钟前"），这个是日历归档——同一个 23:50 的时间戳，
 * 过十几分钟之后那边报「刚刚」，这边已经报「昨天」。两套语义只是恰好都能叫"相对时间"。
 */
private fun formatTimeBucket(timestamp: Long, resources: Resources): String {
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
            resources.getString(R.string.ai_conversation_yesterday)
        }
        // 本周
        diff < 7 * 24 * 60 * 60 * 1000 -> {
            // 数组顺序与 Calendar.DAY_OF_WEEK 一致（周日在首位），下标即 DAY_OF_WEEK - 1
            val weekdays = resources.getStringArray(R.array.ai_conversation_weekdays)
            weekdays[calendar.get(Calendar.DAY_OF_WEEK) - 1]
        }
        // 更早
        else -> {
            SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

/**
 * 本屏特有的布局上界 / 组件尺寸，刻意不并入全局 [AppSpacing] / [AppIconSize] 或 [AiDesign]：
 * 会话列表最大高度是布局上界；前导头像 40 为 M3 ListItem 前导常用径，关联文档小图标 14 钉到
 * bodySmall 字号以与文字齐平——两者都离散于图标标度，保留原像素、不硬凑。按 FileListMetrics 先例落屏幕局部。
 */
private object ConversationListMetrics {
    /** 列表最大高度：超出滚动，避免会话多时把 sheet 撑满。 */
    val ListMaxHeight = 420.dp
    /** ListItem 前导头像 / Agent 状态指示器直径。 */
    val LeadingAvatar = 40.dp
    /** 关联文档行内小图标：钉到 bodySmall 字号（≈14）才与文字齐平，比 AppIconSize.Inline(16) 小。 */
    val RelatedDocIcon = 14.dp
}
