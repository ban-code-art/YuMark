package com.yumark.app.presentation.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.FolderTreeNode
import kotlinx.coroutines.delay

/**
 * 侧栏文件树的管理动作集合。
 * 传 null 给 [SidebarFileTree] 表示纯浏览模式（如编辑器内的切换文档侧栏），
 * 文件夹/文档行都不显示管理菜单。
 */
data class SidebarActions(
    val onCreateDocument: (String?) -> Unit,
    val onCreateSubfolder: (String?) -> Unit,
    val onRenameFolder: (String) -> Unit,
    val onDeleteFolder: (String) -> Unit,
    val onRenameDocument: (Document) -> Unit,
    val onDeleteDocument: (Document) -> Unit
)

@Composable
fun SidebarFileTree(
    tree: List<FolderTreeNode>,
    currentDocumentId: String?,
    expandedFolders: Set<String>,
    onDocumentClick: (String) -> Unit,
    onFolderExpand: (String) -> Unit,
    onFolderCollapse: (String) -> Unit,
    actions: SidebarActions?,
    scrollToCurrentDocument: Boolean = false,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // 自动滚动到当前文档
    LaunchedEffect(scrollToCurrentDocument, currentDocumentId) {
        if (scrollToCurrentDocument && currentDocumentId != null) {
            delay(100) // 等待LazyColumn完成布局

            // 扁平化树结构找到当前文档的索引
            val index = findDocumentIndex(tree, currentDocumentId, expandedFolders)
            if (index >= 0) {
                listState.animateScrollToItem(index)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(tree) { node ->
            FolderTreeItem(
                node = node,
                currentDocumentId = currentDocumentId,
                isExpanded = node.folder?.let { expandedFolders.contains(it.id) } ?: true,
                expandedFolders = expandedFolders,
                onDocumentClick = onDocumentClick,
                onFolderExpand = onFolderExpand,
                onFolderCollapse = onFolderCollapse,
                actions = actions
            )
        }
    }
}

/**
 * 扁平化遍历树结构，找到指定文档在LazyColumn中的索引
 */
private fun findDocumentIndex(
    tree: List<FolderTreeNode>,
    documentId: String,
    expandedFolders: Set<String>
): Int {
    var index = 0

    fun traverse(nodes: List<FolderTreeNode>): Boolean {
        for (node in nodes) {
            // 文件夹行占一个索引
            if (node.folder != null) index++

            // 只遍历展开的节点
            val isExpanded = node.folder == null || expandedFolders.contains(node.folder.id)
            if (isExpanded) {
                // 检查文档列表
                for (doc in node.documents) {
                    if (doc.id == documentId) {
                        return true // 找到目标文档
                    }
                    index++
                }

                // 递归子文件夹
                if (traverse(node.children)) {
                    return true
                }
            }
        }
        return false
    }

    return if (traverse(tree)) index else -1
}

@Composable
fun FolderTreeItem(
    node: FolderTreeNode,
    currentDocumentId: String?,
    isExpanded: Boolean,
    expandedFolders: Set<String>,
    onDocumentClick: (String) -> Unit,
    onFolderExpand: (String) -> Unit,
    onFolderCollapse: (String) -> Unit,
    actions: SidebarActions?
) {
    Column {
        // 文件夹行
        node.folder?.let { folder ->
            FolderRow(
                folder = folder,
                level = node.level,
                isExpanded = isExpanded,
                hasChildren = node.hasChildren,
                onToggleExpand = {
                    if (isExpanded) onFolderCollapse(folder.id)
                    else onFolderExpand(folder.id)
                },
                actions = actions
            )
        }

        // 展开时显示内容
        if (isExpanded || node.folder == null) {
            // 文档列表
            node.documents.forEach { doc ->
                DocumentRow(
                    document = doc,
                    level = node.level + 1,
                    isSelected = doc.id == currentDocumentId,
                    onClick = { onDocumentClick(doc.id) },
                    onRename = actions?.let { { it.onRenameDocument(doc) } },
                    onDelete = actions?.let { { it.onDeleteDocument(doc) } }
                )
            }

            // 递归显示子文件夹
            node.children.forEach { child ->
                FolderTreeItem(
                    node = child,
                    currentDocumentId = currentDocumentId,
                    isExpanded = child.folder?.let { expandedFolders.contains(it.id) } ?: true,
                    expandedFolders = expandedFolders,
                    onDocumentClick = onDocumentClick,
                    onFolderExpand = onFolderExpand,
                    onFolderCollapse = onFolderCollapse,
                    actions = actions
                )
            }
        }
    }
}

@Composable
fun FolderRow(
    folder: Folder,
    level: Int,
    isExpanded: Boolean,
    hasChildren: Boolean,
    onToggleExpand: () -> Unit,
    actions: SidebarActions?
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .padding(start = (level * 16).dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 展开/收起图标
        if (hasChildren) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(modifier = Modifier.width(20.dp))
        }

        Spacer(modifier = Modifier.width(4.dp))

        // 文件夹图标
        Icon(
            imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 文件夹名称
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 更多菜单（纯浏览模式不显示）
        if (actions != null) {
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.menu_file),
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_document)) },
                        onClick = {
                            actions.onCreateDocument(folder.id)
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Add, null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_subfolder)) },
                        onClick = {
                            actions.onCreateSubfolder(folder.id)
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.CreateNewFolder, null)
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        onClick = {
                            actions.onRenameFolder(folder.id)
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_folder)) },
                        onClick = {
                            actions.onDeleteFolder(folder.id)
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DocumentRow(
    document: Document,
    level: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(
                start = (level * 16 + 24).dp,
                end = 8.dp,
                top = 6.dp,
                bottom = 6.dp
            )
            .then(
                if (isSelected) Modifier.padding(start = 4.dp)
                else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 选中指示器
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .padding(end = 4.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary
                ) {}
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        // 文档图标
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 文档名称
        Text(
            text = document.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 收藏图标
        if (document.isFavorite) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // 更多菜单（重命名/删除；纯浏览模式不显示）
        if (onRename != null || onDelete != null) {
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.menu_file),
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (onRename != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = { showMenu = false; onRename() },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                    }
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = { showMenu = false; onDelete() },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
