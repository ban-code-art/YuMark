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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.yumark.app.core.util.diff.DiffLine
import com.yumark.app.core.util.diff.DiffOp
import com.yumark.app.core.util.diff.DiffResult

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
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
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
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    ) {
        Checkbox(checked = accepted, onCheckedChange = { onToggle() })
        Text(
            text = if (accepted) "应用改动 #${index + 1}" else "保留原文 #${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DiffLineRow(line: DiffLine, accepted: List<Boolean>) {
    val isAccepted = line.hunkId == DiffLine.NO_HUNK || accepted.getOrElse(line.hunkId) { true }
    val bg = when (line.op) {
        DiffOp.ADDED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isAccepted) 0.45f else 0.12f)
        DiffOp.REMOVED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = if (!isAccepted) 0.45f else 0.12f)
        DiffOp.UNCHANGED -> Color.Transparent
    }
    // 左侧色条强化“增/删”语义：新增=primary，删除=error，未变=无。
    val bar = when (line.op) {
        DiffOp.ADDED -> MaterialTheme.colorScheme.primary
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
        Box(Modifier.width(3.dp).fillMaxHeight().background(bar))
        Text(
            text = prefix + line.text.ifEmpty { " " },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (line.op == DiffOp.UNCHANGED) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 1.dp)
        )
    }
}
