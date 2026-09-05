package com.yumark.app.core.search

/**
 * FTS4 索引与查询共用的文本规范化器。
 *
 * 存在的唯一理由是 CJK 分词：SQLite 的 unicode61 分词器按「非字母数字」切词，
 * 而中文句子没有空格，`我爱北京天安门` 会被切成一个巨型 token，
 * 结果是「只能整句命中、搜任何子串都搜不到」。这里在写入索引前把每个 CJK 字
 * 拆成独立 token（`我爱北京` → `我 爱 北 京`），查询侧走同一个函数，
 * 再以双引号短语（phrase）查询要求 token 相邻，就还原出了「子串匹配」的语义。
 *
 * 三条不变量（测试逐条盯住）：
 * 1. 纯 Kotlin + java.lang，无任何 Android 依赖 —— JVM 单测里 android.jar 的桩会抛
 *    `RuntimeException("Stub!")`，本文件必须能脱离 Android 运行。
 * 2. 输出只含「token + 单个空格」，无首尾空格、无连续空格，因此可直接嵌入 FTS 短语。
 * 3. 输出永不包含 FTS 语法字符（`"` `*` `^` `-` `:` `(` `)` 等）—— 它们全是非字母数字，
 *    统一退化成分隔符。这是防 FTS 注入的根基，而不是靠转义。
 */
object FtsTextNormalizer {

    /**
     * 规范化文本：小写化 → 按码点遍历 → CJK 单字独立成词、西文词整体保留、其余一律当分隔符。
     *
     * 按码点（而非 Char）遍历是硬要求：emoji 与 CJK 扩展 B 区都是代理对，
     * 逐 Char 处理会把代理对劈成两个孤立代理，写进索引就是永久乱码。
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return ""
        // lowercase() 无 Locale 参数时按 root 规则，不受设备语言影响（土耳其语 i 问题）
        val lowered = text.lowercase()
        val out = StringBuilder(lowered.length)
        // 是否正处在一个「西文词」内部：决定下一个西文字符是续写还是新起一词
        var openWord = false
        var i = 0
        while (i < lowered.length) {
            val codePoint = lowered.codePointAt(i)
            i += Character.charCount(codePoint)
            when {
                // 标点/空白/emoji/FTS 运算符 —— 只断词，不产出字符
                !Character.isLetterOrDigit(codePoint) -> openWord = false

                // CJK 及同类无空格文字：每字独立成 token
                isIsolatedScript(codePoint) -> {
                    if (out.isNotEmpty()) out.append(' ')
                    out.appendCodePoint(codePoint)
                    openWord = false
                }

                // 拉丁/西里尔/数字等：连续字符拼成一个完整 token
                else -> {
                    if (!openWord && out.isNotEmpty()) out.append(' ')
                    out.appendCodePoint(codePoint)
                    openWord = true
                }
            }
        }
        return out.toString()
    }

    /**
     * 该码点是否属于「不用空格分词」的文字，需要单字独立成 token。
     *
     * 只列真正无空格书写的文字块；日文假名同样按字拆（日文也不用空格）。
     * 谚文音节虽然用空格，但按字拆只会让匹配更宽松，不会漏召回，故一并纳入。
     */
    private fun isIsolatedScript(codePoint: Int): Boolean = when (codePoint) {
        in 0x2E80..0x2FDF,   // CJK 部首补充 + 康熙部首
        in 0x3005..0x3007,   // 々 〆 〇（〇 是 Nl，isLetterOrDigit 判 false，实际会当分隔符丢弃）
        in 0x3040..0x30FF,   // 平假名 + 片假名
        in 0x3100..0x312F,   // 注音符号
        in 0x3130..0x318F,   // 谚文兼容字母
        in 0x31A0..0x31BF,   // 注音扩展
        in 0x31F0..0x31FF,   // 片假名音标扩展
        in 0x3400..0x4DBF,   // CJK 扩展 A
        in 0x4E00..0x9FFF,   // CJK 基本区
        in 0xA000..0xA4CF,   // 彝文音节
        in 0xAC00..0xD7AF,   // 谚文音节
        in 0xF900..0xFAFF,   // CJK 兼容表意文字
        in 0xFF00..0xFFEF,   // 半宽/全宽形式（全角字母数字也按单字拆，与全角标点一致）
        in 0x1B000..0x1B16F, // 假名补充 / 假名扩展
        in 0x20000..0x3FFFF  // CJK 扩展 B..I（增补表意平面，全是代理对）
        -> true

        else -> false
    }
}
