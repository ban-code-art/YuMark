package com.yumark.app.core.text

/**
 * 半开区间 `[start, end)` 的文本范围。
 *
 * `end` 独占，与 Kotlin 的 `substring(start, end)`、Compose 的 `TextRange` 一致；
 * 刻意不用 [IntRange]（闭区间）来表达匹配位置——两种约定混用是差一错误的温床。
 *
 * 被 [FindReplace] 与 [SelectionLocator] 共用。
 */
data class MatchRange(val start: Int, val end: Int) {

    init {
        require(start >= 0) { "start 不能为负数：$start" }
        require(end >= start) { "end($end) 不能小于 start($start)" }
    }

    /** 区间长度。 */
    val length: Int get() = end - start

    /** 空区间（零宽匹配）。 */
    val isEmpty: Boolean get() = start == end

    /** 转成闭区间，用于对接接受 [IntRange] 的既有 API。 */
    fun toIntRange(): IntRange = start until end

    companion object {
        /**
         * 从闭区间构造。`IntRange.last` 是包含的，故 `end = last + 1`。
         *
         * 无法表达成一个有效 [MatchRange] 时返回 `null`，**绝不抛异常**：调用方
         * （见 [SelectionLocator.replace]）把 range 当作「可能已经过期的位置提示」，
         * 契约是校验不过就退回全文定位。让 [init] 里的 `require` 从这个可空工厂漏出去，
         * 一次 AI 改写就会把编辑器整个崩掉。三类必须挡在这里的入参：
         *  * 空区间（`1..0`、[IntRange.EMPTY]）——不指向任何位置；
         *  * 负起点（`-1..3`）——[IntRange] 不禁止负数，且它**不是**空区间，`isEmpty` 拦不住；
         *  * `last == Int.MAX_VALUE`（如 `0..Int.MAX_VALUE`，「整篇都选中」的常见写法）
         *    ——`last + 1` 绕成 [Int.MIN_VALUE]，算出 `end < start`。
         */
        fun fromIntRange(range: IntRange): MatchRange? = when {
            range.isEmpty() -> null
            range.first < 0 -> null
            range.last == Int.MAX_VALUE -> null
            else -> MatchRange(range.first, range.last + 1)
        }
    }
}
