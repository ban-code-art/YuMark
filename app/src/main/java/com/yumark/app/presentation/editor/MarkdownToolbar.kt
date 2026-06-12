package com.yumark.app.presentation.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yumark.app.R

@Composable
fun MarkdownToolbar(
    onInsertSyntax: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarButton(
                icon = Icons.Default.Title,
                label = stringResource(R.string.toolbar_heading),
                onClick = { onInsertSyntax("# ") }
            )

            ToolbarButton(
                icon = Icons.Default.FormatBold,
                label = stringResource(R.string.toolbar_bold),
                onClick = { onInsertSyntax("****") }
            )

            ToolbarButton(
                icon = Icons.Default.FormatItalic,
                label = stringResource(R.string.toolbar_italic),
                onClick = { onInsertSyntax("**") }
            )

            VerticalDivider(modifier = Modifier.height(32.dp))

            ToolbarButton(
                icon = Icons.Default.Link,
                label = stringResource(R.string.toolbar_link),
                onClick = { onInsertSyntax("[](url)") }
            )

            ToolbarButton(
                icon = Icons.Default.Code,
                label = stringResource(R.string.toolbar_code),
                onClick = { onInsertSyntax("``") }
            )

            VerticalDivider(modifier = Modifier.height(32.dp))

            ToolbarButton(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                label = stringResource(R.string.toolbar_list),
                onClick = { onInsertSyntax("- ") }
            )

            ToolbarButton(
                icon = Icons.Default.FormatListNumbered,
                label = stringResource(R.string.toolbar_numbered_list),
                onClick = { onInsertSyntax("1. ") }
            )

            ToolbarButton(
                icon = Icons.Default.FormatQuote,
                label = stringResource(R.string.toolbar_quote),
                onClick = { onInsertSyntax("> ") }
            )

            ToolbarButton(
                icon = Icons.Default.TableChart,
                label = stringResource(R.string.toolbar_table),
                onClick = { onInsertSyntax("\n| 列1 | 列2 |\n|-----|-----|\n| 内容 | 内容 |\n") }
            )
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(56.dp)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}
