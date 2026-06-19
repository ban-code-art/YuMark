package com.yumark.app.presentation.ai.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStepStatus

/**
 * AI/Agent 界面共享设计 token。
 *
 * 目标：把间距、圆角、状态→颜色/图标的映射集中一处，让时间轴、工具步骤行、
 * 操作卡、状态徽章共用同一套视觉语言。所有颜色都从 [MaterialTheme.colorScheme]
 * 派生——默认灰白与 Claude 赤陶两套主题、浅色/深色模式均自动成立，绝不写裸 hex。
 */
object AiDesign {
    /** 内容区左右安全边距。 */
    val ScreenPadding = 16.dp

    /** 气泡最大宽度（窄屏下保留留白）。 */
    val BubbleMaxWidth = 320.dp

    /** 气泡主圆角；非对称角在尾部收成 [BubbleTailCorner]。 */
    val BubbleCorner = 16.dp
    val BubbleTailCorner = 4.dp

    /** 卡片（操作卡 / 时间轴容器）圆角。 */
    val CardCorner = 14.dp

    /** 小药丸（状态徽章 / 文档上下文 chip）圆角。 */
    val PillCorner = 999.dp

    /** 时间轴左轨道宽度：状态点与连接线居中其中。 */
    val TimelineRail = 28.dp

    /** 时间轴状态点直径与连接线粗细。 */
    val TimelineDot = 12.dp
    val TimelineConnector = 2.dp

    /** Agent 字形徽标圆形直径。 */
    val GlyphSize = 34.dp

    /** 流式扫光条高度。 */
    val StreamBarHeight = 2.dp

    /** 容器色叠加在 surface 上的低强度填充透明度（药丸/卡片底）。 */
    const val SoftFill = 0.55f
}

/**
 * 一个状态对应的视觉三件套：强调色、淡填充容器色、图标、文案。
 * 时间轴点、工具行图标、操作卡徽章都从这里取，保证语义色一致。
 */
data class StatusVisual(
    val color: Color,
    val container: Color,
    val icon: ImageVector,
    val label: String
)

/** Agent 任务整体状态 → 视觉。 */
@Composable
fun agentTaskStatusVisual(status: AgentTaskStatus): StatusVisual {
    val cs = MaterialTheme.colorScheme
    return when (status) {
        AgentTaskStatus.PLANNING -> StatusVisual(cs.secondary, cs.secondaryContainer, Icons.Default.Schedule, "规划中")
        AgentTaskStatus.EXECUTING -> StatusVisual(cs.primary, cs.primaryContainer, Icons.Default.Sync, "执行中")
        AgentTaskStatus.REPLANNING -> StatusVisual(cs.primary, cs.primaryContainer, Icons.Default.Sync, "重新规划")
        AgentTaskStatus.BLOCKED -> StatusVisual(cs.secondary, cs.secondaryContainer, Icons.Default.Block, "已阻塞")
        AgentTaskStatus.COMPLETED -> StatusVisual(cs.primary, cs.primaryContainer, Icons.Default.Check, "已完成")
        AgentTaskStatus.FAILED -> StatusVisual(cs.error, cs.errorContainer, Icons.Default.ErrorOutline, "失败")
    }
}

/** Agent 单步状态 → 视觉（时间轴点 / 步骤标题）。 */
@Composable
fun stepStatusVisual(status: AgentTaskStepStatus): StatusVisual {
    val cs = MaterialTheme.colorScheme
    return when (status) {
        AgentTaskStepStatus.PENDING -> StatusVisual(cs.onSurfaceVariant, cs.surfaceVariant, Icons.Default.RadioButtonUnchecked, "待执行")
        AgentTaskStepStatus.RUNNING -> StatusVisual(cs.primary, cs.primaryContainer, Icons.Default.Sync, "进行中")
        AgentTaskStepStatus.DONE -> StatusVisual(cs.primary, cs.primaryContainer, Icons.Default.Check, "完成")
        AgentTaskStepStatus.BLOCKED -> StatusVisual(cs.secondary, cs.secondaryContainer, Icons.Default.Block, "阻塞")
        AgentTaskStepStatus.FAILED -> StatusVisual(cs.error, cs.errorContainer, Icons.Default.ErrorOutline, "失败")
        AgentTaskStepStatus.SKIPPED -> StatusVisual(cs.onSurfaceVariant, cs.surfaceVariant, Icons.Default.SkipNext, "跳过")
    }
}

/** Agent 操作（创建/编辑文档）状态 → 视觉（操作卡徽章）。 */
@Composable
fun actionStatusVisual(status: AgentActionStatus): StatusVisual {
    val cs = MaterialTheme.colorScheme
    return when (status) {
        AgentActionStatus.PENDING -> StatusVisual(cs.secondary, cs.secondaryContainer, Icons.Default.Schedule, "待确认")
        AgentActionStatus.APPROVED -> StatusVisual(cs.primary, cs.primaryContainer, Icons.Default.Check, "已批准")
        AgentActionStatus.REJECTED -> StatusVisual(cs.error, cs.errorContainer, Icons.Default.Close, "已拒绝")
        AgentActionStatus.EXECUTED -> StatusVisual(cs.primary, cs.primaryContainer, Icons.Default.Check, "已执行")
    }
}

/**
 * 小药丸状态徽章：淡填充底 + 图标 + 文案，强调色取自 [StatusVisual]。
 * 时间轴折叠头与操作卡共用，保证状态语义在全界面一致。
 */
@Composable
fun StatusPill(visual: StatusVisual, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AiDesign.PillCorner),
        color = visual.container,
        contentColor = visual.color
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(visual.icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = visual.color)
            Text(visual.label, style = MaterialTheme.typography.labelSmall, color = visual.color)
        }
    }
}
