package com.yumark.app.domain.repository

import android.net.Uri
import com.yumark.app.domain.model.Image

interface ImageRepository {
    suspend fun getImageById(id: String): Result<Image>
    suspend fun getImagesByDocument(documentId: String): Result<List<Image>>
    suspend fun saveImage(documentId: String, uri: Uri, compress: Boolean = true): Result<Image>
    suspend fun deleteImage(id: String): Result<Unit>
    suspend fun deleteImagesByDocument(documentId: String): Result<Unit>
    suspend fun getOrphanedImages(): Result<List<Image>>
    suspend fun cleanOrphanedImages(): Result<Int>
}
