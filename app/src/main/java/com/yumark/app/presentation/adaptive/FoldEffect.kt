package com.yumark.app.presentation.adaptive

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.Flow

/**
 * 观察当前窗口的折叠特征，提取「竖直分隔铰链」信息（无则返回 null）。
 *
 * 折叠姿态变化（展开 / 半开 book / 合拢）会经 [WindowInfoTracker] 推送新的 [WindowLayoutInfo]，
 * 驱动重组，使双窗格实时贴合铰链。
 */
@Composable
fun rememberHingeInfo(activity: Activity): HingeInfo? {
    // Flow<T> 是协变的，可安全地当作 Flow<T?> 以便给 collectAsStateWithLifecycle 一个 null 初值。
    val infoFlow: Flow<WindowLayoutInfo?> = remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
    }
    val layoutInfo by infoFlow.collectAsStateWithLifecycle(initialValue = null)

    return remember(layoutInfo) {
        layoutInfo?.displayFeatures
            ?.filterIsInstance<FoldingFeature>()
            ?.firstOrNull()
            ?.let { feature ->
                HingeInfo(
                    isVerticalSeparating = feature.isSeparating &&
                        feature.orientation == FoldingFeature.Orientation.VERTICAL,
                    boundsLeftPx = feature.bounds.left,
                    boundsWidthPx = feature.bounds.width()
                )
            }
    }
}
