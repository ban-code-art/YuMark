package com.yumark.app.domain.repository

import com.yumark.app.domain.model.Document
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    suspend fun getDocumentById(id: String): Result<Document>
    fun observeDocument(id: String): Flow<Document?>
    fun observeAllDocuments(): Flow<List<Document>>
    suspend fun getAllDocuments(): Result<List<Document>>

    /** 仅元数据（content 为空字符串），用于树/列表等不需要正文的场景，避免全量文件 IO */
    suspend fun getAllDocumentMetas(): Result<List<Document>>
    suspend fun getDocumentsByFolder(folderId: String?): Result<List<Document>>
    suspend fun searchDocuments(query: String): Result<List<Document>>
    suspend fun createDocument(name: String, folderId: String?): Result<Document>
    suspend fun saveDocument(document: Document): Result<Unit>
    suspend fun deleteDocument(id: String): Result<Unit>
    suspend fun toggleFavorite(id: String): Result<Unit>
}
