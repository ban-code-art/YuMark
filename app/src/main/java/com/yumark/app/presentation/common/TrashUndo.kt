package com.yumark.app.presentation.common

/**
 * 「刚移入回收站」的撤销目标：Snackbar 上挂一个「撤销」按钮，点了就恢复这篇。
 * 文件列表与编辑器的删除入口共用。
 * [name] 只用于提示文案（取不到时界面退通用文案），恢复按 [id] 走。
 */
data class TrashUndo(val id: String, val name: String?) {
    companion object {
        /**
         * 撤销提示的存活时长：Snackbar 的 Long 档（≈10s）再放宽 1 秒。
         * 两处 VM（文件列表/编辑器）的撤销状态按它超时自清，见各自的 scheduleUndoExpiry。
         */
        const val EXPIRY_MS = 11_000L
    }
}
