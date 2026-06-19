package com.yumark.app.presentation.ai.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 细动效流式指示条：一道 accent 扫光在 2dp 高的轨道上从左往右循环。
 * 取代裸 `LinearProgressIndicator`——更克制，呼应“正在生成”的呼吸感。
 */
@Composable
fun StreamingIndicator(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "stream-bar")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(AiDesign.StreamBarHeight)
    ) {
        val w = size.width
        val band = w * 0.32f
        // 扫光中心从 -band 滑到 w+band，覆盖整条轨道；用渐变笔刷做柔边光带。
        val center = -band + (w + band * 2f) * progress
        drawRect(color = accent.copy(alpha = 0.12f), size = size) // 轨道底
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, accent, Color.Transparent),
                startX = center - band / 2f,
                endX = center + band / 2f
            ),
            topLeft = Offset(center - band / 2f, 0f),
            size = androidx.compose.ui.geometry.Size(band, size.height)
        )
    }
}
