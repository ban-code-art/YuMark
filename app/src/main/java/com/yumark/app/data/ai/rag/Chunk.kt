package com.yumark.app.data.ai.rag

/**
 * Markdown 语义分块（移植自 guanmo `chunker.ts`）。
 *
 * [titlePath] 为该块所属的标题层级（如 `["项目", "架构"]`），用于检索时关键词加权。
 * [embedding] 仅在内存 VectorStore 中填充；持久化由 [com.yumark.app.data.local.db.entity.EmbeddingEntity] 承载。
 */
data class Chunk(
    val id: String,
    val documentId: String,
    val content: String,
    val contentHash: String,
    val index: Int,
    val startLine: Int,
    val endLine: Int,
    val titlePath: List<String>,
    val heading: String?,
    val sourceType: String = "markdown",
    val embedding: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Chunk) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
