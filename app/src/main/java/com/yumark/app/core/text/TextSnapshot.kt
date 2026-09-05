package com.yumark.app.core.text

/**
 * 编辑器文本的一次完整状态：正文 + 选区。
 *
 * 撤销必须连带光标一起回滚——只还原文本会把光标留在早已不存在的位置上，
 * 用户下一次输入就插到错误的地方。
 *
 * [selectionStart] / [selectionEnd] 为字符下标，`end` 独占；两者相等表示折叠光标。
 */
data class TextSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int
) {
    /** 折叠光标（无选中区间）。 */
    val isCollapsed: Boolean get() = selectionStart == selectionEnd

    companion object {
        /** 空文档初始态。 */
        val EMPTY = TextSnapshot("", 0, 0)

        /** 光标落在 [text] 末尾的快照——加载文档时的默认位置。 */
        fun atEnd(text: String): TextSnapshot = TextSnapshot(text, text.length, text.length)
    }
}
