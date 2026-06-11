package com.yumark.app.domain.model

import kotlinx.datetime.Instant

data class Image(
    val id: String,
    val documentId: String,
    val fileName: String,
    val filePath: String,
    val width: Int,
    val height: Int,
    val fileSize: Long,
    val createdAt: Instant
)
