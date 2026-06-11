package com.yumark.app.domain.usecase.import

import android.content.Context
import android.net.Uri
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.repository.DocumentRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

/**
 * 从外部文件导入文档的 UseCase
 * 支持 .md 和 .txt 格式
 */
class ImportDocumentUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentRepository: DocumentRepository
) {
    /**
     * 导入文档
     * @param uri 文件 URI
     * @param targetFolderId 目标文件夹 ID（null 表示根目录）
     * @return 创建的文档对象
     */
    suspend operator fun invoke(uri: Uri, targetFolderId: String? = null): Result<Document> = runCatching {
        // 1. 读取文件内容
        val content = readFileContent(uri)

        // 2. 获取文件名（去除扩展名）
        val fileName = getFileName(uri)

        // 3. 验证文件名
        if (fileName.isBlank()) {
            throw IllegalArgumentException("Invalid file name")
        }

        // 4. 创建文档
        val createResult = documentRepository.createDocument(fileName, targetFolderId)
        val document = createResult.getOrThrow()

        // 5. 保存内容
        val updatedDocument = document.copy(content = content)
        documentRepository.saveDocument(updatedDocument).getOrThrow()

        updatedDocument
    }

    /**
     * 读取文件内容
     */
    private fun readFileContent(uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open file: $uri")

        return BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }

    /**
     * 从 URI 获取文件名（去除扩展名）
     */
    private fun getFileName(uri: Uri): String {
        // 尝试从 URI 获取显示名称
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        }

        // 如果获取失败，使用 URI 的最后一部分
        val rawName = displayName ?: uri.lastPathSegment ?: "imported_document"

        // 去除扩展名
        return rawName.substringBeforeLast(".", rawName)
    }

    /**
     * 验证文件类型是否支持
     */
    fun isSupportedFileType(uri: Uri): Boolean {
        val mimeType = context.contentResolver.getType(uri)
        val fileName = uri.lastPathSegment?.lowercase() ?: ""

        return mimeType == "text/plain" ||
                mimeType == "text/markdown" ||
                fileName.endsWith(".md") ||
                fileName.endsWith(".txt") ||
                fileName.endsWith(".markdown")
    }
}
