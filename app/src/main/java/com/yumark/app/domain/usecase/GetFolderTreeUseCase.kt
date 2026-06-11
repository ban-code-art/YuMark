package com.yumark.app.domain.usecase

import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.FolderTreeNode
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.FolderRepository
import javax.inject.Inject

class GetFolderTreeUseCase @Inject constructor(
    private val folderRepository: FolderRepository,
    private val documentRepository: DocumentRepository
) {
    suspend operator fun invoke(): Result<List<FolderTreeNode>> = runCatching {
        val allFolders = folderRepository.getAllFolders().getOrThrow()
        val allDocuments = documentRepository.getAllDocuments().getOrThrow()

        buildFolderTree(null, allFolders, allDocuments, 0)
    }

    private fun buildFolderTree(
        parentId: String?,
        allFolders: List<Folder>,
        allDocuments: List<Document>,
        level: Int
    ): List<FolderTreeNode> {
        // 获取当前层级的文件夹
        val folders = allFolders.filter { it.parentId == parentId }
            .sortedBy { it.order }

        return folders.map { folder ->
            // 获取该文件夹下的文档
            val documents = allDocuments.filter { it.folderId == folder.id }
                .sortedByDescending { it.updatedAt }

            // 递归构建子文件夹树
            val children = buildFolderTree(folder.id, allFolders, allDocuments, level + 1)

            FolderTreeNode(
                folder = folder,
                documents = documents,
                children = children,
                isExpanded = false,
                level = level
            )
        } + if (parentId == null) {
            // 根节点添加没有文件夹的文档
            val rootDocuments = allDocuments.filter { it.folderId == null }
                .sortedByDescending { it.updatedAt }

            if (rootDocuments.isNotEmpty()) {
                listOf(
                    FolderTreeNode(
                        folder = null,
                        documents = rootDocuments,
                        children = emptyList(),
                        isExpanded = true,
                        level = 0
                    )
                )
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
}
