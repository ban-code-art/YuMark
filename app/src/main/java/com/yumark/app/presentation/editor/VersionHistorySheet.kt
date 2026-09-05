package com.yumark.app.presentation.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.core.util.diff.DiffOp
import com.yumark.app.core.util.diff.LineDiffer
import com.yumark.app.domain.model.DocumentVersion
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppMotion
import com.yumark.app.presentation.theme.AppSpacing
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
        Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Screen).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = AppSpacing.Default)) {
                Icon(Icons.Default.History, null)
                Spacer(Modifier.width(AppSpacing.Default))
                // 标题复用 strings.xml 里侧栏/菜单同名入口的键，避免同一文案两份
                Text(stringResource(R.string.history_versions), style = MaterialTheme.typography.titleLarge)
            }

            if (versions.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(AppSpacing.Section), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.version_history_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = VersionHistoryMetrics.ListMaxHeight)) {
                    items(versions, key = { it.id }) { version ->
                        VersionRow(version, currentContent) { pendingRestore = version }
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(AppSpacing.Default))
        }
    }

    pendingRestore?.let { v ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.version_history_restore_title)) },
            text = {
                Text(stringResource(R.string.version_history_restore_message, formatTime(v.createdAt)))
            },
            confirmButton = {
                TextButton(onClick = { onRestore(v); pendingRestore = null; onDismiss() }) {
                    Text(stringResource(R.string.version_history_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text(stringResource(R.string.cancel)) }
            }
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
    // 展开/收起提示单独一个键：整句由 version_history_row_meta 拼，英文语序可自行调整
    val toggleHint = stringResource(
        if (expanded) R.string.version_history_collapse else R.string.version_history_expand
    )
    Column(Modifier.fillMaxWidth().padding(vertical = AppSpacing.Default)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                Text(formatTime(version.createdAt), style = MaterialTheme.typography.bodyMedium)
                Text(
                    // wordCount 传两次：第一个实参选 quantity 分支（英文 1 → "1 word"），
                    // 第二个填 %1$d；toggleHint 填 %2$s。
                    pluralStringResource(
                        R.plurals.version_history_row_meta,
                        version.wordCount,
                        version.wordCount,
                        toggleHint
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRestore) {
                Icon(Icons.Default.Restore, null, Modifier.size(AppIconSize.Small))
                Spacer(Modifier.width(AppSpacing.Tight))
                Text(stringResource(R.string.version_history_restore))
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
            exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
        ) {
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
            stringResource(R.string.version_history_no_diff),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AppSpacing.Tight)
        )
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.Snug)
    ) {
        Column(
            Modifier.heightIn(max = VersionHistoryMetrics.DiffMaxHeight)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(AppSpacing.Default)
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
                    modifier = Modifier.background(bg).padding(horizontal = AppSpacing.Tight)
                )
            }
        }
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

/**
 * 本弹层特有的两处布局上界，刻意不并入全局 [AppSpacing]：均为「最大可视高度」（超出内部滚动），
 * 属布局上界而非间距节奏，保留原像素、不硬凑标度。按 FileListMetrics 先例落屏幕局部。
 */
private object VersionHistoryMetrics {
    /** 版本列表最大高度：超出滚动，避免快照多时把弹层顶满。 */
    val ListMaxHeight = 480.dp
    /** 展开后只读 diff 的最大高度：超出内部纵/横向滚动。 */
    val DiffMaxHeight = 260.dp
}
