package com.yumark.app.data.repository

import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.data.mapper.DocumentMapper
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val fileManager: FileManager,
    private val mapper: DocumentMapper
) : DocumentRepository {

    override suspend fun getDocumentById(id: String): Result<Document> = runCatching {
        val entity = documentDao.getById(id) ?: throw Exception("Document not found: $id")
        // 正确传播文件读取错误，而不是使用 getOrDefault 吞掉异常
        val content = fileManager.loadDocumentContent(id).getOrElse { error ->
            when (error) {
                is java.io.FileNotFoundException -> "" // 新文档，空内容是合理的
                else -> throw error // 其他错误需要传播给调用者
            }
        }
        mapper.toDomain(entity, content)
    }

    override fun observeDocument(id: String): Flow<Document?> {
        return documentDao.observeById(id).map { entity ->
            entity?.let {
                // 正确处理文件读取错误
                val content = fileManager.loadDocumentContent(id).getOrElse { error ->
                    when (error) {
                        is java.io.FileNotFoundException -> "" // 新文档，空内容是合理的
                        else -> throw error // 传播其他错误
                    }
                }
                mapper.toDomain(it, content)
            }
        }
    }

    override fun observeAllDocuments(): Flow<List<Document>> {
        return documentDao.observeAll().map { entities ->
            entities.map { entity ->
                // 性能优化：列表视图不需要加载完整内容
                mapper.toDomain(entity, "")
            }
        }
    }

    override suspend fun getAllDocuments(): Result<List<Document>> = runCatching {
        documentDao.getAll().map { entity ->
            val content = fileManager.loadDocumentContent(entity.id).getOrElse { error ->
                when (error) {
                    is java.io.FileNotFoundException -> ""
                    else -> throw error
                }
            }
            mapper.toDomain(entity, content)
        }
    }

    override suspend fun getAllDocumentMetas(): Result<List<Document>> = runCatching {
        documentDao.getAll().map { entity -> mapper.toDomain(entity, "") }
    }

    override suspend fun getDocumentsByFolder(folderId: String?): Result<List<Document>> = runCatching {
        documentDao.getByFolder(folderId).map { entity ->
            val content = fileManager.loadDocumentContent(entity.id).getOrElse { error ->
                when (error) {
                    is java.io.FileNotFoundException -> ""
                    else -> throw error
                }
            }
            mapper.toDomain(entity, content)
        }
    }

    override suspend fun searchDocuments(query: String): Result<List<Document>> = runCatching {
        // 全文搜索：正文存在文件系统而非数据库，需加载后在内存过滤（名称或正文，忽略大小写）
        documentDao.getAll()
            .map { entity ->
                val content = fileManager.loadDocumentContent(entity.id).getOrElse { error ->
                    when (error) {
                        is java.io.FileNotFoundException -> ""
                        else -> throw error
                    }
                }
                mapper.toDomain(entity, content)
            }
            .filter { doc ->
                doc.name.contains(query, ignoreCase = true) ||
                    doc.content.contains(query, ignoreCase = true)
            }
    }

    override suspend fun createDocument(name: String, folderId: String?): Result<Document> = runCatching {
        val id = UUID.randomUUID().toString()
        val document = Document.create(id, name, folderId)
        val entity = mapper.toEntity(document)
        documentDao.insert(entity)
        fileManager.saveDocumentContent(id, "")
        document
    }

    override suspend fun saveDocument(document: Document): Result<Unit> = runCatching {
        // 原子性保存：先写文件，成功后再更新数据库
        // 这样可以避免数据库和文件系统不一致的情况
        fileManager.saveDocumentContent(document.id, document.content).getOrThrow()

        // 文件写入成功后才更新数据库
        val entity = mapper.toEntity(document)
        documentDao.update(entity)
    }

    override suspend fun deleteDocument(id: String): Result<Unit> = runCatching {
        documentDao.deleteById(id)
        fileManager.deleteDocumentFile(id).getOrThrow()
    }

    override suspend fun moveDocument(id: String, targetFolderId: String?): Result<Unit> = runCatching {
        // 仅改归属(folder_id),正文文件不动;顺带刷新 updated_at
        documentDao.moveToFolder(id, targetFolderId, System.currentTimeMillis())
    }

    override suspend fun toggleFavorite(id: String): Result<Unit> = runCatching {
        documentDao.toggleFavorite(id)
    }
}
