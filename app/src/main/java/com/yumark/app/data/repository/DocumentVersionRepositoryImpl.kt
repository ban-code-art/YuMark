package com.yumark.app.data.repository

import com.yumark.app.data.local.db.dao.DocumentVersionDao
import com.yumark.app.data.local.db.entity.DocumentVersionEntity
import com.yumark.app.domain.model.DocumentVersion
import com.yumark.app.domain.repository.DocumentVersionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentVersionRepositoryImpl @Inject constructor(
    private val dao: DocumentVersionDao
) : DocumentVersionRepository {

    override fun observeVersions(documentId: String): Flow<List<DocumentVersion>> =
        dao.observeByDocument(documentId).map { list -> list.map { it.toDomain() } }

    override suspend fun getVersion(versionId: String): DocumentVersion? =
        dao.getById(versionId)?.toDomain()

    override suspend fun snapshotIfChanged(documentId: String, content: String, wordCount: Int): Boolean {
        // 去重：与最新版本内容相同则跳过，避免重复快照
        val latest = dao.latest(documentId)
        if (latest != null && latest.content == content) return false
        // 空文档不快照
        if (content.isEmpty() && latest == null) return false

        dao.insert(
            DocumentVersionEntity(
                id = UUID.randomUUID().toString(),
                documentId = documentId,
                content = content,
                wordCount = wordCount,
                createdAt = System.currentTimeMillis()
            )
        )
        // 裁剪到保留上限（FIFO 删最旧）
        dao.pruneOldest(documentId, MAX_VERSIONS_PER_DOCUMENT)
        return true
    }

    override suspend fun deleteVersion(versionId: String) = dao.deleteById(versionId)

    private fun DocumentVersionEntity.toDomain() = DocumentVersion(
        id = id,
        documentId = documentId,
        content = content,
        wordCount = wordCount,
        createdAt = createdAt
    )

    companion object {
        /** 每文档保留的最大版本数，超出删最旧。 */
        private const val MAX_VERSIONS_PER_DOCUMENT = 50
    }
}
