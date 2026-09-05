package com.yumark.app.presentation.navigation

/**
 * 一次「用外部 URI 打开编辑器」的请求。
 *
 * 带 [seq] 而不是直接往下传 URI 字符串：导航副作用挂在 `LaunchedEffect(request)` 上，而
 * `mutableStateOf` 判定变更用的是结构相等。用户第二次从文件管理器打开**同一个**文件时，
 * 纯字符串写回去与旧值相等 —— Compose 不重组，副作用不重跑，表现为「点了没反应」。
 * 递增的 [seq] 让每次请求都是一个新值，同一个 URI 也能再次触发导航。
 *
 * [seq] 只在进程内单调递增，不需要持久化：Activity 重建后不该重放旧请求
 * （见 `MainActivity.onCreate` 里的 `savedInstanceState` 守卫）。
 */
data class ExternalOpenRequest(val uri: String, val seq: Long)
