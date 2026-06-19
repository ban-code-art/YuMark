package com.yumark.app.domain.model

/** 文档历史版本（本地内容快照）。 */
data class DocumentVersion(
    val id: String,
    val documentId: String,
    val content: String,
    val wordCount: Int,
    val createdAt: Long
)
