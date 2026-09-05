package com.yumark.app.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [UndoRedoStack] 单测。时间由参数注入，故合并窗口可被精确断言，无需真实等待。
 */
class UndoRedoStackTest {

    private fun snap(text: String) = TextSnapshot(text, text.length, text.length)

    @Test
    fun `初始状态不可撤销也不可重做`() {
        val stack = UndoRedoStack()
        assertThat(stack.canUndo).isFalse()
        assertThat(stack.canRedo).isFalse()
        assertThat(stack.current).isEqualTo(TextSnapshot.EMPTY)
        assertThat(stack.undoDepth).isEqualTo(0)
        assertThat(stack.redoDepth).isEqualTo(0)
    }

    @Test
    fun `构造参数非法时立即失败`() {
        assertThrows<IllegalArgumentException> { UndoRedoStack(capacity = 0) }
        assertThrows<IllegalArgumentException> { UndoRedoStack(mergeWindowMs = -1L) }
    }

    @Test
    fun `reset 设定当前状态并清空历史`() {
        val stack = UndoRedoStack()
        stack.record(snap("a"), nowMs = 1_000)
        stack.reset(snap("全新文档"))

        assertThat(stack.current.text).isEqualTo("全新文档")
        assertThat(stack.canUndo).isFalse()
        assertThat(stack.canRedo).isFalse()
    }

    @Test
    fun `首次记录必定压栈_初始状态要能撤回`() {
        val stack = UndoRedoStack()
        stack.reset(snap(""))
        stack.record(snap("a"), nowMs = 1_000)

        assertThat(stack.undoDepth).isEqualTo(1)
        assertThat(stack.undo()!!.text).isEmpty()
    }

    @Test
    fun `合并窗口内的连续输入并成一个撤销单元`() {
        val stack = UndoRedoStack(mergeWindowMs = 700L)
        stack.reset(snap(""))
        stack.record(snap("你"), nowMs = 1_000)
        stack.record(snap("你好"), nowMs = 1_200)
        stack.record(snap("你好世"), nowMs = 1_500)

        assertThat(stack.current.text).isEqualTo("你好世")
        assertThat(stack.undoDepth).isEqualTo(1)
        assertThat(stack.undo()!!.text).isEmpty()
    }

    @Test
    fun `超出合并窗口的输入另起一个撤销单元`() {
        val stack = UndoRedoStack(mergeWindowMs = 700L)
        stack.reset(snap(""))
        stack.record(snap("第一段"), nowMs = 1_000)
        stack.record(snap("第一段第二段"), nowMs = 5_000) // 间隔 4s > 700ms

        assertThat(stack.undoDepth).isEqualTo(2)
        assertThat(stack.undo()!!.text).isEqualTo("第一段")
        assertThat(stack.undo()!!.text).isEmpty()
    }

    @Test
    fun `forceBoundary 在窗口内也强制开新单元`() {
        val stack = UndoRedoStack(mergeWindowMs = 700L)
        stack.reset(snap(""))
        stack.record(snap("正文"), nowMs = 1_000)
        // 工具栏插入语法这类原子操作：即使紧跟着打字也不能被并进去
        stack.record(snap("正文**加粗**"), nowMs = 1_100, forceBoundary = true)

        assertThat(stack.undoDepth).isEqualTo(2)
        assertThat(stack.undo()!!.text).isEqualTo("正文")
    }

    @Test
    fun `纯光标移动不产生历史`() {
        val stack = UndoRedoStack()
        stack.reset(TextSnapshot("abc", 0, 0))
        stack.record(TextSnapshot("abc", 2, 2), nowMs = 1_000)

        assertThat(stack.undoDepth).isEqualTo(0)
        assertThat(stack.canUndo).isFalse()
        assertThat(stack.current.selectionStart).isEqualTo(2)
    }

    @Test
    fun `纯光标移动不清空重做链`() {
        val stack = UndoRedoStack()
        stack.reset(snap(""))
        stack.record(snap("abc"), nowMs = 1_000)
        stack.undo()
        assertThat(stack.canRedo).isTrue()

        // 撤销后用户点了一下屏幕改光标——重做必须还在
        stack.record(TextSnapshot("", 0, 0), nowMs = 2_000)
        assertThat(stack.canRedo).isTrue()
        assertThat(stack.redo()!!.text).isEqualTo("abc")
    }

    @Test
    fun `撤销重做往返回到原状态`() {
        val stack = UndoRedoStack(mergeWindowMs = 0L)
        stack.reset(snap("a"))
        stack.record(snap("ab"), nowMs = 1_000)
        stack.record(snap("abc"), nowMs = 2_000)

        assertThat(stack.undo()!!.text).isEqualTo("ab")
        assertThat(stack.undo()!!.text).isEqualTo("a")
        assertThat(stack.canUndo).isFalse()
        assertThat(stack.redo()!!.text).isEqualTo("ab")
        assertThat(stack.redo()!!.text).isEqualTo("abc")
        assertThat(stack.canRedo).isFalse()
    }

    @Test
    fun `到底后再撤销或重做返回 null`() {
        val stack = UndoRedoStack()
        stack.reset(snap("only"))
        assertThat(stack.undo()).isNull()
        assertThat(stack.redo()).isNull()
        assertThat(stack.current.text).isEqualTo("only")
    }

    @Test
    fun `新的编辑让重做链失效`() {
        val stack = UndoRedoStack(mergeWindowMs = 0L)
        stack.reset(snap(""))
        stack.record(snap("a"), nowMs = 1_000)
        stack.record(snap("ab"), nowMs = 2_000)
        stack.undo()
        assertThat(stack.canRedo).isTrue()

        stack.record(snap("aX"), nowMs = 3_000)
        assertThat(stack.canRedo).isFalse()
    }

    @Test
    fun `撤销后紧接着输入不与被撤销的那段合并`() {
        val stack = UndoRedoStack(mergeWindowMs = 700L)
        stack.reset(snap(""))
        stack.record(snap("abc"), nowMs = 1_000)
        stack.undo() // 回到 ""
        // 距上次 record 仅 100ms，但撤销已切断合并锚点，这里必须开新单元
        stack.record(snap("X"), nowMs = 1_100)

        assertThat(stack.undoDepth).isEqualTo(1)
        assertThat(stack.undo()!!.text).isEmpty()
    }

    @Test
    fun `超出容量丢弃最旧的历史`() {
        val stack = UndoRedoStack(capacity = 2, mergeWindowMs = 0L)
        stack.reset(snap("v0"))
        stack.record(snap("v1"), nowMs = 1_000)
        stack.record(snap("v2"), nowMs = 2_000)
        stack.record(snap("v3"), nowMs = 3_000)

        assertThat(stack.undoDepth).isEqualTo(2)
        assertThat(stack.undo()!!.text).isEqualTo("v2")
        assertThat(stack.undo()!!.text).isEqualTo("v1")
        // v0 已被淘汰
        assertThat(stack.undo()).isNull()
    }

    @Test
    fun `撤销连带回滚选区`() {
        val stack = UndoRedoStack(mergeWindowMs = 0L)
        stack.reset(TextSnapshot("hello", 5, 5))
        stack.record(TextSnapshot("hello world", 11, 11), nowMs = 1_000)

        val undone = stack.undo()!!
        assertThat(undone.text).isEqualTo("hello")
        assertThat(undone.selectionStart).isEqualTo(5)
        assertThat(undone.selectionEnd).isEqualTo(5)
    }

    @Test
    fun `合并窗口边界值按包含处理`() {
        val stack = UndoRedoStack(mergeWindowMs = 700L)
        stack.reset(snap(""))
        stack.record(snap("a"), nowMs = 1_000)
        stack.record(snap("ab"), nowMs = 1_700) // 恰好 700ms，仍合并

        assertThat(stack.undoDepth).isEqualTo(1)
    }
}
