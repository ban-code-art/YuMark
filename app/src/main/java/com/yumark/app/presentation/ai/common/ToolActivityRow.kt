package com.yumark.app.presentation.ai.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yumark.app.domain.model.AgentStep

/**
 * 一行 Agent 工具活动：状态图标 + 自然语言动作 + 等宽参数摘要。
 * 取代原先 `🔧 调用…` 的 emoji 纯文本行。活跃（正在调用）行图标做轻微 shimmer。
 */
@Composable
fun ToolActivityRow(
    step: AgentStep,
    active: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val (icon, tint) = when (step) {
        is AgentStep.ToolCalling -> Icons.Default.Build to cs.primary
        is AgentStep.ToolDone -> if (step.ok) Icons.Default.Check to cs.primary else Icons.Default.Close to cs.error
    }
    // shimmer 动画：无条件创建 Animatable + LaunchedEffect（切勿条件式调用 @Composable），
    // 仅当 active 时循环呼吸，非 active 时停在 1f。
    val shimmer = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (active) {
            while (true) {
                shimmer.animateTo(0.4f, tween(900))
                shimmer.animateTo(1f, tween(900))
            }
        } else {
            shimmer.snapTo(1f)
        }
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp).alpha(shimmer.value),
            tint = tint
        )
        when (step) {
            is AgentStep.ToolCalling -> {
                Text(
                    narrateTool(step.tool),
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurface,
                )
                if (step.argsSummary.isNotBlank()) {
                    Text(
                        step.argsSummary,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            is AgentStep.ToolDone -> {
                Text(
                    narrateTool(step.tool),
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                )
                if (step.summary.isNotBlank()) {
                    Text(
                        "→ ${step.summary}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 把工具名转成面向用户的自然语言动作。 */
fun narrateTool(tool: String): String = when (tool) {
    "read_document" -> "读取文档"
    "search_in_project" -> "检索项目"
    "list_documents" -> "浏览文档"
    else -> tool
}
