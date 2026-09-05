package com.yumark.app.core.text

/**
 * 编辑器撤销/重做栈。
 *
 * 纯 Kotlin、无 Android 依赖，因此能被 JVM 单测完整覆盖——撤销栈的边界条件
 * （合并窗口、容量淘汰、纯光标移动、undo 后再输入）全是肉眼审不出来的地方。
 *
 * ### 合并策略
 * 逐字符输入若每次都压栈，撤销一次只退一个字，实际不可用。这里按**时间窗口**合并：
 * 距上次记录不超过 [mergeWindowMs] 的连续文本变更并入同一撤销单元。
 * 语义原子操作（工具栏插入语法、查找替换、AI 改写）应传 `forceBoundary = true`
 * 强制开一个新单元，否则它们会被并进用户刚才的打字里，撤销时一起消失。
 *
 * ### 纯光标移动
 * `record` 会被 `onValueChange` 每次调用，包含只动选区的情况。文本未变时只更新
 * 当前快照的选区：**不压栈、不清 redo、不刷新合并窗口**。若清了 redo，用户撤销后
 * 点一下屏幕就再也重做不回来了。
 *
 * 非线程安全：只在主线程（Compose 事件回调）使用。
 *
 * @param capacity 历史上限，超出后丢弃最旧的一条。
 * @param mergeWindowMs 合并窗口，单位毫秒。
 */
class UndoRedoStack(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val mergeWindowMs: Long = DEFAULT_MERGE_WINDOW_MS
) {
    init {
        require(capacity > 0) { "capacity 必须为正数，实际为 $capacity" }
        require(mergeWindowMs >= 0) { "mergeWindowMs 不能为负数，实际为 $mergeWindowMs" }
    }

    /** 历史（不含当前状态），队尾是最近一条。 */
    private val undoStack = ArrayDeque<TextSnapshot>()

    /** 被撤销掉的状态，队尾是最近一条。 */
    private val redoStack = ArrayDeque<TextSnapshot>()

    private var _current: TextSnapshot = TextSnapshot.EMPTY

    /**
     * 上一次**产生历史**的时刻。`null` 表示尚未记录过（或刚 [reset]），
     * 此时下一次 [record] 必须开新单元——否则初始状态永远撤不回去。
     */
    private var lastRecordMs: Long? = null

    /** 当前状态。 */
    val current: TextSnapshot get() = _current

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 可撤销步数，用于 UI 调试与单测断言。 */
    val undoDepth: Int get() = undoStack.size

    /** 可重做步数。 */
    val redoDepth: Int get() = redoStack.size

    /**
     * 重置为 [snapshot]，清空全部历史。
     *
     * 切换文档、外部内容热替换（如 AI 直接改写整篇）时调用：旧文档的撤销历史
     * 套在新文档上会撤出完全不相干的内容。
     */
    fun reset(snapshot: TextSnapshot) {
        undoStack.clear()
        redoStack.clear()
        _current = snapshot
        lastRecordMs = null
    }

    /**
     * 记录一次新状态。
     *
     * @param nowMs 当前时刻（由调用方注入，便于单测控制时间）。
     * @param forceBoundary 强制开新撤销单元，绕过合并窗口。
     */
    fun record(snapshot: TextSnapshot, nowMs: Long, forceBoundary: Boolean = false) {
        // 文本没变 → 只跟一下选区，不动历史（见类 KDoc「纯光标移动」）
        if (snapshot.text == _current.text) {
            _current = snapshot
            return
        }

        val last = lastRecordMs
        val mergeable = !forceBoundary && last != null && (nowMs - last) <= mergeWindowMs

        if (!mergeable) {
            undoStack.addLast(_current)
            // 超容量丢最旧：撤销历史的价值随时间衰减，最近的必须留住
            trimToCapacity()
        }
        // 合并时直接顶替 current，本次输入并入上一个撤销单元

        _current = snapshot
        redoStack.clear() // 新的编辑让原重做链失效
        lastRecordMs = nowMs
    }

    /**
     * 撤销一步，返回撤销后的状态；无可撤销时返回 `null`（调用方据此不动 UI）。
     */
    fun undo(): TextSnapshot? {
        if (undoStack.isEmpty()) return null
        val previous = undoStack.removeAt(undoStack.size - 1)
        redoStack.addLast(_current)
        _current = previous
        // 撤销后立刻输入必须开新单元，否则会并进被撤销的那一段里
        lastRecordMs = null
        return previous
    }

    /**
     * 重做一步，返回重做后的状态；无可重做时返回 `null`。
     */
    fun redo(): TextSnapshot? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeAt(redoStack.size - 1)
        undoStack.addLast(_current)
        trimToCapacity()
        _current = next
        lastRecordMs = null
        return next
    }

    /**
     * 裁剪历史到 [capacity]。
     *
     * 一律用 `removeAt(0)` 而非 `removeFirst()`：后者在部分 Android 版本上会被解析到
     * Java 21 `SequencedCollection.removeFirst()`，运行期抛 `NoSuchMethodError`。
     */
    private fun trimToCapacity() {
        while (undoStack.size > capacity) undoStack.removeAt(0)
    }

    companion object {
        /** 默认历史上限。按一条快照即一份全文估算，百来条对长文档仍在可接受的内存量级。 */
        const val DEFAULT_CAPACITY = 100

        /** 默认合并窗口：连续打字的间隔通常远小于此，停顿超过它即视为一个新的编辑意图。 */
        const val DEFAULT_MERGE_WINDOW_MS = 700L
    }
}
