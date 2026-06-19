package com.yumark.app.domain.repository

import com.yumark.app.domain.model.DocumentVersion
import kotlinx.coroutines.flow.Flow

/** 文档历史版本仓库：保存内容快照、读取列表、按需恢复。 */
interface DocumentVersionRepository {
    /** 观察某文档的版本列表（按时间倒序）。 */
    fun observeVersions(documentId: String): Flow<List<DocumentVersion>>

    /** 取单个版本。 */
    suspend fun getVersion(versionId: String): DocumentVersion?

    /**
     * 内容相较最新版本有变化时落一条快照（去重），并裁剪到保留上限。
     * 无变化则跳过。返回是否新建了快照。
     */
    suspend fun snapshotIfChanged(documentId: String, content: String, wordCount: Int): Boolean

    /** 删除单个版本。 */
    suspend fun deleteVersion(versionId: String)
}
