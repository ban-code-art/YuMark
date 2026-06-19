package com.yumark.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.local.db.dao.DocumentVersionDao
import com.yumark.app.data.local.db.entity.DocumentVersionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentVersionRepositoryImplTest {

    /** 内存版 DAO，复现去重/裁剪关键行为。 */
    private class FakeDao : DocumentVersionDao {
        val store = mutableListOf<DocumentVersionEntity>()
        override suspend fun insert(version: DocumentVersionEntity) { store.add(version) }
        override fun observeByDocument(docId: String): Flow<List<DocumentVersionEntity>> =
            flowOf(store.filter { it.documentId == docId }.sortedByDescending { it.createdAt })
        override suspend fun getById(id: String): DocumentVersionEntity? = store.find { it.id == id }
        override suspend fun latest(docId: String): DocumentVersionEntity? =
            store.filter { it.documentId == docId }.maxByOrNull { it.createdAt }
        override suspend fun count(docId: String): Int = store.count { it.documentId == docId }
        override suspend fun pruneOldest(docId: String, keep: Int) {
            val forDoc = store.filter { it.documentId == docId }.sortedByDescending { it.createdAt }
            forDoc.drop(keep).forEach { store.remove(it) }
        }
        override suspend fun deleteById(id: String) { store.removeAll { it.id == id } }
    }

    @Test
    fun `snapshot inserts on change and dedupes identical content`() = runTest {
        val dao = FakeDao()
        val repo = DocumentVersionRepositoryImpl(dao)

        assertThat(repo.snapshotIfChanged("d1", "v1", 1)).isTrue()
        // 相同内容 → 跳过
        assertThat(repo.snapshotIfChanged("d1", "v1", 1)).isFalse()
        // 变化 → 记录
        assertThat(repo.snapshotIfChanged("d1", "v2", 1)).isTrue()
        assertThat(dao.count("d1")).isEqualTo(2)
    }

    @Test
    fun `empty first content is not snapshotted`() = runTest {
        val dao = FakeDao()
        val repo = DocumentVersionRepositoryImpl(dao)
        assertThat(repo.snapshotIfChanged("d1", "", 0)).isFalse()
        assertThat(dao.count("d1")).isEqualTo(0)
    }
}
