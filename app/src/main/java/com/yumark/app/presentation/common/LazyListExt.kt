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
    val total = layoutInfo.totalItemsCount
    if (total == 0) return false
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisible >= total - 1 - threshold
}
