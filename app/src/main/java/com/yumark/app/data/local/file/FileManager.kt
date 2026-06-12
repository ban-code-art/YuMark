package com.yumark.app.data.local.file

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val documentsDir = File(context.filesDir, "documents")
    private val imagesDir = File(context.filesDir, "images")
    private val exportsDir = File(context.filesDir, "exports")

    // 导入库的图片资产：按导入时的相对目录结构镜像存放，供预览解析相对路径引用
    private val importAssetsDir = File(context.filesDir, "import_assets")

    init {
        documentsDir.mkdirs()
        imagesDir.mkdirs()
        exportsDir.mkdirs()
        importAssetsDir.mkdirs()
    }

    /**
     * 验证文件 ID 是否安全
     * 只允许 UUID 格式: 字母、数字、连字符
     * 防止路径遍历攻击（如 ../../etc/passwd）
     */
    private fun validateFileId(id: String) {
        // UUID 格式: 8-4-4-4-12 (例如: 550e8400-e29b-41d4-a716-446655440000)
        val uuidPattern = Pattern.compile("^[a-zA-Z0-9-]{1,255}$")

        if (!uuidPattern.matcher(id).matches()) {
            throw SecurityException("Invalid file ID format: $id")
        }

        // 额外检查：不允许包含路径分隔符
        if (id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw SecurityException("File ID contains illegal path characters: $id")
        }
    }

    /**
     * 验证文件路径在指定目录内
     * 防止符号链接和规范化路径攻击
     */
    private fun validatePathInDirectory(file: File, allowedDir: File) {
        val canonicalFile = file.canonicalFile
        val canonicalDir = allowedDir.canonicalFile

        if (!canonicalFile.path.startsWith(canonicalDir.path)) {
            throw SecurityException(
                "File path ${canonicalFile.path} is outside allowed directory ${canonicalDir.path}"
            )
        }
    }

    suspend fun saveDocumentContent(id: String, content: String): Result<Unit> =
        // NonCancellable：写盘一旦开始必须完成，避免协程取消留下半截文件
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            try {
                // 验证 ID 安全性
                validateFileId(id)

                val file = File(documentsDir, "$id.md")

                // 验证文件路径在允许的目录内
                validatePathInDirectory(file, documentsDir)

                file.writeText(content)
                Result.success(Unit)
            } catch (e: SecurityException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun loadDocumentContent(id: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                // 验证 ID 安全性
                validateFileId(id)

                val file = File(documentsDir, "$id.md")

                // 验证文件路径在允许的目录内
                validatePathInDirectory(file, documentsDir)

                Result.success(if (file.exists()) file.readText() else "")
            } catch (e: SecurityException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun deleteDocumentFile(id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                // 验证 ID 安全性
                validateFileId(id)

                val file = File(documentsDir, "$id.md")

                // 验证文件路径在允许的目录内
                validatePathInDirectory(file, documentsDir)

                if (file.exists()) file.delete()
                Result.success(Unit)
            } catch (e: SecurityException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun getDocumentsDir(): File = documentsDir
    fun getImagesDir(): File = imagesDir
    fun getExportsDir(): File = exportsDir
    fun getImportAssetsDir(): File = importAssetsDir

    companion object {
        /**
         * 导入库镜像目录（import_assets）的路径段消毒：防 SAF 返回的名称带路径分隔符或 ".."。
         * 写镜像（导入复制）与算镜像路径（重命名/删除同步）必须用同一规则，否则对不上。
         */
        fun sanitizeImportSegment(segment: String): String {
            val cleaned = segment.replace('/', '_').replace('\\', '_')
            return if (cleaned == "..") "_" else cleaned
        }
    }
}
