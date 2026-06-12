package com.yumark.app.domain.usecase.importing

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.FolderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.inject.Inject

/**
 * 一个可导入的候选文件（扫描所选 SAF 文件夹得到）。
 *
 * @param uri 文件的 SAF URI
 * @param displayName 文档为不含扩展名的显示名；图片为含扩展名的完整文件名
 * @param relativeFolderPath 从所选根文件夹（含）到该文件所在文件夹的名称链；
 *        用于在导入库下重建同样的子文件夹结构。空表示直接在所选根下。
 */
data class ImportCandidate(
    val uri: String,
    val displayName: String,
    val relativeFolderPath: List<String>
)

/**
 * 扫描结果：文档候选（供用户勾选）+ 图片资产（随勾选文档自动复制，不进勾选列表）。
 */
data class ImportScanResult(
    val documents: List<ImportCandidate>,
    val images: List<ImportCandidate>
)

/**
 * 把外部文件夹中的 Markdown/文本文件「复制」进导入库（内部 Room + filesDir）。
 *
 * 与外部工作区不同：导入是复制，复制后与原文件解耦，可在库中独立删除。
 * 用户在 UI 中手动勾选要导入哪些文件（默认全不选），仅勾选项被复制。
 * 图片资产（如 Typora 的 <文档名>.assets 文件夹）随勾选文档一并复制到
 * filesDir/import_assets/ 下，保留相对目录结构，供预览解析相对路径图片引用。
 */
class ImportFolderUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentRepository: DocumentRepository,
    private val folderRepository: FolderRepository,
    private val fileManager: FileManager
) {
    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("md", "markdown", "txt")
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
        private const val MAX_DEPTH = 10
        private const val MAX_FILES = 2000
        private const val MAX_IMAGES = 2000
        private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    }

    /**
     * 扫描所选文件夹，返回可导入的候选文档（供 UI 勾选）和图片资产。
     * 复用与工作区一致的过滤规则：跳隐藏项，限深限量。
     */
    suspend fun scan(treeUri: String): Result<ImportScanResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                    ?: error("无法访问所选文件夹")
                if (!root.canRead()) error("没有该文件夹的读取权限")
                val docs = mutableListOf<ImportCandidate>()
                val images = mutableListOf<ImportCandidate>()
                // 根文件夹自身的名称作为重建结构的第一层
                collect(root, listOf(root.name ?: "导入"), depth = 0, docs = docs, images = images)
                ImportScanResult(docs, images)
            }
        }

    private fun collect(
        dir: DocumentFile,
        path: List<String>,
        depth: Int,
        docs: MutableList<ImportCandidate>,
        images: MutableList<ImportCandidate>
    ) {
        if (depth >= MAX_DEPTH || docs.size >= MAX_FILES) return
        for (child in dir.listFiles()) {
            if (docs.size >= MAX_FILES) break
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                collect(child, path + name, depth + 1, docs, images)
            } else {
                val ext = name.substringAfterLast('.', "").lowercase()
                when {
                    ext in SUPPORTED_EXTENSIONS -> docs += ImportCandidate(
                        uri = child.uri.toString(),
                        displayName = name.substringBeforeLast('.', name),
                        relativeFolderPath = path
                    )
                    ext in IMAGE_EXTENSIONS &&
                        images.size < MAX_IMAGES &&
                        child.length() in 1..MAX_IMAGE_BYTES -> images += ImportCandidate(
                        uri = child.uri.toString(),
                        displayName = name,
                        relativeFolderPath = path
                    )
                }
            }
        }
    }

    /**
     * 复制选中的候选文档进导入库（保留子文件夹结构），并复制与之相关的图片资产。
     * 图片取「位于任一选中文档所在目录（或其子目录）」的项，覆盖 Typora 的
     * ./<文档名>.assets/ 约定；复制到 filesDir/import_assets/<相对路径>。
     * @return 成功导入的文档数
     */
    suspend operator fun invoke(
        selected: List<ImportCandidate>,
        images: List<ImportCandidate> = emptyList()
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (selected.isEmpty()) return@runCatching 0

                val importRoot = folderRepository.ensureImportLibraryFolder().getOrThrow()

                // 路径 -> folderId 缓存，避免为同一子文件夹重复创建
                val folderIdByPath = HashMap<String, String>()
                folderIdByPath[""] = importRoot.id

                var imported = 0
                for (candidate in selected) {
                    val targetFolderId = resolveFolderPath(
                        candidate.relativeFolderPath, importRoot.id, folderIdByPath
                    )
                    val content = readContent(candidate.uri)
                    val doc = documentRepository
                        .createDocument(candidate.displayName, targetFolderId)
                        .getOrThrow()
                    documentRepository.saveDocument(doc.copy(content = content)).getOrThrow()
                    imported++
                }

                copyRelatedImages(selected, images)
                imported
            }
        }

    /** 沿相对路径在导入库下逐级 ensure 子文件夹，返回最末层 folderId */
    private suspend fun resolveFolderPath(
        path: List<String>,
        importRootId: String,
        cache: HashMap<String, String>
    ): String {
        var parentId = importRootId
        val acc = StringBuilder()
        for (segment in path) {
            acc.append('/').append(segment)
            val key = acc.toString()
            val cached = cache[key]
            if (cached != null) {
                parentId = cached
                continue
            }
            // 同名子文件夹已存在则复用，否则新建
            val existing = folderRepository.getFoldersByParent(parentId).getOrNull()
                ?.firstOrNull { it.name == segment }
            val folderId = existing?.id
                ?: folderRepository.createFolder(segment, parentId).getOrThrow().id
            cache[key] = folderId
            parentId = folderId
        }
        return parentId
    }

    /** 复制图片到 import_assets 镜像目录；单张失败不中断整体导入 */
    private fun copyRelatedImages(selected: List<ImportCandidate>, images: List<ImportCandidate>) {
        if (images.isEmpty()) return
        val docDirs = selected.map { it.relativeFolderPath }
        val assetsRoot = fileManager.getImportAssetsDir()
        for (image in images) {
            val related = docDirs.any { docDir ->
                image.relativeFolderPath.size >= docDir.size &&
                    image.relativeFolderPath.subList(0, docDir.size) == docDir
            }
            if (!related) continue
            runCatching {
                val dir = image.relativeFolderPath
                    .map { sanitizeSegment(it) }
                    .fold(assetsRoot) { parent, segment -> File(parent, segment) }
                dir.mkdirs()
                val target = File(dir, sanitizeSegment(image.displayName))
                context.contentResolver.openInputStream(Uri.parse(image.uri))?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /** 目录/文件名只取安全部分，防 SAF 返回的名称带路径分隔符或 ".." */
    private fun sanitizeSegment(segment: String): String {
        val cleaned = segment.replace('/', '_').replace('\\', '_')
        return if (cleaned == "..") "_" else cleaned
    }

    private fun readContent(uri: String): String {
        val input = context.contentResolver.openInputStream(Uri.parse(uri))
            ?: error("无法打开文件: $uri")
        return BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
    }
}
