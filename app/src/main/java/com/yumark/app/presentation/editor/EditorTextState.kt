package com.yumark.app.presentation.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.yumark.app.core.text.TextSnapshot
import com.yumark.app.core.text.UndoRedoStack

/**
 * [TextFieldValue] → [TextSnapshot]。
 *
 * 选区按 Compose 的 start/end 原样带走（反向拖选时 start > end，这是合法状态，不要在这里"修正"，
 * 否则撤销回来的选区方向会莫名反转）。
 */
fun TextFieldValue.toSnapshot(): TextSnapshot = TextSnapshot(text, selection.start, selection.end)

/**
 * [TextSnapshot] → [TextFieldValue]。
 *
 * 选区先夹到文本长度内：撤销回来的快照自带自洽的选区，但外部整篇热替换（AI 改写、版本恢复）
 * 会让栈里的 current 与实际文本错位，越界选区对 TextFieldValue 是非法输入，交给 Compose 之前必须夹紧。
 */
fun TextSnapshot.toTextFieldValue(): TextFieldValue {
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(0, text.length)
    return TextFieldValue(text, TextRange(start, end))
}

/**
 * [UndoRedoStack] 的 Compose 门面。
 *
 * ### 为什么必须包一层
 * [UndoRedoStack] 是普通 Kotlin 对象，`canUndo`/`canRedo` 只是普通属性——把它们直接读进
 * 按钮的 `enabled`，Compose 快照系统看不见这次读，栈变化不会触发重组，撤销按钮会永远停在
 * 首次组合时的那个值（通常是灰的，怎么打字都不亮）。这里把两个标志显式镜像成 Compose State，
 * 并且镜像只在本类内部同步：不指望调用方每次 record/undo/redo 之后记得手动 `historyVersion++`，
 * 漏一处就又变成永远灰的按钮。
 *
 * 非线程安全，只在主线程（Compose 事件回调）使用，与 [UndoRedoStack] 的约束一致。
 */
@Stable
class EditorHistory(
    private val stack: UndoRedoStack = UndoRedoStack(),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    var canUndo by mutableStateOf(stack.canUndo)
        private set

    var canRedo by mutableStateOf(stack.canRedo)
        private set

    /** 以 [value] 为新起点清空历史：切换文档、外部整篇热替换（AI 改写、版本恢复）时调用。 */
    fun reset(value: TextFieldValue) {
        stack.reset(value.toSnapshot())
        sync()
    }

    /**
     * 记录一次新状态。文本未变时引擎只更新选区、不动历史，所以纯光标移动也可以放心调。
     *
     * @param forceBoundary 语义原子操作（工具栏插入语法、查找替换）必须传 `true`，
     *   否则会被合并窗口并进用户刚才的连续打字，撤销时一起消失。
     */
    fun record(value: TextFieldValue, forceBoundary: Boolean = false) {
        stack.record(value.toSnapshot(), nowMs(), forceBoundary)
        sync()
    }

    /**
     * 撤销一步。返回 `null` 表示无可撤销，调用方必须**什么都不做**——
     * 把 null 当成空字符串写回去等于一键清空文档。
     */
    fun undo(): TextFieldValue? {
        val snapshot = stack.undo() ?: return null
        sync()
        return snapshot.toTextFieldValue()
    }

    /** 重做一步，语义同 [undo]。 */
    fun redo(): TextFieldValue? {
        val snapshot = stack.redo() ?: return null
        sync()
        return snapshot.toTextFieldValue()
    }

    private fun sync() {
        canUndo = stack.canUndo
        canRedo = stack.canRedo
    }
}
