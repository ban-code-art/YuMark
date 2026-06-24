package com.yumark.app.data.ai.rag

import com.google.common.truth.Truth.assertThat
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
}
