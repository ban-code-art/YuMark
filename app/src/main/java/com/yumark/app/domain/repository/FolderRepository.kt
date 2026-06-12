package com.yumark.app.domain.repository

import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.FolderTree
import kotlinx.coroutines.flow.Flow

interface FolderRepository {
    suspend fun getFolderById(id: String): Result<Folder>
    suspend fun getAllFolders(): Result<List<Folder>>
    suspend fun getFoldersByParent(parentId: String?): Result<List<Folder>>
    suspend fun getFolderTree(): Result<FolderTree>
    fun observeFolders(): Flow<List<Folder>>
    suspend fun createFolder(name: String, parentId: String?): Result<Folder>
    suspend fun renameFolder(id: String, newName: String): Result<Unit>
    suspend fun deleteFolder(id: String, deleteContents: Boolean): Result<Unit>
    suspend fun moveFolder(id: String, targetParentId: String?): Result<Unit>

    /**
     * 确保「导入库」根文件夹存在并返回它（固定 ID，惰性创建）。
     * 导入收纳库的所有文件/子文件夹都挂在这个根下。
     */
    suspend fun ensureImportLibraryFolder(): Result<Folder>

    companion object {
        /** 导入库根文件夹的保留 ID */
        const val IMPORT_LIBRARY_FOLDER_ID = "__import_library__"

        /** 导入库根文件夹的显示名 */
        const val IMPORT_LIBRARY_FOLDER_NAME = "导入库"
    }
}
