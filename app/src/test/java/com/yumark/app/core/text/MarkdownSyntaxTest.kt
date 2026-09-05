package com.yumark.app.core.text

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * [MarkdownAction.applyTo] 单测。
 *
 * 这套断言的价值在于：从前这段逻辑是 EditorScreen 里一个 60 行的 Compose lambda，
 * 靠三张以语法字面量为键的表分派，一行也测不到。真机上暴露过的两个 bug（选中文字点加粗
 * 得到 `****重要`、表格紧贴正文行导致 GFM 不认表格）现在各有一条用例钉住。
 */
class MarkdownSyntaxTest {

    // ---------- 行首前缀：标题 / 列表 / 引用 ----------

    @Test
    fun `无选区时前缀加在当前行行首`() {
        MarkdownAction.HEADING.applyTo(caret("abc", 3)).shouldBe("# abc", 5)
    }

    @Test
    fun `空文档也能加前缀`() {
        MarkdownAction.HEADING.applyTo(caret("", 0)).shouldBe("# ", 2)
    }

    @Test
    fun `只影响光标所在的那一行`() {
        // 光标在第二行的两个 b 之间
        MarkdownAction.BULLET_LIST.applyTo(caret("aa\nbb", 4)).shouldBe("aa\n- bb", 6)
    }

    @Test
    fun `跨行选区里每一行都加前缀`() {
        // 选中 "ne\ntwo\nt"，覆盖三行
        MarkdownAction.BULLET_LIST.applyTo(sel("one\ntwo\nthree", 1, 9))
            .shouldBe("- one\n- two\n- three", 3, 15)
    }

    @Test
    fun `编号列表每行都写 1 点`() {
        // CommonMark 会自动递增，写死 "1." 渲染出来仍是 1. 2.
        MarkdownAction.NUMBERED_LIST.applyTo(sel("a\nb", 0, 3)).shouldBe("1. a\n1. b", 3, 9)
    }

    @Test
    fun `选区末尾正好落在行首时不带上下一行`() {
        // 选中 "one\n"：拖到行尾松手常常会多带一个换行，下一行不该跟着变引用
        MarkdownAction.QUOTE.applyTo(sel("one\ntwo", 0, 4)).shouldBe("> one\ntwo", 2, 6)
    }

    // ---------- 行内包裹：加粗 / 斜体 / 删除线 / 行内代码 ----------

    @Test
    fun `无选区时两半都插下去且光标落在中间`() {
        MarkdownAction.BOLD.applyTo(caret("", 0)).shouldBe("****", 2)
        MarkdownAction.ITALIC.applyTo(caret("", 0)).shouldBe("**", 1)
        MarkdownAction.CODE.applyTo(caret("", 0)).shouldBe("``", 1)
        MarkdownAction.STRIKETHROUGH.applyTo(caret("", 0)).shouldBe("~~~~", 2)
    }

    @Test
    fun `有选区时标记夹住原文而不是插在它前面`() {
        // 真机 bug：从前只看 selection start，选中「重要」点加粗得到 `****重要`
        MarkdownAction.BOLD.applyTo(sel("重要", 0, 2)).shouldBe("**重要**", 2, 4)
    }

    @Test
    fun `对称包裹后选区仍框着原文，可以连点加粗再点斜体`() {
        val bolded = MarkdownAction.BOLD.applyTo(sel("重要", 0, 2))
        MarkdownAction.ITALIC.applyTo(bolded).shouldBe("***重要***", 3, 5)
    }

    @Test
    fun `删除线包裹选区`() {
        MarkdownAction.STRIKETHROUGH.applyTo(sel("删了", 0, 2)).shouldBe("~~删了~~", 2, 4)
    }

    @Test
    fun `反向选区按正向处理`() {
        // TextFieldValue 的 selection 可以 start 大于 end（从后往前拖选）
        MarkdownAction.BOLD.applyTo(TextSnapshot("重要", 2, 0)).shouldBe("**重要**", 2, 4)
    }

    @Test
    fun `越界坐标被夹回文本范围内`() {
        MarkdownAction.BOLD.applyTo(TextSnapshot("ab", 99, 99)).shouldBe("ab****", 4)
    }

    // ---------- 链接与图片：占位符要被选中 ----------

    @Test
    fun `链接有选区时选中 url 占位符`() {
        MarkdownAction.LINK.applyTo(sel("官网", 0, 2)).shouldBe("[官网](url)", 5, 8)
    }

    @Test
    fun `链接无选区时光标落在方括号里先写显示文字`() {
        MarkdownAction.LINK.applyTo(caret("", 0)).shouldBe("[](url)", 1)
    }

    @Test
    fun `图片语法比链接多一个叹号`() {
        MarkdownAction.IMAGE.applyTo(caret("", 0)).shouldBe("![](url)", 2)
        MarkdownAction.IMAGE.applyTo(sel("图", 0, 1)).shouldBe("![图](url)", 5, 8)
    }

    @Test
    fun `选中的原文里含 url 三个字母也不会选错位置`() {
        // 占位符只在右半边里找；全串搜索会命中原文里的 "url"
        MarkdownAction.LINK.applyTo(sel("myurl", 0, 5)).shouldBe("[myurl](url)", 8, 11)
    }

    // ---------- 独占成块：代码块 / 表格 / 分隔线 ----------

    @Test
    fun `代码块把选区围进栅栏`() {
        MarkdownAction.CODE_BLOCK.applyTo(sel("x=1", 0, 3)).shouldBe("```\nx=1\n```\n", 4, 7)
    }

    @Test
    fun `代码块无选区时光标停在两条栅栏之间`() {
        MarkdownAction.CODE_BLOCK.applyTo(caret("", 0)).shouldBe("```\n\n```\n", 4)
    }

    @Test
    fun `分隔线前面补出一个空行`() {
        // 紧贴正文行时 `---` 是 setext 二级标题的下划线，会把上一行整行变成标题
        MarkdownAction.HORIZONTAL_RULE.applyTo(caret("abc", 3)).shouldBe("abc\n\n---\n", 8)
    }

    @Test
    fun `空文档插分隔线不补前导空行`() {
        MarkdownAction.HORIZONTAL_RULE.applyTo(caret("", 0)).shouldBe("---\n", 3)
    }

    @Test
    fun `上面已经是空行就不再补`() {
        MarkdownAction.HORIZONTAL_RULE.applyTo(caret("abc\n\n", 5)).shouldBe("abc\n\n---\n", 8)
    }

    @Test
    fun `已在行首但上一行有内容时补一个换行`() {
        MarkdownAction.HORIZONTAL_RULE.applyTo(caret("abc\n", 4)).shouldBe("abc\n\n---\n", 8)
    }

    @Test
    fun `插在行中间时先断行再空行，后面同样隔开`() {
        MarkdownAction.HORIZONTAL_RULE.applyTo(caret("abcdef", 3)).shouldBe("abc\n\n---\n\ndef", 8)
    }

    @Test
    fun `表格前面补空行，否则 GFM 不认它是表格`() {
        // 从前的模板只带一个前导换行，紧贴段落时整块被当成那一段的普通文字渲染
        MarkdownAction.TABLE.applyTo(caret("abc", 3))
            .shouldBe("abc\n\n| 列1 | 列2 |\n|-----|-----|\n| 内容 | 内容 |\n", 7, 9)
    }

    @Test
    fun `表格插入后选中第一个表头，直接打字就是列名`() {
        val result = MarkdownAction.TABLE.applyTo(caret("", 0))

        assertThat(result.text).isEqualTo("| 列1 | 列2 |\n|-----|-----|\n| 内容 | 内容 |\n")
        assertThat(result.text.substring(result.selectionStart, result.selectionEnd)).isEqualTo("列1")
    }

    @Test
    fun `表格与分隔线不吃掉选中的原文`() {
        // right 为空的块走纯插入：块落在选区之后，选中的字原样留着
        MarkdownAction.HORIZONTAL_RULE.applyTo(sel("abc", 0, 3)).shouldBe("abc\n\n---\n", 8)
    }

    // ---------- 全语法不变量 ----------

    @Test
    fun `任何语法作用在任何快照上都不删字且选区不越界`() {
        val inputs = listOf(
            caret("", 0),
            caret("abc", 0),
            caret("abc", 3),
            caret("aa\nbb\ncc", 4),
            caret("aa\n\nbb", 3),
            sel("aa\nbb\ncc", 1, 7),
            sel("abc", 3, 0),          // 反向
            TextSnapshot("abc", -5, 99) // 双向越界
        )
        for (action in MarkdownAction.entries) {
            for (input in inputs) {
                val out = action.applyTo(input)
                val where = assertWithMessage(
                    "$action on ${input.text.length} chars, sel ${input.selectionStart}..${input.selectionEnd}"
                )
                // 只增不减：任何按钮都不该吃掉用户已经写下的字
                where.that(out.text.length).isGreaterThan(input.text.length)
                where.that(out.selectionStart).isAtLeast(0)
                where.that(out.selectionStart).isAtMost(out.selectionEnd)
                // 选区右端不能越过文本末尾：越界的 TextFieldValue 在 Compose 侧直接抛异常
                where.that(out.selectionEnd).isAtMost(out.text.length)
            }
        }
    }

    // ---------- 已落盘图片的引用插入 ----------

    @Test
    fun `无选区时光标落在 alt 位置`() {
        // 下一步就是打 alt 文本，所以光标必须在 ![| 而不是括号里或整串之后
        insertImageRef(caret("", 0), "images/a.jpg")
            .shouldBe("![](images/a.jpg)", 2)
    }

    @Test
    fun `有选区时选中的字变成 alt 文本`() {
        insertImageRef(sel("风景照", 0, 3), "images/a.jpg")
            .shouldBe("![风景照](images/a.jpg)", 2, 5)
    }

    @Test
    fun `反向选区与越界坐标都被规整`() {
        insertImageRef(sel("abc", 3, 0), "images/a.jpg")
            .shouldBe("![abc](images/a.jpg)", 2, 5)
        insertImageRef(TextSnapshot("abc", -5, 99), "images/a.jpg")
            .shouldBe("![abc](images/a.jpg)", 2, 5)
    }

    @Test
    fun `插在正文中间只动光标处`() {
        insertImageRef(caret("前后", 1), "images/a.jpg")
            .shouldBe("前![](images/a.jpg)后", 3)
    }

    // ---------- 链接目标转义 ----------

    @Test
    fun `仓库生成的路径一个字符都不动`() {
        // ImageRepositoryImpl 给的是 images/<uuid>.<ext>，永远走 raw 分支
        assertThat(markdownDestination("images/0f3c1a2b-4d5e.jpg"))
            .isEqualTo("images/0f3c1a2b-4d5e.jpg")
    }

    @Test
    fun `带空格或括号的路径改用尖括号形态`() {
        // 裸目标里空格会截断链接，渲染出来是一行字面量而不是图片
        assertThat(markdownDestination("my photo.jpg")).isEqualTo("<my photo.jpg>")
        assertThat(markdownDestination("a(1).jpg")).isEqualTo("<a(1).jpg>")
        assertThat(markdownDestination("")).isEqualTo("<>")
    }

    @Test
    fun `尖括号形态里转义反斜杠与尖括号，换行压成空格`() {
        assertThat(markdownDestination("a b<c>d")).isEqualTo("<a b\\<c\\>d>")
        assertThat(markdownDestination("a\\ b")).isEqualTo("<a\\\\ b>")
        assertThat(markdownDestination("a\nb")).isEqualTo("<a b>")
        assertThat(markdownDestination("a\r\nb")).isEqualTo("<a  b>")
    }

    @Test
    fun `插入后的引用不含裸空格与半个括号`() {
        // 转义的意义全在这里：无论上游递进来什么，正文里落下的都是一条能渲染的引用
        for (path in listOf("images/a.jpg", "my photo.jpg", "a(1).jpg", "a\nb", "")) {
            val out = insertImageRef(caret("", 0), path)
            val dest = out.text.substringAfter("](").substringBeforeLast(")")
            val where = assertWithMessage("path=$path -> ${out.text}")
            where.that(out.text).startsWith("![](")
            where.that(out.text).endsWith(")")
            where.that(dest.contains('\n')).isFalse()
            if (dest.startsWith("<")) {
                where.that(dest).endsWith(">")
            } else {
                where.that(dest.any { it.isWhitespace() }).isFalse()
                where.that(dest.count { it == '(' }).isEqualTo(dest.count { it == ')' })
            }
        }
    }
}

// ---------- 断言辅助 ----------

/** 无选区快照。 */
private fun caret(text: String, at: Int) = TextSnapshot(text, at, at)

/** 有选区快照。 */
private fun sel(text: String, start: Int, end: Int) = TextSnapshot(text, start, end)

/** 一次断言文本与选区两端，让用例读起来是「输入 → 期望」一行。 */
private fun TextSnapshot.shouldBe(expectedText: String, start: Int, end: Int) {
    assertThat(text).isEqualTo(expectedText)
    assertThat(selectionStart).isEqualTo(start)
    assertThat(selectionEnd).isEqualTo(end)
}

/** 光标塌成一点时的重载。 */
private fun TextSnapshot.shouldBe(expectedText: String, caretAt: Int) =
    shouldBe(expectedText, caretAt, caretAt)
