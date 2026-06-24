package com.yumark.app.domain.usecase.ai.intent

import com.yumark.app.domain.model.AiTool

/**
 * 工具选择器 —— 移植自 guanmo `toolSelector.ts`。
 *
 * 按意图候选能力裁剪工具：只发送相关 tools，禁止每轮全量发送。
 * `update_plan` 为 YuMark 任务管理工具，当存在任意候选能力时始终保留；
 * 无候选（纯闲聊且无打开文档）时不发送任何工具，让模型直接用自身知识作答。
 *
 * 前向兼容：[selectTools] 对 `available` 取交集 —— 未实现的工具（如 web_search、
 * search_knowledge）不在 available 中时自然被过滤；后续 Phase 落地新工具只需扩展
 * `DocumentContextTools.getAllTools()` 的聚合来源，无需改本类。
 */
object ToolSelector {

    /** 能力 → 工具名映射（跨 Phase 逐步生效）。 */
    val CAPABILITY_TOOLS: Map<Capability, List<String>> = mapOf(
        Capability.MEMORY to listOf("search_memory", "list_memories"),
        Capability.KNOWLEDGE to listOf("search_knowledge", "knowledge_stats"),
        Capability.DOCUMENT_READ to listOf("read_document", "list_documents", "search_in_project"),
        Capability.DOCUMENT_WRITE to listOf("create_document", "edit_document"),
        Capability.WEB to listOf("web_search"),
        Capability.TIME to listOf("get_current_time")
    )

    private val WRITE_TOOLS = setOf("create_document", "edit_document", "save_memory", "replace_current_tab_text")

    private val READ_TOOLS = setOf(
        "search_memory", "list_memories",
        "search_knowledge", "knowledge_stats",
        "read_document", "list_documents", "search_in_project",
        "web_search", "get_current_time"
    )

    /** 任务管理工具：存在候选能力时始终保留。 */
    private const val ALWAYS_KEEP = "update_plan"

    /**
     * 外部知识源工具。编辑/创建当前文档时与任务无关，且 search_knowledge 会触发 embedding
     * 请求（慢端点上可挂起到 socket 超时 ~120s），故编辑意图明确时一律不下发。
     */
    private val AUXILIARY_TOOLS = setOf(
        "search_knowledge", "knowledge_stats",
        "web_search",
        "search_memory", "list_memories", "save_memory"
    )

    /**
     * 根据意图从 [available] 中选出本轮发送的工具子集。
     */
    fun selectTools(intent: IntentDetectionResult, available: List<AiTool>): List<AiTool> {
        if (intent.candidates.isEmpty()) return emptyList()

        val wanted = mutableSetOf<String>(ALWAYS_KEEP)
        for (cap in intent.candidates) {
            CAPABILITY_TOOLS[cap]?.let { wanted.addAll(it) }
        }
        // 编辑/创建当前文档时不需要外部知识源——它们与针对当前文档的改动无关，
        // 且 search_knowledge 会触发 embedding 请求，慢端点上长时间挂起。
        if (Capability.DOCUMENT_WRITE in intent.required) {
            wanted.removeAll(AUXILIARY_TOOLS)
        }
        return available.filter { it.name in wanted }
    }

    fun isReadTool(name: String): Boolean = name in READ_TOOLS

    fun isWriteTool(name: String): Boolean = name in WRITE_TOOLS
}
