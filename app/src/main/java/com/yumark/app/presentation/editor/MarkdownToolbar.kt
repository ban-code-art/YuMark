package com.yumark.app.presentation.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yumark.app.R
import com.yumark.app.core.text.MarkdownAction
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppShapes
import com.yumark.app.presentation.theme.AppSpacing
import com.yumark.app.presentation.theme.extendedColors

/**
 * 编辑器底部工具栏：撤销/重做、查找替换开关、Markdown 语法插入。
 *
 * 撤销/重做与查找相关参数全部带默认值，是为了让「只插入语法」的调用方不受影响；
 * 撤销栈本身不在这里持有——它必须和 `editValue` 同生命周期，只能由 EditorScreen 管。
 *
 * @param onAction 用户点了哪个语法按钮。传枚举而不是语法字面量：具体插什么、光标落哪
 *   全在 [MarkdownAction.applyTo] 一处定义（纯函数、可单测），本文件只管图标和文案。
 * @param canUndo/canRedo 由调用方从可观察状态传入。直接读 `UndoRedoStack.canUndo` 是个坑：
 *   它是普通 Kotlin 属性，变化不触发重组，按钮会永远停在初始的禁用态。
 * @param findReplaceActive 查找栏是否已展开，用于把「查找」按钮标成高亮。
 */
@Composable
fun MarkdownToolbar(
    onAction: (MarkdownAction) -> Unit,
    modifier: Modifier = Modifier,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onToggleFindReplace: () -> Unit = {},
    findReplaceActive: Boolean = false
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = MarkdownToolbarMetrics.SurfaceElevation
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.Default, vertical = AppSpacing.Tight),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Tight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 撤销/重做排在最左：误点了语法插入按钮，手指最自然的下一步就是往左找撤销。
            // 文案与其余按钮一样走资源：编辑器模块的键放在 strings_editor.xml（与 strings.xml 同一
            // R.string 命名空间，分片只是为了并行改文案不冲突）。
            ToolbarButton(
                icon = Icons.AutoMirrored.Filled.Undo,
                label = stringResource(R.string.toolbar_undo),
                onClick = onUndo,
                enabled = canUndo
            )

            ToolbarButton(
                icon = Icons.AutoMirrored.Filled.Redo,
                label = stringResource(R.string.toolbar_redo),
                onClick = onRedo,
                enabled = canRedo
            )

            ToolbarButton(
                icon = Icons.Default.FindReplace,
                label = stringResource(R.string.toolbar_find),
                onClick = onToggleFindReplace,
                highlighted = findReplaceActive
            )

            VerticalDivider(modifier = Modifier.height(MarkdownToolbarMetrics.DividerHeight))

            ToolbarButton(
                icon = Icons.Default.Title,
                label = stringResource(R.string.toolbar_heading),
                onClick = { onAction(MarkdownAction.HEADING) }
            )

            ToolbarButton(
                icon = Icons.Default.FormatBold,
                label = stringResource(R.string.toolbar_bold),
                onClick = { onAction(MarkdownAction.BOLD) }
            )

            ToolbarButton(
                icon = Icons.Default.FormatItalic,
                label = stringResource(R.string.toolbar_italic),
                onClick = { onAction(MarkdownAction.ITALIC) }
            )

            ToolbarButton(
                icon = Icons.Default.FormatStrikethrough,
                label = stringResource(R.string.toolbar_strikethrough),
                onClick = { onAction(MarkdownAction.STRIKETHROUGH) }
            )

            VerticalDivider(modifier = Modifier.height(MarkdownToolbarMetrics.DividerHeight))

            ToolbarButton(
                icon = Icons.Default.Link,
                label = stringResource(R.string.toolbar_link),
                onClick = { onAction(MarkdownAction.LINK) }
            )

            // 唯一一个不直接落语法的按钮：EditorScreen 收到 IMAGE 后先弹来源选择
            // （相册 → 复制进应用私有 images/ → 写回 `images/<uuid>.<ext>` 相对路径，
            // 或手填地址走原来的 `![](url)` 壳子）。外部 SAF 文档没有 Room 行，
            // images.document_id 这个外键挂不上，那时会直接走手填，不弹选择。
            ToolbarButton(
                icon = Icons.Default.Image,
                label = stringResource(R.string.toolbar_image),
                onClick = { onAction(MarkdownAction.IMAGE) }
            )

            ToolbarButton(
                icon = Icons.Default.Code,
                label = stringResource(R.string.toolbar_code),
                onClick = { onAction(MarkdownAction.CODE) }
            )

            // 行内代码用 `</>`、代码块用 `{ }`：两个按钮挨着放，图标必须一眼能分开
            ToolbarButton(
                icon = Icons.Default.DataObject,
                label = stringResource(R.string.toolbar_code_block),
                onClick = { onAction(MarkdownAction.CODE_BLOCK) }
            )

            VerticalDivider(modifier = Modifier.height(MarkdownToolbarMetrics.DividerHeight))

            ToolbarButton(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                label = stringResource(R.string.toolbar_list),
                onClick = { onAction(MarkdownAction.BULLET_LIST) }
            )

            ToolbarButton(
                icon = Icons.Default.FormatListNumbered,
                label = stringResource(R.string.toolbar_numbered_list),
                onClick = { onAction(MarkdownAction.NUMBERED_LIST) }
            )

            ToolbarButton(
                icon = Icons.Default.FormatQuote,
                label = stringResource(R.string.toolbar_quote),
                onClick = { onAction(MarkdownAction.QUOTE) }
            )

            ToolbarButton(
                icon = Icons.Default.TableChart,
                label = stringResource(R.string.toolbar_table),
                onClick = { onAction(MarkdownAction.TABLE) }
            )

            ToolbarButton(
                icon = Icons.Default.HorizontalRule,
                label = stringResource(R.string.toolbar_horizontal_rule),
                onClick = { onAction(MarkdownAction.HORIZONTAL_RULE) }
            )
        }
    }
}

/**
 * 工具栏单个按钮：图标 + 图标下面那行小字。
 *
 * 整列可点，而不是「IconButton 可点、下面的字只是装饰」。两个原因：
 *
 * 1. **触摸区**。IconButton 内部虽然带 `minimumInteractiveComponentSize()`（最小 48dp），但那个
 *    修饰符只能在约束允许时把自己撑大——外面套一个 `.size(36.dp)` 就把约束钉死成 36dp，撑不动，
 *    实际触摸区就是 36dp × 36dp，低于 48dp 的最小可触摸尺寸。整列本来就有 56dp 宽、48dp 高，
 *    改成整列可点，像素一个没动，触摸区直接合规，而且点那行字也能触发——用户本来就会去点字。
 * 2. **无障碍树**。从前是「IconButton（可激活，名字来自 contentDescription）+ 一个独立的
 *    Text 节点」，读屏会把同一个按钮的名字念两遍。现在 clickable 合并子节点，图标的
 *    contentDescription 置空，名字只由 Text 提供，一个按钮就是一个节点、念一遍。
 *    这和设置页主题单选行用 `selectable` 的理由是同一个。
 *
 * 禁用态也一并交给 clickable：从前 `enabled` 只灰了 IconButton 内部的图标，得手动再把标签
 * 调灰（漏了就是「图标灰了、字还亮着」），而整列没有 disabled 语义，读屏读不出「不可用」。
 */
@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlighted: Boolean = false
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        highlighted -> extendedColors.primaryText
        else -> LocalContentColor.current
    }
    Column(
        modifier = Modifier
            .width(MarkdownToolbarMetrics.ButtonWidth)
            .heightIn(min = AppSpacing.MinTouchTarget)
            // 先 clip 再 clickable：涟漪要被圆角裁住，否则整块方形涟漪贴着邻居按钮
            .clip(RoundedCornerShape(AppShapes.Small))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = AppSpacing.Tight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            // 名字由下面的 Text 给（见函数注释第 2 条）
            contentDescription = null,
            modifier = Modifier.size(AppIconSize.Medium),
            tint = contentColor
        )
        Spacer(modifier = Modifier.height(AppSpacing.Micro))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = MarkdownToolbarMetrics.LabelFontSize,
            maxLines = 1,
            color = contentColor
        )
    }
}

/**
 * 本工具栏特有的组件尺寸 / 排版，刻意不并入全局 [AppSpacing] / [AppIconSize]：
 * 均为离散于间距/图标标度的组件几何——按钮列宽、分隔线目视高度、Surface 色调高程（M3 Level 2），
 * 以及把 labelSmall 压到 10sp 以在 56dp 窄列里单行不换行。保留原像素，不硬凑标度。
 * 按 FileListMetrics 先例落屏幕局部。
 */
private object MarkdownToolbarMetrics {
    /** Surface 色调高程：M3 tonal elevation Level 2，非间距轴。 */
    val SurfaceElevation = 2.dp
    /** 分组分隔竖线目视高度：短于按钮列（48dp）才不顶满，是组件高度而非 Section 间距。 */
    val DividerHeight = 32.dp
    /** 单个按钮列宽：容纳图标 + 单行标签。 */
    val ButtonWidth = 56.dp
    /** 标签字号：压到 10sp 才能在 [ButtonWidth] 窄列里单行不换行（labelSmall 默认约 11sp 会溢出）。 */
    val LabelFontSize = 10.sp
}
