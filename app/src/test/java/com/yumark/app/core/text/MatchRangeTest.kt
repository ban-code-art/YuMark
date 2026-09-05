package com.yumark.app.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [MatchRange] 单测。半开区间与 [IntRange] 闭区间的换算是差一错误的高发处，单独钉住。
 */
class MatchRangeTest {

    @Test
    fun `长度与空判定`() {
        val range = MatchRange(3, 7)

        assertThat(range.length).isEqualTo(4)
        assertThat(range.isEmpty).isFalse()
    }

    @Test
    fun `零宽区间合法且判定为空`() {
        val range = MatchRange(5, 5)

        assertThat(range.length).isEqualTo(0)
        assertThat(range.isEmpty).isTrue()
    }

    @Test
    fun `非法区间构造时立即失败`() {
        assertThrows<IllegalArgumentException> { MatchRange(-1, 0) }
        assertThrows<IllegalArgumentException> { MatchRange(5, 3) }
    }

    @Test
    fun `转闭区间时末端减一`() {
        assertThat(MatchRange(3, 7).toIntRange()).isEqualTo(3..6)
        assertThat(MatchRange(5, 5).toIntRange().isEmpty()).isTrue()
    }

    @Test
    fun `从闭区间构造时末端加一`() {
        assertThat(MatchRange.fromIntRange(3..6)).isEqualTo(MatchRange(3, 7))
        assertThat(MatchRange.fromIntRange(5..5)).isEqualTo(MatchRange(5, 6))
    }

    @Test
    fun `空闭区间无法表达有效位置_返回 null`() {
        assertThat(MatchRange.fromIntRange(IntRange.EMPTY)).isNull()
        assertThat(MatchRange.fromIntRange(1..0)).isNull()
        // 零宽区间转成闭区间后就是空的，往返不可逆——这是有意的
        assertThat(MatchRange.fromIntRange(MatchRange(5, 5).toIntRange())).isNull()
    }

    @Test
    fun `负起点的闭区间返回 null 而不是抛异常`() {
        // 一个返回 MatchRange? 的工厂对某些入参却抛异常，是比返回 null 更隐蔽的坑：
        // 调用方（[SelectionLocator.replace]）拿到的 range 只是「提示」，
        // 契约是校验不过就退回全文定位。IntRange 不禁止负数，且 -1..3 不是空区间，
        // isEmpty 那道闸门拦不住它。
        assertThat(MatchRange.fromIntRange(-1..3)).isNull()
        assertThat(MatchRange.fromIntRange(-5..-1)).isNull()
    }

    @Test
    fun `末端为 Int MAX_VALUE 时返回 null 而不是让 end 溢出`() {
        // last + 1 绕成 Int.MIN_VALUE，构造出 end < start 的区间再被 require 打回来。
        // `0..Int.MAX_VALUE` 是「整篇都选中」的常见写法，不该是一条崩溃路径。
        assertThat(MatchRange.fromIntRange(0..Int.MAX_VALUE)).isNull()
        assertThat(MatchRange.fromIntRange(7..Int.MAX_VALUE)).isNull()
    }
}
