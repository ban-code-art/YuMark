package com.yumark.app.presentation.ai.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yumark.app.core.util.diff.DiffComposer
import com.yumark.app.core.util.diff.LineDiffer
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.presentation.ai.common.AiDesign
import com.yumark.app.presentation.ai.common.DiffView
import com.yumark.app.presentation.ai.common.StatusPill
import com.yumark.app.presentation.ai.common.actionStatusVisual

/**
 * Agent 操作卡片。
 * - EDIT_DOCUMENT 且提供 [baseContent]：进入 **diff 闸门**——展示逐行改动、逐 hunk 接受/拒绝，
 *   批准时把合成内容经 [onApproveDiff] 回传（D1：改用户既有文档前必经审阅）。
 * - 其余（CREATE_DOCUMENT 或未提供 base）：整体预览 + [onApprove]/[onReject]。
 */
@Composable
fun AgentActionCard(
    action: AgentAction,
    baseContent: String? = null,
    onApproveDiff: (finalContent: String) -> Unit = {},
    onApprove: () -> Unit = {},
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val diffMode = action.type == AgentActionType.EDIT_DOCUMENT && baseContent != null
    if (diffMode) {
        EditDiffCard(action, baseContent!!, onApproveDiff, onReject, modifier)
    } else {
        WholeContentCard(action, onApprove, onReject, modifier)
    }
}

/** EDIT 路径：行级 diff + 逐 hunk 接受/拒绝 + 合成应用。 */
@Composable
private fun EditDiffCard(
    action: AgentAction,
    baseContent: String,
    onApproveDiff: (String) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier
) {
    val diff = remember(baseContent, action.content) { LineDiffer.diff(baseContent, action.content) }
    val accepted = remember(diff) {
        mutableStateListOf<Boolean>().apply { repeat(diff.hunks.size) { add(true) } }
    }
    var expanded by remember(diff) { mutableStateOf(true) }

    Card(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(AiDesign.CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionHeader(AgentActionType.EDIT_DOCUMENT, action.status)
            Text(action.description, style = MaterialTheme.typography.bodyMedium)

            // 用 if/else 而非 early return：应用后 baseContent 更新会让 diff 重算为「无改动」，
            // 若用 return@Column 会改变可组合组结构 → 重组时 Compose 组栈下溢崩溃。
            if (!diff.hasChanges) {
                Text(
                    "AI 未提出实际改动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (action.status == AgentActionStatus.PENDING) {
                    OutlinedButton(onClick = onReject) { Text("知道了") }
                }
            } else {
                if (diff.degraded) {
                    Text(
                        "文档较大，已切换为整体对照预览。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (expanded) "收起改动" else "查看改动")
                }
                AnimatedVisibility(visible = expanded) {
                    DiffView(
                        result = diff,
                        accepted = accepted,
                        onToggleHunk = { id -> accepted[id] = !accepted[id] }
                    )
                }

                if (action.status == AgentActionStatus.PENDING) {
                    val acceptedCount = accepted.count { it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onApproveDiff(DiffComposer.applyHunks(diff, accepted)) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("应用已选 $acceptedCount 处")
                        }
                        OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("全部拒绝")
                        }
                    }
                }
            }
        }
    }
}

/** CREATE / 无 base 路径：整体内容预览 + 批准/拒绝（原行为）。 */
@Composable
private fun WholeContentCard(
    action: AgentAction,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(AiDesign.CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionHeader(action.type, action.status)

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

/** 操作卡头部：类型字形徽标 + 类型名 + 右侧状态药丸。创建/编辑共用。 */
@Composable
private fun ActionHeader(type: AgentActionType, status: AgentActionStatus) {
    val cs = MaterialTheme.colorScheme
    val (icon, label) = when (type) {
        AgentActionType.CREATE_DOCUMENT -> Icons.Default.Add to "创建文档"
        AgentActionType.EDIT_DOCUMENT -> Icons.Default.Edit to "编辑文档"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.size(26.dp).clip(CircleShape).background(cs.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = cs.onPrimaryContainer)
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        StatusPill(actionStatusVisual(status))
    }
}
