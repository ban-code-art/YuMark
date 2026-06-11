package com.yumark.app.domain.model

data class FolderTreeNode(
    val folder: Folder?,  // null 表示根节点
    val documents: List<Document>,
    val children: List<FolderTreeNode>,
    val isExpanded: Boolean = false,
    val level: Int = 0
) {
    val hasChildren: Boolean get() = children.isNotEmpty()
    val hasDocuments: Boolean get() = documents.isNotEmpty()
    val isEmpty: Boolean get() = !hasChildren && !hasDocuments
}
