package com.yumark.app.core.text

/**
 * Markdown 正文的字数/字符数统计。
 *
 * 单独提出来而不是留在 `SaveDocumentUseCase` 里：编辑器保存时要把算好的计数写回内存中的
 * 文档，历史版本快照才不会记成上一版的字数（从前 use case 私有此方法、只把结果写进数据库，
 * 内存里的 `Document.wordCount` 永远停在打开文档那一刻，于是历史列表每一行显示的字数
 * 都属于另一个版本）。两处要用同一套算法，就不能再是某个类的 private 方法。
 *
 * 顺带把它变成可测的：这里不碰 Context、不碰协程，纯函数。
 */
object WordCount {

    /**
     * 统计「字数」：中文按字算、英文按词算，两者相加。
     *
     * 统计前先剥掉 Markdown 语法：代码块和行内代码整段不算（那是代码不是正文），
     * 链接/图片只留可见文本（`[标题](url)` 算「标题」，URL 不算）。
     *
     * 已知边界（有意保持现状，改了会让所有历史版本的字数与新版本对不上）：
     * 只有 U+4E00–U+9FFF 被当成表意文字逐字计数。假名与 CJK 扩展区不在这个区间，
     * 于是它们走英文分词那条路——一整段连续假名之间没有空格，会被算成 **1 个词**
     * （`WordCount.of("ひらがな") == 1`）。日文正文的字数因此偏低。
     * 真要支持得连历史版本记录里的旧数字一起迁移，不能只改这里。
     */
    fun of(content: String): Int {
        val plainText = stripMarkdown(content)
        if (plainText.isEmpty()) return 0

        val chineseChars = plainText.count { it in ZH_RANGE }

        // 先把中日韩字符换成空格再分词：否则「中文abc中文」会被当成一个整词
        val englishWords = plainText.replace(ZH_REGEX, " ")
            .trim()
            .split(WHITESPACE)
            .count { it.isNotBlank() }

        return chineseChars + englishWords
    }

    /** 字符数：正文原样长度，不剥语法——它回答的是「这个文件多大」，与 [of] 不是一回事。 */
    fun charactersOf(content: String): Int = content.length

    private fun stripMarkdown(content: String): String = content
        .replace(CODE_FENCE, "")     // 移除代码块
        .replace(INLINE_CODE, "")    // 移除行内代码
        .replace(LINK, "$1")         // 保留链接/图片的可见文本
        .replace(SYMBOLS, "")        // 移除 Markdown 符号
        .trim()

    private val ZH_RANGE = '一'..'鿿'
    private val ZH_REGEX = Regex("[一-鿿]")
    private val CODE_FENCE = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`[^`]+`")
    private val LINK = Regex("!?\\[([^]]+)]\\([^)]+\\)")
    private val SYMBOLS = Regex("[#*_~`]")
    private val WHITESPACE = Regex("\\s+")
}
