package com.yumark.app.presentation.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.domain.model.Folder

/**
 * 「移动到…」目标文件夹选择器。
 * 顶部为「根目录」(targetFolderId = null),其下为全部文件夹(按完整路径排序、按层级缩进)。
 * [disabledFolderIds] 内的项置灰不可选(文件夹移动时=自身及其所有后代,防止形成环)。
 * [rootEnabled]=false 时置灰根目录行(用于被移动对象当前就在根目录、重选等同 no-op 的场景)。
 */
@Composable
fun MoveToFolderDialog(
    title: String,
    folders: List<Folder>,
    disabledFolderIds: Set<String> = emptySet(),
    rootEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onPick: (targetFolderId: String?) -> Unit
) {
    val rows = remember(folders) {
        val byId = folders.associateBy { it.id }
        fun depthOf(f: Folder): Int {
            var d = 0; var p = f.parentId; var guard = 0
            while (p != null && guard++ < 100) { d++; p = byId[p]?.parentId }
            return d
        }
        fun pathOf(f: Folder): String {
            val names = ArrayDeque<String>()
            var cur: Folder? = f; var guard = 0
            while (cur != null && guard++ < 100) {
                names.addFirst(cur.name)
                cur = cur.parentId?.let { byId[it] }
            }
            return names.joinToString(" / ")
        }
        // 按完整路径排序 → 父文件夹排在其子文件夹之前
        folders.map { Triple(it, depthOf(it), pathOf(it)) }.sortedBy { it.third }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                item {
                    MoveTargetRow(
                        label = stringResource(R.string.move_to_root),
                        depth = 0,
                        enabled = rootEnabled,
                        icon = Icons.Default.Home,
                        onClick = { if (rootEnabled) onPick(null) }
                    )
                }
                items(rows) { (folder, depth, _) ->
                    val enabled = folder.id !in disabledFolderIds
                    MoveTargetRow(
                        label = folder.name,
                        depth = depth + 1,
                        enabled = enabled,
                        icon = Icons.Default.Folder,
                        onClick = { if (enabled) onPick(folder.id) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun MoveTargetRow(
    label: String,
    depth: Int,
    enabled: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = (depth * 16).dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary else contentColor
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 某文件夹自身 + 其所有后代的 id 集合(移动文件夹时作为禁选目标,避免移进自己的子树)。 */
fun selfAndDescendantFolderIds(folders: List<Folder>, folderId: String): Set<String> {
    val childrenByParent = folders.groupBy { it.parentId }
    val result = mutableSetOf(folderId)
    val queue = ArrayDeque<String>().apply { add(folderId) }
    var guard = 0
    while (queue.isNotEmpty() && guard++ < 10_000) {
        val cur = queue.removeFirst()
        childrenByParent[cur]?.forEach { child ->
            if (result.add(child.id)) queue.add(child.id)
        }
    }
    return result
}
