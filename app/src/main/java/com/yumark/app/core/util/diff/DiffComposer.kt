package com.yumark.app.core.util.diff

/**
 * 按各 hunk 的接受状态把 [DiffResult] 合成最终文本。
 * 纯函数。[accepted] 下标与 [DiffResult.hunks] 对齐：true=接受该块改动，false=保留原文。
 *
 * 规则：UNCHANGED 行恒保留；ADDED 行仅在该块被接受时写入；REMOVED 行仅在该块被拒绝时
 * 还原。于是全接受=new、全拒绝=old（依赖 [LineDiffer] 的行序不变式）。
 */
object DiffComposer {
    fun applyHunks(result: DiffResult, accepted: List<Boolean>): String {
        val out = ArrayList<String>(result.lines.size)
        for (line in result.lines) {
            when (line.op) {
                DiffOp.UNCHANGED -> out.add(line.text)
                // 越界（accepted 与 hunks 不匹配）时默认接受，宁可应用不可丢改动
                DiffOp.ADDED -> if (accepted.getOrElse(line.hunkId) { true }) out.add(line.text)
                DiffOp.REMOVED -> if (!accepted.getOrElse(line.hunkId) { true }) out.add(line.text)
            }
        }
        return out.joinToString("\n")
    }
}
