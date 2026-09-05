package com.yumark.app.data.ai.rag

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

class MarkdownChunkerTest {

    @Test
    fun `empty content yields no chunks`() {
        val chunks = MarkdownChunker.chunkMarkdown("", "doc1")
        assertThat(chunks).isEmpty()
    }

    @Test
    fun `short meaningful content becomes a single chunk`() {
        val md = """
            # 标题一
            这是一段足够长且有意义的正文内容，用于测试分块逻辑能否正常工作。
        """.trimIndent()
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].heading).isEqualTo("标题一")
        assertThat(chunks[0].titlePath).contains("标题一")
        assertThat(chunks[0].content).contains("正文内容")
    }

    @Test
    fun `heading inside code fence is not a section boundary`() {
        val md = """
            # 真标题
            ```python
            # 这只是注释，不是标题
            code = 1
            ```
            正文段落保持完整不被注释切断。
        """.trimIndent()
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        // 应只产生一个节：代码围栏内的 # 不切分
        assertThat(chunks).isNotEmpty()
        assertThat(chunks.any { it.heading == "真标题" }).isTrue()
        assertThat(chunks.any { it.heading == "这只是注释，不是标题" }).isFalse()
    }

    @Test
    fun `duplicate content hashes are deduplicated`() {
        val body = "重复的正文内容重复的正文内容重复的正文内容重复的正文内容。"
        val md = """
            # 同标题
            $body

            # 同标题
            $body
        """.trimIndent()
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        // 两节标题与正文完全相同 → contentHash 去重，只保留一块
        assertThat(chunks).hasSize(1)
    }

    @Test
    fun `long text block is split near chunk size with overlap`() {
        val para = "这是一段用于测试软断点切分的长文本。".repeat(80)  // 远超 900，无换行
        val md = "# 长文\n$para"
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1", chunkSize = 900, overlap = 150)
        // 软断点切分生效：单一大段被切成多块（overlap 取整块时后续块可能略超 chunkSize，与 guanmo 一致）
        assertThat(chunks.size).isGreaterThan(1)
        assertThat(chunks.map { it.index }).isEqualTo(chunks.indices.toList())
    }

    @Test
    fun `chunk ids are namespaced by document id`() {
        val chunks = MarkdownChunker.chunkMarkdown("# T\n有意义的正文内容有意义的正文内容有意义的正文内容。", "doc-42")
        assertThat(chunks).isNotEmpty()
        assertThat(chunks[0].documentId).isEqualTo("doc-42")
        assertThat(chunks[0].id).startsWith("doc-42-chunk-")
    }

    @Test
    fun `content hash is stable for identical content`() {
        val md = "# T\n有意义的正文内容有意义的正文内容有意义的正文内容。"
        val a = MarkdownChunker.chunkMarkdown(md, "d1")
        val b = MarkdownChunker.chunkMarkdown(md, "d2")
        assertThat(a).isNotEmpty()
        // 相同正文 → 相同 contentHash（与 documentId 无关）
        assertThat(a[0].contentHash).isEqualTo(b[0].contentHash)
    }

    // ---- 围栏识别：``` 与 ~~~ 两种都要认，且必须同字符配对 ----

    @Test
    fun `波浪号围栏内的假标题不切分也不进 heading`() {
        val md = listOf(
            "# 真标题",
            "~~~python",
            "# 这只是注释，不是标题",
            "code = 1",
            "~~~",
            "正文段落保持完整不被代码注释切断，这句话足够长以保证有意义。"
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        // 只认 ``` 时，~~~ 块里的注释会成为新章节标题 → 代码块被拦腰切成两块
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].heading).isEqualTo("真标题")
        assertWithMessage("~~~ 围栏内的注释不得成为章节标题")
            .that(chunks.any { it.heading == "这只是注释，不是标题" }).isFalse()
        assertWithMessage("~~~ 围栏内的注释不得进入 titlePath")
            .that(chunks.any { c -> c.titlePath.any { it == "这只是注释，不是标题" } }).isFalse()
        assertThat(chunks[0].content).contains("# 这只是注释，不是标题")
    }

    @Test
    fun `反引号围栏内的波浪号不结束代码块`() {
        val md = listOf(
            "# 真标题",
            "```markdown",
            "~~~",
            "# 反引号块里的假标题",
            "~~~",
            "# 仍然在反引号块里",
            "```",
            "正文说明这段代码块里嵌了另一种围栏，长度足够判定有意义。"
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].heading).isEqualTo("真标题")
        assertWithMessage("``` 块里的 ~~~ 只是代码内容，不能提前闭合代码块")
            .that(chunks.any { it.heading == "反引号块里的假标题" || it.heading == "仍然在反引号块里" })
            .isFalse()
        assertThat(chunks[0].content).contains("# 反引号块里的假标题")
        assertThat(chunks[0].content).contains("# 仍然在反引号块里")
    }

    @Test
    fun `波浪号围栏内的反引号不结束代码块`() {
        val md = listOf(
            "# 真标题",
            "~~~markdown",
            "```",
            "# 波浪号块里的假标题",
            "```",
            "# 仍然在波浪号块里",
            "~~~",
            "正文说明这段代码块里嵌了另一种围栏，长度足够判定有意义。"
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].heading).isEqualTo("真标题")
        assertWithMessage("~~~ 块里的 ``` 只是代码内容，不能提前闭合代码块")
            .that(chunks.any { it.heading == "波浪号块里的假标题" || it.heading == "仍然在波浪号块里" })
            .isFalse()
        assertThat(chunks[0].content).contains("# 波浪号块里的假标题")
        assertThat(chunks[0].content).contains("# 仍然在波浪号块里")
    }

    @Test
    fun `更短的围栏不能关闭更长的围栏`() {
        val md = listOf(
            "# 真标题",
            "````text",
            "```",
            "# 四个反引号块里的假标题",
            "```",
            "````",
            "正文说明长围栏包住了短围栏，长度足够判定有意义。"
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].heading).isEqualTo("真标题")
        assertWithMessage("```` 开的围栏不能被 ``` 关掉，长围栏正是用来包住短围栏的")
            .that(chunks.any { it.heading == "四个反引号块里的假标题" }).isFalse()
        assertThat(chunks[0].content).contains("# 四个反引号块里的假标题")
    }

    @Test
    fun `围栏前 1 到 3 个空格缩进仍被识别为围栏`() {
        for (marker in listOf("```", "~~~")) {
            for (indent in 1..3) {
                val pad = " ".repeat(indent)
                val md = listOf(
                    "# 真标题",
                    pad + marker + "python",
                    pad + "# 缩进围栏里的注释",
                    pad + "---",
                    pad + "code = 1",
                    pad + marker,
                    "正文段落保持完整不被代码注释切断，这句话足够长以保证有意义。"
                ).joinToString("\n")
                val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
                assertThat(chunks).hasSize(1)
                assertWithMessage("缩进 ${indent} 个空格的 ${marker} 围栏内的注释不得成为标题")
                    .that(chunks.any { it.heading == "缩进围栏里的注释" }).isFalse()
                assertWithMessage("缩进 ${indent} 个空格的 ${marker} 围栏内容应原样保留")
                    .that(chunks[0].content).contains(pad + "# 缩进围栏里的注释")
                // 缩进围栏没被认出来时，这一行会被当成分隔线整行删掉
                assertWithMessage("缩进 ${indent} 个空格的 ${marker} 围栏内的 --- 应原样保留")
                    .that(chunks[0].content).contains(pad + "---")
            }
        }
    }

    // ---- 围栏内的行原样保留：删一行就等于篡改用户的代码 ----

    @Test
    fun `代码块内部的分隔线原样保留`() {
        for (marker in listOf("```", "~~~")) {
            val md = listOf(
                "# 配置说明",
                marker + "yaml",
                "---",
                "name: demo",
                "---",
                marker,
                "上面是这份配置的完整内容，说明文字保证这块内容有意义。"
            ).joinToString("\n")
            val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
            assertThat(chunks).hasSize(1)
            assertWithMessage("${marker} 围栏内的 --- 是代码内容（YAML 文档分隔符），删掉就是篡改用户文档")
                .that(chunks[0].content).contains("---\nname: demo\n---")
        }
    }

    @Test
    fun `代码块内部的空行原样保留`() {
        val md = listOf(
            "# 示例代码",
            "```python",
            "def first():",
            "    return 1",
            "",
            "",
            "def second():",
            "    return 2",
            "```",
            "上面两个函数之间的空行是代码格式的一部分，不能被折叠掉。"
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        assertThat(chunks).hasSize(1)
        assertWithMessage("围栏内的空行既不能当块边界，也不能被折叠")
            .that(chunks[0].content).contains("    return 1\n\n\ndef second():")
    }

    @Test
    fun `未闭合围栏一直延伸到文末且不抛异常`() {
        val md = listOf(
            "# 真标题",
            "```python",
            "# 这是注释不是标题",
            "code = 1",
            "---",
            "还在代码块里，因为这个围栏一直没有闭合。"
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        // 语义：与 CommonMark 的隐式闭合一致，未闭合围栏延伸到输入末尾，整段原样保留
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].heading).isEqualTo("真标题")
        assertThat(chunks.any { it.heading == "这是注释不是标题" }).isFalse()
        assertThat(chunks[0].content).contains("# 这是注释不是标题")
        assertWithMessage("未闭合围栏内的 --- 同样不能被当分隔线删掉")
            .that(chunks[0].content).contains("---")
    }

    @Test
    fun `相邻两个代码块之后的标题仍能分节`() {
        val md = listOf(
            "# 第一节",
            "```python",
            "a = 1",
            "```",
            "```js",
            "const b = 2;",
            "```",
            "# 第二节",
            "第二节的正文内容足够长，用来确认围栏配对没有把后面的标题吞掉。"
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        assertThat(chunks.map { it.heading }).containsExactly("第一节", "第二节").inOrder()
    }

    // ---- 长度与参数：块不得超上限，参数越界也不得丢正文 ----

    /** 每句正好 20 字、句末带 `。`，好精确推断软断点落在哪里。 */
    private fun sentences(count: Int): List<String> =
        (1..count).map { "第" + "%02d".format(it) + "句正文内容用来测试切分不丢失内容。" }

    @Test
    fun `chunkSize 与 overlap 越界时不崩溃也不丢正文`() {
        val body = sentences(90)
        val md = "# 参数消毒\n\n" + body.joinToString("")
        // 0 / 负值 / 大于 chunkSize 的 overlap：三种都会在原实现里各自炸一次（见 Budget 注释）
        for ((size, overlap) in listOf(0 to 0, -10 to -5, 1 to 900, 900 to 900, 900 to -1, 300 to 0)) {
            val label = "chunkSize=${size} overlap=${overlap}"
            val chunks = MarkdownChunker.chunkMarkdown(md, "doc1", chunkSize = size, overlap = overlap)
            assertWithMessage("${label} 应切出分块").that(chunks).isNotEmpty()
            assertWithMessage("${label} 不该有空块").that(chunks.none { it.content.isBlank() }).isTrue()
            assertWithMessage("${label} chunk index 必须连续")
                .that(chunks.map { it.index }).isEqualTo(chunks.indices.toList())
            val joined = chunks.joinToString("\n") { it.content }
            assertWithMessage("${label} 时这些句子从索引里消失了（负 overlap 会让切分跳过一段正文）")
                .that(body.filter { it !in joined }).isEmpty()
            val ceiling = MarkdownChunker.maxChunkChars(size, overlap)
            assertWithMessage("${label} 有块超过硬上限 ${ceiling}（embedding 请求会超模型上下文，整篇索引失败）")
                .that(chunks.filter { it.content.length > ceiling }.map { it.content.length }).isEmpty()
        }
    }

    @Test
    fun `块间重叠不会把块撑成两倍 chunkSize`() {
        val paragraphs = (1..6).map { p ->
            (1..40).joinToString("") { i -> "第" + "%d%02d".format(p, i) + "句正文内容用来测试重叠不超上限。" }
        }
        val md = "# 长文档\n\n" + paragraphs.joinToString("\n\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")

        val limit = MarkdownChunker.maxTextChunkChars()
        assertWithMessage(
            "不含代码块的文档，块长不得超过 chunkSize + overlap = ${limit}。" +
                "原来重叠会把上一块的整个 800 字段落搬进下一块，块直接涨到 2 倍"
        ).that(chunks.filter { it.content.length > limit }.map { it.content.length }).isEmpty()

        assertWithMessage("索引出去的总字数不该接近原文两倍：重叠里重复的正文都要花 embedding 的钱")
            .that(chunks.sumOf { it.content.length }).isLessThan(md.length * 3 / 2)

        val joined = chunks.joinToString("\n") { it.content }
        assertWithMessage("段落不得丢失").that(paragraphs.filter { it !in joined }).isEmpty()
    }

    // ---- 超长代码块：不切会让整篇文档索引失败，切了就必须补回围栏 ----

    @Test
    fun `超长代码块被切成多片且每片都自带围栏`() {
        val codeLines = (1..400)
            .map { "line_" + "%03d".format(it) + " = compute(" + "%03d".format(it) + ")" }
            .toMutableList()
        // 落在第二片里的一行 ---：片子没补回围栏时，它会被 cleanLine 当分隔线整行删掉
        codeLines.add(200, "---")
        val md = (
            listOf("# 源码", "```python") + codeLines +
                listOf("```", "尾部说明文字，用来确认代码块之后的正文仍然进索引。")
            ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")

        assertWithMessage("近万字符的代码块整块进 embedding 会超模型上下文，那会让整篇文档索引失败")
            .that(chunks.size).isGreaterThan(2)
        val ceiling = MarkdownChunker.maxChunkChars()
        assertWithMessage("代码块切开后仍不得有块超过硬上限 ${ceiling}")
            .that(chunks.filter { it.content.length > ceiling }.map { it.content.length }).isEmpty()

        val joined = chunks.joinToString("\n") { it.content }
        assertWithMessage("代码行不得在切分中丢失").that(codeLines.filter { it !in joined }).isEmpty()
        assertWithMessage("代码块内部的 --- 只有在每片都自带围栏时才不会被当分隔线删掉")
            .that(joined).contains("\n---\n")
        for (chunk in chunks.filter { "line_" in it.content }) {
            assertWithMessage("每片代码都要带开围栏，否则这片会被当普通 Markdown 清洗一遍")
                .that(chunk.content).contains("```python")
            assertWithMessage("每片代码都要闭合围栏，AI 拿到的不能是一截没头没尾的字符流")
                .that(chunk.content.trimEnd().endsWith("```")).isTrue()
        }
    }

    // ---- 代码块与正文的边界 ----

    @Test
    fun `代码块前后的正文不会被拼成相邻段落`() {
        val md = listOf(
            "# 步骤",
            "第一步先做准备工作，这一段是代码块前面的说明文字，长度足够判定有意义。",
            "",
            "```bash",
            "run --prepare",
            "```",
            "",
            "第二步再做收尾工作，这一段是代码块后面的说明文字，长度足够判定有意义。"
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1", chunkSize = 64, overlap = 32)
        // 原来取重叠时遇到代码块是跳过它继续往前找正文，于是代码块前后的两段被拼成"相邻段落"
        assertWithMessage("代码块前后的说明在文档里并不相邻，拼进同一块会让 AI 照着这个不存在的段落回答问题")
            .that(chunks.none { "准备工作" in it.content && "收尾工作" in it.content }).isTrue()
    }

    @Test
    fun `正文紧接代码围栏时代码不会被软断点切开`() {
        val codeLine = "value = obj." + (1..10).joinToString(".") { "step" + "%02d".format(it) + "(alpha)" }
        // 正文与围栏之间不空行（"说明：" 紧跟 ```js）在真实文档里很常见
        val md = listOf(
            "# 混排",
            "说明文字".repeat(24) + "结束。",
            "```js",
            codeLine,
            "```"
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1", chunkSize = 200, overlap = 50)
        // 混排块以正文开头 → isCode=false → 软断点会把 obj.method() 里的 `.` 当句末，从中间切开代码
        assertWithMessage("代码行不得被软断点切成两半，检索到半行代码等于答不出问题")
            .that(chunks.any { codeLine in it.content }).isTrue()
    }

    // ---- 表格：切开后每片都得有表头，否则数据行没有列名 ----

    @Test
    fun `超长表格的每一片都带表头`() {
        val rows = (1..200).map { "| 年" + "%03d".format(it) + " | " + (10000 + it) + " | 是 |" }
        val md = (
            listOf("# 年度数据", "", "| 年份 | 收入 | 达标 |", "| --- | --- | --- |") + rows
            ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1", chunkSize = 400, overlap = 0)

        val dataChunks = chunks.filter { "| 1" in it.content }
        assertWithMessage("4000 多字符的表格必须被切开").that(dataChunks.size).isGreaterThan(1)
        for (chunk in dataChunks) {
            assertWithMessage(
                "检索命中一行 | 年007 | 10007 | 是 | 时，没有表头的话 AI 既不知道列名，" +
                    "也分不清 10007 是收入还是别的什么"
            ).that(chunk.content).contains("| 年份 | 收入 | 达标 |")
        }
        val joined = chunks.joinToString("\n") { it.content }
        assertWithMessage("数据行不得丢失").that(rows.filter { it !in joined }).isEmpty()
    }

    // ---- 去重：32 位摘要撞车不能让一整块正文永久消失 ----

    @Test
    fun `两段撞哈希的正文都要留在索引里`() {
        // 这两段正文的 FNV-1a 32 位摘要都是 8a5bfafa（真实碰撞对，不是构造的假设）
        val first = "j7bzn2en8l66qp0qfuldzktpcfaa0je567nnq3c3"
        val second = "24geigdun4yejmiy1ttjptotoy6bzlpe1nxs16wg"
        assertThat(createContentHash("# jiaduan\n" + first))
            .isEqualTo(createContentHash("# yiduan\n" + second))

        val md = listOf("# jiaduan", first, "", "# yiduan", second).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        val joined = chunks.joinToString("\n") { it.content }
        assertWithMessage("摘要相同但内容不同：拿 8 位十六进制串当去重键会让后一段正文彻底消失在检索里，" +
            "而且不报错、不提示，只要用户不改这篇文档就永远消失")
            .that(joined).contains(second)
        assertThat(joined).contains(first)
        assertThat(chunks).hasSize(2)
    }

    // ---- 内容哈希：肉眼看不见的字符不能触发整篇重建索引 ----

    @Test
    fun `不可见字符不改变内容哈希`() {
        val base = "# 标题\n这是一段用于固定哈希值的正文内容。"
        assertWithMessage("干净正文的哈希必须和补齐归一化之前一致，否则存量文档会被这次改动逼着整篇重算 embedding")
            .that(createContentHash(base)).isEqualTo("737ca0de")

        val variants = linkedMapOf(
            "CRLF 行尾" to base.replace("\n", "\r\n"),
            "裸 CR 行尾" to base.replace("\n", "\r"),
            "开头的 BOM" to "\uFEFF" + base,
            "结尾的零宽空格" to base + "\u200B",
            "NBSP 当空格" to base.replace("# ", "#\u00A0"),
            "全角空格当空格" to base.replace("# ", "#\u3000"),
            "结尾多余空行" to base + "\n\n\n"
        )
        for ((name, text) in variants) {
            assertWithMessage(
                "${name} 在编辑器里看不出任何差别，却会让 RagPipeline 判定整篇文档变了 → " +
                    "整篇重算 embedding（真花钱、真耗时），用户什么都没改却看到索引又跑一遍"
            ).that(createContentHash(text)).isEqualTo("737ca0de")
        }

        assertWithMessage("归一化不能宽到把真实改动也抹掉：少一个句号就是改过")
            .that(createContentHash(base.dropLast(1))).isNotEqualTo("737ca0de")
        assertWithMessage("ZWNJ 在波斯语/emoji 序列里改变内容，抹掉它会让两段不同的正文被判成同一块")
            .that(createContentHash("a\u200Cb")).isNotEqualTo(createContentHash("ab"))
    }

    // ---- 缩进标题：CommonMark 允许 1~3 个前导空格 ----

    @Test
    fun `前导 1 到 3 个空格的标题仍然分节`() {
        for (indent in 1..3) {
            val pad = " ".repeat(indent)
            val md = listOf(
                pad + "# 第一节",
                "第一节的正文内容足够长，用来确认缩进标题也能正常分节。",
                "",
                pad + "## 第二节",
                "第二节的正文内容足够长，用来确认缩进标题也能正常分节。"
            ).joinToString("\n")
            val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
            assertWithMessage(
                "缩进 ${indent} 个空格的 # 在 CommonMark 里仍是标题（渲染侧就这么渲染），" +
                    "认不出来时整篇挤成一个巨型 section，heading 与 titlePath 全空"
            ).that(chunks.map { it.heading }).containsExactly("第一节", "第二节").inOrder()
            assertThat(chunks.last().titlePath).containsExactly("第一节", "第二节").inOrder()
        }
    }

    @Test
    fun `缩进 4 个空格的井号不是标题`() {
        val md = listOf(
            "    # 这是缩进代码块不是标题",
            "正文内容足够长，用来确认四个空格缩进的井号没有被当成标题分节。"
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        assertWithMessage("4 个空格缩进在 CommonMark 里是代码块，不能当标题")
            .that(chunks.none { it.heading != null }).isTrue()
    }

    // ---- 行号：Chunk.startLine/endLine 要指得回原文的那一行 ----

    @Test
    fun `代码块之后的正文行号指向它在原文里的那一行`() {
        val md = listOf(
            "# 行号标题",                                   // 1
            "第一段正文，用来占住行号。",                     // 2
            "",                                            // 3
            "",                                            // 4
            "```js",                                       // 5
            "const a = 1;",                                // 6
            "```",                                         // 7
            "",                                            // 8
            "最后一段正文，它的行号必须指向原文里的第 9 行。"   // 9
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1", chunkSize = 64, overlap = 0)
        assertThat(chunks).hasSize(2)
        assertWithMessage("围栏块的 endLine 应指向闭围栏所在的第 7 行")
            .that(chunks[0].endLine).isEqualTo(7)
        assertWithMessage(
            "Section 里存规整后的行时，第 4 行那个被折掉的空行会让后面每一行的行号都前移一格：" +
                "最后一段明明在第 9 行却被记成第 8 行，AI 引用行号时用户跳过去看到的是别的内容"
        ).that(chunks[1].startLine).isEqualTo(9)
        assertThat(chunks[1].endLine).isEqualTo(9)
    }

    // ---- front matter：文档开头的元数据不是正文 ----

    @Test
    fun `文档开头的 front matter 不进索引`() {
        val md = listOf(
            "---",                                                         // 1
            "title: 我的文档标题",                                          // 2
            "description: 这是一段被写进 front matter 的描述文字，不该进检索。", // 3
            "tags: [笔记, 索引]",                                           // 4
            "---",                                                         // 5
            "",                                                            // 6
            "# 正文标题",                                                   // 7
            "这一段是真正的正文内容，长度足够越过有效内容门槛。"                // 8
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        assertWithMessage(
            "title/description/tags 既不是正文也答不出问题，却很容易凑够 30 个有效字符被判成「有意义」，" +
                "于是每篇带 front matter 的文档都白占一块 embedding，检索时还顶掉真正的内容"
        ).that(chunks).hasSize(1)
        assertThat(chunks[0].heading).isEqualTo("正文标题")
        assertThat(chunks[0].startLine).isEqualTo(7)
        assertThat(chunks[0].content).doesNotContain("description:")
    }

    @Test
    fun `文档中间的分隔线不是 front matter`() {
        val md = listOf(
            "# 标题",
            "第一段正文内容足够长，用来确认中间的分隔线不会被当成 front matter。",
            "",
            "---",
            "",
            "第二段正文内容足够长，用来确认中间的分隔线不会被当成 front matter。"
        ).joinToString("\n")
        val joined = MarkdownChunker.chunkMarkdown(md, "doc1").joinToString("\n") { it.content }
        assertWithMessage("只有第一行的 --- 才是 front matter 的开头").that(joined).contains("第一段正文内容")
        assertThat(joined).contains("第二段正文内容")
    }

    @Test
    fun `第一行的分隔线没有闭合时不算 front matter`() {
        val md = listOf(
            "---",
            "这一段正文内容足够长，用来确认没有闭合的分隔线不会把整篇文档吃掉。"
        ).joinToString("\n")
        val joined = MarkdownChunker.chunkMarkdown(md, "doc1").joinToString("\n") { it.content }
        assertWithMessage("找不到闭合的 --- 就当没有 front matter，否则整篇文档从索引里消失")
            .that(joined).contains("没有闭合的分隔线")
    }

    // ---- setext 标题：`===` / `---` 下划线在 CommonMark 里与 # / ## 等价 ----

    @Test
    fun `setext 标题也能分节`() {
        val md = listOf(
            "一级标题",                                     // 1
            "=====",                                       // 2
            "一级标题下的正文内容，长度足够越过有效内容门槛。",  // 3
            "",                                            // 4
            "二级标题",                                     // 5
            "---",                                         // 6
            "二级标题下的正文内容，长度足够越过有效内容门槛。"   // 7
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        assertWithMessage(
            "`===` / `---` 下划线正好落在 HR_REGEX 的字符集里：下划线被当分隔线整行删掉、标题行退化成" +
                "普通正文，于是一篇通篇用 setext 写标题的文档（Pandoc、旧版 Typora 的默认风格）一个 " +
                "section 都分不出来；正文短的时候连「有意义」都判不成，整篇文档从索引里消失"
        ).that(chunks.map { it.heading }).containsExactly("一级标题", "二级标题").inOrder()
        assertThat(chunks[0].startLine).isEqualTo(1)
        assertThat(chunks[1].startLine).isEqualTo(5)
        assertWithMessage("`===` 是一级、`---` 是二级，层级要跟 # / ## 一致")
            .that(chunks.last().titlePath).containsExactly("一级标题", "二级标题").inOrder()
        assertWithMessage("下划线本身不该进正文").that(chunks[1].content).doesNotContain("---")
    }

    @Test
    fun `setext 与 ATX 标题共用同一个标题层级栈`() {
        val md = listOf(
            "# ATX 一级",                                        // 1
            "正文内容足够长，用来确认 setext 与 ATX 共用标题栈。",   // 2
            "",                                                  // 3
            "setext 二级",                                       // 4
            "------",                                            // 5
            "二级标题下的正文内容，长度足够越过有效内容门槛。"        // 6
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        assertThat(chunks.map { it.heading }).containsExactly("ATX 一级", "setext 二级").inOrder()
        assertWithMessage("setext 二级标题必须挂在上面那个 ATX 一级标题下面")
            .that(chunks.last().titlePath).containsExactly("ATX 一级", "setext 二级").inOrder()
        assertThat(chunks.last().startLine).isEqualTo(4)
    }

    // 下面三条是护栏：它们在改动前后行为完全一致，作用是钉住「这些 --- 不是 setext 标题」
    @Test
    fun `表格分隔行不会被当成 setext 下划线`() {
        val md = listOf(
            "| 年份 | 收入 |",
            "| --- | --- |",
            "| 2024 | 100 |",
            "这张表的说明文字，长度足够越过有效内容门槛。"
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        assertWithMessage("表头行的下一行正是 |---|---|，认成 setext 标题会把每张表从表头处切开")
            .that(chunks.none { it.heading != null }).isTrue()
    }

    @Test
    fun `列表项与空行之后的分隔线仍然是分隔线`() {
        val listMd = listOf(
            "# 列表",
            "- 项目 A 的说明文字，长度足够越过有效内容门槛。",
            "- 项目 B 的说明文字，长度足够越过有效内容门槛。",
            "---",
            "分隔线之后的正文内容，长度足够越过有效内容门槛。"
        ).joinToString("\n")
        assertWithMessage("列表项后面的 --- 是分隔线，不是「把这个列表项变成二级标题」")
            .that(MarkdownChunker.chunkMarkdown(listMd, "doc1").map { it.heading })
            .containsExactly("列表")

        val hrMd = listOf(
            "段落一的内容足够长足够长，用来确认空行之后的分隔线仍然只是分隔线。",
            "",
            "---",
            "",
            "段落二的内容足够长足够长，用来确认空行之后的分隔线仍然只是分隔线。"
        ).joinToString("\n")
        assertWithMessage("--- 上面是空行时不构成 setext 标题")
            .that(MarkdownChunker.chunkMarkdown(hrMd, "doc1").none { it.heading != null }).isTrue()
    }

    @Test
    fun `1 到 2 个字符的下划线不当 setext 标题`() {
        for (underline in listOf("-", "--", "=", "==")) {
            val md = listOf("标题候选", underline, "正文内容足够长，用来确认短下划线不被当成 setext 标题。")
                .joinToString("\n")
            assertWithMessage("1~2 个字符的下划线本来就不在 HR_REGEX 射程内，没被误删过，这次不改它的现状")
                .that(MarkdownChunker.chunkMarkdown(md, "doc1").none { it.heading != null }).isTrue()
        }
    }

    // ---- 死代码：「与上一个标题重复就删掉」那条规则删掉后，缩进代码块不再被吃 ----

    @Test
    fun `缩进代码块里长得像标题的行不会被删掉`() {
        val md = listOf(
            "## 重复标题",
            "正文内容足够长，用来确认缩进四空格的同名标题会被静默删掉。",
            "    ## 重复标题",
            "这一行是缩进代码块的内容，长度足够越过有效内容门槛。"
        ).joinToString("\n")
        val chunks = MarkdownChunker.chunkMarkdown(md, "doc1")
        assertThat(chunks).hasSize(1)
        assertWithMessage(
            "「与上一个标题重复就删掉」这条规则对真正的重复 ATX 标题永远到不了（标题一出现就另起一节），" +
                "唯一能走到的路径是缩进四空格、trim 后长得像标题的那一行——那是用户的代码块内容，被静默删掉"
        ).that(chunks[0].content).contains("    ## 重复标题")
    }
}
