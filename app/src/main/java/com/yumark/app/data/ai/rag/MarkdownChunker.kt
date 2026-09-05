package com.yumark.app.data.ai.rag

/**
 * Markdown 语义分块器 —— 移植自 guanmo `chunker.ts`。
 *
 * 切分策略：跳过文档开头的 YAML front matter → 按 `#{1,6}` 与 setext（`===` / `---` 下划线）标题分节
 * （跳过代码围栏内的标题，维护标题层级栈）→ 节内按空行**和围栏边界**
 * 分块 → 超长非代码块按 `\n` / `。！？.` 软断点切、超长代码块按行切。聚合到接近 `chunkSize` 时落
 * 一块，块尾取不超过 `overlap` 个字符作为下一块的上文。归一化正文全串去重，过短无意义内容丢弃。
 *
 * Section 里存的是**原始的连续行**（不是规整后的行）：`Chunk.startLine/endLine` 是拿
 * `section.startLine + 行下标` 反推的，一旦存规整后的行，规整丢掉的每一行都会让后面所有块的行号
 * 整体前移，引用行号就指不到原文对应的位置。
 *
 * 长度不变量（见 [maxChunkChars] / [maxTextChunkChars]）：任何 chunk 都不超过
 * `chunkSize * CODE_BLOCK_SIZE_FACTOR + overlap + 1`；不含代码块的文档不超过
 * `chunkSize + overlap + 1`。这两条是硬约束——超出上限的 chunk 会让 embedding 请求超出模型上下文，
 * 而那意味着整篇文档索引失败。
 */
object MarkdownChunker {

    private const val MIN_MEANINGFUL_CHARS = 30
    private const val MIN_MEANINGFUL_CHARS_WITH_HEADING = 6

    internal const val DEFAULT_CHUNK_SIZE = 900
    internal const val DEFAULT_OVERLAP = 150

    /** chunkSize 的下限：再小的窗口连一句中文都放不下，软断点必然退化成硬切。 */
    private const val MIN_CHUNK_SIZE = 64

    /** chunkSize 的上限：纯粹为了让 `chunkSize * CODE_BLOCK_SIZE_FACTOR` 不溢出成负数。 */
    private const val MAX_CHUNK_SIZE = 200_000

    /**
     * 代码块允许的长度上限倍数。
     *
     * 代码块不按 chunkSize 切：把一个 40 行的函数从中间切成两半，两半在检索里都答不出问题，
     * 还不如整块进一个 chunk。但也不能像原来那样完全不切——见 [splitOversizedCodeBlock]。
     */
    private const val CODE_BLOCK_SIZE_FACTOR = 4

    /**
     * 一次分块的字数预算。[chunkSize] / [overlap] 都已消毒。
     *
     * 消毒不是防御性洁癖，三种取值各自对应一个真实故障：
     * - `chunkSize <= 0`：切分循环里 `substring(start, start + chunkSize)` 的 end 小于 start，
     *   直接抛 `StringIndexOutOfBoundsException`，用户看到的是「保存文档后索引任务永远失败」；
     * - `overlap < 0`：负重叠等于「跳过 |overlap| 个字符再继续切」，段落中间被静默吃掉一段，
     *   那段正文在检索里根本不存在；
     * - `overlap >= chunkSize`：每一块几乎完整重复上一块，embedding 费用翻倍，检索结果还会被
     *   一堆雷同块占满 topK。
     */
    private class Budget(val chunkSize: Int, val overlap: Int) {
        /** 代码块只有超过这个长度才切。 */
        val codeLimit: Int = chunkSize * CODE_BLOCK_SIZE_FACTOR

        /** 任何 chunk 的硬上限：最大的单块（代码）+ 一份重叠 + 两者之间的换行分隔符。 */
        val ceiling: Int = codeLimit + overlap + 1

        /** 不含代码块的文档的 chunk 上限。 */
        val textCeiling: Int = chunkSize + overlap + 1
    }

    private fun budgetOf(chunkSize: Int, overlap: Int): Budget {
        val safeSize = chunkSize.coerceIn(MIN_CHUNK_SIZE, MAX_CHUNK_SIZE)
        // 重叠最多占半块：再多就没有「新内容」了。
        val safeOverlap = overlap.coerceIn(0, safeSize / 2)
        return Budget(safeSize, safeOverlap)
    }

    /** 给定参数下任何 chunk 的字符数硬上限。 */
    internal fun maxChunkChars(chunkSize: Int = DEFAULT_CHUNK_SIZE, overlap: Int = DEFAULT_OVERLAP): Int =
        budgetOf(chunkSize, overlap).ceiling

    /** 给定参数下「不含代码块的文档」的 chunk 字符数上限。 */
    internal fun maxTextChunkChars(chunkSize: Int = DEFAULT_CHUNK_SIZE, overlap: Int = DEFAULT_OVERLAP): Int =
        budgetOf(chunkSize, overlap).textCeiling

    private val HR_REGEX = Regex("""^[-*_=\s]{3,}$""")
    private val TOC_REGEX = Regex("""^(目录|导航|table of contents|toc)$""", RegexOption.IGNORE_CASE)
    private val ANCHOR_LIST_REGEX = Regex("""^\s*[-*+]\s+\[[^\]]+\]\(#[^)]+\)\s*$""")
    // 行首允许 1~3 个空格：CommonMark 里缩进不超过 3 个空格的 `#` 仍然是标题（4 个才变成缩进代码块），
    // 而渲染侧就是按 CommonMark 渲染的。以前 `^#` 死锁行首，一篇「标题带了一个前导空格」的文档
    // （从别的编辑器/网页粘过来非常常见）会被判成一节都没有：整篇挤成一个巨型 section，
    // heading 与 titlePath 全空，检索时既没有章节加权，也切不出合理的块。
    private val HEADING_REGEX = Regex("""^ {0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")
    // 不能当 setext 标题正文的行（见 [setextHeadingLevel]）：4 个以上空格缩进（那是缩进代码块）、
    // 列表项、引用、表格行。表格行尤其要挡住——`| 年份 | 收入 |` 的下一行正是 `| --- | --- |`，
    // 不挡的话每张表的表头都会变成一个二级标题，表格从表头处被切开，剩下的数据行连列名都没有。
    private val SETEXT_INELIGIBLE_REGEX = Regex("""^(?: {4,}|\s*(?:[-*+]\s|\d+[.)]\s|>|\|))""")
    // 表格分隔行（`|---|:--:|`）。只用来判断「这段是不是表格」，见 [tableHeaderOf]。
    private val TABLE_DELIMITER_REGEX = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
    // 两种围栏都要认：只把 ``` 段落当代码，`~~~` 块里的每个符号都会被算进「有效字符」，
    // 于是纯 `~~~` 代码块被当成长正文（判定有意义、还被软断点切开）。
    private val CODE_FENCE_GLOBAL = Regex("""```[\s\S]*?```|~~~[\s\S]*?~~~""")
    private val MARKDOWN_SYMBOLS = Regex("""[#>*`~_\-\[\](){}]""")
    private val WHITESPACE = Regex("""\s+""")
    // 短内容的兜底：出现代码标记就算有意义。`~~~` 必须在列，否则一个只含 `~~~` 代码块的
    // 分块会因为「有效字符不足 30」被整块丢掉，这段代码在 RAG 里等于不存在。
    private val CODE_MARKER_HINT =
        Regex("""```|~~~|`[^`]+`|\b[A-Z][A-Z0-9_-]{2,}\b|\b[a-z]+[A-Z][A-Za-z0-9]*\b""")

    private data class HeadingEntry(val level: Int, val title: String)

    private class Section(
        val lines: List<String>,
        val startLine: Int,
        val endLine: Int,
        val titlePath: List<String>,
        val heading: String?
    )

    private class TextBlock(
        val text: String,
        val startLine: Int,
        val endLine: Int,
        val isCode: Boolean
    )

    /** 一道围栏：标记字符、连续长度、后面的 info string。 */
    private class Fence(val marker: Char, val length: Int, val info: String) {
        /** 闭合这道围栏要写的裸标记（不含 info string）。 */
        val markerText: String get() = marker.toString().repeat(length)
    }

    /**
     * 取出行首（跳过缩进）连续 3 个以上的 ` 或 ~，以及其后的 info string。
     *
     * 缩进故意不设「最多 3 空格」上限：列表项里的围栏在 CommonMark 里是按列表内容列算缩进的，
     * 「1. 步骤」下面缩进 4 空格的代码块是最常见的写法之一，按列 0 卡 3 空格会把它判成普通文本。
     */
    private fun parseFence(line: String): Fence? {
        val start = line.indexOfFirst { it != ' ' && it != '\t' }
        if (start < 0) return null
        val marker = line[start]
        if (marker != '`' && marker != '~') return null
        var end = start
        while (end < line.length && line[end] == marker) end++
        if (end - start < 3) return null
        return Fence(marker, end - start, line.substring(end))
    }

    /**
     * 围栏代码块状态机 —— 全类唯一的「这一行是不是代码围栏」判断入口。
     *
     * 以前只认 ```，而 `~~~` 存在的意义恰恰是「代码内容里本来就带反引号」：`~~~` 块里的
     * `# 注释` 会被当成 Markdown 标题，分块器在代码中间切开，还把代码注释写进
     * heading/titlePath，AI 检索到的是拦腰截断的代码 + 一个根本不存在的章节名。
     *
     * 配对规则跟渲染侧的 commonmark 对齐，否则同一份文档「渲染出来的代码块」和
     * 「索引出去的代码块」不是同一段：
     * - 开围栏是哪种字符，就只能由同种字符闭合（``` 块里的 `~~~` 只是普通代码内容）；
     * - 闭围栏不能比开围栏短（长围栏本来就是用来包住短围栏的）；
     * - 闭围栏后面不能带 info string，```js 这种只能开新块、关不掉旧块。
     */
    private class FenceScanner {

        private var openMarker: Char? = null
        private var openLength = 0

        /** 当前是否处在某个未闭合的围栏里。在 [accept] 前后各取一次即可分辨开围栏行与闭围栏行。 */
        val isOpen: Boolean get() = openMarker != null

        /**
         * 喂入一行并推进状态，返回这一行是否属于某个围栏代码块。
         * 开围栏行、块内行、闭围栏行都算「属于」——它们都不该按 Markdown 结构解析。
         */
        fun accept(line: String): Boolean {
            val open = openMarker
            if (open == null) {
                val fence = parseOpen(line) ?: return false
                openMarker = fence.marker
                openLength = fence.length
                return true
            }
            val fence = parseFence(line)
            if (fence != null && fence.marker == open && fence.length >= openLength && fence.info.isBlank()) {
                openMarker = null
                openLength = 0
            }
            // 等不到闭围栏就一直算在围栏内直到输入结束，与 CommonMark 的隐式闭合一致：
            // 宁可把整段代码原样留着，也不能把它当 Markdown 切碎。
            return true
        }

        /**
         * 能否用这一行开启围栏。反引号围栏的 info string 里不许再出现反引号，否则
         * 「```code``` 是行内代码」这种以行内代码开头的段落会被当成开围栏，
         * 之后整篇文档都被当作代码，所有标题失效。
         */
        private fun parseOpen(line: String): Fence? {
            val fence = parseFence(line) ?: return null
            if (fence.marker == '`' && fence.info.contains('`')) return null
            return fence
        }
    }

    /** 一段文本是否以代码围栏开头 —— isCode 决定它不参与软断点切分、也不进 overlap。 */
    private fun startsWithFence(text: String): Boolean =
        FenceScanner().accept(text.substringBefore('\n'))

    /**
     * 清理单行：丢弃分隔线/目录/锚点列表项，其余去掉行尾空白。返回 null 表示丢弃该行。
     *
     * 只能喂围栏外的行。围栏内的 `---` 可能是 YAML 文档分隔符、Python 的注释分隔线或
     * ASCII 表格边框，按分隔线丢掉就是篡改用户的代码；围栏判断由 [normalizeChunkText] 负责。
     */
    private fun cleanLine(line: String): String? {
        val trimmed = line.trim()
        if (HR_REGEX.matches(trimmed)) return null
        if (TOC_REGEX.matches(trimmed)) return null
        if (ANCHOR_LIST_REGEX.matches(line)) return null
        return line.replace(Regex("[ \\t]+$"), "")
    }

    /**
     * 规整一段文本：清行、折叠连续空行。
     *
     * 围栏代码块内部整段跳过上述处理、逐行原样保留：删掉一行 `---`、折掉一个空行、裁掉一段
     * 行尾空白，都会让索引出去的代码和用户看到的不是同一份，而这种差异在检索结果里看不出来，
     * 最后表现为 AI 拿着被篡改过的代码回答问题。
     *
     * 这里**故意不再有**「跳过连续重复标题」那条规则：标题一定会在 [splitSections] 里开一个新节，
     * 一个 Section 的行里不可能出现两个相同的 ATX 标题，规则永远碰不到它想防的东西。它唯一能命中的
     * 反而是「缩进 4 个以上空格、trim 之后长得像标题」的行——[splitSections] 拿**原始行**判标题
     * （`^ {0,3}#`，4 空格缩进不是标题），这里却拿 **trim 过的行**判，于是缩进代码块里写着
     * `    ## 重复标题` 的那一行被当成重复标题整行删掉，删的是用户的代码。
     */
    private fun normalizeChunkText(lines: List<String>): String {
        val cleaned = mutableListOf<String>()
        var previousBlank = false
        val fence = FenceScanner()
        var hasFence = false
        // 先摊平成物理行：pushChunk 传进来的每个元素是一个 block 的整段文本（多行），
        // 不摊平围栏状态机就看不见块内部的围栏行，代码又会被当 Markdown 清洗一遍。
        for (rawLine in lines.flatMap { it.split("\n") }) {
            if (fence.accept(rawLine)) {
                hasFence = true
                cleaned.add(rawLine)
                previousBlank = false
                continue
            }
            val line = cleanLine(rawLine) ?: continue
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                if (!previousBlank) cleaned.add("")
                previousBlank = true
                continue
            }
            cleaned.add(line)
            previousBlank = false
        }
        val joined = cleaned.joinToString("\n")
        // 兜底折叠只对不含围栏的文本做：围栏外的连续空行上面已经被 previousBlank 压成一个，
        // 这条规则唯一还能命中的就是代码块内部的空行，而那正是要原样保留的东西。
        return (if (hasFence) joined else joined.replace(Regex("\n{3,}"), "\n\n")).trim()
    }

    /** 剥除代码与 Markdown 符号后的“有效字符”长度，用于判断是否有意义。 */
    private fun meaningfulLength(content: String): Int =
        content.replace(CODE_FENCE_GLOBAL, " code ")
            .replace(MARKDOWN_SYMBOLS, "")
            .replace(WHITESPACE, "")
            .length

    private fun isMeaningful(content: String): Boolean {
        if (meaningfulLength(content) >= MIN_MEANINGFUL_CHARS) return true
        return CODE_MARKER_HINT.containsMatchIn(content)
    }

    private fun isMeaningfulWithHeading(content: String, titlePath: List<String>, heading: String?): Boolean {
        if (isMeaningful(content)) return true
        val metadata = if (titlePath.isNotEmpty()) titlePath.joinToString("\n") else (heading ?: "")
        if (metadata.isBlank()) return false
        return meaningfulLength(listOf(metadata, content).filter { it.isNotBlank() }.joinToString("\n")) >=
            MIN_MEANINGFUL_CHARS_WITH_HEADING
    }

    /**
     * 文档开头的 YAML front matter 占了几行（返回正文的起始下标，没有 front matter 时返回 0）。
     *
     * 只在**第一行就是** `---` 时才认，且必须能找到闭合的 `---`；找不到就当没有 front matter，
     * 否则一篇以分隔线开头的文档会被整篇吃掉。文档中间的 `---` 一律还是分隔线。
     *
     * 不跳过的代价：`title: / tags: / draft:` 这几行元数据既不是正文、也答不出任何问题，却很容易
     * 凑够 30 个有效字符从而被判成「有意义」，于是每篇带 front matter 的文档都白占一块 embedding，
     * 检索时还可能顶掉真正的内容。
     */
    private fun frontMatterEnd(lines: List<String>): Int {
        if (lines.firstOrNull()?.trim() != "---") return 0
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") return i + 1
        }
        return 0
    }

    /**
     * `lines[index]` 是否为 setext 标题的标题行；是则返回级别（`===` → 1，`---` → 2），否则返回 0。
     *
     * 这两种标题在 CommonMark 里和 `#` / `##` 完全等价，渲染侧也就是这么渲染的，但下划线那一行正好
     * 落在 [HR_REGEX] 的字符集里：以前它被当成分隔线整行删掉，标题行则退化成一句普通正文。后果是
     * 一篇通篇用 setext 写标题的文档（Pandoc、旧版 Typora 的默认风格）一个 section 都分不出来，
     * heading 与 titlePath 全空；正文短的时候连「有意义」都判不成，**整篇文档从索引里消失**。
     *
     * 下划线要求至少 3 个字符：1~2 个字符的 `-` / `=` 本来就不在 HR_REGEX 的射程内，从来没被误删过，
     * 这里不去改它们的现状（CommonMark 认，但认了就是新增行为变更，不属于这次修复的范围）。
     */
    private fun setextHeadingLevel(lines: List<String>, index: Int): Int {
        if (index + 1 >= lines.size) return 0
        val text = lines[index]
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        // 标题行必须是「普通正文行」：本身是 ATX 标题、是分隔线、是列表/引用/表格/缩进代码，都不算。
        if (HEADING_REGEX.containsMatchIn(text)) return 0
        if (HR_REGEX.matches(trimmed)) return 0
        if (SETEXT_INELIGIBLE_REGEX.containsMatchIn(text)) return 0
        val underline = lines[index + 1].trim()
        if (underline.length < 3) return 0
        return when {
            underline.all { it == '=' } -> 1
            underline.all { it == '-' } -> 2
            else -> 0
        }
    }

    private fun splitSections(lines: List<String>): List<Section> {
        val sections = mutableListOf<Section>()
        val headingStack = mutableListOf<HeadingEntry>()
        var currentLines = mutableListOf<String>()
        var currentStartLine = 1
        var currentTitlePath: List<String> = emptyList()
        var currentHeading: String? = null
        val fence = FenceScanner()

        fun flush(endLine: Int) {
            // 「有意义」判定用规整后的文本（清掉分隔线/目录/多余空行之后还剩多少内容），但存进
            // Section 的必须是**原始的连续行**。[splitBlocks] 是靠 `section.startLine + i` 反推行号的，
            // 一旦这里存的是规整后的行，规整每丢一行（分隔线、锚点目录项、第 2 个起的连续空行、
            // 开头结尾被 trim 掉的空行）后面所有块的行号就整体前移一格：Chunk.startLine/endLine
            // 指向的不再是原文里那几行，AI 引用「第 8 行」时用户跳过去看到的是别的内容。
            val text = normalizeChunkText(currentLines)
            if (text.isNotEmpty() && isMeaningfulWithHeading(text, currentTitlePath, currentHeading)) {
                sections.add(
                    Section(
                        lines = currentLines.toList(),
                        startLine = currentStartLine,
                        endLine = endLine,
                        titlePath = currentTitlePath,
                        heading = currentHeading
                    )
                )
            }
            currentLines = mutableListOf()
        }

        // setext 标题的下划线行在识别到标题时就跟着标题行一起收进 currentLines 了，轮到它自己
        // 这一轮必须跳过，否则会被收第二遍。用下标记而不是「删掉这一行」：删行就又把行号搞偏了。
        var consumedUnderline = -1

        for (i in frontMatterEnd(lines) until lines.size) {
            val line = lines[i]
            val lineNumber = i + 1
            // 围栏内的行一律不按 Markdown 结构解析：代码里的 `# 注释` 是注释不是标题，
            // 当成标题会在代码中间开新节，并把注释文本写进 heading/titlePath。
            val inFence = fence.accept(line)
            if (i == consumedUnderline) continue

            val headingMatch = if (!inFence) HEADING_REGEX.find(line) else null
            // setext 标题（下一行是 `===` / `---`）走和 ATX 标题**完全同一条**分支：同一个标题栈、
            // 同一套 titlePath、同样开新节，否则两种写法的标题在检索里权重不一样。
            val setextLevel = if (headingMatch == null && !inFence) setextHeadingLevel(lines, i) else 0
            if (headingMatch != null || setextLevel > 0) {
                if (currentLines.isNotEmpty()) flush(lineNumber - 1)
                val level: Int
                val title: String
                if (headingMatch != null) {
                    level = headingMatch.groupValues[1].length
                    title = headingMatch.groupValues[2].trim()
                } else {
                    level = setextLevel
                    title = line.trim()
                }
                // 不用 removeLast()：Java 21 的 SequencedCollection 给 java.util.List 加了同名方法，
                // compileSdk 36 的 android.jar 已含它，于是调用会解析到 List.removeLast() 而非
                // Kotlin 扩展；该方法在 API < 35 的设备上不存在，运行期抛 NoSuchMethodError。
                while (headingStack.isNotEmpty() && headingStack.last().level >= level) {
                    headingStack.removeAt(headingStack.lastIndex)
                }
                headingStack.add(HeadingEntry(level, title))
                currentStartLine = lineNumber
                currentTitlePath = headingStack.map { it.title }
                currentHeading = title
                // 下划线行照样留在 currentLines 里，好让 Section 的行保持连续（行号靠连续性算）；
                // 它在 [splitBlocks] 里会被 [cleanLine] 判成分隔线跳过，不会进块正文。
                if (setextLevel > 0) {
                    consumedUnderline = i + 1
                    currentLines = mutableListOf(line, lines[i + 1])
                } else {
                    currentLines = mutableListOf(line)
                }
                continue
            }

            if (currentLines.isEmpty()) {
                currentStartLine = lineNumber
                currentTitlePath = headingStack.map { it.title }
                currentHeading = headingStack.lastOrNull()?.title
            }
            currentLines.add(line)
        }
        if (currentLines.isNotEmpty()) flush(lines.size)
        return sections
    }

    private fun splitBlocks(section: Section): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        var current = mutableListOf<String>()
        var currentStart = section.startLine
        val fence = FenceScanner()

        fun flush(endLine: Int) {
            val text = normalizeChunkText(current)
            if (text.isNotEmpty()) {
                blocks.add(
                    TextBlock(
                        text = text,
                        startLine = currentStart,
                        endLine = endLine,
                        isCode = startsWithFence(text)
                    )
                )
            }
            current = mutableListOf()
        }

        for (i in section.lines.indices) {
            val line = section.lines[i]
            val lineNumber = section.startLine + i
            // 围栏内的空行是代码的一部分（Python/YAML 里空行有语义），不能当块边界，
            // 否则一个代码块会被切成互不相干的两半，检索到的永远只有半截代码。
            val wasInFence = fence.isOpen
            val inFence = fence.accept(line)
            val opensFence = !wasInFence && inFence
            val closesFence = wasInFence && !fence.isOpen

            if (!inFence && line.isBlank()) {
                flush(lineNumber - 1)
                currentStart = lineNumber + 1
                continue
            }
            // 规整时反正会被丢掉的行（分隔线 / 目录 / 锚点目录项，也包括 setext 标题的下划线），
            // 在这里就跳过：它们进不了块正文，却会把 currentStart 钉在自己这一行上，让整块的
            // startLine 比真实起点小。注意必须放在空行分支之后——空行是块边界，不是要丢的噪声行。
            if (!inFence && cleanLine(line) == null) continue
            // 围栏的前后各切一刀，让每个块要么全是代码要么全是正文。
            //
            // 正文和围栏之间不空行（`说明：` 紧跟 ```js）在真实文档里很常见，而块的 isCode 只看
            // **首行**是不是围栏：混排块会被判成普通正文，于是
            // - 软断点会拿代码里的 `.` 当句末（`obj.method()` 里到处都是），把一行代码从中间切开，
            //   检索到的是半行代码；
            // - 这个块还会被当正文塞进块间 overlap，代码被复制进别的 chunk。
            if (opensFence && current.isNotEmpty()) flush(lineNumber - 1)
            if (current.isEmpty()) currentStart = lineNumber
            current.add(line)
            if (closesFence) {
                flush(lineNumber)
                currentStart = lineNumber + 1
            }
        }
        if (current.isNotEmpty()) flush(section.endLine)
        return blocks.filter { isMeaningfulWithHeading(it.text, section.titlePath, section.heading) }
    }

    /** 若干块按 `\n` 拼起来之后的长度（各块正文 + 块间分隔符），落库的 content 不会比它更长。 */
    private fun joinedLength(blocks: List<TextBlock>): Int =
        blocks.sumOf { it.text.length } + maxOf(0, blocks.size - 1)

    /**
     * 若这段文本是 Markdown 表格（首行含 `|`、次行是分隔行），返回「表头行 + 分隔行」。
     *
     * 只在 [splitOversizedTextBlock] 里用：长表格被切开后，后面几片只剩数据行，检索命中
     * 「| 2024 | 12000 | 是 |」时 AI 既不知道这三列叫什么，也分不清 12000 是收入还是人数，只能猜。
     */
    private fun tableHeaderOf(text: String): String? {
        val lines = text.split("\n")
        if (lines.size < 3) return null
        if (!lines[0].contains('|')) return null
        if (!TABLE_DELIMITER_REGEX.matches(lines[1])) return null
        return lines[0] + "\n" + lines[1]
    }

    /** 一行本身就超过预算时只能硬切：切在字中间总比让这一片超限、整篇文档索引失败好。 */
    private fun hardSplitLine(line: String, max: Int): List<String> =
        if (line.length <= max) listOf(line) else line.chunked(max)

    /** 超出预算的块按类型分派：代码按行切，正文按软断点切。 */
    private fun splitOversizedBlock(block: TextBlock, budget: Budget): List<TextBlock> =
        if (block.isCode) splitOversizedCodeBlock(block, budget.codeLimit)
        else splitOversizedTextBlock(block, budget)

    private fun splitOversizedTextBlock(block: TextBlock, budget: Budget): List<TextBlock> {
        if (block.text.length <= budget.chunkSize) return listOf(block)

        // 表头长到占掉半块就不补了：那时补表头挤掉的正文比它带来的信息更多。
        val header = tableHeaderOf(block.text)?.takeIf { it.length <= budget.chunkSize / 2 }
        val headerPrefix = if (header != null) header + "\n" else ""

        val parts = mutableListOf<TextBlock>()
        var start = 0
        while (start < block.text.length) {
            val window = if (parts.isEmpty()) budget.chunkSize else budget.chunkSize - headerPrefix.length
            val hardEnd = minOf(block.text.length, start + window)
            // 断点必须落在 [start, hardEnd) 里：lastIndexOf 的第二个参数是**包含**的，原来直接传
            // hardEnd，断点正好落在 hardEnd 上时 end = hardEnd + 1，切出来的片比上限长 1 个字符，
            // 上限就不成上限。
            val searchEnd = hardEnd - 1
            val softBreak = block.text.lastIndexOf('\n', searchEnd)
            val sentenceBreak = maxOf(
                block.text.lastIndexOf('。', searchEnd),
                block.text.lastIndexOf('！', searchEnd),
                block.text.lastIndexOf('？', searchEnd),
                block.text.lastIndexOf('.', searchEnd)
            )
            val breakAt = maxOf(softBreak, sentenceBreak)
            val end = if (breakAt > start + window * 0.5) breakAt + 1 else hardEnd
            val body = block.text.substring(start, end).trim()
            if (body.isNotEmpty()) {
                val text = if (parts.isEmpty()) body else headerPrefix + body
                parts.add(TextBlock(text, block.startLine, block.endLine, isCode = false))
            }
            // 这里只做「不重不漏」的切分，重叠统一交给 [chunkMarkdown] 的聚合层。原来每片自己再往回
            // 退 overlap 个字符，而聚合层还会再补一次重叠，同一段文字会在一个 chunk 的开头连续出现
            // 两遍（白占 embedding 额度，检索时还挤掉别的内容）；overlap 为负时
            // `end - overlap` 大于 end，下一片会直接跳过一段正文，那段内容从索引里消失。
            start = end
        }
        return parts
    }

    /**
     * 代码块超过 [limit] 时按行切。
     *
     * 原来是 `if (block.isCode) return listOf(block)` —— 代码块一律不切。于是一段 5 万字符的日志或
     * 源码整块变成一个 chunk 塞进 embedding 请求：请求超出模型上下文上限 → adapter 抛错 →
     * RagPipeline 把任务标 failed 且不会自动重建 → 这篇文档的**任何**内容都检索不到，用户只看到
     * 「索引失败」。切开确实可能把一个函数切成两半，但那远好过整篇文档进不了索引。
     *
     * 每一片都补回开/闭围栏，两个理由都不能省：
     * - [normalizeChunkText] 是按「这段里有没有围栏」决定要不要逐行原样保留的。第二片开头若不是
     *   围栏，块里的 `---` 会被当分隔线整行删掉、连续空行会被折叠、行尾空白会被裁掉——索引出去的
     *   代码和用户写的不是同一份，而这种差异在检索结果里根本看不出来；
     * - 补齐后每一片自己就是一段合法 Markdown，AI 拿到的不是一截没头没尾的字符流。
     */
    private fun splitOversizedCodeBlock(block: TextBlock, limit: Int): List<TextBlock> {
        if (block.text.length <= limit) return listOf(block)

        val lines = block.text.split("\n")
        val open = parseFence(lines.first())
        // info string 长得离谱时只补裸标记，否则每片都被一行 info 吃掉大半预算。
        val openLine = when {
            open == null -> null
            lines.first().length <= limit / 4 -> lines.first()
            else -> open.markerText
        }
        val rawOverhead = (openLine?.let { it.length + 1 } ?: 0) + (open?.let { it.length + 1 } ?: 0)
        // 围栏标记本身就占掉半块（几千个反引号这种病态输入）时放弃补围栏：补出来的片会比上限还长。
        val head = openLine?.takeIf { rawOverhead <= limit / 2 }
        val tail = if (head != null) open?.markerText else null
        // 末行是否为配对的闭围栏。刻意先把它解析成局部变量再比较，而不是在 `?.let {}` 里比：
        // 那样写要靠「`open != null` 的智能转换穿进 lambda」才成立，读代码的人得先确认 let 是 inline。
        val lastFence = if (lines.size > 1) parseFence(lines.last()) else null
        val closedAtEnd = open != null && head != null && lastFence != null &&
            lastFence.marker == open.marker && lastFence.length >= open.length && lastFence.info.isBlank()
        // 只有打算补回去才摘掉原来的围栏行，否则围栏行会凭空消失。
        val body = lines.subList(
            if (head != null) 1 else 0,
            if (closedAtEnd) lines.size - 1 else lines.size
        )

        val overhead = if (head != null) rawOverhead else 0
        val bodyBudget = (limit - overhead).coerceAtLeast(MIN_CHUNK_SIZE)
        val groups = mutableListOf<List<String>>()
        var group = mutableListOf<String>()
        var groupLength = 0
        for (raw in body) {
            for (piece in hardSplitLine(raw, (bodyBudget - 1).coerceAtLeast(1))) {
                if (group.isNotEmpty() && groupLength + piece.length + 1 > bodyBudget) {
                    groups.add(group)
                    group = mutableListOf()
                    groupLength = 0
                }
                group.add(piece)
                groupLength += piece.length + 1
            }
        }
        if (group.isNotEmpty()) groups.add(group)
        if (groups.isEmpty()) return listOf(block)

        return groups.mapIndexed { index, groupLines ->
            val text = buildString {
                if (head != null) append(head).append('\n')
                append(groupLines.joinToString("\n"))
                if (tail != null && (index < groups.lastIndex || closedAtEnd)) append('\n').append(tail)
            }
            // 每一片都沿用整块的行号范围，与正文切分一致：一块代码切成几片后，片与片的边界落在
            // 哪一行是按字符预算算出来的，硬要给每片编一个更精确的行号只会编错。
            TextBlock(text, block.startLine, block.endLine, isCode = true)
        }
    }

    /**
     * 取上一块尾部不超过 [overlap] 个字符的内容，作为下一块的上文。
     *
     * 两处必须改：
     * 1. 原来「selected 还空着就无条件收下整块」：一个 800 字符的段落会被整段搬进下一块，chunk 直接
     *    涨到 2× chunkSize（用户设的 chunkSize 形同虚设，embedding 请求还可能超模型上限），同一段
     *    正文在两块里各存一份——费用翻倍，检索也会拿两块几乎一样的内容顶掉别的结果。现在超出预算
     *    就只取块尾。
     * 2. 原来遇到代码块是 `continue`（跳过它继续往前找正文），于是代码块**前**的一段和代码块**后**的
     *    一段被当成相邻两段拼进同一个 chunk——文档里根本不存在这种相邻关系，AI 会照着这个拼出来的
     *    「段落」回答问题。现在遇到代码块就停，宁可这一块没有上文。
     */
    private fun getOverlapBlocks(blocks: List<TextBlock>, overlap: Int): List<TextBlock> {
        if (overlap <= 0) return emptyList()
        val selected = mutableListOf<TextBlock>()
        var length = 0
        for (i in blocks.indices.reversed()) {
            val block = blocks[i]
            if (block.isCode) break
            val remaining = overlap - length
            if (remaining <= 0) break
            if (block.text.length > remaining) {
                if (selected.isEmpty()) tailOf(block, remaining)?.let { selected.add(it) }
                break
            }
            selected.add(0, block)
            length += block.text.length
        }
        return selected
    }

    /** 取块尾不超过 [maxChars] 个字符，尽量从行/句边界之后起切，别让上文从半个词开始。 */
    private fun tailOf(block: TextBlock, maxChars: Int): TextBlock? {
        val window = block.text.takeLast(maxChars)
        val breakAt = listOf(
            window.indexOf('\n'), window.indexOf('。'), window.indexOf('！'), window.indexOf('？')
        ).filter { it in 0 until window.length - 1 }.minOrNull()
        val tail = (if (breakAt != null) window.substring(breakAt + 1) else window).trim()
        return if (tail.isEmpty()) null else TextBlock(tail, block.startLine, block.endLine, isCode = false)
    }

    /**
     * 重叠块不能把下一块顶出硬上限：从最旧的一块开始丢，直到「重叠 + 下一块」放得进
     * [Budget.ceiling]。正常正文不会触发（重叠 ≤ overlap、单块 ≤ chunkSize），只有整块顶到
     * codeLimit 的代码块才会把重叠挤掉。
     */
    private fun trimmedOverlap(current: List<TextBlock>, next: TextBlock, budget: Budget): List<TextBlock> {
        var selected = getOverlapBlocks(current, budget.overlap)
        while (selected.isNotEmpty() && joinedLength(selected) + next.text.length + 1 > budget.ceiling) {
            selected = selected.drop(1)
        }
        return selected
    }

    private fun pushChunk(
        chunks: MutableList<Chunk>,
        seenKeys: MutableSet<String>,
        documentId: String,
        section: Section,
        blocks: List<TextBlock>,
        chunkIndex: Int
    ): Int {
        val content = normalizeChunkText(blocks.map { it.text })
        if (content.isEmpty() || !isMeaningfulWithHeading(content, section.titlePath, section.heading)) return chunkIndex

        // 去重比的是归一化后的正文**全串**，不是它的 32 位摘要。原来拿 createContentHash 的 8 位
        // 十六进制串当去重键：FNV-1a 只有 32 位，两段毫不相干的正文一旦撞上同一个值，后一块会被
        // 当成「重复内容」整块丢掉——那段正文在检索里彻底不存在，而且只要用户不改这篇文档就永远
        // 不存在，既不报错也没有任何提示。详见 [contentDedupKey]。
        if (!seenKeys.add(contentDedupKey(content))) return chunkIndex

        chunks.add(
            Chunk(
                id = "$documentId-chunk-$chunkIndex",
                documentId = documentId,
                content = content,
                // 落库仍存 32 位哈希：RagPipeline / VectorStore 都按它比对，换成全串会撑大数据库。
                // 只有「同一篇文档内去重」这一步不能用它，因为那一步的代价是静默丢正文。
                contentHash = createContentHash(content),
                index = chunkIndex,
                startLine = blocks.minOf { it.startLine },
                endLine = blocks.maxOf { it.endLine },
                titlePath = section.titlePath,
                heading = section.heading,
                sourceType = "markdown"
            )
        )
        return chunkIndex + 1
    }

    /**
     * 把 Markdown 正文切成语义分块。
     *
     * @param chunkSize 目标块字符数，默认 [DEFAULT_CHUNK_SIZE]；越界值会被夹进
     *   `[MIN_CHUNK_SIZE, MAX_CHUNK_SIZE]`（见 [Budget]：非法值会让切分循环直接抛异常）
     * @param overlap 块间重叠字符数，默认 [DEFAULT_OVERLAP]；负值按 0 处理，最多取 chunkSize 的一半
     */
    fun chunkMarkdown(
        content: String,
        documentId: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        overlap: Int = DEFAULT_OVERLAP
    ): List<Chunk> {
        val budget = budgetOf(chunkSize, overlap)
        val sections = splitSections(content.split("\n"))
        val chunks = mutableListOf<Chunk>()
        val seenKeys = mutableSetOf<String>()
        var chunkIndex = 0

        for (section in sections) {
            val blocks = splitBlocks(section).flatMap { splitOversizedBlock(it, budget) }
            var current = mutableListOf<TextBlock>()

            for (block in blocks) {
                // 长度按「拼起来之后」算：原来只累加各块正文长度，漏掉了块间的换行分隔符，块数一多
                // 累出来的数就比真正落库的 content 短，chunk 会悄悄超过用户设的上限。
                if (current.isNotEmpty() && joinedLength(current) + block.text.length + 1 > budget.chunkSize) {
                    chunkIndex = pushChunk(chunks, seenKeys, documentId, section, current, chunkIndex)
                    current = trimmedOverlap(current, block, budget).toMutableList()
                }
                current.add(block)
            }
            if (current.isNotEmpty()) {
                chunkIndex = pushChunk(chunks, seenKeys, documentId, section, current, chunkIndex)
            }
        }
        return chunks
    }
}
