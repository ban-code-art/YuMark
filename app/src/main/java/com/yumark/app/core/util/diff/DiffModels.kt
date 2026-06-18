package com.yumark.app.core.util.diff

/** 一行 diff 的操作类型。 */
enum class DiffOp { UNCHANGED, ADDED, REMOVED }

/**
 * diff 的一行。[hunkId] 指向所属变更块；UNCHANGED 行为 [NO_HUNK]。
 */
data class DiffLine(
    val op: DiffOp,
    val text: String,
    val hunkId: Int = NO_HUNK
) {
    companion object {
        const val NO_HUNK = -1
    }
}

/**
 * 一个连续变更块：相邻的 REMOVED/ADDED 行聚为一块，被 UNCHANGED 打断即分块。
 * 纯增（[removed] 空）、纯删（[added] 空）、改（两者皆非空）。
 * 逐 hunk 接受/拒绝以本块为单位。
 */
data class DiffHunk(
    val id: Int,
    val removed: List<String>,
    val added: List<String>
)

/**
 * 行级 diff 结果。
 * - [lines] 有序，用于渲染（含 UNCHANGED）。
 * - [hunks] 变更块，用于逐块接受/拒绝与合成。
 * - [degraded] 为 true 表示文档过大、已降级为单块整体对照（见 [LineDiffer]）。
 */
data class DiffResult(
    val lines: List<DiffLine>,
    val hunks: List<DiffHunk>,
    val degraded: Boolean = false
) {
    val hasChanges: Boolean get() = hunks.isNotEmpty()
}
