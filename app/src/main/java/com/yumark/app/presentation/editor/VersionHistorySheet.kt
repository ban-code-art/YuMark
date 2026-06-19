package com.yumark.app.presentation.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yumark.app.core.util.diff.DiffOp
import com.yumark.app.core.util.diff.LineDiffer
import com.yumark.app.domain.model.DocumentVersion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文档历史版本底部弹层：按时间倒序列出快照，可展开查看与当前内容的逐行差异，并恢复到该版本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionHistorySheet(
    versions: List<DocumentVersion>,
    currentContent: String,
    onRestore: (DocumentVersion) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingRestore by remember { mutableStateOf<DocumentVersion?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(Icons.Default.History, null)
                Spacer(Modifier.width(8.dp))
                Text("历史版本", style = MaterialTheme.typography.titleLarge)
            }

            if (versions.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("暂无历史版本——编辑并保存后会自动记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(versions, key = { it.id }) { version ->
                        VersionRow(version, currentContent) { pendingRestore = version }
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    pendingRestore?.let { v ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("恢复此版本") },
            text = { Text("将文档内容恢复到「${formatTime(v.createdAt)}」的版本？当前内容会先存为一条历史，可再恢复回来。") },
            confirmButton = {
                TextButton(onClick = { onRestore(v); pendingRestore = null; onDismiss() }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun VersionRow(
    version: DocumentVersion,
    currentContent: String,
    onRestore: () -> Unit
) {
    var expanded by remember(version.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                Text(formatTime(version.createdAt), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${version.wordCount} 字 · 点按${if (expanded) "收起" else "查看改动"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRestore) {
                Icon(Icons.Default.Restore, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("恢复")
            }
        }
        if (expanded) {
            ReadOnlyDiff(oldContent = version.content, newContent = currentContent)
        }
    }
}

/** 只读逐行 diff（该版本 → 当前）：绿=当前新增，红=该版本中已删除。 */
@Composable
private fun ReadOnlyDiff(oldContent: String, newContent: String) {
    val diff = remember(oldContent, newContent) { LineDiffer.diff(oldContent, newContent) }
    if (!diff.hasChanges) {
        Text(
            "与当前内容一致。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    ) {
        Column(
            Modifier.heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            diff.lines.forEach { line ->
                val (bg, prefix) = when (line.op) {
                    DiffOp.ADDED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) to "+ "
                    DiffOp.REMOVED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f) to "− "
                    DiffOp.UNCHANGED -> androidx.compose.ui.graphics.Color.Transparent to "  "
                }
                Text(
                    prefix + line.text.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (line.op == DiffOp.UNCHANGED) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.background(bg).padding(horizontal = 4.dp)
                )
            }
        }
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
