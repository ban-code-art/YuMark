package com.yumark.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.db.entity.DocumentEntity
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.data.mapper.DocumentMapper
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DocumentRepositoryImplTest {

    private lateinit var repository: DocumentRepositoryImpl
    private val dao: DocumentDao = mockk()
    private val fileManager: FileManager = mockk()
    private val mapper = DocumentMapper()

    @BeforeEach
    fun setup() {
        repository = DocumentRepositoryImpl(dao, fileManager, mapper)
    }

    @AfterEach
    fun tearDown() = clearAllMocks()

    @Test
    fun `getDocumentById returns document when exists`() = runTest {
        val id = "test-id"
        val entity = DocumentEntity(id, "Test", null, 0L, 0L, false, 10, 50)
        coEvery { dao.getById(id) } returns entity
        coEvery { fileManager.loadDocumentContent(id) } returns Result.success("# Hello")

        val result = repository.getDocumentById(id)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.id).isEqualTo(id)
        assertThat(result.getOrNull()?.content).isEqualTo("# Hello")
    }

    @Test
    fun `searchDocuments 按文件名或正文匹配且忽略大小写`() = runTest {
        val byName = DocumentEntity("1", "Kotlin 笔记", null, 0L, 0L, false, 0, 0)
        val byContent = DocumentEntity("2", "随笔", null, 0L, 0L, false, 0, 0)
        val noMatch = DocumentEntity("3", "购物清单", null, 0L, 0L, false, 0, 0)
        coEvery { dao.getAll() } returns listOf(byName, byContent, noMatch)
        coEvery { fileManager.loadDocumentContent("1") } returns Result.success("无关内容")
        coEvery { fileManager.loadDocumentContent("2") } returns Result.success("今天学了 KOTLIN 协程")
        coEvery { fileManager.loadDocumentContent("3") } returns Result.success("牛奶 鸡蛋")

        val result = repository.searchDocuments("kotlin")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.map { it.id }).containsExactly("1", "2")
    }
}
