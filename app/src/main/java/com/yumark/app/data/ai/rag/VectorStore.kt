package com.yumark.app.data.ai.rag

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/** 文档元信息（VectorStore 内用于关键词加权的文档名）。YuMark 无文件路径，按 documentId 聚类。 */
data class DocMeta(val id: String, val name: String)

/** 检索单条结果。 */
data class SearchResult(
    val chunk: Chunk,
    val score: Float,
    val vectorScore: Float,
    val keywordScore: Float,
    val documentId: String,
    val documentName: String,
    val retrievalMode: String   // vector / keyword / hybrid
)

/**
 * 内存向量存储 —— 移植自 guanmo `vectorStore.ts`。
 *
 * 纯线性扫描 + 手写余弦，无 ANN 库。启动时从 DB 全量 hydrate（文档量不大时可行）。
 * 混合检索：向量 topK*3 + 关键词 topK*3，按 chunkId 合并——双命中 `vectorScore*0.72 +
 * keywordScore*0.28 + 0.04`，单命中取 max；再按 contentHash 去重 + 按文档多样化轮询填到 topK。
 */
@Singleton
class VectorStore @Inject constructor() {

    private val chunks = LinkedHashMap<String, Chunk>()           // chunkId -> Chunk
    private val embeddings = HashMap<String, FloatArray>()        // chunkId -> vector
    private val documents = LinkedHashMap<String, DocMeta>()      // docId -> meta

    @Synchronized
    fun hydrate(chunkList: List<Chunk>, embeddingMap: Map<String, FloatArray>, docList: List<DocMeta>) {
        chunks.clear()
        embeddings.clear()
        documents.clear()
        docList.forEach { documents[it.id] = it }
        chunkList.forEach { chunks[it.id] = it }
        embeddingMap.forEach { (id, vec) -> embeddings[id] = vec }
    }

    /** 写入/替换一篇文档的分块（先移除该文档旧分块与向量，再插入新的）。 */
    @Synchronized
    fun upsertDocument(docId: String, name: String, newChunks: List<Chunk>) {
        removeDocument(docId)
        documents[docId] = DocMeta(docId, name)
        newChunks.forEach { chunks[it.id] = it }
    }

    @Synchronized
    fun setEmbedding(chunkId: String, vector: FloatArray) {
        embeddings[chunkId] = vector
    }

    @Synchronized
    fun removeDocument(docId: String) {
        val toRemove = chunks.values.filter { it.documentId == docId }.map { it.id }
        toRemove.forEach { chunks.remove(it); embeddings.remove(it) }
        documents.remove(docId)
    }

    fun chunkCount(): Int = chunks.size
    fun documentCount(): Int = documents.size
    fun isEmpty(): Boolean = chunks.isEmpty()

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }

    /** 按 score 降序截断到 topK，并按文档多样化轮询（避免单文档霸榜）。 */
    private fun sortAndTruncate(results: List<SearchResult>, topK: Int): List<SearchResult> {
        // contentHash 去重：同一内容只保留最高分
        val bestByContent = LinkedHashMap<String, SearchResult>()
        for (r in results) {
            val key = r.chunk.contentHash.ifEmpty { createContentHash(r.chunk.content) }
            val existing = bestByContent[key]
            if (existing == null || r.score > existing.score) bestByContent[key] = r
        }
        val sorted = bestByContent.values.sortedByDescending { it.score }

        // 按文档分组，交替取一
        val byDocument = LinkedHashMap<String, ArrayDeque<SearchResult>>()
        for (r in sorted) byDocument.getOrPut(r.documentId) { ArrayDeque() }.addLast(r)

        val diversified = mutableListOf<SearchResult>()
        while (diversified.size < topK) {
            var added = false
            for (group in byDocument.values) {
                val next = group.removeFirstOrNull() ?: continue
                diversified.add(next)
                added = true
                if (diversified.size >= topK) break
            }
            if (!added) break
        }
        return diversified
    }

    /** 纯向量检索。 */
    @Synchronized
    fun search(queryEmbedding: FloatArray, topK: Int, threshold: Float): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        for (chunk in chunks.values) {
            val vec = embeddings[chunk.id] ?: continue
            val doc = documents[chunk.documentId] ?: continue
            val score = cosine(queryEmbedding, vec)
            if (score >= threshold) {
                results.add(SearchResult(chunk, score, score, 0f, doc.id, doc.name, "vector"))
            }
        }
        return sortAndTruncate(results, topK)
    }

    private fun tokenize(text: String): List<String> {
        val normalized = text.lowercase()
        val rawTerms = Regex("""[a-z0-9_+#./-]{2,}|[一-鿿]{2,}""").findAll(normalized).map { it.value }
        val terms = LinkedHashSet<String>()
        for (term in rawTerms) {
            terms.add(term)
            if (Regex("""^[一-鿿]+$""").matches(term) && term.length > 3) {
                for (i in 0 until term.length - 1) terms.add(term.substring(i, i + 2))
            }
        }
        return terms.toList()
    }

    private fun getKeywordScore(query: String, chunk: Chunk, doc: DocMeta): Float {
        val terms = tokenize(query)
        if (terms.isEmpty()) return 0f
        val content = chunk.content.lowercase()
        val heading = (chunk.heading ?: "").lowercase()
        val titlePath = chunk.titlePath.joinToString(" > ").lowercase()
        val docName = doc.name.lowercase()

        var score = 0f
        for (term in terms) {
            if (content.contains(term)) score += 1f
            if (heading.contains(term)) score += 1.8f
            if (titlePath.contains(term)) score += 1.5f
            if (docName.contains(term)) score += 1.4f
        }
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.length >= 3) {
            if (content.contains(normalizedQuery)) score += 1.2f
            if (titlePath.contains(normalizedQuery) || docName.contains(normalizedQuery)) score += 1.6f
        }
        return minOf(1f, score / maxOf(terms.size, 1))
    }

    private fun applyRankingBoosts(result: SearchResult, preferCurrentDocId: String?): SearchResult {
        var score = result.score
        if (preferCurrentDocId != null && result.documentId == preferCurrentDocId) {
            score = minOf(1f, score + 0.08f)
        }
        return result.copy(score = score)
    }

    /** 纯关键词检索（embedding 不可用时的兜底，或与向量合并）。 */
    @Synchronized
    fun keywordSearch(query: String, topK: Int, preferCurrentDocId: String?): List<SearchResult> {
        if (tokenize(query).isEmpty()) return emptyList()
        val results = mutableListOf<SearchResult>()
        for (chunk in chunks.values) {
            val doc = documents[chunk.documentId] ?: continue
            val ks = getKeywordScore(query, chunk, doc)
            if (ks > 0f) {
                results.add(
                    applyRankingBoosts(
                        SearchResult(chunk, ks, 0f, ks, doc.id, doc.name, "keyword"),
                        preferCurrentDocId
                    )
                )
            }
        }
        return sortAndTruncate(results, topK)
    }

    /**
     * 混合检索：向量 + 关键词。
     * @param queryEmbedding 查询向量；为 null 时退化为纯关键词检索。
     */
    @Synchronized
    fun hybridSearch(
        query: String,
        queryEmbedding: FloatArray?,
        topK: Int,
        threshold: Float,
        preferCurrentDocId: String?
    ): List<SearchResult> {
        val candidateLimit = maxOf(topK * 3, topK)
        val vectorResults = queryEmbedding?.let { search(it, candidateLimit, threshold) } ?: emptyList()
        val keywordResults = keywordSearch(query, candidateLimit, preferCurrentDocId)

        val byChunk = LinkedHashMap<String, SearchResult>()
        for (result in vectorResults + keywordResults) {
            val existing = byChunk[result.chunk.id]
            if (existing == null) {
                byChunk[result.chunk.id] = applyRankingBoosts(result, preferCurrentDocId)
                continue
            }
            val vectorScore = maxOf(existing.vectorScore, result.vectorScore)
            val keywordScore = maxOf(existing.keywordScore, result.keywordScore)
            val hasVector = vectorScore > 0f
            val hasKeyword = keywordScore > 0f
            val score = if (hasVector && hasKeyword) {
                minOf(1f, vectorScore * 0.72f + keywordScore * 0.28f + 0.04f)
            } else maxOf(vectorScore, keywordScore)
            val mode = if (hasVector && hasKeyword) "hybrid" else existing.retrievalMode
            byChunk[result.chunk.id] = applyRankingBoosts(
                existing.copy(vectorScore = vectorScore, keywordScore = keywordScore, score = score, retrievalMode = mode),
                preferCurrentDocId
            )
        }
        return sortAndTruncate(byChunk.values.toList(), topK)
    }
}
