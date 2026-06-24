package com.yumark.app.data.ai.rag

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class VectorStoreTest {

    private fun chunk(id: String, docId: String, content: String, heading: String? = null, titlePath: List<String> = emptyList()): Chunk =
        Chunk(
            id = id,
            documentId = docId,
            content = content,
            contentHash = id,   // 测试中用 id 作 hash，便于控制去重
            index = 0,
            startLine = 1,
            endLine = 1,
            titlePath = titlePath,
            heading = heading
        )

    @Test
    fun `cosine of identical vectors approaches one`() {
        val store = VectorStore()
        val vec = floatArrayOf(0.1f, 0.2f, 0.3f)
        val c = chunk("c1", "d1", "内容内容内容内容内容内容内容内容内容内容内容内容内容")
        store.upsertDocument("d1", "文档A", listOf(c))
        store.setEmbedding("c1", vec)
        val results = store.search(vec, topK = 5, threshold = 0.5f)
        assertThat(results).isNotEmpty()
        assertThat(results[0].chunk.id).isEqualTo("c1")
        assertThat(results[0].vectorScore).isWithin(0.001f).of(1f)
    }

    @Test
    fun `orthogonal vectors score below threshold`() {
        val store = VectorStore()
        store.upsertDocument("d1", "文档A", listOf(chunk("c1", "d1", "内容内容内容内容内容内容内容内容")))
        store.setEmbedding("c1", floatArrayOf(1f, 0f))
        val results = store.search(floatArrayOf(0f, 1f), topK = 5, threshold = 0.5f)
        assertThat(results).isEmpty()
    }

    @Test
    fun `keyword search matches heading with higher weight than body`() {
        val store = VectorStore()
        store.upsertDocument(
            "d1", "架构笔记", listOf(
                chunk("c1", "d1", "普通正文里没有关键词", heading = "微服务"),
                chunk("c2", "d1", "微服务出现在标题里", heading = "其它")
            )
        )
        val results = store.keywordSearch("微服务", topK = 5, preferCurrentDocId = null)
        assertThat(results).isNotEmpty()
        // 标题命中权重更高 → 排前
        assertThat(results[0].chunk.heading).isEqualTo("微服务")
    }

    @Test
    fun `hybrid merge gives higher score when both vector and keyword match`() {
        val store = VectorStore()
        val chunkVec = floatArrayOf(1f, 0f)
        // 相似但不完全相同的查询向量，使 vectorScore 落在 (0,1)，混合分才能体现加成
        val queryVec = floatArrayOf(0.6f, 0.8f)
        val c = chunk("c1", "d1", "微服务架构正文微服务架构正文微服务架构正文", heading = "微服务")
        store.upsertDocument("d1", "微服务笔记", listOf(c))
        store.setEmbedding("c1", chunkVec)

        val hybrid = store.hybridSearch("微服务", queryVec, topK = 5, threshold = 0.3f, preferCurrentDocId = null)
        assertThat(hybrid).hasSize(1)
        assertThat(hybrid[0].retrievalMode).isEqualTo("hybrid")
        // 双命中：vectorScore*0.72 + keywordScore*0.28 + 0.04，应高于纯向量分
        assertThat(hybrid[0].score).isGreaterThan(hybrid[0].vectorScore)
    }

    @Test
    fun `results are diversified across documents`() {
        val store = VectorStore()
        val vec = floatArrayOf(1f, 0f)
        // 同一篇文档三个块都高相似；另一篇文档一个块
        store.upsertDocument("d1", "文档A", listOf(
            chunk("a1", "d1", "内容内容内容内容内容内容内容内容内容内容内容内容"),
            chunk("a2", "d1", "内容内容内容内容内容内容内容内容内容内容内容内容X"),
            chunk("a3", "d1", "内容内容内容内容内容内容内容内容内容内容内容内容Y")
        ))
        store.upsertDocument("d2", "文档B", listOf(
            chunk("b1", "d2", "内容内容内容内容内容内容内容内容内容内容内容内容Z")
        ))
        listOf("a1", "a2", "a3", "b1").forEach { store.setEmbedding(it, vec) }

        val results = store.search(vec, topK = 2, threshold = 0.5f)
        assertThat(results).hasSize(2)
        // 多样化轮询：两篇文档应各占一席
        val docs = results.map { it.documentId }.toSet()
        assertThat(docs).containsExactly("d1", "d2")
    }

    @Test
    fun `duplicate content hash collapses to one result`() {
        val store = VectorStore()
        val vec = floatArrayOf(1f, 0f)
        // 两块内容相同、contentHash 相同
        val same = "重复正文重复正文重复正文重复正文重复正文重复正文"
        store.upsertDocument("d1", "文档A", listOf(
            chunk("c1", "d1", same).copy(contentHash = "H"),
            chunk("c2", "d1", same).copy(contentHash = "H")
        ))
        store.setEmbedding("c1", vec)
        store.setEmbedding("c2", vec)
        val results = store.search(vec, topK = 5, threshold = 0.5f)
        assertThat(results).hasSize(1)
    }

    @Test
    fun `prefer current document adds boost`() {
        val store = VectorStore()
        val vec = floatArrayOf(1f, 0f)
        store.upsertDocument("d1", "当前文档", listOf(chunk("c1", "d1", "正文正文正文正文正文正文正文正文")))
        store.upsertDocument("d2", "其它文档", listOf(chunk("c2", "d2", "正文正文正文正文正文正文正文")))
        store.setEmbedding("c1", vec)
        store.setEmbedding("c2", vec)
        val results = store.hybridSearch("正文", vec, topK = 5, threshold = 0.3f, preferCurrentDocId = "d1")
        // 当前文档因 +0.08 加成应排第一
        assertThat(results.first().documentId).isEqualTo("d1")
    }
}
