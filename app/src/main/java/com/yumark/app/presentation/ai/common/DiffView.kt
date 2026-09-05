package com.yumark.app.presentation.ai.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.core.util.diff.DiffLine
import com.yumark.app.core.util.diff.DiffOp
import com.yumark.app.core.util.diff.DiffResult
import com.yumark.app.presentation.theme.AppSpacing
import com.yumark.app.presentation.theme.extendedColors

/**
 * 行级 diff 视图：高亮增删行，每个变更块(hunk)前带一个勾选框控制接受/拒绝。
 * 接受态由调用方持有（[accepted] 与 [DiffResult.hunks] 对齐），通过 [onToggleHunk] 翻转。
 * 未接受的改动以删除线/淡化提示"不会应用"。
 */
@Composable
fun DiffView(
    result: DiffResult,
    accepted: List<Boolean>,
    onToggleHunk: (hunkId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .heightIn(max = DiffViewMetrics.ListMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(vertical = AppSpacing.Tight)
        ) {
            var lastHunk = DiffLine.NO_HUNK
            result.lines.forEach { line ->
                if (line.hunkId != DiffLine.NO_HUNK && line.hunkId != lastHunk) {
                    HunkToggle(
                        index = line.hunkId,
                        accepted = accepted.getOrElse(line.hunkId) { true },
                        onToggle = { onToggleHunk(line.hunkId) }
                    )
                }
                lastHunk = line.hunkId
                DiffLineRow(line, accepted)
            }
        }
    }
}

@Composable
private fun HunkToggle(index: Int, accepted: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.Snug)
    ) {
        Checkbox(checked = accepted, onCheckedChange = { onToggle() })
        Text(
            // hunk 序号从 1 起（index 是 0 基）。序号不是数量词，所以是普通 <string> 而非 plurals
            text = stringResource(
                if (accepted) R.string.ai_diff_hunk_apply else R.string.ai_diff_hunk_keep,
                index + 1
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DiffLineRow(line: DiffLine, accepted: List<Boolean>) {
    val isAccepted = line.hunkId == DiffLine.NO_HUNK || accepted.getOrElse(line.hunkId) { true }
    val bg = when (line.op) {
        DiffOp.ADDED -> extendedColors.successContainer.copy(alpha = if (isAccepted) 0.45f else 0.12f)
        DiffOp.REMOVED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = if (!isAccepted) 0.45f else 0.12f)
        DiffOp.UNCHANGED -> Color.Transparent
    }
    // 左侧色条强化“增/删”语义：新增=success（绿），删除=error（红），未变=无。
    val bar = when (line.op) {
        DiffOp.ADDED -> extendedColors.success
        DiffOp.REMOVED -> MaterialTheme.colorScheme.error
        DiffOp.UNCHANGED -> Color.Transparent
    }
    val prefix = when (line.op) {
        DiffOp.ADDED -> "+ "
        DiffOp.REMOVED -> "− "
        DiffOp.UNCHANGED -> "  "
    }
    // 删除线表示"不会进入最终文本"：被拒绝的新增、被接受的删除
    val strike = (line.op == DiffOp.ADDED && !isAccepted) || (line.op == DiffOp.REMOVED && isAccepted)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(bg)
    ) {
        Box(Modifier.width(DiffViewMetrics.BarWidth).fillMaxHeight().background(bar))
        Text(
            text = prefix + line.text.ifEmpty { " " },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (line.op == DiffOp.UNCHANGED) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AppSpacing.Default, vertical = DiffViewMetrics.LineVerticalPadding)
        )
    }
}

/**
 * 本 diff 视图特有的绘制度量，刻意不并入全局 [AppSpacing]：滚动区最大高度上界（超出内部滚动）、
 * 左侧增删色条宽度（3dp 绘制轴）、等宽代码行的纵向内边距（1dp，紧到贴合行距）。各自成轴、
 * 离散于 8dp 间距标度，保留原像素、不硬凑。按 FileListMetrics 先例落屏幕局部。
 */
private object DiffViewMetrics {
    /** diff 滚动区最大高度：超出内部滚动，避免长 diff 把气泡 / 弹层顶满。 */
    val ListMaxHeight = 320.dp
    /** 行左侧增/删语义色条宽度：细窄竖条，非间距轴。 */
    val BarWidth = 3.dp
    /** 等宽代码行的纵向内边距：紧贴行距、密排 diff，off-grid。 */
    val LineVerticalPadding = 1.dp
}
