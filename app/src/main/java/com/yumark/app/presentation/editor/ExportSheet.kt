package com.yumark.app.presentation.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.domain.model.ExportFormat

/**
 * 导出底部弹层：把原先平铺在「更多」菜单里的多种导出格式收纳到一处，
 * 每行一种格式（图标 + 名称 + 说明）。选中即触发导出并关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    onExport: (ExportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export_document), style = MaterialTheme.typography.titleLarge)
            }

            ExportFormatRow(
                icon = Icons.Default.Description,
                titleRes = R.string.export_markdown,
                descRes = R.string.export_markdown_desc
            ) { onExport(ExportFormat.MARKDOWN) }

            ExportFormatRow(
                icon = Icons.Default.Code,
                titleRes = R.string.export_html,
                descRes = R.string.export_html_desc
            ) { onExport(ExportFormat.HTML) }

            ExportFormatRow(
                icon = Icons.Default.PictureAsPdf,
                titleRes = R.string.export_pdf,
                descRes = R.string.export_pdf_desc
            ) { onExport(ExportFormat.PDF) }

            ExportFormatRow(
                icon = Icons.Default.Article,
                titleRes = R.string.export_word,
                descRes = R.string.export_word_desc
            ) { onExport(ExportFormat.WORD) }

            ExportFormatRow(
                icon = Icons.Default.Image,
                titleRes = R.string.export_image,
                descRes = R.string.export_image_desc
            ) { onExport(ExportFormat.IMAGE) }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ExportFormatRow(
    icon: ImageVector,
    titleRes: Int,
    descRes: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
