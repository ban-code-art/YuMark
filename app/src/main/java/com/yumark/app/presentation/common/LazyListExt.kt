package com.yumark.app.presentation.common

import androidx.compose.foundation.lazy.LazyListState

/**
 * 判断列表是否已滚动到接近底部。
 *
 * 用于 AI 对话流式自动滚动：仅当用户本就在底部附近时才跟随滚动，
 * 避免用户向上翻阅历史时被流式新 token 强行拽回底部。
 *
 * 无可见项时（首次布局/列表尚未测量）视为「在底部」，使首次打开能正确滚到末条。
 */
fun LazyListState.isNearBottom(threshold: Int = 1): Boolean {
    val info = layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return false
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
    if (lastVisible.index < total - 1 - threshold) return false
    // 末项比视口高时（超长 AI 回复），单看"末项可见"会误判——只要它铺满屏幕就恒真，
    // 导致用户在长回复里上滑阅读时无法解除跟随。改看末项底部是否真的贴近视口底部：
    // 仍停在消息上半部分时判定为"非底部"，自动跟随才让位给用户翻阅。
    val slack = info.viewportSize.height / 3
    return (lastVisible.offset + lastVisible.size) <= info.viewportEndOffset + slack
}
