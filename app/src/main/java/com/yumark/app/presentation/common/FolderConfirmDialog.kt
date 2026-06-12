package com.yumark.app.presentation.common

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.documentfile.provider.DocumentFile
import com.yumark.app.R

/**
 * 「选完文件夹回显名称确认」对话框：打开工作区 / 导入文件夹 / 设置默认目录共用。
 * 解析所选树 URI 的显示名填入 messageRes（需含 %1$s 占位符）；
 * 持久授权等副作用由调用方在 [onConfirm] 里完成。
 */
@Composable
fun FolderConfirmDialog(
    uri: Uri,
    @StringRes titleRes: Int,
    @StringRes messageRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val fallbackName = stringResource(R.string.default_dir_picked_fallback)
    val name = remember(uri, fallbackName) {
        DocumentFile.fromTreeUri(context, uri)?.name
            ?: uri.lastPathSegment ?: fallbackName
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(messageRes, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
