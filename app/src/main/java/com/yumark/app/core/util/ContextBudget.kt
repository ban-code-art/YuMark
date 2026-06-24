package com.yumark.app.core.util

/**
 * 统一的上下文预算（替代此前 Agent 200 / Quick 12000 / Chat 0 三处分散口径）。
 */
object ContextBudget {
    /** system prompt 内注入的当前文档上下文上限（用大纲而非全文）。 */
    const val SYSTEM_DOC_CHARS = 1500
    /** 单次工具结果回填上限。 */
    const val TOOL_RESULT_CHARS = 4000
    /** 选区/快捷处理注入的文档上下文上限。 */
    const val QUICK_SELECTION_CHARS = 12000

    /** 粗略 token 估算（char/4）。 */
    fun estimateTokens(text: String): Int = text.length / 4

    /** 各工具结果回填的字符预算（移植 guanmo getToolTokenBudget，按工具名查表）。 */
    private val TOOL_BUDGETS: Map<String, Int> = mapOf(
        "search_memory" to 3000,
        "list_memories" to 4000,
        "save_memory" to 1000,
        "search_knowledge" to 5000,
        "knowledge_stats" to 2000,
        "list_documents" to 5000,
        "read_document" to 6000,
        "search_in_project" to 5000,
        "web_search" to 3000,
        "get_current_time" to 1000,
        "create_document" to 2000,
        "edit_document" to 2000,
        "update_plan" to 1000
    )

    /** 取工具结果回填上限；未登记的工具回退到通用 [TOOL_RESULT_CHARS]。 */
    fun toolResultBudget(toolName: String): Int = TOOL_BUDGETS[toolName] ?: TOOL_RESULT_CHARS
}

/**
 * 提取文档大纲（标题层级 + 开头段落），用于轻量注入 system prompt。
 * 无标题时回退为内容前缀。超出 [budget] 截断。
 */
fun documentOutline(content: String, budget: Int = ContextBudget.SYSTEM_DOC_CHARS): String {
    val lines = content.lines()
    val headings = lines.filter { it.trimStart().startsWith("#") }
    val firstPara = lines.firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
    val sb = StringBuilder()
    if (headings.isNotEmpty()) {
        sb.append("大纲：\n")
        headings.forEach { sb.append(it.trim()).append("\n") }
    }
    firstPara?.let { sb.append("开头：").append(it.trim()) }
    val outline = sb.toString().trim().ifBlank { content }
    return if (outline.length <= budget) outline else outline.take(budget) + "…"
}
