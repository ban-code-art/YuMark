package com.yumark.app.presentation.ai.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.domain.model.AgentStatusCode
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.presentation.ai.common.AiDesign
import com.yumark.app.presentation.ai.common.StatusPill
import com.yumark.app.presentation.ai.common.agentTaskStatusVisual
import com.yumark.app.presentation.ai.common.stepStatusVisual
import com.yumark.app.presentation.common.resolve
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppMotion
import com.yumark.app.presentation.theme.AppSpacing

/**
 * Agent 执行时间轴——本次美化的签名元素。
 *
 * 用一条左侧连接式轨道把多步执行串起来：每步一个状态点（待执行=空心、进行中=脉冲、
 * 完成=实心、失败/阻塞=语义色、跳过=淡化），点之间用细连接线相连，活跃步骤标题加粗高亮。
 * 替换原扁平的 `AgentTaskProgressPanel`，**沿用同样的入参契约**（[collapsed]/[onToggleCollapse]
 * 由 ViewModel 的持久化偏好驱动，逻辑不变）。
 */
@Composable
fun AgentTimeline(
    progress: TaskProgressUiState,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val taskVisual = agentTaskStatusVisual(progress.status)
    val active = progress.status == AgentTaskStatus.EXECUTING ||
        progress.status == AgentTaskStatus.REPLANNING ||
        progress.status == AgentTaskStatus.PLANNING
    val cs = MaterialTheme.colorScheme

    // 本地展开态：以传入 [collapsed] 为初值、按状态重置。这样终态（已完成/失败）也能由
    // chevron 展开回看步骤；活跃态额外回写 [onToggleCollapse] 以持久化用户偏好。
    var localCollapsed by remember(progress.status) { mutableStateOf(collapsed) }

    // 单个共享脉冲（无条件调用——切勿条件式调用 @Composable，否则重组时组结构错乱崩溃）。
    // 仅在绘制 RUNNING 状态点时使用该值；其余状态点忽略它。
    val pulse = rememberPulse()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AiDesign.ScreenPadding, vertical = AppSpacing.Snug),
        shape = RoundedCornerShape(AiDesign.CardCorner),
        color = cs.surfaceVariant.copy(alpha = AiDesign.SoftFill),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // 进行中时左侧一条强调竖条，赋予“正在推进”的体感。
            Box(
                Modifier
                    .width(AgentTimelineMetrics.AccentBar)
                    .fillMaxHeight()
                    .background(if (active) taskVisual.color else Color.Transparent)
            )
            Column(Modifier.padding(start = AppSpacing.Cozy, top = AgentTimelineMetrics.ContentVertical, end = AppSpacing.Cozy, bottom = AgentTimelineMetrics.ContentVertical)) {
                // 折叠头：点击展开/收起。收起时只剩这一行。
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        localCollapsed = !localCollapsed
                        if (active) onToggleCollapse()
                    },
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Default),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.agent_timeline_task_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant
                    )
                    Text(
                        progress.goal,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    StatusPill(taskVisual)
                    Icon(
                        imageVector = if (localCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = stringResource(
                            if (localCollapsed) R.string.cd_agent_timeline_expand
                            else R.string.cd_agent_timeline_collapse
                        ),
                        modifier = Modifier.size(AppIconSize.Small),
                        tint = cs.onSurfaceVariant
                    )
                }

                AnimatedVisibility(
                    visible = !localCollapsed,
                    enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
                    exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
                ) {
                    Column {
                        Spacer(Modifier.height(AgentTimelineMetrics.ContentVertical))
                        val shown = progress.steps.take(MAX_STEPS)
                        shown.forEachIndexed { index, step ->
                            TimelineStepRow(
                                title = step.title,
                                status = step.status,
                                isActive = step.title == progress.activeStepTitle,
                                isFirst = index == 0,
                                isLast = index == shown.lastIndex && progress.steps.size <= MAX_STEPS,
                                pulse = pulse
                            )
                        }
                        if (progress.steps.size > MAX_STEPS) {
                            val hidden = progress.steps.size - MAX_STEPS
                            Text(
                                // count 传两次：一次选 quantity，一次做 %1$d 的实参
                                pluralStringResource(R.plurals.agent_timeline_more_steps, hidden, hidden),
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(start = AiDesign.TimelineRail, top = AppSpacing.Micro)
                            )
                        }

                        // 阻塞原因 / 结果摘要：步骤之外的附加信息，作为淡色提示行保留。
                        // 两列存的是 AgentStatusCode 稳定码，查表后才有文案；认不出的值
                        // （历史行、模型自己写的摘要）由 decode 按原文透出。
                        progress.blockingReason?.let { reason ->
                            AgentStatusCode.decode(reason)?.let { message ->
                                NoteRow(
                                    stringResource(R.string.agent_timeline_note_blocked),
                                    message.resolve(),
                                    cs.error
                                )
                            }
                        }
                        progress.finalSummary?.let { summary ->
                            AgentStatusCode.decode(summary)?.let { message ->
                                NoteRow(
                                    stringResource(R.string.agent_timeline_note_result),
                                    message.resolve(),
                                    cs.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_STEPS = 6

/** 单步行：左轨道（连接线 + 状态点）+ 标题 + 状态药丸。 */
@Composable
private fun TimelineStepRow(
    title: String,
    status: AgentTaskStepStatus,
    isActive: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    pulse: Float
) {
    val visual = stepStatusVisual(status)
    val connectorColor = MaterialTheme.colorScheme.outlineVariant
    val running = status == AgentTaskStepStatus.RUNNING
    val filled = status == AgentTaskStepStatus.DONE ||
        status == AgentTaskStepStatus.RUNNING ||
        status == AgentTaskStepStatus.FAILED ||
        status == AgentTaskStepStatus.BLOCKED

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // 左轨道：用 Canvas 画上下连接线段与状态点，点心固定在首行文字中线。
        androidx.compose.foundation.Canvas(
            modifier = Modifier.width(AiDesign.TimelineRail).fillMaxHeight()
        ) {
            val cx = size.width / 2
            val cy = AgentTimelineMetrics.DotCenterY.toPx()
            val stroke = AiDesign.TimelineConnector.toPx()
            val r = AiDesign.TimelineDot.toPx() / 2
            if (!isFirst) drawLine(connectorColor, Offset(cx, 0f), Offset(cx, cy), stroke)
            if (!isLast) drawLine(connectorColor, Offset(cx, cy), Offset(cx, size.height), stroke)
            if (running) {
                // 脉冲外环：随 pulse 放大并淡出。
                drawCircle(visual.color.copy(alpha = (1f - pulse) * 0.45f), radius = r * (1f + pulse), center = Offset(cx, cy))
            }
            if (filled) {
                drawCircle(visual.color, radius = r, center = Offset(cx, cy))
            } else {
                drawCircle(visual.color, radius = r, center = Offset(cx, cy), style = Stroke(width = stroke))
            }
        }

        Column(Modifier.weight(1f).padding(top = AppSpacing.Tight, bottom = AppSpacing.Tight)) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isActive -> visual.color
                    status == AgentTaskStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 仅非进行中/非完成态显示文字状态，减少视觉噪声（点已表意）。
        if (status != AgentTaskStepStatus.DONE && status != AgentTaskStepStatus.RUNNING) {
            Text(
                visual.label,
                style = MaterialTheme.typography.labelSmall,
                color = visual.color,
                modifier = Modifier.padding(top = AppSpacing.Tight)
            )
        }
    }
}

@Composable
private fun NoteRow(tag: String, text: String, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AiDesign.TimelineRail, top = AppSpacing.Snug),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Snug)
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(AiDesign.PillCorner))
                .background(accent.copy(alpha = 0.14f))
                .padding(horizontal = AppSpacing.Snug, vertical = AgentTimelineMetrics.TagChipVertical)
        ) {
            Text(tag, style = MaterialTheme.typography.labelSmall, color = accent)
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 状态点脉冲：0→1 循环，外环据此放大淡出。单实例共享给所有进行中步骤。 */
@Composable
private fun rememberPulse(): Float {
    val transition = rememberInfiniteTransition(label = "timeline-pulse")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )
    return value
}

/**
 * 本屏特有的纵向 / 组件尺寸，刻意不并入全局 [AppSpacing] / [AppIconSize] 或 [AiDesign]：
 * 均为离散于 8dp 标度的组件几何，保留原像素、不硬凑标度；时间轴自身几何（轨道宽 / 点径 /
 * 连接线）仍走 AiDesign。按 FileListMetrics 先例落屏幕局部。
 */
private object AgentTimelineMetrics {
    /** 左侧「推进中」强调竖条宽度。 */
    val AccentBar = 3.dp
    /** 卡片内容纵向节奏：内容区上下内距、头部↔步骤列表间隔共用此值。 */
    val ContentVertical = 10.dp
    /** NoteRow 标签片纵向内距（发丝级）。 */
    val TagChipVertical = 1.dp
    /** 状态点心相对行顶的固定偏移：对齐 bodySmall 首行中线。 */
    val DotCenterY = 12.dp
}
