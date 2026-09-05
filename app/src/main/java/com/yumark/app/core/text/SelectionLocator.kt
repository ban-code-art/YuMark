package com.yumark.app.core.text

/**
 * 在全文中定位一段"大致相同"的文本，用于把 AI 返回的改写落回原文。
 *
 * ### 为什么需要模糊定位
 * 模型回传的 `oldText` 常与原文有细微差异：换行被折成空格、缩进丢失、
 * 中文标点被换成英文标点。逐字节 `indexOf` 会大面积失手，而失手的后果是改写整个失效。
 *
 * ### 为什么必须限界
 * 上一版实现用 `[^\p{L}\p{N}_]*`（无界量词）连接词元且不限词元数量。
 * 对一段长文本会构造出上百个无界量词串联的正则，在不匹配的输入上触发灾难性回溯，
 * 表现为编辑器整个卡死。这里的两个上限 [MAX_TOKENS] / [MAX_SEPARATOR] 就是为此存在，
 * 不是性能调优，是可用性底线。
 *
 * ### 匹配策略（依次尝试，先便宜后昂贵）
 * 1. 原串精确匹配；
 * 2. 去首尾空白后精确匹配（模型最常见的差异）；
 * 3. 词元序列正则匹配，且**要求全文唯一**——多处候选时宁可定位失败，
 *    也不能赌一个位置去改，改错地方比改不了严重得多。
 *
 * 纯 Kotlin，无 Android 依赖。
 */
object SelectionLocator {

    /**
     * 参与模糊定位的最大词元数，超出直接放弃。
     *
     * 词元 = 连续的字母/数字/下划线段。一整段无空格中文是**一个**词元，
     * 因此这个上限主要约束西文长文本。超限时返回 `null`，上层提示"无法定位"，
     * 好过让主线程卡在正则回溯里。
     */
    const val MAX_TOKENS = 64

    /**
     * 词元之间允许的非词字符最大长度。
     *
     * 用 `{0,40}` 取代无界 `*`：既能吸收换行/缩进/标点差异，
     * 又让每个量词的回溯空间有界。
     */
    const val MAX_SEPARATOR = 40

    /** 词字符：字母（含 CJK）、数字、下划线。 */
    private val WORD_TOKEN = Regex("[\\p{L}\\p{N}_]+")

    /** 连续空白，用于空白归一化比较。 */
    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * 在 [content] 中定位 [oldText]，找不到或存在歧义时返回 `null`。
     */
    fun locate(content: String, oldText: String): MatchRange? {
        if (content.isEmpty() || oldText.isEmpty()) return null

        // 1) 原串精确：绝大多数情况在这里就结束
        val exact = content.indexOf(oldText)
        if (exact >= 0) return MatchRange(exact, exact + oldText.length)

        // 2) 去首尾空白后精确
        val trimmed = oldText.trim()
        if (trimmed.isNotEmpty() && trimmed != oldText) {
            val i = content.indexOf(trimmed)
            if (i >= 0) return MatchRange(i, i + trimmed.length)
        }

        // 3) 词元序列模糊匹配，要求唯一
        return locateByTokens(content, trimmed.ifEmpty { oldText })
    }

    /**
     * 把 [oldText] 拆成词元序列，用有界分隔符连接成正则去匹配。
     *
     * 仅在全文**只有一处**候选时返回结果。词元数为 0（纯标点/空白）或超过 [MAX_TOKENS] 时放弃。
     */
    private fun locateByTokens(content: String, oldText: String): MatchRange? {
        val tokens = WORD_TOKEN.findAll(oldText).map { it.value }.take(MAX_TOKENS + 1).toList()
        if (tokens.isEmpty() || tokens.size > MAX_TOKENS) return null

        val separator = "[^\\p{L}\\p{N}_]{0,$MAX_SEPARATOR}"
        val pattern = tokens.joinToString(separator) { Regex.escape(it) }
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return null

        // 取前两个候选即可判定歧义，不必扫完全文
        val candidates = regex.findAll(content).take(2).toList()
        if (candidates.size != 1) return null
        val m = candidates[0]
        return MatchRange(m.range.first, m.range.last + 1)
    }

    /**
     * 把 [content] 中的 [oldText] 换成 [newText]，返回新全文；定位失败返回 `null`。
     *
     * [range] 是调用方已知的候选位置（**闭区间**，Kotlin [IntRange] 语义）。
     * 它只在该处内容与 [oldText] 相符（允许空白差异）时被采用——
     * 光标位置在 AI 请求往返期间可能已经变了，盲信 range 会改到无关内容上。
     * 校验不过就退回全文定位。
     */
    fun replace(content: String, oldText: String, newText: String, range: IntRange? = null): String? {
        if (oldText.isEmpty()) return null

        val hinted = range?.let { MatchRange.fromIntRange(it) }
        if (hinted != null && hinted.end <= content.length) {
            val slice = content.substring(hinted.start, hinted.end)
            if (matchesLoosely(slice, oldText)) {
                return content.substring(0, hinted.start) + newText + content.substring(hinted.end)
            }
        }

        val located = locate(content, oldText) ?: return null
        return content.substring(0, located.start) + newText + content.substring(located.end)
    }

    /** 两段文本在"连续空白折叠为单空格 + 去首尾空白"下是否相等。 */
    private fun matchesLoosely(a: String, b: String): Boolean =
        normalizeWhitespace(a) == normalizeWhitespace(b)

    /** 连续空白折叠成单个空格并去首尾。 */
    private fun normalizeWhitespace(s: String): String =
        WHITESPACE_RUN.replace(s, " ").trim()
}
