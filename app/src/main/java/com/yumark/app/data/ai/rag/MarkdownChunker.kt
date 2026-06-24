package com.yumark.app.data.ai.rag

/**
 * Markdown 语义分块器 —— 移植自 guanmo `chunker.ts`。
 *
 * 切分策略：按 `#{1,6}` 标题分节（跳过代码围栏内的标题，维护标题层级栈）→ 节内按空行分块 →
 * 超长非代码块按 `\n` / `。！？.` 软断点切。聚合到接近 [chunkSize] 时落一块，尾部带 [overlap]
 * 进入下段。`contentHash` 全局去重，过短无意义内容丢弃。
 */
object MarkdownChunker {

    private const val MIN_MEANINGFUL_CHARS = 30
    private const val MIN_MEANINGFUL_CHARS_WITH_HEADING = 6

    private val HR_REGEX = Regex("""^[-*_=\s]{3,}$""")
    private val TOC_REGEX = Regex("""^(目录|导航|table of contents|toc)$""", RegexOption.IGNORE_CASE)
    private val ANCHOR_LIST_REGEX = Regex("""^\s*[-*+]\s+\[[^\]]+\]\(#[^)]+\)\s*$""")
    private val FENCE_REGEX = Regex("""^\s*```""")
    private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val CODE_FENCE_GLOBAL = Regex("""```[\s\S]*?```""")
    private val MARKDOWN_SYMBOLS = Regex("""[#>*`_\-\[\](){}]""")
    private val WHITESPACE = Regex("""\s+""")

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

    /** 清理单行：丢弃分隔线/目录/锚点列表项，其余去掉行尾空白。返回 null 表示丢弃该行。 */
    private fun cleanLine(line: String): String? {
        val trimmed = line.trim()
        if (HR_REGEX.matches(trimmed)) return null
        if (TOC_REGEX.matches(trimmed)) return null
        if (ANCHOR_LIST_REGEX.matches(line)) return null
        return line.replace(Regex("[ \\t]+$"), "")
    }

    /** 规整一段文本：清行、折叠连续空行、跳过连续重复标题。 */
    private fun normalizeChunkText(lines: List<String>): String {
        val cleaned = mutableListOf<String>()
        var previousBlank = false
        var previousHeading = ""
        for (rawLine in lines) {
            val line = cleanLine(rawLine) ?: continue
            val trimmed = line.trim()
            val headingTitle = HEADING_REGEX.find(trimmed)?.groupValues?.get(2)?.trim() ?: ""
            if (headingTitle.isNotEmpty() && headingTitle == previousHeading) continue
            if (headingTitle.isNotEmpty()) previousHeading = headingTitle

            if (trimmed.isEmpty()) {
                if (!previousBlank) cleaned.add("")
                previousBlank = true
                continue
            }
            cleaned.add(line)
            previousBlank = false
        }
        return cleaned.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** 剥除代码与 Markdown 符号后的“有效字符”长度，用于判断是否有意义。 */
    private fun meaningfulLength(content: String): Int =
        content.replace(CODE_FENCE_GLOBAL, " code ")
            .replace(MARKDOWN_SYMBOLS, "")
            .replace(WHITESPACE, "")
            .length

    private fun isMeaningful(content: String): Boolean {
        if (meaningfulLength(content) >= MIN_MEANINGFUL_CHARS) return true
        return Regex("""```|`[^`]+`|\b[A-Z][A-Z0-9_-]{2,}\b|\b[a-z]+[A-Z][A-Za-z0-9]*\b""").containsMatchIn(content)
    }

    private fun isMeaningfulWithHeading(content: String, titlePath: List<String>, heading: String?): Boolean {
        if (isMeaningful(content)) return true
        val metadata = if (titlePath.isNotEmpty()) titlePath.joinToString("\n") else (heading ?: "")
        if (metadata.isBlank()) return false
        return meaningfulLength(listOf(metadata, content).filter { it.isNotBlank() }.joinToString("\n")) >=
            MIN_MEANINGFUL_CHARS_WITH_HEADING
    }

    private fun splitSections(lines: List<String>): List<Section> {
        val sections = mutableListOf<Section>()
        val headingStack = mutableListOf<HeadingEntry>()
        var currentLines = mutableListOf<String>()
        var currentStartLine = 1
        var currentTitlePath: List<String> = emptyList()
        var currentHeading: String? = null
        var inFence = false

        fun flush(endLine: Int) {
            val text = normalizeChunkText(currentLines)
            if (text.isNotEmpty() && isMeaningfulWithHeading(text, currentTitlePath, currentHeading)) {
                sections.add(
                    Section(
                        lines = text.split("\n"),
                        startLine = currentStartLine,
                        endLine = endLine,
                        titlePath = currentTitlePath,
                        heading = currentHeading
                    )
                )
            }
            currentLines = mutableListOf()
        }

        for (i in lines.indices) {
            val line = lines[i]
            val lineNumber = i + 1
            if (FENCE_REGEX.containsMatchIn(line)) inFence = !inFence

            val headingMatch = if (!inFence) HEADING_REGEX.find(line) else null
            if (headingMatch != null) {
                if (currentLines.isNotEmpty()) flush(lineNumber - 1)
                val level = headingMatch.groupValues[1].length
                val title = headingMatch.groupValues[2].trim()
                while (headingStack.isNotEmpty() && headingStack.last().level >= level) headingStack.removeLast()
                headingStack.add(HeadingEntry(level, title))
                currentStartLine = lineNumber
                currentTitlePath = headingStack.map { it.title }
                currentHeading = title
                currentLines = mutableListOf(line)
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
        var inFence = false

        fun flush(endLine: Int) {
            val text = normalizeChunkText(current)
            if (text.isNotEmpty()) {
                blocks.add(
                    TextBlock(
                        text = text,
                        startLine = currentStart,
                        endLine = endLine,
                        isCode = text.trim().startsWith("```")
                    )
                )
            }
            current = mutableListOf()
        }

        for (i in section.lines.indices) {
            val line = section.lines[i]
            val lineNumber = section.startLine + i
            if (FENCE_REGEX.containsMatchIn(line)) inFence = !inFence

            if (!inFence && line.isBlank()) {
                flush(lineNumber - 1)
                currentStart = lineNumber + 1
                continue
            }
            if (current.isEmpty()) currentStart = lineNumber
            current.add(line)
        }
        if (current.isNotEmpty()) flush(section.endLine)
        return blocks.filter { isMeaningfulWithHeading(it.text, section.titlePath, section.heading) }
    }

    private fun splitLongTextBlock(block: TextBlock, chunkSize: Int, overlap: Int): List<TextBlock> {
        if (block.isCode || block.text.length <= chunkSize) return listOf(block)

        val parts = mutableListOf<TextBlock>()
        var start = 0
        while (start < block.text.length) {
            val hardEnd = minOf(block.text.length, start + chunkSize)
            val softBreak = block.text.lastIndexOf('\n', hardEnd)
            val sentenceBreak = maxOf(
                block.text.lastIndexOf('。', hardEnd),
                block.text.lastIndexOf('！', hardEnd),
                block.text.lastIndexOf('？', hardEnd),
                block.text.lastIndexOf('.', hardEnd)
            )
            val end = if (maxOf(softBreak, sentenceBreak) > start + chunkSize * 0.5) {
                maxOf(softBreak, sentenceBreak) + 1
            } else hardEnd
            val text = block.text.substring(start, end).trim()
            if (text.isNotEmpty()) {
                parts.add(TextBlock(text, block.startLine, block.endLine, isCode = false))
            }
            if (end >= block.text.length) break
            val nextStart = maxOf(0, end - overlap)
            start = if (nextStart > start) nextStart else end
        }
        return parts
    }

    private fun getOverlapBlocks(blocks: List<TextBlock>, overlap: Int): List<TextBlock> {
        if (overlap <= 0) return emptyList()
        val selected = mutableListOf<TextBlock>()
        var length = 0
        for (i in blocks.indices.reversed()) {
            val block = blocks[i]
            if (block.isCode) continue
            if (length + block.text.length > overlap && selected.isNotEmpty()) break
            selected.add(0, block)
            length += block.text.length
            if (length >= overlap) break
        }
        return selected
    }

    private fun pushChunk(
        chunks: MutableList<Chunk>,
        seenHashes: MutableSet<String>,
        documentId: String,
        section: Section,
        blocks: List<TextBlock>,
        chunkIndex: Int
    ): Int {
        val content = normalizeChunkText(blocks.map { it.text })
        if (content.isEmpty() || !isMeaningfulWithHeading(content, section.titlePath, section.heading)) return chunkIndex

        val contentHash = createContentHash(content)
        if (contentHash in seenHashes) return chunkIndex
        seenHashes.add(contentHash)

        chunks.add(
            Chunk(
                id = "$documentId-chunk-$chunkIndex",
                documentId = documentId,
                content = content,
                contentHash = contentHash,
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
     * @param chunkSize 目标块字符数，默认 900
     * @param overlap 块间重叠字符数，默认 150
     */
    fun chunkMarkdown(
        content: String,
        documentId: String,
        chunkSize: Int = 900,
        overlap: Int = 150
    ): List<Chunk> {
        val sections = splitSections(content.split("\n"))
        val chunks = mutableListOf<Chunk>()
        val seenHashes = mutableSetOf<String>()
        var chunkIndex = 0

        for (section in sections) {
            val blocks = splitBlocks(section).flatMap { splitLongTextBlock(it, chunkSize, overlap) }
            var current = mutableListOf<TextBlock>()
            var currentLength = 0

            for (block in blocks) {
                val nextLength = currentLength + block.text.length
                if (current.isNotEmpty() && nextLength > chunkSize) {
                    chunkIndex = pushChunk(chunks, seenHashes, documentId, section, current, chunkIndex)
                    val overlapBlocks = getOverlapBlocks(current, overlap)
                    current = overlapBlocks.toMutableList()
                    currentLength = overlapBlocks.sumOf { it.text.length }
                }
                current.add(block)
                currentLength += block.text.length
            }
            if (current.isNotEmpty()) {
                chunkIndex = pushChunk(chunks, seenHashes, documentId, section, current, chunkIndex)
            }
        }
        return chunks
    }
}
