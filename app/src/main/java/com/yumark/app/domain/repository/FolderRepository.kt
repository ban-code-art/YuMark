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
}
