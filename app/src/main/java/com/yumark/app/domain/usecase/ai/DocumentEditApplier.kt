package com.yumark.app.domain.usecase.ai

/**
 * 单段外科式编辑：把 [oldString] 替换为 [newString]。
 * [replaceAll] 为 true 时替换全部命中，否则要求唯一命中。
 */
data class EditOp(
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean = false
)

/**
 * 编辑失败原因（消息回填给模型，引导自我修正）。
 */
class EditException(message: String) : Exception(message)

/**
 * 外科式编辑器：把一组 [EditOp] 顺序作用于文档原文，得到更新后全文。
 *
 * 设计对齐 Claude Code / OpenCode / Pi 的 `edit`（str_replace）：精确字符串替换为主，
 * 失败时退到「行级 trim 容差」匹配（仅吸收空白差异，不做语义模糊匹配，避免误改）。
 * 非 [EditOp.replaceAll] 时命中多处即报错，要求模型补充上下文。纯逻辑，便于单测。
 */
object DocumentEditApplier {

    /** 顺序应用所有编辑；任一段失败则整体失败（[EditException]）。 */
    fun applyEdits(base: String, edits: List<EditOp>): Result<String> = runCatching {
        require(edits.isNotEmpty()) { throw EditException("没有可应用的编辑。") }
        var text = base
        edits.forEachIndexed { index, op ->
            text = applyOne(text, op, index)
        }
        text
    }

    private fun applyOne(text: String, op: EditOp, index: Int): String {
        if (op.oldString.isEmpty()) {
            throw EditException("第${index + 1}处编辑的 old_string 为空。")
        }

        // 1) 精确匹配
        val exactCount = countOccurrences(text, op.oldString)
        if (exactCount == 1 || (exactCount > 1 && op.replaceAll)) {
            return if (op.replaceAll) text.replace(op.oldString, op.newString)
            else text.replaceFirst(op.oldString, op.newString)
        }
        if (exactCount > 1) {
            throw EditException(
                "第${index + 1}处编辑的 old_string 在文档中出现 $exactCount 次，不唯一；" +
                    "请补充更多上下文使其唯一，或设 replace_all=true。"
            )
        }

        // 2) 行级 trim 容差匹配（仅当精确 0 命中）
        val tolerant = tolerantReplace(text, op)
        if (tolerant != null) return tolerant

        throw EditException(
            "第${index + 1}处编辑未命中：在文档中找不到指定原文。" +
                "请先用 read_document 获取确切原文，并提供能唯一定位的片段。"
        )
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val idx = haystack.indexOf(needle, from)
            if (idx < 0) break
            count++
            from = idx + needle.length
        }
        return count
    }

    /**
     * 行级 trim 容差：把原文与 old_string 各自逐行去除行尾空白、去掉首尾空行后比对；
     * 命中唯一连续行块则按原文真实区间替换。命中多处或 0 处返回 null（交回精确逻辑报错）。
     */
    private fun tolerantReplace(text: String, op: EditOp): String? {
        val textLines = text.split("\n")
        val needleLines = op.oldString.split("\n")
            .map { it.trimEnd() }
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
        if (needleLines.isEmpty()) return null

        val normText = textLines.map { it.trimEnd() }
        val matches = ArrayList<Int>()
        var i = 0
        while (i <= normText.size - needleLines.size) {
            var hit = true
            for (j in needleLines.indices) {
                if (normText[i + j] != needleLines[j]) { hit = false; break }
            }
            if (hit) matches.add(i)
            i++
        }
        if (matches.isEmpty()) return null
        // 非 replace_all 时若容差匹配到多处，不擅自挑一处，交回精确逻辑报「未命中」
        if (matches.size != 1 && !op.replaceAll) return null

        // 用原文真实行替换（保留缩进等），newString 整体替换匹配区间
        val newLines = op.newString.split("\n")
        val result = ArrayList<String>()
        var cursor = 0
        val targets = if (op.replaceAll) matches else listOf(matches.first())
        var ti = 0
        while (cursor < textLines.size) {
            if (ti < targets.size && cursor == targets[ti]) {
                result.addAll(newLines)
                cursor += needleLines.size
                ti++
            } else {
                result.add(textLines[cursor])
                cursor++
            }
        }
        return result.joinToString("\n")
    }
}
