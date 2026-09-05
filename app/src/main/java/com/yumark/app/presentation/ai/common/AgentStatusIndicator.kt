package com.yumark.app.presentation.ai.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.domain.model.ConversationStatus
import com.yumark.app.presentation.theme.extendedColors

/**
 * Agent 状态指示器
 * - IDLE: 普通图标
 * - WORKING: 水波扩散动画
 * - COMPLETED: 完成图标
 */
@Composable
fun AgentStatusIndicator(
    status: ConversationStatus,
    modifier: Modifier = Modifier,
    size: Dp = AgentStatusMetrics.DefaultSize
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            ConversationStatus.IDLE -> {
                Icon(
                    Icons.Default.SmartToy,
                    // 两个语区都念作 "Agent"：读屏播报的是产品里这个功能的名字
                    contentDescription = stringResource(R.string.cd_agent_status_idle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ConversationStatus.WORKING -> {
                // Canvas 自己不产生任何语义节点，读屏原先会整块跳过 WORKING 态
                // （既没有图标也没有文字），这里补一句播报。
                val workingDescription = stringResource(R.string.cd_agent_status_working)
                WorkingAnimation(
                    size = size,
                    modifier = Modifier.semantics { contentDescription = workingDescription }
                )
            }
            ConversationStatus.COMPLETED -> {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.cd_agent_status_completed),
                    tint = extendedColors.primaryText,
                    modifier = Modifier.size(size * 0.6f)
                )
            }
        }
    }
}

/**
 * 工作中的水波扩散动画
 *
 * modifier 参数是为了让调用方挂 semantics：Canvas 本身没有语义节点，
 * 读屏需要外部补 contentDescription。
 */
@Composable
private fun WorkingAnimation(size: Dp, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "ripple")

    // 第一个波纹
    val ripple1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(AgentStatusMetrics.RippleDurationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple1"
    )

    // 第二个波纹（延迟 0.6 秒）
    val ripple2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(AgentStatusMetrics.RippleDurationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple2"
    )

    // 第三个波纹（延迟 1.2 秒）
    val ripple3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(AgentStatusMetrics.RippleDurationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple3"
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier.size(size)) {
        val centerX = this.size.width / 2
        val centerY = this.size.height / 2
        val maxRadius = this.size.minDimension / 2

        // 绘制三个波纹
        listOf(
            Triple(ripple1, 0.0f, 1.0f),
            Triple((ripple2 + 0.3f) % 1f, 0.3f, 0.7f),
            Triple((ripple3 + 0.6f) % 1f, 0.6f, 0.4f)
        ).forEach { (progress, _, alphaMultiplier) ->
            if (progress > 0.01f) {
                val radius = maxRadius * progress
                val alpha = (1f - progress) * alphaMultiplier

                drawCircle(
                    color = primaryColor.copy(alpha = alpha),
                    radius = radius,
                    center = androidx.compose.ui.geometry.Offset(centerX, centerY),
                    style = Stroke(width = AgentStatusMetrics.RippleStroke.toPx())
                )
            }
        }

        // 中心图标
        drawCircle(
            color = primaryColor,
            radius = maxRadius * 0.3f,
            center = androidx.compose.ui.geometry.Offset(centerX, centerY)
        )
    }
}

/**
 * 本指示器特有的度量，刻意不并入全局图标 / 间距标度：默认整体直径（40dp，介于 Avatar32 与
 * Hero48）、水波圆环描边宽度（绘制轴）、水波单程时长。离散于全局标度，保留原像素与原时长、不硬凑。
 * 按 FileListMetrics 先例落屏幕局部。
 */
private object AgentStatusMetrics {
    /** 指示器默认直径：调用方可覆盖；off-grid（介于 Avatar32/Hero48）。 */
    val DefaultSize = 40.dp
    /** 水波扩散圆环描边宽度：绘制轴，非间距。 */
    val RippleStroke = 2.dp
    /** 单个水波从生到灭的时长（毫秒），三环同时长、错相位。 */
    const val RippleDurationMs = 2000
}
