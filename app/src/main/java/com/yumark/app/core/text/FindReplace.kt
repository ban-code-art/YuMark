package com.yumark.app.core.text

/**
 * 查找选项。
 *
 * @param caseSensitive 区分大小写。
 * @param wholeWord 全词匹配。用 lookaround 而非 `\b` 实现，原因见 [FindReplace.buildRegex]。
 * @param useRegex 把查询串当正则。非法正则不抛异常，[FindReplace.findAll] 返回空结果——
 *   用户正在输入的半截正则天天非法，不能让编辑器崩。
 */
data class FindOptions(
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val useRegex: Boolean = false
)

/** [FindReplace.replaceAll] 的结果：替换后全文与实际替换次数。 */
data class ReplaceAllResult(val text: String, val count: Int)

/**
 * 文档内查找/替换的纯逻辑。
 *
 * 无 Android 依赖，可被 JVM 单测完整覆盖。UI 只负责持有查询串、当前匹配下标与高亮。
 *
 * ### 替换一律是字面量
 * 即使 [FindOptions.useRegex] 为真，替换文本中的 `$1`、`\n`、`\` 也**不做**任何转义解释，
 * 原样写入。理由：逐个替换（[replaceOne]）与全部替换（[replaceAll]）必须同语义，
 * 而 `replaceOne` 拿不到 options；两者一个支持组引用一个不支持，比都不支持糟得多。
 * 实现上全部走 `substring` 拼接，不经 `Regex.replace`，因此天然字面量。
 */
object FindReplace {

    /**
     * 单次查找返回的匹配上限。
     *
     * 在超长文档里搜一个空格能产出几十万个匹配，全部装进 List 再交给 Compose 高亮
     * 会直接卡死。截断后 UI 应提示"结果过多，仅显示前 N 个"。
     */
    const val MAX_MATCHES = 5000

    /** 无匹配时 [nextIndex] 的返回值。 */
    const val NO_MATCH = -1

    /**
     * 找出 [text] 中 [query] 的全部匹配，按位置升序，最多 [MAX_MATCHES] 个。
     *
     * 空查询串返回空列表（否则会退化成"每个位置都匹配"）。
     * 零宽匹配（如正则 `a*` 在无 a 处的空匹配）一律丢弃：它们无法高亮、无法替换，
     * 只会把结果列表撑爆。
     */
    fun findAll(text: String, query: String, options: FindOptions = FindOptions()): List<MatchRange> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val regex = buildRegex(query, options) ?: return emptyList()

        val result = ArrayList<MatchRange>()
        for (m in regex.findAll(text)) {
            if (m.value.isEmpty()) continue // 零宽匹配
            result += MatchRange(m.range.first, m.range.last + 1)
            if (result.size >= MAX_MATCHES) break
        }
        return result
    }

    /**
     * 在 [matches] 里挑出相对 [caret] 的下一个/上一个匹配的**下标**，到头回绕。
     *
     * 空列表返回 [NO_MATCH]。
     * 向前取第一个 `start >= caret`（光标正压在匹配起点时就选它，这样刚打开查找框、
     * 光标在文首时第一次点"下一个"会跳到第一个匹配，而不是跳过它）；
     * 向后取最后一个 `end <= caret`（光标在匹配内部时跳到更前面那个）。
     */
    fun nextIndex(matches: List<MatchRange>, caret: Int, forward: Boolean): Int {
        if (matches.isEmpty()) return NO_MATCH
        return if (forward) {
            val i = matches.indexOfFirst { it.start >= caret }
            if (i >= 0) i else 0
        } else {
            val i = matches.indexOfLast { it.end <= caret }
            if (i >= 0) i else matches.lastIndex
        }
    }

    /**
     * 把 [text] 中 [match] 处替换为 [replacement]（字面量）。
     *
     * [match] 越出 [text] 边界时原样返回——宁可不改，也不能抛出把编辑器带崩。
     */
    fun replaceOne(text: String, match: MatchRange, replacement: String): String {
        if (match.start > text.length || match.end > text.length) return text
        return text.substring(0, match.start) + replacement + text.substring(match.end)
    }

    /**
     * 替换全部匹配（字面量），返回新全文与替换次数。
     *
     * **从后往前**替换：先改前面的会让后面所有匹配偏移失效。
     * 匹配数达到 [MAX_MATCHES] 时只替换这一批，`count` 反映实际替换数，调用方可再跑一轮。
     */
    fun replaceAll(
        text: String,
        query: String,
        replacement: String,
        options: FindOptions = FindOptions()
    ): ReplaceAllResult {
        val matches = findAll(text, query, options)
        if (matches.isEmpty()) return ReplaceAllResult(text, 0)

        val sb = StringBuilder(text)
        for (i in matches.indices.reversed()) {
            val m = matches[i]
            sb.replace(m.start, m.end, replacement)
        }
        return ReplaceAllResult(sb.toString(), matches.size)
    }

    /**
     * 替换掉第 [replacedIndex] 个匹配之后，把剩余匹配的位置对齐到新文本。
     *
     * 被替换的那一项从列表移除，其后各项整体平移 [delta]
     * （`delta = replacement.length - 被替换匹配的长度`，可为负）。
     * 这是"逐个替换"避免每次全量重扫的快路径。
     *
     * 注意：若 [replacement] 自身又包含查询串（把 `a` 换成 `aa`），新产生的匹配不会出现在
     * 结果里——需要新匹配就得重新 [findAll]。下标越界时原样返回。
     */
    fun shiftMatches(matches: List<MatchRange>, replacedIndex: Int, delta: Int): List<MatchRange> {
        if (replacedIndex !in matches.indices) return matches
        val result = ArrayList<MatchRange>(matches.size - 1)
        for (i in matches.indices) {
            if (i == replacedIndex) continue
            val m = matches[i]
            if (i < replacedIndex) {
                result += m
            } else {
                // 平移后仍须是合法区间：极端 delta 下夹到 0
                val start = (m.start + delta).coerceAtLeast(0)
                val end = (m.end + delta).coerceAtLeast(start)
                result += MatchRange(start, end)
            }
        }
        return result
    }

    /**
     * 构造查找用正则；非法正则返回 `null`。
     *
     * 全词匹配用 `(?<![\p{L}\p{N}_])…(?![\p{L}\p{N}_])` 而不是 `\b`：
     * `\b` 的定义依赖 `\w`（默认只含 ASCII），对中文既不生效也不可预期，
     * 而 lookaround 显式列出"词字符"集合，中英文行为一致。
     *
     * 非正则模式下查询串走 [Regex.escape]，用户搜 `a.b` 就是搜这三个字符。
     */
    private fun buildRegex(query: String, options: FindOptions): Regex? {
        val core = if (options.useRegex) query else Regex.escape(query)
        val pattern = if (options.wholeWord) "(?<![\\p{L}\\p{N}_])(?:$core)(?![\\p{L}\\p{N}_])" else core
        val flags = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return runCatching { Regex(pattern, flags) }.getOrNull()
    }
}
