package com.yumark.app.presentation.ai

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.yumark.app.domain.model.ConversationType
import com.yumark.app.presentation.ai.agent.AgentContent
import com.yumark.app.presentation.ai.chat.ChatContent
import com.yumark.app.presentation.ai.conversation.ConversationListContent

private sealed interface AiScreen {
    data object List : AiScreen
    data class Chat(val id: String) : AiScreen
    data class Agent(val id: String) : AiScreen
}

/**
 * AI 助手入口宿主：单个 BottomSheet 内在 对话列表 ↔ 聊天/Agent 间切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantHost(
    currentDocumentId: String?,
    currentDocumentName: String?,
    currentDocumentContent: String?,
    onNavigateToDocument: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var screen by remember { mutableStateOf<AiScreen>(AiScreen.List) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        when (val s = screen) {
            is AiScreen.List -> ConversationListContent(
                onOpen = { conv ->
                    screen = if (conv.type == ConversationType.AGENT) AiScreen.Agent(conv.id)
                    else AiScreen.Chat(conv.id)
                },
                onCreate = { /* 创建在列表内完成并回调 onOpen */ }
            )

            is AiScreen.Chat -> ChatContent(
                conversationId = s.id,
                onBack = { screen = AiScreen.List },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            )

            is AiScreen.Agent -> AgentContent(
                conversationId = s.id,
                documentId = currentDocumentId,
                documentName = currentDocumentName,
                documentContent = currentDocumentContent,
                onBack = { screen = AiScreen.List },
                onNavigateToDocument = { docId ->
                    onNavigateToDocument(docId)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            )
        }
    }
}
