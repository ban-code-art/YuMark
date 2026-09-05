package com.yumark.app.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 钉住 [WordCount] 的计数口径。
 *
 * 这些数字会被写进历史版本记录并长期展示，改算法就等于让老版本和新版本的字数无法比较，
 * 所以每条口径都要有一个用例挡着——包括那些「看起来像 bug 其实是有意为之」的边界。
 */
class WordCountTest {

    @Test
    fun `空正文与纯空白都算 0`() {
        assertThat(WordCount.of("")).isEqualTo(0)
        assertThat(WordCount.of("   \n\t  ")).isEqualTo(0)
    }

    @Test
    fun `中文按字算`() {
        assertThat(WordCount.of("今天天气不错")).isEqualTo(6)
    }

    @Test
    fun `英文按空白分词`() {
        assertThat(WordCount.of("the quick   brown\nfox")).isEqualTo(4)
    }

    @Test
    fun `中英混排时中文字数与英文词数相加`() {
        // 「中文」2 字 + hello + world = 4；关键是中文与英文之间没有空格也要分开算，
        // 否则「中文hello」会被当成一个整词。
        assertThat(WordCount.of("中文hello world")).isEqualTo(4)
    }

    @Test
    fun `代码块整段不计入`() {
        val content = "正文两字\n```kotlin\nfun main() { println(\"hi\") }\n```\n"
        assertThat(WordCount.of(content)).isEqualTo(4)
    }

    @Test
    fun `行内代码不计入`() {
        assertThat(WordCount.of("请运行 `npm install` 命令")).isEqualTo(5)
    }

    @Test
    fun `链接只算可见文本不算 URL`() {
        // 「查看文档」4 字；URL 里的 example / com 不该被当成两个英文词。
        assertThat(WordCount.of("[查看文档](https://example.com/a/b)")).isEqualTo(4)
    }

    @Test
    fun `图片只算 alt 文本`() {
        assertThat(WordCount.of("![示意图](img/a.png)")).isEqualTo(3)
    }

    @Test
    fun `标题与强调符号不计入`() {
        // `## 标题` 去掉 # 后是「标题」2 字；`**加粗**` 去掉 * 后是「加粗」2 字。
        assertThat(WordCount.of("## 标题\n\n**加粗**")).isEqualTo(4)
    }

    @Test
    fun `字符数是原样长度不剥语法`() {
        // 与 of 刻意不同：它回答「文件多大」，代码块和 URL 都要算进去。
        val content = "# 标题\n`code`"
        assertThat(WordCount.charactersOf(content)).isEqualTo(content.length)
    }

    @Test
    fun `假名与 CJK 扩展区走英文分词一整段算一个词`() {
        // 有意保持的边界：只有 U+4E00–U+9FFF 被当成表意文字逐字计数，假名不在区间内，
        // 于是落到英文分词那条路——连续假名之间没有空格，整段算 1。
        // 日文正文的字数因此偏低；改这条要连历史版本里的旧数字一起迁移。
        assertThat(WordCount.of("ひらがな")).isEqualTo(1)
        // 混排：日本語 3 个汉字逐字计入，剩下的「のひらがな」是一段连续假名算 1 个词。
        assertThat(WordCount.of("日本語のひらがな")).isEqualTo(4)
    }
}
