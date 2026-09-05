package com.yumark.app.presentation.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * 一次性提示：把 [message] 弹成 Snackbar，弹完就调 [onConsumed] 把 ViewModel 里的状态清掉。
 *
 * 全项目八处「错误/提示 → Snackbar → 清状态」原先各写一遍，且都栽在同一个坑上，所以收成一个。
 *
 * **清空必须在 `finally` 里。** [SnackbarHostState.showSnackbar] 会一直挂起到提示消失，
 * 这期间只要 [message] 变了（来了新提示、页面被销毁），效果就被取消——写在 `showSnackbar`
 * 之后的那一句于是永远不跑，提示一直留在 ViewModel 里，下次进这个页面又弹一遍。
 * `finally` 在取消路径上照样执行，正好补上这个洞。
 *
 * 反过来「先清空再弹」也不行：清空让 [message] 变 null，key 一变当场把自己取消掉，
 * 提示一次都弹不出来。
 *
 * [message] 传的是**已解析的文案**而不是 [com.yumark.app.core.util.UiMessage]：解析要组合期的
 * 资源上下文，而且用文案当 key 顺带让语区切换也能正确重弹。
 */
@Composable
fun SnackbarEffect(
    message: String?,
    hostState: SnackbarHostState,
    duration: SnackbarDuration = SnackbarDuration.Short,
    onConsumed: () -> Unit
) {
    // 效果的生命周期比一次组合长，捕获到的 lambda 可能已经过期；这里始终调最新的那个。
    val consume = rememberUpdatedState(onConsumed)
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        try {
            hostState.showSnackbar(text, duration = duration)
        } finally {
            consume.value()
        }
    }
}
