package com.yumark.app.core.util.diff

/**
 * 行级 diff（LCS）。把 old/new 按行对比，产出可逐块审阅的 [DiffResult]。
 * 纯函数、无 Android 依赖，便于单测。
 *
 * 不变式（[DiffComposer] 依赖）：[DiffResult.lines] 丢弃 ADDED 行即还原 old、
 * 丢弃 REMOVED 行即还原 new（按出现顺序），因此全接受=new、全拒绝=old。
 */
object LineDiffer {
    /** 超过此行数或单元格数（n*m）则降级为整体对照，避免 O(n*m) 爆炸。 */
    const val MAX_DIFF_LINES = 2000
    const val MAX_DIFF_CELLS = 2_000_000L

    fun diff(old: String, new: String): DiffResult {
        val o = old.toLines()
        val n = new.toLines()

        // 降级：超大文档不做 O(n*m) LCS，退化为"整段删 + 整段增"单块对照
        if (o.size > MAX_DIFF_LINES || n.size > MAX_DIFF_LINES ||
            o.size.toLong() * n.size > MAX_DIFF_CELLS
        ) {
            return degraded(o, n)
        }

        // LCS 动态规划：dp[i][j] = o[i..] 与 n[j..] 的最长公共子序列长度
        val dp = Array(o.size + 1) { IntArray(n.size + 1) }
        for (i in o.size - 1 downTo 0) {
            for (j in n.size - 1 downTo 0) {
                dp[i][j] = if (o[i] == n[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        // 回溯产出有序的 (op, text) 序列
        val ops = ArrayList<Pair<DiffOp, String>>(o.size + n.size)
        var i = 0
        var j = 0
        while (i < o.size && j < n.size) {
            when {
                o[i] == n[j] -> { ops.add(DiffOp.UNCHANGED to o[i]); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { ops.add(DiffOp.REMOVED to o[i]); i++ }
                else -> { ops.add(DiffOp.ADDED to n[j]); j++ }
            }
        }
        while (i < o.size) { ops.add(DiffOp.REMOVED to o[i]); i++ }
        while (j < n.size) { ops.add(DiffOp.ADDED to n[j]); j++ }

        return assemble(ops)
    }

    /** 把 (op, text) 序列聚成 hunk：连续的非 UNCHANGED 行归为同一块。 */
    private fun assemble(ops: List<Pair<DiffOp, String>>): DiffResult {
        val lines = ArrayList<DiffLine>(ops.size)
        val hunks = ArrayList<DiffHunk>()
        var block = ArrayList<Pair<DiffOp, String>>()

        fun flush() {
            if (block.isEmpty()) return
            val id = hunks.size
            hunks.add(
                DiffHunk(
                    id = id,
                    removed = block.filter { it.first == DiffOp.REMOVED }.map { it.second },
                    added = block.filter { it.first == DiffOp.ADDED }.map { it.second }
                )
            )
            block.forEach { lines.add(DiffLine(it.first, it.second, id)) }
            block = ArrayList()
        }

        for (pair in ops) {
            if (pair.first == DiffOp.UNCHANGED) {
                flush()
                lines.add(DiffLine(DiffOp.UNCHANGED, pair.second, DiffLine.NO_HUNK))
            } else {
                block.add(pair)
            }
        }
        flush()
        return DiffResult(lines, hunks)
    }

    private fun degraded(o: List<String>, n: List<String>): DiffResult {
        val lines = ArrayList<DiffLine>(o.size + n.size)
        o.forEach { lines.add(DiffLine(DiffOp.REMOVED, it, 0)) }
        n.forEach { lines.add(DiffLine(DiffOp.ADDED, it, 0)) }
        val hunks = if (o.isEmpty() && n.isEmpty()) emptyList() else listOf(DiffHunk(0, o, n))
        return DiffResult(lines, hunks, degraded = true)
    }

    /** 空串视为 0 行（而非 1 个空行），保证 split/join 与合成互逆。 */
    private fun String.toLines(): List<String> = if (isEmpty()) emptyList() else split("\n")
}
