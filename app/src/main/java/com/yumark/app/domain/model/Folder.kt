package com.yumark.app.domain.model

import kotlinx.datetime.Instant

data class Folder(
    val id: String,
    val name: String,
    val parentId: String?,
    val createdAt: Instant,
    val order: Int
) {
    companion object {
        fun create(id: String, name: String, parentId: String? = null, order: Int = 0): Folder {
            return Folder(
                id = id,
                name = name,
                parentId = parentId,
                createdAt = kotlinx.datetime.Clock.System.now(),
                order = order
            )
        }
    }
}

data class FolderTree(
    val folder: Folder?,
    val children: List<FolderTree>,
    val documentCount: Int,
    val isExpanded: Boolean = false
)
