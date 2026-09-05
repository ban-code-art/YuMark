package com.yumark.app.presentation.navigation

import java.net.URLEncoder

sealed class Screen(val route: String) {
    data object FileList : Screen("files")
    data object Editor : Screen("editor?documentId={documentId}&docUri={docUri}") {
        fun createRoute(documentId: String) = "editor?documentId=${encodeRouteArg(documentId)}"
        fun createExternalRoute(docUri: String) = "editor?docUri=${encodeRouteArg(docUri)}"
    }
    data object Settings : Screen("settings")
    data object AiConfig : Screen("ai_config")
    data object Sync : Screen("sync")
}

/**
 * 把参数值编码成能安全塞进 Navigation 路由的形式。
 *
 * 不能直接交给 [URLEncoder] 了事：它按 `application/x-www-form-urlencoded` 把空格编成 `+`，
 * 而 Navigation 取参数时走的是百分号解码（`Uri.decode`），`+` 会被原样留下。于是
 * `file:///sdcard/my note.md` 这类含裸空格的外部 URI 到了编辑器手里变成 `my+note.md`，
 * 表现为「从文件管理器打开就说文件不存在」，而两边各自看都没错。补一步 `+` → `%20` 让两侧配对。
 *
 * 值里本来就有的 `+` 不受影响：[URLEncoder] 先把它编成 `%2B`，替换时已经没有 `+` 可匹配。
 * 同理，本来就是 `%20` 的（SAF 给出的 content URI 都已百分号编码）会被再编一层成 `%2520`，
 * Navigation 解码一次正好还原 —— 这是对的，少编这一层才会丢字符。
 */
private fun encodeRouteArg(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
