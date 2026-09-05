package com.yumark.app.core.search

/**
 * 把用户输入的原始搜索词编译成一条**安全**的 FTS4 MATCH 表达式。
 *
 * FTS4 的 MATCH 语法没有转义机制，用户输入直接拼进去有两类后果：
 * - 硬报错：`"未闭合的引号` 与 `NEAR/2` 会让 SQLite 抛
 *   `malformed MATCH expression`，搜索直接失败；
 * - 静默变味：`kot*` 变成前缀查询、`a OR b` 变成布尔或、`-x` 变成排除项，
 *   用户以为在搜字面量，实际执行的是另一条查询。
 *
 * 这里的做法不是转义而是**字面化**：先过 [FtsTextNormalizer]（所有非字母数字字符
 * 退化为分隔符，`"` `*` `^` `-` `:` 全部消失），再整体套一对双引号变成短语查询。
 * 于是输出恒定形如 `"tok1 tok2 tok3"`，全串只有首尾两个双引号，
 * `AND` / `OR` / `NOT` / `NEAR` 落在引号内即是普通词，不再是运算符。
 */
object FtsQueryBuilder {

    /**
     * 短语最多允许的 token 数。
     *
     * 超限不做截断而是返回 null：截断会让「粘贴一大段文字去搜」匹配到只共享前
     * [MAX_PHRASE_TOKENS] 个 token 的无关文档（召回变宽、精度崩掉），
     * 让调用方退回逐文档子串扫描反而给出精确结果 —— 这种输入本就罕见，慢一点无所谓。
     */
    const val MAX_PHRASE_TOKENS = 64

    /**
     * @return 可直接绑定给 `MATCH ?` 的短语表达式；无法构造出有效查询时返回 null，
     *         调用方应据此退回旧的内存子串搜索（而不是拿空串去 MATCH）。
     *         返回 null 的两种情况：规范化后没有任何 token（空串、纯空白、纯标点、
     *         纯 emoji），以及 token 数超过 [MAX_PHRASE_TOKENS]。
     */
    fun build(rawQuery: String): String? {
        val normalized = FtsTextNormalizer.normalize(rawQuery)
        if (normalized.isEmpty()) return null
        // normalize 保证是单空格分隔且无首尾空格，所以 split 不会产出空 token
        if (normalized.count { it == ' ' } + 1 > MAX_PHRASE_TOKENS) return null
        return "\"$normalized\""
    }
}
