package com.yumark.app.domain.usecase.ai

/**
 * 项目内检索的相关度计算（纯函数，便于单测）。
 * 替代纯 `contains`：查询归一化分词 → 按文档评分（标题命中加权）→ 提取命中片段。
 */
object SearchRanker {
    // 按空白与 ASCII 标点切分；中文词以空格分隔（保守，不做分词）
    private val splitter = Regex("[\\s\\p{Punct}]+")

    fun tokenize(query: String): List<String> =
        query.split(splitter).filter { it.isNotBlank() }.map { it.lowercase() }

    fun score(name: String, content: String, tokens: List<String>): Int {
        if (tokens.isEmpty()) return 0
        val lcName = name.lowercase()
        val lcContent = content.lowercase()
        var total = 0
        for (t in tokens) {
            total += countOccurrences(lcContent, t)
            if (lcName.contains(t)) total += 5  // 标题命中加权
        }
        return total
    }

    fun snippets(content: String, tokens: List<String>, maxSnippets: Int): List<Pair<Int, String>> {
        if (tokens.isEmpty()) return emptyList()
        val lc = tokens.map { it.lowercase() }
        return content.lines().withIndex()
            .filter { (_, line) -> val l = line.lowercase(); lc.any { l.contains(it) } }
            .take(maxSnippets)
            .map { it.index to it.value }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) break
            count++
            idx = found + needle.length
        }
        return count
    }
}
