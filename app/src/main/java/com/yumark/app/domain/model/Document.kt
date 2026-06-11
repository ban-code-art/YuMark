package com.yumark.app.domain.model

import kotlinx.datetime.Instant

data class Document(
    val id: String,
    val name: String,
    val content: String,
    val folderId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isFavorite: Boolean,
    val wordCount: Int,
    val characterCount: Int
) {
    companion object {
        fun create(id: String, name: String, folderId: String? = null): Document {
            val now = kotlinx.datetime.Clock.System.now()
            return Document(
                id = id,
                name = name,
                content = "",
                folderId = folderId,
                createdAt = now,
                updatedAt = now,
                isFavorite = false,
                wordCount = 0,
                characterCount = 0
            )
        }
    }
}
