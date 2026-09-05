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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.domain.model.AgentStep
import com.yumark.app.presentation.theme.AppSpacing

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
                shimmer.animateTo(0.4f, tween(ToolActivityMetrics.ShimmerHalfCycleMs))
                shimmer.animateTo(1f, tween(ToolActivityMetrics.ShimmerHalfCycleMs))
            }
        } else {
            shimmer.snapTo(1f)
        }
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = AppSpacing.Micro),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Snug)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(ToolActivityMetrics.IconSize).alpha(shimmer.value),
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

/**
 * 把工具名转成面向用户的自然语言动作。
 *
 * when 的**键**（read_document / search_in_project / list_documents）是发给模型的
 * function name，属于协议字面量，一个字都不能翻；只有分支产出的旁白进了资源。
 * else 分支原样返回工具名：新工具还没配旁白时至少露出它的真名，比显示空白有用。
 * 为了能调 stringResource 而改成 @Composable —— 两处调用点都在 ToolActivityRow 的
 * 组合作用域内（when 分支里调 @Composable 是允许的，不是条件式创建状态）。
 */
@Composable
fun narrateTool(tool: String): String = when (tool) {
    "read_document" -> stringResource(R.string.ai_tool_read_document)
    "search_in_project" -> stringResource(R.string.ai_tool_search_in_project)
    "list_documents" -> stringResource(R.string.ai_tool_list_documents)
    else -> tool
}

/**
 * 本工具活动行特有的度量，刻意不并入全局间距 / 图标标度：状态图标直径（14dp，比最小图标标度 16
 * 更紧凑）、active 行图标呼吸 shimmer 的单程时长。离散于全局标度，保留原像素与原时长、不硬凑。
 * 按 FileListMetrics 先例落屏幕局部。
 */
private object ToolActivityMetrics {
    /** 工具行状态图标直径：14dp，比最小图标标度(16)略紧，贴合密排步骤行；off-grid。 */
    val IconSize = 14.dp
    /** active（正在调用）行图标呼吸 shimmer 的单程时长（毫秒）。 */
    const val ShimmerHalfCycleMs = 900
}
