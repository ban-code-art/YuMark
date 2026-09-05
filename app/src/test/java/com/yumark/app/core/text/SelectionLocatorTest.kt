package com.yumark.app.core.text

import com.google.common.truth.Truth.assertThat
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Test

/**
 * [SelectionLocator] 单测。
 *
 * 两个刻意保留的不对称语义在这里被钉住：
 * - 精确匹配取第一处（不要求唯一），词元模糊匹配要求全文唯一；
 * - `range` 提示必须经内容校验才被采用，校验不过退回全文定位。
 */
class SelectionLocatorTest {

    // ---------- locate：精确路径 ----------

    @Test
    fun `原串精确匹配`() {
        val content = "第一段\n第二段\n第三段"

        assertThat(SelectionLocator.locate(content, "第二段")).isEqualTo(MatchRange(4, 7))
    }

    @Test
    fun `精确匹配取第一处_不要求唯一`() {
        // 与模糊匹配不同：精确命中说明模型回传的就是原文，取第一处是合理默认
        assertThat(SelectionLocator.locate("abc abc", "abc")).isEqualTo(MatchRange(0, 3))
    }

    @Test
    fun `去首尾空白后精确匹配`() {
        val content = "hello world"

        assertThat(SelectionLocator.locate(content, "  world\n")).isEqualTo(MatchRange(6, 11))
    }

    // ---------- locate：入参边界 ----------

    @Test
    fun `空正文或空目标返回 null`() {
        assertThat(SelectionLocator.locate("", "abc")).isNull()
        assertThat(SelectionLocator.locate("abc", "")).isNull()
        assertThat(SelectionLocator.locate("", "")).isNull()
    }

    @Test
    fun `完全不存在的目标返回 null`() {
        assertThat(SelectionLocator.locate("abc def", "xyz")).isNull()
    }

    // ---------- locate：词元模糊路径 ----------

    @Test
    fun `模型把换行折成空格时仍能定位`() {
        // 这是模型回传 oldText 时最常见的差异形态
        val content = "开头\n第一行\n第二行\n结尾"

        val range = SelectionLocator.locate(content, "第一行 第二行")!!

        assertThat(content.substring(range.start, range.end)).isEqualTo("第一行\n第二行")
    }

    @Test
    fun `中文标点被换成英文标点时仍能定位`() {
        val content = "他说：“好的”。"

        val range = SelectionLocator.locate(content, "他说:\"好的\".")!!

        // 匹配只覆盖到最后一个词元，尾部标点不含在内
        assertThat(content.substring(range.start, range.end)).isEqualTo("他说：“好的")
    }

    @Test
    fun `缩进丢失时仍能定位`() {
        val content = "list:\n    - 甲\n    - 乙"

        val range = SelectionLocator.locate(content, "- 甲 - 乙")!!

        assertThat(content.substring(range.start, range.end)).isEqualTo("甲\n    - 乙")
    }

    @Test
    fun `模糊匹配存在多处候选时返回 null_不赌位置`() {
        // 改错地方比改不了严重得多，宁可让上层提示"无法定位"
        val content = "foo bar\nfoo bar"

        assertThat(SelectionLocator.locate(content, "foo  bar")).isNull()
    }

    @Test
    fun `纯标点或纯空白的目标返回 null`() {
        assertThat(SelectionLocator.locate("正文内容", "，。！")).isNull()
        assertThat(SelectionLocator.locate("正文内容", "   ")).isNull()
    }

    @Test
    fun `词元数超过 MAX_TOKENS 直接放弃`() {
        val oldText = (1..SelectionLocator.MAX_TOKENS + 10).joinToString(" ") { "w$it" }

        assertThat(SelectionLocator.locate("毫不相干的正文", oldText)).isNull()
    }

    @Test
    fun `词元数恰好等于 MAX_TOKENS 仍能定位`() {
        val words = (1..SelectionLocator.MAX_TOKENS).map { "w$it" }
        val expected = words.joinToString("\n")
        val content = "前缀 $expected 后缀"

        val range = SelectionLocator.locate(content, words.joinToString(" "))!!

        assertThat(content.substring(range.start, range.end)).isEqualTo(expected)
    }

    @Test
    fun `词元间隔超过 MAX_SEPARATOR 时不再视为同一段`() {
        val gap = " ".repeat(SelectionLocator.MAX_SEPARATOR + 5)
        // 必须写 ${gap} 而不是 $gap：CJK 是合法的 Kotlin 标识符字符，
        // 简单模板 $gap乙 会被解析成标识符 `gap乙`（编译期 Unresolved reference）
        val content = "甲${gap}乙"

        assertThat(SelectionLocator.locate(content, "甲 乙")).isNull()
    }

    @Test
    fun `超长文本与大量词元时能在有限时间内返回`() {
        val oldText = (1..SelectionLocator.MAX_TOKENS).joinToString(" ") { "token$it" }
        // 20 万字符正文，遍地是与首词元同形的诱饵，逼正则在每个起点重试
        val content = "token1${" ".repeat(20)}".repeat(8_000)

        val elapsed = measureTimeMillis {
            assertThat(SelectionLocator.locate(content, oldText)).isNull()
        }

        // 阈值放得很宽：只为拦住"卡死"级别的退化，不做性能基准
        assertThat(elapsed).isLessThan(5_000L)
    }

    // ---------- replace ----------

    @Test
    fun `replace 采用内容相符的 range 提示`() {
        assertThat(SelectionLocator.replace("hello world", "world", "there", 6..10))
            .isEqualTo("hello there")
    }

    @Test
    fun `range 提示允许空白差异`() {
        // slice 是 "a  b"，oldText 是 "a b"：折叠空白后相等即认可
        assertThat(SelectionLocator.replace("a  b c", "a b", "X", 0..3)).isEqualTo("X c")
    }

    @Test
    fun `range 提示内容不符时退回全文定位`() {
        // AI 请求往返期间光标已经移开，盲信 range 会改到无关内容上
        assertThat(SelectionLocator.replace("AAAA world", "world", "there", 0..3))
            .isEqualTo("AAAA there")
    }

    @Test
    fun `range 提示越出文本边界时被忽略`() {
        assertThat(SelectionLocator.replace("hello", "hello", "hi", 0..99)).isEqualTo("hi")
    }

    @Test
    fun `空 range 提示被忽略`() {
        assertThat(SelectionLocator.replace("hello", "hello", "hi", IntRange.EMPTY))
            .isEqualTo("hi")
    }

    @Test
    fun `replace 无 range 时走模糊定位`() {
        val content = "开头\n第一行\n第二行\n结尾"

        assertThat(SelectionLocator.replace(content, "第一行 第二行", "改写后"))
            .isEqualTo("开头\n改写后\n结尾")
    }

    @Test
    fun `replace 支持删除_新文本为空串`() {
        assertThat(SelectionLocator.replace("前中后", "中", "")).isEqualTo("前后")
    }

    @Test
    fun `replace 目标为空串返回 null`() {
        assertThat(SelectionLocator.replace("abc", "", "new")).isNull()
    }

    @Test
    fun `replace 定位不到返回 null`() {
        assertThat(SelectionLocator.replace("abc", "xyz", "new")).isNull()
    }

    @Test
    fun `replace 在模糊定位有歧义时返回 null`() {
        assertThat(SelectionLocator.replace("foo bar\nfoo bar", "foo  bar", "X")).isNull()
    }

    @Test
    fun `range 提示不符且全文也定位不到时返回 null`() {
        // 提示失效 + 无法回退定位：必须一起失败，不能改到 range 那一段上
        assertThat(SelectionLocator.replace("AAAA BBBB", "world", "there", 0..3)).isNull()
    }

    @Test
    fun `负数 range 提示被忽略而不是抛异常`() {
        // range 是「调用方以为的位置」，不是契约保证的位置——它可能来自一个还没初始化的选区，
        // 或一次越界的下标运算。IntRange 本身不禁止负数，且 -1..3 不是空区间，所以
        // isEmpty 那道闸门拦不住它。这里若让 MatchRange 的 require 抛出来，
        // 一次 AI 改写就直接崩掉编辑器；正确行为与「内容不符」一致：忽略提示，退回全文定位。
        assertThat(SelectionLocator.replace("hello world", "world", "there", -1..3))
            .isEqualTo("hello there")
        assertThat(SelectionLocator.replace("hello world", "world", "there", -5..-1))
            .isEqualTo("hello there")
    }

    @Test
    fun `range 末端为 Int MAX_VALUE 时被忽略而不是溢出`() {
        // last + 1 绕成 Int.MIN_VALUE，构造出 end < start 的区间。
        // `0..Int.MAX_VALUE` 是「整篇都选中」的常见写法，走到这里必须是忽略提示，不是崩溃。
        assertThat(SelectionLocator.replace("hello", "hello", "hi", 0..Int.MAX_VALUE))
            .isEqualTo("hi")
    }
}
