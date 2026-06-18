package com.yumark.app.data.repository

import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.db.dao.FolderDao
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.data.mapper.FolderMapper
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.FolderTree
import com.yumark.app.domain.repository.FolderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderRepositoryImpl @Inject constructor(
    private val folderDao: FolderDao,
    private val documentDao: DocumentDao,
    private val fileManager: FileManager,
    private val mapper: FolderMapper
) : FolderRepository {

    override suspend fun getFolderById(id: String): Result<Folder> = runCatching {
        val entity = folderDao.getById(id) ?: throw Exception("Folder not found: $id")
        mapper.toDomain(entity)
    }

    override suspend fun getAllFolders(): Result<List<Folder>> = runCatching {
        folderDao.getAll().map { mapper.toDomain(it) }
    }

    override suspend fun getFoldersByParent(parentId: String?): Result<List<Folder>> = runCatching {
        folderDao.getByParent(parentId).map { mapper.toDomain(it) }
    }

    override suspend fun getFolderTree(): Result<FolderTree> = runCatching {
        buildFolderTree(null, depth = 0, visited = mutableSetOf())
    }

    override fun observeFolders(): Flow<List<Folder>> {
        return folderDao.observeAll().map { entities ->
            entities.map { mapper.toDomain(it) }
        }
    }

    override suspend fun createFolder(name: String, parentId: String?): Result<Folder> = runCatching {
        val id = UUID.randomUUID().toString()
        // order 取 MAX(order)+1，避免并发创建同父文件夹时 size 竞态导致 order 重复
        val order = folderDao.maxOrder(parentId) + 1
        val folder = Folder.create(id, name, parentId, order)
        folderDao.insert(mapper.toEntity(folder))
        folder
    }

    override suspend fun ensureImportLibraryFolder(): Result<Folder> = runCatching {
        // 导入库根用固定 ID（不走 UUID），存在则复用、不存在则惰性创建
        val existing = folderDao.getById(FolderRepository.IMPORT_LIBRARY_FOLDER_ID)
        if (existing != null) {
            mapper.toDomain(existing)
        } else {
            val folder = Folder(
                id = FolderRepository.IMPORT_LIBRARY_FOLDER_ID,
                name = FolderRepository.IMPORT_LIBRARY_FOLDER_NAME,
                parentId = null,
                createdAt = kotlinx.datetime.Clock.System.now(),
                order = 0
            )
            folderDao.insert(mapper.toEntity(folder))
            folder
        }
    }

    override suspend fun renameFolder(id: String, newName: String): Result<Unit> = runCatching {
        val entity = folderDao.getById(id) ?: throw Exception("Folder not found: $id")
        // 改名前先算出旧镜像目录（依赖旧名称链）
        val oldMirror = importMirrorDir(id)
        folderDao.update(entity.copy(name = newName))
        // 导入库子树：同步重命名 import_assets 镜像目录，否则该子树下文档的图片全部失效
        if (oldMirror != null && oldMirror != fileManager.getImportAssetsDir() && oldMirror.exists()) {
            oldMirror.renameTo(File(oldMirror.parentFile, FileManager.sanitizeImportSegment(newName)))
        }
    }

    override suspend fun deleteFolder(id: String, deleteContents: Boolean): Result<Unit> = runCatching {
        // 删除前先算镜像目录（删完 Room 记录就找不到名称链了）
        val mirror = importMirrorDir(id)
        if (!deleteContents) {
            // 不删除内容时，检查是否为空
            val documents = documentDao.getByFolder(id)
            if (documents.isNotEmpty()) throw Exception("Folder is not empty")
            val subfolders = folderDao.getByParent(id)
            if (subfolders.isNotEmpty()) throw Exception("Folder has subfolders")

            // 仅删除空文件夹
            folderDao.deleteById(id)
        } else {
            // 级联删除所有内容
            deleteFolderRecursively(id)
        }
        // 导入库子树：清理 import_assets 镜像，避免图片孤儿文件占用存储
        if (mirror != null) {
            if (mirror == fileManager.getImportAssetsDir()) {
                // 删除导入库根：清空镜像内容但保留目录本身
                mirror.listFiles()?.forEach { it.deleteRecursively() }
            } else {
                mirror.deleteRecursively()
            }
        }
    }

    /**
     * 文件夹在 import_assets 镜像中的目录；不在导入库子树内返回 null。
     * 路径段消毒规则与导入复制时一致（[FileManager.sanitizeImportSegment]）。
     */
    private suspend fun importMirrorDir(folderId: String): File? {
        var cur = folderId
        val names = ArrayDeque<String>()
        var guard = 0
        while (cur != FolderRepository.IMPORT_LIBRARY_FOLDER_ID) {
            if (++guard > MAX_FOLDER_DEPTH) return null
            val entity = folderDao.getById(cur) ?: return null
            names.addFirst(entity.name)
            cur = entity.parentId ?: return null
        }
        return names.fold(fileManager.getImportAssetsDir()) { parent, segment ->
            File(parent, FileManager.sanitizeImportSegment(segment))
        }
    }

    /**
     * 递归删除文件夹及其所有内容
     * @param folderId 要删除的文件夹 ID
     */
    private suspend fun deleteFolderRecursively(folderId: String) {
        // 1. 递归删除所有子文件夹
        val subfolders = folderDao.getByParent(folderId)
        subfolders.forEach { subfolder ->
            deleteFolderRecursively(subfolder.id)
        }

        // 2. 删除该文件夹下的所有文档
        val documents = documentDao.getByFolder(folderId)
        documents.forEach { document ->
            documentDao.deleteById(document.id)
            // 注意：这里不删除文档文件，因为那是 DocumentRepository 的职责
            // 实际应用中应该调用 DocumentRepository.deleteDocument()
        }

        // 3. 最后删除文件夹本身
        folderDao.deleteById(folderId)
    }

    override suspend fun moveFolder(id: String, targetParentId: String?): Result<Unit> = runCatching {
        val entity = folderDao.getById(id) ?: throw Exception("Folder not found: $id")
        // 防环:不能移动到自身,也不能移动到自己的子孙(否则子树脱离根、构建树时死循环)
        if (targetParentId == id) throw IllegalArgumentException("不能移动到自身")
        if (targetParentId != null && isDescendant(targetParentId, ancestorId = id)) {
            throw IllegalArgumentException("不能移动到自己的子文件夹")
        }
        // 追加到目标文件夹末尾,避免与目标内既有 order 冲突
        val newOrder = folderDao.maxOrder(targetParentId) + 1
        folderDao.update(entity.copy(parentId = targetParentId, order = newOrder))
    }

    /** folderId 是否是 ancestorId 的后代(沿 parentId 链上溯,带深度/循环 guard)。 */
    private suspend fun isDescendant(folderId: String, ancestorId: String): Boolean {
        var current: String? = folderId
        val visited = mutableSetOf<String>()
        var depth = 0
        while (current != null) {
            if (current == ancestorId) return true
            if (current in visited || depth > MAX_FOLDER_DEPTH) return false
            visited.add(current)
            current = folderDao.getById(current)?.parentId
            depth++
        }
        return false
    }

    /**
     * 递归构建文件夹树
     * @param parentId 父文件夹 ID
     * @param depth 当前深度
     * @param visited 已访问的文件夹 ID（循环检测）
     */
    private suspend fun buildFolderTree(
        parentId: String?,
        depth: Int,
        visited: MutableSet<String>
    ): FolderTree {
        // 深度限制检查
        if (depth > MAX_FOLDER_DEPTH) {
            throw IllegalStateException("Folder tree depth exceeds limit: $MAX_FOLDER_DEPTH")
        }

        // 循环引用检查
        if (parentId != null && parentId in visited) {
            throw IllegalStateException("Circular folder reference detected: $parentId")
        }

        val folder = parentId?.let { folderDao.getById(it) }?.let { mapper.toDomain(it) }
        val children = folderDao.getByParent(parentId)

        // 将当前文件夹添加到已访问集合
        if (parentId != null) {
            visited.add(parentId)
        }

        val childTrees = children.map { child ->
            buildFolderTree(child.id, depth + 1, visited)
        }

        val documentCount = documentDao.getByFolder(parentId).size
        return FolderTree(folder, childTrees, documentCount)
    }

    companion object {
        private const val MAX_FOLDER_DEPTH = 100  // 最大文件夹层级
    }
}
