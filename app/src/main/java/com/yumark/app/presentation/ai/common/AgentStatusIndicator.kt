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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yumark.app.domain.model.ConversationStatus

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
    size: Dp = 40.dp
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            ConversationStatus.IDLE -> {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = "Agent",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ConversationStatus.WORKING -> {
                WorkingAnimation(size = size)
            }
            ConversationStatus.COMPLETED -> {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "已完成",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(size * 0.6f)
                )
            }
        }
    }
}

/**
 * 工作中的水波扩散动画
 */
@Composable
private fun WorkingAnimation(size: Dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "ripple")

    // 第一个波纹
    val ripple1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple1"
    )

    // 第二个波纹（延迟 0.6 秒）
    val ripple2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple2"
    )

    // 第三个波纹（延迟 1.2 秒）
    val ripple3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple3"
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.size(size)) {
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
                    style = Stroke(width = 2.dp.toPx())
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
