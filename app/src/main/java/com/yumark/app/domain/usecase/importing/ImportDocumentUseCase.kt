package com.yumark.app.domain.usecase.importing

import android.content.Context
import android.net.Uri
import com.yumark.app.R
import com.yumark.app.core.util.FriendlyIOException
import com.yumark.app.core.util.FriendlyValidationException
import com.yumark.app.core.util.UiMessage
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
        // 复用 validation_name_empty：去掉扩展名后为空（形如 ".md"）的确就是「文件名不能为空」，
        // 与手动新建文档时 FileNameValidator 给的判定一致，用户看到的也是同一句话。
        if (fileName.isBlank()) {
            throw FriendlyValidationException(UiMessage.Res(R.string.validation_name_empty))
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
     * 读取文件内容。
     *
     * 打不开时抛 [FriendlyIOException]，与 [ImportFolderUseCase.readContent] 同一套理由：
     * 从前这里是 `IllegalArgumentException("Cannot open file: $uri")`，两处不对——
     * 裸异常过不了 [com.yumark.app.core.util.ErrorHandler.classify]（归 `Unknown` →
     * 「出现未知问题，请重试」，同时占一格崩溃日志配额），而且 `content://` URI 被拼进了
     * 异常 message，那条 message 恰好会被 `worthRecording` 写进崩溃日志。
     */
    private fun readFileContent(uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw FriendlyIOException(UiMessage.Res(R.string.saf_error_read_source))

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
