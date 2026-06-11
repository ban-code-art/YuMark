package com.yumark.app.presentation.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yumark.app.domain.model.WorkspaceDoc
import com.yumark.app.domain.model.WorkspaceNode

/**
 * 外部工作区文件树（结构只读：不提供新建/重命名/删除）
 */
@Composable
fun WorkspaceFileTree(
    root: WorkspaceNode,
    expandedFolders: Set<String>,
    onDocumentClick: (WorkspaceDoc) -> Unit,
    onFolderToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(root.docs, key = { it.uri }) { doc ->
            WorkspaceDocRow(doc = doc, level = 0, onClick = { onDocumentClick(doc) })
        }
        items(root.folders, key = { it.uri }) { folder ->
            WorkspaceFolderItem(
                node = folder,
                level = 0,
                expandedFolders = expandedFolders,
                onDocumentClick = onDocumentClick,
                onFolderToggle = onFolderToggle
            )
        }
    }
}

@Composable
private fun WorkspaceFolderItem(
    node: WorkspaceNode,
    level: Int,
    expandedFolders: Set<String>,
    onDocumentClick: (WorkspaceDoc) -> Unit,
    onFolderToggle: (String) -> Unit
) {
    val isExpanded = expandedFolders.contains(node.uri)

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onFolderToggle(node.uri) }
                .padding(start = (level * 16).dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isExpanded) {
            node.docs.forEach { doc ->
                WorkspaceDocRow(doc = doc, level = level + 1, onClick = { onDocumentClick(doc) })
            }
            node.folders.forEach { child ->
                WorkspaceFolderItem(
                    node = child,
                    level = level + 1,
                    expandedFolders = expandedFolders,
                    onDocumentClick = onDocumentClick,
                    onFolderToggle = onFolderToggle
                )
            }
        }
    }
}

@Composable
private fun WorkspaceDocRow(
    doc: WorkspaceDoc,
    level: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (level * 16 + 24).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = doc.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
