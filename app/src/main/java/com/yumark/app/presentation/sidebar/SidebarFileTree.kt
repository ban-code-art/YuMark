package com.yumark.app.presentation.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.FolderTreeNode

@Composable
fun SidebarFileTree(
    tree: List<FolderTreeNode>,
    currentDocumentId: String?,
    expandedFolders: Set<String>,
    onDocumentClick: (String) -> Unit,
    onFolderExpand: (String) -> Unit,
    onFolderCollapse: (String) -> Unit,
    onCreateDocument: (String?) -> Unit,
    onCreateSubfolder: (String?) -> Unit,
    onRenameFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
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
                onCreateDocument = onCreateDocument,
                onCreateSubfolder = onCreateSubfolder,
                onRenameFolder = onRenameFolder,
                onDeleteFolder = onDeleteFolder
            )
        }
    }
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
    onCreateDocument: (String?) -> Unit,
    onCreateSubfolder: (String?) -> Unit,
    onRenameFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit
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
                onCreateDocument = { onCreateDocument(folder.id) },
                onCreateSubfolder = { onCreateSubfolder(folder.id) },
                onRename = { onRenameFolder(folder.id) },
                onDelete = { onDeleteFolder(folder.id) }
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
                    onClick = { onDocumentClick(doc.id) }
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
                    onCreateDocument = onCreateDocument,
                    onCreateSubfolder = onCreateSubfolder,
                    onRenameFolder = onRenameFolder,
                    onDeleteFolder = onDeleteFolder
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
    onCreateDocument: () -> Unit,
    onCreateSubfolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
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

        // 更多菜单
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
                        onCreateDocument()
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Add, null)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.create_subfolder)) },
                    onClick = {
                        onCreateSubfolder()
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
                        onRename()
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, null)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete_folder)) },
                    onClick = {
                        onDelete()
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

@Composable
fun DocumentRow(
    document: Document,
    level: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            tint = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 文档名称
        Text(
            text = document.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
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
    }
}
