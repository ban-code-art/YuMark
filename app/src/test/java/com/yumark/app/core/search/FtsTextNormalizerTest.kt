package com.yumark.app.core.search

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [FtsTextNormalizer] 的分词契约。
 *
 * 这个类是全文搜索唯一的「分词真相」——索引侧和查询侧都调它，两边行为必须逐字一致，
 * 否则写进去的 token 和搜出来的 token 对不上，表现为「明明有这段文字却搜不到」。
 * 因此这里盯的是输出的精确字符串，而不是「大概能匹配」。
 *
 * 三条不变量：
 * 1. 输出只含 token 与单个空格（无首尾空格、无连续空格）；
 * 2. 输出永不含 FTS 语法字符（`"` `*` `^` `-` `:` `(` `)`），这是防注入的根基；
 * 3. 按码点遍历，代理对（emoji、CJK 扩展 B）绝不被劈成孤立代理。
 */
class FtsTextNormalizerTest {

    /** 覆盖后面各条共性断言的输入池，含正常文本、恶意语法、边界字符 */
    private val corpus = listOf(
        "Hello World", "我爱北京", "Kotlin协程", "我爱Kotlin", "你好，世界！",
        "项目、计划【重要】", "😀😃😄", "a😀b", "Hi 👋 世界", "𠀋", "",
        "   \t\n  ", "\"quoted\"", "kot*", "a OR b", "foo NEAR/2 bar",
        "^start", "-exclude", "col:val", "(a AND b) NOT c", "KOTLIN",
        "foo_bar", "v1.2.3", "こんにちは", "한글", "ＡＢ１", "https://a.b/c?d=e"
    )

    // ---- 纯 ASCII ----

    @Test
    fun `纯英文按空白切词并小写化`() {
        assertThat(FtsTextNormalizer.normalize("Hello World")).isEqualTo("hello world")
    }

    @Test
    fun `连续空白折叠成一个空格且不留首尾空格`() {
        // 首尾空格会让 FTS 短语里多出空 token，unicode61 虽然容忍但结果不可预测
        assertThat(FtsTextNormalizer.normalize("  Hello   World  ")).isEqualTo("hello world")
        assertThat(FtsTextNormalizer.normalize("line1\r\nline2")).isEqualTo("line1 line2")
        assertThat(FtsTextNormalizer.normalize("1\t2")).isEqualTo("1 2")
    }

    @Test
    fun `字母与数字连写视为同一个词`() {
        assertThat(FtsTextNormalizer.normalize("utf8")).isEqualTo("utf8")
        assertThat(FtsTextNormalizer.normalize("Android15")).isEqualTo("android15")
    }

    // ---- 纯中文 ----

    @Test
    fun `中文逐字拆成独立 token`() {
        // 这是整套方案的核心：不拆的话 unicode61 会把整句当一个 token，搜任何子串都为空
        assertThat(FtsTextNormalizer.normalize("我爱北京")).isEqualTo("我 爱 北 京")
    }

    @Test
    fun `单个汉字不产生多余空格`() {
        assertThat(FtsTextNormalizer.normalize("我")).isEqualTo("我")
    }

    @Test
    fun `日文假名与谚文同样逐字拆`() {
        // 日文不用空格，行为必须与中文一致；谚文按字拆只会更宽松，不会漏召回
        assertThat(FtsTextNormalizer.normalize("こんにちは")).isEqualTo("こ ん に ち は")
        assertThat(FtsTextNormalizer.normalize("한글")).isEqualTo("한 글")
    }

    @Test
    fun `全角字母数字按单字拆并小写`() {
        assertThat(FtsTextNormalizer.normalize("ＡＢ１")).isEqualTo("ａ ｂ １")
    }

    @Test
    fun `重复符与合字在 CJK 区内也按单字拆`() {
        assertThat(FtsTextNormalizer.normalize("々〆")).isEqualTo("々 〆")
    }

    // ---- 中英混排 ----

    @Test
    fun `中英混排时英文词保持完整`() {
        // 英文按字拆会让 token 爆炸且精度归零，必须只对 CJK 逐字
        assertThat(FtsTextNormalizer.normalize("Kotlin协程")).isEqualTo("kotlin 协 程")
        assertThat(FtsTextNormalizer.normalize("我爱Kotlin")).isEqualTo("我 爱 kotlin")
        assertThat(FtsTextNormalizer.normalize("用 Room 做 FTS 索引"))
            .isEqualTo("用 room 做 fts 索 引")
    }

    @Test
    fun `汉字与英文之间自动补空格`() {
        // 原文没有空格，但两侧必须成为不同 token，否则 "a我" 会被当成一个词
        assertThat(FtsTextNormalizer.normalize("a我b")).isEqualTo("a 我 b")
    }

    // ---- 中文标点 ----

    @Test
    fun `中文标点当分隔符且不出现在输出里`() {
        assertThat(FtsTextNormalizer.normalize("你好，世界！")).isEqualTo("你 好 世 界")
        assertThat(FtsTextNormalizer.normalize("项目、计划【重要】"))
            .isEqualTo("项 目 计 划 重 要")
        assertThat(FtsTextNormalizer.normalize("“引用”")).isEqualTo("引 用")
    }

    @Test
    fun `纯中文标点规范化为空串`() {
        assertThat(FtsTextNormalizer.normalize("，。！？；：")).isEmpty()
    }

    // ---- emoji 与代理对 ----

    @Test
    fun `纯 emoji 规范化为空串`() {
        // emoji 是 So 类，非字母数字，只能当分隔符；留在索引里也搜不到任何东西
        assertThat(FtsTextNormalizer.normalize("😀😃😄")).isEmpty()
    }

    @Test
    fun `emoji 作分隔符切开两侧的词`() {
        assertThat(FtsTextNormalizer.normalize("a😀b")).isEqualTo("a b")
        assertThat(FtsTextNormalizer.normalize("Hi 👋 世界")).isEqualTo("hi 世 界")
    }

    @Test
    fun `emoji 不会残留半个代理`() {
        // 逐 Char 处理会把代理对劈开，孤立代理写进索引就是永久乱码
        val result = FtsTextNormalizer.normalize("a😀b🎉c")
        assertThat(result).isEqualTo("a b c")
        assertThat(result.none { it.isSurrogate() }).isTrue()
    }

    @Test
    fun `CJK 扩展 B 的代理对完整保留并按单字拆`() {
        // 这些字本身就是代理对，既要保住成对，也要按 CJK 规则逐字拆
        assertThat(FtsTextNormalizer.normalize("𠀋")).isEqualTo("𠀋")
        assertThat(FtsTextNormalizer.normalize("𠀋𠀌")).isEqualTo("𠀋 𠀌")
        assertThat(FtsTextNormalizer.normalize("𠀋").length).isEqualTo(2)
    }

    // ---- 空串与空白 ----

    @Test
    fun `空串返回空串`() {
        assertThat(FtsTextNormalizer.normalize("")).isEmpty()
    }

    @Test
    fun `纯空白返回空串`() {
        assertThat(FtsTextNormalizer.normalize("   \t\n\r  ")).isEmpty()
        assertThat(FtsTextNormalizer.normalize(" 　")).isEmpty()
    }

    @Test
    fun `零宽字符不粘连两侧汉字`() {
        assertThat(FtsTextNormalizer.normalize("中​文")).isEqualTo("中 文")
    }

    // ---- 超长输入 ----

    @Test
    fun `超长中文输入逐字拆且不丢字`() {
        val result = FtsTextNormalizer.normalize("我".repeat(5000))

        assertThat(result.length).isEqualTo(9999)      // 5000 字 + 4999 空格
        assertThat(result.count { it == '我' }).isEqualTo(5000)
        assertThat(result.first()).isEqualTo('我')
        assertThat(result.last()).isEqualTo('我')
    }

    @Test
    fun `超长英文输入不丢词`() {
        val result = FtsTextNormalizer.normalize("ab ".repeat(5000))

        assertThat(result.length).isEqualTo(14999)     // 5000×2 字符 + 4999 空格
        assertThat(result.split(' ')).hasSize(5000)
    }

    // ---- FTS 运算符恶意输入 ----

    @Test
    fun `双引号被彻底剥离`() {
        // 未闭合的引号会让 SQLite 抛 malformed MATCH expression，搜索直接失败
        assertThat(FtsTextNormalizer.normalize("\"quoted\"")).isEqualTo("quoted")
        assertThat(FtsTextNormalizer.normalize("\"unterminated")).isEqualTo("unterminated")
    }

    @Test
    fun `星号被剥离不再是前缀查询`() {
        assertThat(FtsTextNormalizer.normalize("kot*")).isEqualTo("kot")
        assertThat(FtsTextNormalizer.normalize("*")).isEmpty()
    }

    @Test
    fun `布尔运算符降级为普通小写词`() {
        // 大写 AND/OR/NOT 才是运算符；小写化后即便脱离引号也不再有语法作用
        assertThat(FtsTextNormalizer.normalize("a OR b")).isEqualTo("a or b")
        assertThat(FtsTextNormalizer.normalize("(a AND b) NOT c")).isEqualTo("a and b not c")
    }

    @Test
    fun `NEAR 运算符被拆成词与数字`() {
        // NEAR/2 是硬报错来源之一，斜杠被当分隔符后 2 变成独立 token
        assertThat(FtsTextNormalizer.normalize("foo NEAR/2 bar")).isEqualTo("foo near 2 bar")
    }

    @Test
    fun `列限定与排除前缀被剥离`() {
        assertThat(FtsTextNormalizer.normalize("^start")).isEqualTo("start")
        assertThat(FtsTextNormalizer.normalize("-exclude")).isEqualTo("exclude")
        assertThat(FtsTextNormalizer.normalize("col:val")).isEqualTo("col val")
    }

    @Test
    fun `混合运算符轰炸后输出仍是纯 token 序列`() {
        assertThat(FtsTextNormalizer.normalize("a\"b*c^d-e:f OR g NEAR/2 h"))
            .isEqualTo("a b c d e f or g near 2 h")
    }

    // ---- 大小写 ----

    @Test
    fun `大小写归一到小写`() {
        val expected = "kotlin"
        assertThat(FtsTextNormalizer.normalize("KOTLIN")).isEqualTo(expected)
        assertThat(FtsTextNormalizer.normalize("Kotlin")).isEqualTo(expected)
        assertThat(FtsTextNormalizer.normalize("kOtLiN")).isEqualTo(expected)
    }

    // ---- 代码标识符 ----

    @Test
    fun `下划线标识符拆成相邻 token 仍可短语命中`() {
        // 下划线是 Pc 类标点，unicode61 本身也会在此断词；索引与查询同样处理即可对齐
        assertThat(FtsTextNormalizer.normalize("foo_bar")).isEqualTo("foo bar")
        assertThat(FtsTextNormalizer.normalize("MAX_PHRASE_TOKENS"))
            .isEqualTo("max phrase tokens")
    }

    @Test
    fun `版本号拆成相邻 token`() {
        assertThat(FtsTextNormalizer.normalize("v1.2.3")).isEqualTo("v1 2 3")
    }

    @Test
    fun `纯符号标识符不会产生空 token`() {
        assertThat(FtsTextNormalizer.normalize("___")).isEmpty()
        assertThat(FtsTextNormalizer.normalize("--")).isEmpty()
    }

    @Test
    fun `路径与邮箱按分隔符切开`() {
        assertThat(FtsTextNormalizer.normalize("user@example.com"))
            .isEqualTo("user example com")
        assertThat(FtsTextNormalizer.normalize("https://a.b/c?d=e"))
            .isEqualTo("https a b c d e")
    }

    // ---- 全量输入的共性 ----

    @Test
    fun `任何输入都不产生首尾空格或连续空格`() {
        corpus.forEach { input ->
            val out = FtsTextNormalizer.normalize(input)
            assertThat(out).isEqualTo(out.trim())
            assertThat(out).doesNotContain("  ")
        }
    }

    @Test
    fun `任何输入都不产生 FTS 语法字符`() {
        // 这条是防注入的最终防线：输出里没有语法字符，套引号后就不可能改变语义
        corpus.forEach { input ->
            val out = FtsTextNormalizer.normalize(input)
            listOf('"', '*', '^', '-', ':', '(', ')', '/').forEach { forbidden ->
                assertThat(out).doesNotContain(forbidden.toString())
            }
        }
    }

    @Test
    fun `规范化是幂等的`() {
        // 索引侧写入的是已规范化文本；若不幂等，重建索引就会漂移
        corpus.forEach { input ->
            val once = FtsTextNormalizer.normalize(input)
            assertThat(FtsTextNormalizer.normalize(once)).isEqualTo(once)
        }
    }

    @Test
    fun `任何输入都不残留孤立代理`() {
        corpus.forEach { input ->
            val out = FtsTextNormalizer.normalize(input)
            var i = 0
            while (i < out.length) {
                val c = out[i]
                if (c.isHighSurrogate()) {
                    // 高代理后必须紧跟低代理
                    assertThat(i + 1 < out.length).isTrue()
                    assertThat(out[i + 1].isLowSurrogate()).isTrue()
                    i += 2
                } else {
                    assertThat(c.isLowSurrogate()).isFalse()
                    i += 1
                }
            }
        }
    }
}
