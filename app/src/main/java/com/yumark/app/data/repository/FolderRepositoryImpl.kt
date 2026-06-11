package com.yumark.app.data.repository

import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.db.dao.FolderDao
import com.yumark.app.data.mapper.FolderMapper
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.FolderTree
import com.yumark.app.domain.repository.FolderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderRepositoryImpl @Inject constructor(
    private val folderDao: FolderDao,
    private val documentDao: DocumentDao,
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
        val order = folderDao.getByParent(parentId).size
        val folder = Folder.create(id, name, parentId, order)
        folderDao.insert(mapper.toEntity(folder))
        folder
    }

    override suspend fun renameFolder(id: String, newName: String): Result<Unit> = runCatching {
        val entity = folderDao.getById(id) ?: throw Exception("Folder not found: $id")
        folderDao.update(entity.copy(name = newName))
    }

    override suspend fun deleteFolder(id: String, deleteContents: Boolean): Result<Unit> = runCatching {
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
        folderDao.update(entity.copy(parentId = targetParentId))
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
