package com.yumark.app.data.ai.rag

/**
 * 内容哈希 —— FNV-1a 32-bit（移植自 guanmo `contentHash.ts`），用于分块去重。
 *
 * **不要与 [com.yumark.app.core.text.ContentHash] 互换**（文件名已改为 `RagContentHash.kt`
 * 以消灭同名陷阱；包与用途仍截然不同）：
 * - 这一个只有 32 位、且 [normalizeForHash] 会抹掉大小写与空白差异。用途是「这段正文值不值得
 *   重新付费算一次 embedding」，抹掉空白正是想要的，碰撞也只是白算一次或少算一次，代价有限。
 * - 那一个是 SHA-256 且逐字节比对。用途是 Agent 编辑提议的基线校验：抹掉空白会让「只改了缩进」
 *   的改动被判成没变，于是整篇覆盖把它吃掉；32 位的碰撞面在这种「校验用户数据没被悄悄改掉」的
 *   场合也太窄。
 */
internal fun createContentHash(content: String): String {
    val normalized = normalizeForHash(content)
    var hash = -0x7ee3623b  // 0x811c9dc5 的 Int 表示
    for (ch in normalized) {
        hash = hash xor ch.code
        hash *= 0x01000193
    }
    return (hash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
}

/**
 * 分块去重用的比较键 —— 归一化后的正文**本身**，不是它的 32 位摘要。
 *
 * 分块器以前拿 [createContentHash] 的 8 位十六进制串当去重集合的元素。FNV-1a 只有 32 位：两段毫不
 * 相干的正文一旦撞上同一个值，后一块会被当成「重复内容」整块丢掉——那段正文在检索里彻底不存在，
 * 而且只要用户不改这篇文档就永远不存在，既不报错也没有任何提示，用户只会觉得「AI 看不见我写的这段」。
 * 一篇文档几百块时撞车概率确实很小，但代价是静默丢正文；改成比全串，只多占一份正文的内存
 * （文档本来就整篇在内存里被切分），没有任何计算代价。
 *
 * 仍然走 [normalizeForHash]：只差一个行尾空格或大小写的两块本来就该算同一块，这是去重想要的语义。
 *
 * 两处按内容去重的地方都用它：[MarkdownChunker] 落块时、[VectorStore] 截断检索结果时。后者撞车的
 * 表现是「这段明明索引过、embedding 也算过，就是检索不出来」，比前者更难查。
 */
internal fun contentDedupKey(content: String): String = normalizeForHash(content)

/**
 * 零宽字符：Java 的 `Character.isWhitespace` 对它们一律返回 false，`\s` 和 `trim()` 都抓不到。
 *
 * 故意**不含** U+200C/U+200D（ZWNJ/ZWJ）：它们在波斯语/阿拉伯语里改变字形、在 emoji 序列里
 * 决定「一家三口」还是三个独立的人，抹掉就是改内容，反而会让两段不同的正文被判成同一块。
 */
private val ZERO_WIDTH_REGEX = Regex("[\\uFEFF\\u200B\\u2060]")

/** Unicode 空格类：同样不被 `[ \t]`、`\s`（Java 语义）或 `trim()` 覆盖，NBSP 尤其常见。 */
private val UNICODE_SPACE_REGEX = Regex("[\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]")

/**
 * 归一化：折行尾、抹不可见字符、压空白、去大小写。
 *
 * 补齐裸 `\r` / 零宽 / BOM / NBSP / 全角空格这几类，是因为它们在肉眼上完全看不出来，而
 * [com.yumark.app.data.ai.rag.RagPipeline] 拿整篇文档的这个哈希判断「内容变没变」：差一个 BOM 或
 * 一个从网页粘进来的 NBSP，就会被判成整篇改过 → 整篇重新算 embedding（真花钱、真耗时），
 * 用户什么都没改却看到索引任务又跑了一遍。原来的实现只处理 `\r\n` 和 `[ \t]+`：
 * - 裸 `\r`（老 Mac 行尾、或 WebDAV 对端把 `\r\n` 拆散后剩下的）直接留在正文里；
 * - `U+FEFF`/`U+200B` 在 Java 里既不是 `\s` 也不算 `isWhitespace`，`trim()` 也带不走；
 * - `U+00A0`(NBSP) 同理；`U+3000`(全角空格) 虽然 `isWhitespace` 认，但 `[ \t]+` 不认，所以
 *   「全角空格缩进」和「半角空格缩进」以前是两份哈希。
 *
 * 关键：这些替换对**不含**这些字符的正文是空操作，哈希值一字不变（已核对：`"# 标题\n这是一段用于
 * 固定哈希值的正文内容。"` 补齐前后都是 `737ca0de`），所以存量文档不会被这次改动逼着重建索引，
 * 只有确实带着不可见字符的文档会重建一次——而那一次本来就该重建。
 */
private fun normalizeForHash(content: String): String =
    content.replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(ZERO_WIDTH_REGEX, "")
        .replace(UNICODE_SPACE_REGEX, " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        .lowercase()
