package com.yumark.app.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [FindReplace] 单测。
 *
 * 重点覆盖三处容易在真机上才暴露的行为：CJK 全词匹配、非法正则不崩、替换一律字面量。
 */
class FindReplaceTest {

    // ---------- findAll：入参边界 ----------

    @Test
    fun `空查询串或空文本返回空结果`() {
        assertThat(FindReplace.findAll("abc", "")).isEmpty()
        assertThat(FindReplace.findAll("", "abc")).isEmpty()
        assertThat(FindReplace.findAll("", "")).isEmpty()
    }

    // ---------- findAll：大小写 ----------

    @Test
    fun `默认忽略大小写`() {
        val matches = FindReplace.findAll("Hello hello HELLO", "hello")

        assertThat(matches).hasSize(3)
        assertThat(matches.map { it.start }).containsExactly(0, 6, 12).inOrder()
    }

    @Test
    fun `caseSensitive 只匹配完全同形的那一处`() {
        val matches = FindReplace.findAll(
            "Hello hello HELLO",
            "hello",
            FindOptions(caseSensitive = true)
        )

        assertThat(matches).containsExactly(MatchRange(6, 11))
    }

    // ---------- findAll：全词匹配 ----------

    @Test
    fun `wholeWord 排除作为子串出现的位置`() {
        val matches = FindReplace.findAll("cat category cat", "cat", FindOptions(wholeWord = true))

        assertThat(matches).containsExactly(MatchRange(0, 3), MatchRange(13, 16)).inOrder()
    }

    @Test
    fun `wholeWord 对中文同样生效_这是词边界转义符做不到的`() {
        // "中文 中文测试"：第一处独立成词应命中，第二处后面紧跟"测"不应命中。
        // 若用 \b 实现（其定义依赖 ASCII-only 的 \w），'中' 不算词字符，
        // 两处都不构成边界，结果会是 0 个匹配——功能整体失效而非略有偏差。
        val matches = FindReplace.findAll("中文 中文测试", "中文", FindOptions(wholeWord = true))

        assertThat(matches).containsExactly(MatchRange(0, 2))
    }

    @Test
    fun `wholeWord 下标点与空白都算词边界`() {
        val matches = FindReplace.findAll("(cat), cat_1", "cat", FindOptions(wholeWord = true))

        // 括号和逗号是边界；cat_1 里的下划线是词字符，故不命中
        assertThat(matches).containsExactly(MatchRange(1, 4))
    }

    // ---------- findAll：正则 ----------

    @Test
    fun `useRegex 生效`() {
        val matches = FindReplace.findAll("a1 b22 c333", "[a-z]\\d+", FindOptions(useRegex = true))

        assertThat(matches)
            .containsExactly(MatchRange(0, 2), MatchRange(3, 6), MatchRange(7, 11))
            .inOrder()
    }

    @Test
    fun `非正则模式下元字符按字面量处理`() {
        val matches = FindReplace.findAll("a.b axb", "a.b")

        assertThat(matches).containsExactly(MatchRange(0, 3))
    }

    @Test
    fun `非法正则返回空结果而不是抛异常`() {
        // 用户边打边搜，"[" "(" "\" 这类半截正则是常态，不能让编辑器崩
        for (broken in listOf("[", "(", "*", "a{2,1}", "\\")) {
            val matches = FindReplace.findAll("abc", broken, FindOptions(useRegex = true))
            assertThat(matches).isEmpty()
        }
    }

    @Test
    fun `wholeWord 与 useRegex 组合时非法正则也不抛`() {
        val matches = FindReplace.findAll("abc", "(", FindOptions(wholeWord = true, useRegex = true))

        assertThat(matches).isEmpty()
    }

    @Test
    fun `零宽匹配被丢弃`() {
        // a* 在每个位置都能空匹配；空匹配无法高亮也无法替换，必须过滤掉
        assertThat(FindReplace.findAll("bbb", "a*", FindOptions(useRegex = true))).isEmpty()
        assertThat(FindReplace.findAll("xaay", "a*", FindOptions(useRegex = true)))
            .containsExactly(MatchRange(1, 3))
    }

    @Test
    fun `匹配数在 MAX_MATCHES 处截断`() {
        val matches = FindReplace.findAll("a".repeat(FindReplace.MAX_MATCHES + 1_000), "a")

        assertThat(matches).hasSize(FindReplace.MAX_MATCHES)
    }

    // ---------- nextIndex ----------

    @Test
    fun `nextIndex 空列表返回 NO_MATCH`() {
        assertThat(FindReplace.nextIndex(emptyList(), 0, forward = true))
            .isEqualTo(FindReplace.NO_MATCH)
        assertThat(FindReplace.nextIndex(emptyList(), 0, forward = false))
            .isEqualTo(FindReplace.NO_MATCH)
    }

    @Test
    fun `nextIndex 向前时光标正压在匹配起点就选它`() {
        // 刚打开查找框、光标在文首，第一次点"下一个"必须跳到第一个匹配而不是跳过它
        assertThat(FindReplace.nextIndex(threeMatches, caret = 0, forward = true)).isEqualTo(0)
    }

    @Test
    fun `nextIndex 向前取第一个起点不小于光标的匹配`() {
        assertThat(FindReplace.nextIndex(threeMatches, caret = 1, forward = true)).isEqualTo(1)
        assertThat(FindReplace.nextIndex(threeMatches, caret = 10, forward = true)).isEqualTo(1)
        assertThat(FindReplace.nextIndex(threeMatches, caret = 11, forward = true)).isEqualTo(2)
    }

    @Test
    fun `nextIndex 向前到底回绕到第一个`() {
        assertThat(FindReplace.nextIndex(threeMatches, caret = 25, forward = true)).isEqualTo(0)
    }

    @Test
    fun `nextIndex 向后取最后一个终点不大于光标的匹配`() {
        assertThat(FindReplace.nextIndex(threeMatches, caret = 23, forward = false)).isEqualTo(2)
        // 光标落在第三个匹配内部 → 跳到更前面那个
        assertThat(FindReplace.nextIndex(threeMatches, caret = 22, forward = false)).isEqualTo(1)
    }

    @Test
    fun `nextIndex 向后到头回绕到最后一个`() {
        assertThat(FindReplace.nextIndex(threeMatches, caret = 0, forward = false)).isEqualTo(2)
    }

    // ---------- replaceOne ----------

    @Test
    fun `replaceOne 替换指定区间`() {
        assertThat(FindReplace.replaceOne("hello world", MatchRange(6, 11), "there"))
            .isEqualTo("hello there")
    }

    @Test
    fun `replaceOne 允许替换到文本末尾`() {
        assertThat(FindReplace.replaceOne("abc", MatchRange(0, 3), "X")).isEqualTo("X")
    }

    @Test
    fun `replaceOne 区间越界时原样返回`() {
        // 文本已被别处改短，旧匹配位置失效——宁可不改也不能抛出把编辑器带崩
        assertThat(FindReplace.replaceOne("abc", MatchRange(2, 99), "X")).isEqualTo("abc")
        assertThat(FindReplace.replaceOne("abc", MatchRange(50, 60), "X")).isEqualTo("abc")
    }

    @Test
    fun `replaceOne 支持替换成空串`() {
        assertThat(FindReplace.replaceOne("abcdef", MatchRange(2, 4), "")).isEqualTo("abef")
    }

    // ---------- replaceAll ----------

    @Test
    fun `replaceAll 从后往前替换_不因前面变长而错位`() {
        val result = FindReplace.replaceAll("aXaXa", "a", "bb")

        assertThat(result.text).isEqualTo("bbXbbXbb")
        assertThat(result.count).isEqualTo(3)
    }

    @Test
    fun `replaceAll 无匹配时原样返回且计数为零`() {
        val result = FindReplace.replaceAll("abc", "z", "x")

        assertThat(result.text).isEqualTo("abc")
        assertThat(result.count).isEqualTo(0)
    }

    @Test
    fun `replaceAll 空查询串不做任何事`() {
        val result = FindReplace.replaceAll("abc", "", "x")

        assertThat(result.text).isEqualTo("abc")
        assertThat(result.count).isEqualTo(0)
    }

    @Test
    fun `replaceAll 替换文本里的组引用保持字面量`() {
        // 即使开了 useRegex，替换串也不做转义解释：$1 原样写入。
        // 理由是 replaceOne 拿不到 options，两者必须同语义。
        val result = FindReplace.replaceAll(
            "2026-09-02",
            "(\\d{4})-(\\d{2})",
            "\$1/\$2",
            FindOptions(useRegex = true)
        )

        assertThat(result.text).isEqualTo("\$1/\$2-02")
        assertThat(result.count).isEqualTo(1)
    }

    @Test
    fun `replaceAll 替换文本里的反斜杠保持字面量`() {
        val result = FindReplace.replaceAll("foo", "foo", "\\n")

        // 两个字符：反斜杠 + n，不是换行
        assertThat(result.text).isEqualTo("\\n")
        assertThat(result.text).hasLength(2)
    }

    @Test
    fun `replaceAll 支持删除全部匹配`() {
        val result = FindReplace.replaceAll("a-b-c", "-", "")

        assertThat(result.text).isEqualTo("abc")
        assertThat(result.count).isEqualTo(2)
    }

    @Test
    fun `replaceAll 遵循大小写与全词选项`() {
        val result = FindReplace.replaceAll(
            "cat category CAT",
            "cat",
            "dog",
            FindOptions(caseSensitive = true, wholeWord = true)
        )

        assertThat(result.text).isEqualTo("dog category CAT")
        assertThat(result.count).isEqualTo(1)
    }

    // ---------- shiftMatches ----------

    @Test
    fun `shiftMatches 移除被替换项并把其后各项按正 delta 平移`() {
        // 把长度 1 的匹配换成长度 3 的文本：delta = 3 - 1 = 2
        val shifted = FindReplace.shiftMatches(unitMatches, replacedIndex = 0, delta = 2)

        assertThat(shifted).containsExactly(MatchRange(7, 8), MatchRange(12, 13)).inOrder()
    }

    @Test
    fun `shiftMatches 负 delta 只影响被替换项之后`() {
        // 把长度 1 的匹配删掉：delta = 0 - 1 = -1
        val shifted = FindReplace.shiftMatches(unitMatches, replacedIndex = 1, delta = -1)

        assertThat(shifted).containsExactly(MatchRange(0, 1), MatchRange(9, 10)).inOrder()
    }

    @Test
    fun `shiftMatches 替换最后一项时只剩前面的_且原样不动`() {
        val shifted = FindReplace.shiftMatches(unitMatches, replacedIndex = 2, delta = 100)

        assertThat(shifted).containsExactly(MatchRange(0, 1), MatchRange(5, 6)).inOrder()
    }

    @Test
    fun `shiftMatches delta 为零时仅移除被替换项`() {
        val shifted = FindReplace.shiftMatches(unitMatches, replacedIndex = 1, delta = 0)

        assertThat(shifted).containsExactly(MatchRange(0, 1), MatchRange(10, 11)).inOrder()
    }

    @Test
    fun `shiftMatches 极端负 delta 被夹到合法区间`() {
        val shifted = FindReplace.shiftMatches(unitMatches, replacedIndex = 0, delta = -100)

        // 夹到 0 后仍是合法区间（end 不小于 start），不会触发 MatchRange 的 require
        assertThat(shifted).containsExactly(MatchRange(0, 0), MatchRange(0, 0)).inOrder()
    }

    @Test
    fun `shiftMatches 下标越界时原样返回同一实例`() {
        assertThat(FindReplace.shiftMatches(unitMatches, replacedIndex = 3, delta = 1))
            .isSameInstanceAs(unitMatches)
        assertThat(FindReplace.shiftMatches(unitMatches, replacedIndex = -1, delta = 1))
            .isSameInstanceAs(unitMatches)
        assertThat(FindReplace.shiftMatches(emptyList(), replacedIndex = 0, delta = 1)).isEmpty()
    }

    @Test
    fun `shiftMatches 单元素列表替换后变空`() {
        assertThat(FindReplace.shiftMatches(listOf(MatchRange(0, 1)), 0, 5)).isEmpty()
    }

    private val threeMatches = listOf(MatchRange(0, 3), MatchRange(10, 13), MatchRange(20, 23))

    /** 三个长度为 1 的匹配，便于用 delta 直接推算平移结果。 */
    private val unitMatches = listOf(MatchRange(0, 1), MatchRange(5, 6), MatchRange(10, 11))
}
