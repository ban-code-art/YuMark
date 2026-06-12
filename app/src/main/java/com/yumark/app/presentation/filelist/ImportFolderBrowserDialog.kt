package com.yumark.app.presentation.filelist

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.yumark.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导入文件夹的应用内浏览器：系统选择器只负责授权一个入口文件夹，
 * 之后在这里逐层进入子文件夹，点「导入此文件夹」才确认。
 *
 * 之所以不依赖系统选择器逐层导航：部分 ROM 的选择器「点进文件夹即视为选中并返回」，
 * 用户根本到不了深层目录；应用内浏览不受 ROM 行为影响。
 */
@Composable
fun ImportFolderBrowserDialog(
    treeUri: Uri,
    onConfirm: (relativePath: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var path by remember { mutableStateOf(listOf<String>()) }
    // null = 正在加载当前层
    var subDirs by remember { mutableStateOf<List<String>?>(null) }

    val rootName = remember(treeUri) {
        DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: context.getString(R.string.default_dir_picked_fallback)
    }

    LaunchedEffect(treeUri, path) {
        subDirs = null
        subDirs = withContext(Dispatchers.IO) {
            runCatching {
                var dir = DocumentFile.fromTreeUri(context, treeUri)
                for (segment in path) {
                    dir = dir?.findFile(segment)?.takeIf { it.isDirectory }
                }
                dir?.listFiles()
                    ?.filter { it.isDirectory && it.name?.startsWith(".") != true }
                    ?.mapNotNull { it.name }
                    ?.sorted()
            }.getOrNull() ?: emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_browser_title)) },
        text = {
            Column {
                // 当前位置（根名 / 子路径链）
                Text(
                    (listOf(rootName) + path).joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalDivider()

                // 返回上一级
                if (path.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { path = path.dropLast(1) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.import_browser_up),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                val dirs = subDirs
                when {
                    dirs == null -> LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    )
                    dirs.isEmpty() -> Text(
                        stringResource(R.string.import_browser_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(dirs, key = { it }) { name ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { path = path + name }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(path) }) {
                Text(stringResource(R.string.import_browser_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
