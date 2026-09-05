package com.yumark.app.presentation.sidebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppMotion
import com.yumark.app.presentation.theme.AppSpacing
import com.yumark.app.presentation.theme.extendedColors

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
        contentPadding = PaddingValues(vertical = AppSpacing.Default)
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
                .padding(start = WorkspaceTreeMetrics.IndentPerLevel * level, end = AppSpacing.Tight, top = AppSpacing.Snug, bottom = AppSpacing.Snug),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(AppIconSize.Medium),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(AppSpacing.Tight))
            Icon(
                imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(AppIconSize.Medium),
                tint = extendedColors.primaryText
            )
            Spacer(modifier = Modifier.width(AppSpacing.Default))
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
            exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
        ) {
            Column {
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
            .padding(start = WorkspaceTreeMetrics.IndentPerLevel * level + WorkspaceTreeMetrics.DocRowExtraIndent, end = AppSpacing.Default, top = AppSpacing.Snug, bottom = AppSpacing.Snug),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(AppIconSize.Small),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(AppSpacing.Default))
        Text(
            text = doc.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 文件树特有的缩进几何，刻意不并入全局 [AppSpacing]：按层级递增的水平缩进步长与文档行的
 * 附加缩进，是树结构专属的布局度量而非通用间距节奏，保留原像素。按 FileListMetrics 先例落屏幕局部。
 */
private object WorkspaceTreeMetrics {
    /** 每下探一层的水平缩进步长；实际缩进 = 本值 × level。 */
    val IndentPerLevel = 16.dp
    /** 文档行相对同级文件夹的额外缩进：让文档图标对齐到文件夹名起始列（让出 chevron+图标列宽）。 */
    val DocRowExtraIndent = 24.dp
}
