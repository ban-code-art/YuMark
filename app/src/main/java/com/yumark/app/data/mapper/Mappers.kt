package com.yumark.app.data.mapper

import com.yumark.app.data.local.db.entity.DocumentEntity
import com.yumark.app.data.local.db.entity.FolderEntity
import com.yumark.app.data.local.db.entity.ImageEntity
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.Image
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentMapper @Inject constructor() {
    fun toDomain(entity: DocumentEntity, content: String) = Document(
        id = entity.id,
        name = entity.name,
        content = content,
        folderId = entity.folderId,
        createdAt = Instant.fromEpochMilliseconds(entity.createdAt),
        updatedAt = Instant.fromEpochMilliseconds(entity.updatedAt),
        isFavorite = entity.isFavorite,
        wordCount = entity.wordCount,
        characterCount = entity.characterCount
    )

    fun toEntity(document: Document) = DocumentEntity(
        id = document.id,
        name = document.name,
        folderId = document.folderId,
        createdAt = document.createdAt.toEpochMilliseconds(),
        updatedAt = document.updatedAt.toEpochMilliseconds(),
        isFavorite = document.isFavorite,
        wordCount = document.wordCount,
        characterCount = document.characterCount
    )
}

@Singleton
class FolderMapper @Inject constructor() {
    fun toDomain(entity: FolderEntity) = Folder(
        id = entity.id,
        name = entity.name,
        parentId = entity.parentId,
        createdAt = Instant.fromEpochMilliseconds(entity.createdAt),
        order = entity.order
    )

    fun toEntity(folder: Folder) = FolderEntity(
        id = folder.id,
        name = folder.name,
        parentId = folder.parentId,
        createdAt = folder.createdAt.toEpochMilliseconds(),
        order = folder.order
    )
}

@Singleton
class ImageMapper @Inject constructor() {
    fun toDomain(entity: ImageEntity) = Image(
        id = entity.id,
        documentId = entity.documentId,
        fileName = entity.fileName,
        filePath = entity.filePath,
        width = entity.width,
        height = entity.height,
        fileSize = entity.fileSize,
        createdAt = Instant.fromEpochMilliseconds(entity.createdAt)
    )

    fun toEntity(image: Image) = ImageEntity(
        id = image.id,
        documentId = image.documentId,
        fileName = image.fileName,
        filePath = image.filePath,
        width = image.width,
        height = image.height,
        fileSize = image.fileSize,
        createdAt = image.createdAt.toEpochMilliseconds()
    )
}
