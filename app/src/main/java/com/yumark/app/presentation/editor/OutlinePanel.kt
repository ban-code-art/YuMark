package com.yumark.app.presentation.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.domain.model.OutlineItem
import com.yumark.app.presentation.theme.AppSpacing

@Composable
fun OutlinePanel(
    outline: List<OutlineItem>,
    onItemClick: (OutlineItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxHeight()) {
        Text(
            text = stringResource(R.string.outline),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(AppSpacing.Screen)
        )
        HorizontalDivider()
        if (outline.isEmpty()) {
            Text(
                text = stringResource(R.string.outline_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(AppSpacing.Screen)
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = AppSpacing.Default)) {
                items(outline) { item ->
                    Text(
                        text = item.text,
                        style = if (item.level <= 1) {
                            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onItemClick(item) }
                            .padding(
                                start = AppSpacing.Screen + OutlineMetrics.IndentPerLevel * (item.level - 1),
                                end = AppSpacing.Screen,
                                top = OutlineMetrics.RowVerticalPadding,
                                bottom = OutlineMetrics.RowVerticalPadding
                            )
                    )
                }
            }
        }
    }
}

/**
 * 本面板特有的布局度量，刻意不并入全局 [AppSpacing]：大纲每级标题的水平缩进步长、条目行纵向
 * 内边距（10dp 行高节拍，介于 Default(8) 与 Cozy(12)），均离散于 8dp 间距标度，保留原像素、
 * 不硬凑。缩进基准仍复用 [AppSpacing.Screen]，令条目左缘与标题文字对齐。按 WorkspaceTreeMetrics 先例落屏幕局部。
 */
private object OutlineMetrics {
    /** 每下探一级标题的水平缩进步长；实际起始缩进 = Screen + 本值 × (level - 1)。 */
    val IndentPerLevel = 16.dp
    /** 大纲条目行纵向内边距：撑出可点击行高，长标题两行也不至于贴死。 */
    val RowVerticalPadding = 10.dp
}
