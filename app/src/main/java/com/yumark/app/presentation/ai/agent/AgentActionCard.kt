package com.yumark.app.presentation.ai.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.AgentActionType

/** Agent 操作卡片：展示操作类型、描述、内容预览与批准/拒绝。 */
@Composable
fun AgentActionCard(
    action: AgentAction,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val typeLabel = when (action.type) {
                    AgentActionType.CREATE_DOCUMENT -> "创建文档"
                    AgentActionType.EDIT_DOCUMENT -> "编辑文档"
                }
                AssistChip(onClick = {}, label = { Text(typeLabel) })
                Spacer(Modifier.weight(1f))
                StatusBadge(action.status)
            }

            Text(action.description, style = MaterialTheme.typography.bodyMedium)

            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                Spacer(Modifier.width(4.dp))
                Text(if (expanded) "收起内容" else "查看内容")
            }
            AnimatedVisibility(visible = expanded) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        action.content,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    )
                }
            }

            if (action.status == AgentActionStatus.PENDING) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("批准")
                    }
                    OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("拒绝")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: AgentActionStatus) {
    val (label, color) = when (status) {
        AgentActionStatus.PENDING -> "待确认" to MaterialTheme.colorScheme.secondary
        AgentActionStatus.APPROVED -> "已批准" to MaterialTheme.colorScheme.primary
        AgentActionStatus.REJECTED -> "已拒绝" to MaterialTheme.colorScheme.error
        AgentActionStatus.EXECUTED -> "已执行" to MaterialTheme.colorScheme.primary
    }
    Text(label, color = color, style = MaterialTheme.typography.labelMedium)
}
