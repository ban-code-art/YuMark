package com.yumark.app.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.repository.DocumentRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SaveDocumentUseCaseTest {

    private lateinit var useCase: SaveDocumentUseCase
    private val repo: DocumentRepository = mockk()

    @BeforeEach
    fun setup() {
        useCase = SaveDocumentUseCase(repo)
    }

    @Test
    fun `save document updates word count and character count`() = runTest {
        val document = Document.create("test-id", "Test").copy(content = "Hello World! This is a test.")
        coEvery { repo.saveDocument(any()) } returns Result.success(Unit)

        val result = useCase(document)

        assertThat(result.isSuccess).isTrue()
        val saved = slot<Document>()
        coVerify { repo.saveDocument(capture(saved)) }
        assertThat(saved.captured.wordCount).isEqualTo(6)
        assertThat(saved.captured.characterCount).isEqualTo(28)
    }

    @Test
    fun `save document fails when name is blank`() = runTest {
        val document = Document.create("test-id", "")

        val result = useCase(document)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("name cannot be empty")
    }
}
