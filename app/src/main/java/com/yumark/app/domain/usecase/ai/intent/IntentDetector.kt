package com.yumark.app.domain.usecase.ai.intent

/**
 * 意图检测器 —— 移植自 guanmo `intentDetector.ts`。
 *
 * 打分制：弱关键词 +1、强关键词 +3、正则 +2、上下文加成。
 * score >= 4 判强依赖（required）。读类能力在打开文档时宽松命中（+1）。
 * 纯字符串+正则，无 LLM 调用，可在每轮循环前低成本重算。
 *
 * Phase 1 暂不接入 guanmo 的 classifyMemoryRetrievalIntent（依赖 Phase 3 的 MemoryService），
 * 记忆能力仅靠关键词/正则打分；Phase 3 落地后补回 classifier 加成。
 */

enum class Capability { MEMORY, KNOWLEDGE, DOCUMENT_READ, DOCUMENT_WRITE, WEB, TIME }

data class IntentScore(
    val capability: Capability,
    val score: Int,
    val signals: List<String>,
    val isRequired: Boolean
)

data class IntentDetectionResult(
    val candidates: List<Capability>,   // score > 0，按分数降序
    val required: List<Capability>,
    val scores: List<IntentScore>
)

data class AppContext(
    val hasOpenDocument: Boolean = false,
    val hasSelection: Boolean = false,
    val hasContextTags: Boolean = false,
    val hasRecentEdit: Boolean = false
)

object IntentDetector {

    private data class KeywordConfig(val weak: List<String>, val strong: List<String>)

    private val KEYWORD_CONFIG: Map<Capability, KeywordConfig> = mapOf(
        Capability.MEMORY to KeywordConfig(
            weak = listOf("记忆", "偏好", "习惯", "地址", "喜欢", "记得", "之前说过", "告诉过你"),
            strong = listOf("查询记忆", "搜索记忆", "我的记忆", "记住", "保存记忆", "添加记忆", "remember")
        ),
        Capability.KNOWLEDGE to KeywordConfig(
            weak = listOf("知识", "文档", "笔记", "资料", "索引", "rag", "文件内容", "文章", "这篇", "提到", "总结", "解释", "分析", "概述", "归纳"),
            strong = listOf("知识库", "本地知识", "本地文档", "文档库", "资料库", "笔记库", "search_knowledge", "查找文档", "搜索文档")
        ),
        Capability.DOCUMENT_READ to KeywordConfig(
            weak = listOf("文件", "文档", "内容", "看看", "查看", "读取", "打开"),
            strong = listOf("读取文件", "查看文件", "打开文件", "文件内容", "read_context_file")
        ),
        Capability.DOCUMENT_WRITE to KeywordConfig(
            weak = listOf("修改", "改写", "润色", "优化", "重写", "调整", "更新"),
            strong = listOf("修改文件", "改写文档", "替换内容", "replace_current_tab_text", "编辑文件")
        ),
        Capability.WEB to KeywordConfig(
            weak = listOf("搜索", "查找", "网上", "联网", "最新", "新闻", "查一下", "搜一下"),
            strong = listOf("网络搜索", "联网搜索", "网上搜索", "web_search", "搜索信息", "查找信息")
        ),
        Capability.TIME to KeywordConfig(
            weak = listOf("时间", "日期", "几点", "今天", "现在", "当前", "星期", "周几"),
            strong = listOf("当前时间", "今天日期", "现在几点", "get_current_time", "今天星期几")
        )
    )

    private val REGEX_PATTERNS: Map<Capability, List<Regex>> = mapOf(
        Capability.MEMORY to listOf(
            Regex("""(查询|搜索|查看|找找).*(记忆|我的记忆|长期记忆)""", RegexOption.IGNORE_CASE),
            Regex("""(记住|记下来|保存|添加).*(记忆|到记忆|进记忆)""", RegexOption.IGNORE_CASE),
            Regex("""remember""", RegexOption.IGNORE_CASE)
        ),
        Capability.KNOWLEDGE to listOf(
            Regex("""(查|查找|查询|搜索|检索|找找|看看).*(文档|文件|笔记|资料|知识库|索引|rag)""", RegexOption.IGNORE_CASE),
            Regex("""(文档|文件|笔记|资料|知识库|索引|rag).*(有没有|是否有|哪些|哪里|提到|相关|包含)""", RegexOption.IGNORE_CASE),
            Regex("""(根据|基于).*(知识库|本地文档|笔记|资料|rag|文件|文档).*(回答|总结|分析|归纳)""", RegexOption.IGNORE_CASE),
            Regex("""(总结|解释|分析|概述|归纳).*(文件|文档|笔记|内容|这篇)""", RegexOption.IGNORE_CASE),
            Regex("""(这个文件|这篇).*(什么|说|讲|内容)""", RegexOption.IGNORE_CASE),
            Regex("""(什么|说|讲|内容).*(这个文件|这篇)""", RegexOption.IGNORE_CASE),
            Regex("""(?:[\w一-鿿 ._-]+\.(?:md|markdown|mdx|txt)).*(?:总结|解释|分析|概述|归纳|说了什么|讲了什么|提到|内容)""", RegexOption.IGNORE_CASE),
            Regex("""(?:总结|解释|分析|概述|归纳|看看|查询|检索).*(?:[\w一-鿿 ._-]+\.(?:md|markdown|mdx|txt))""", RegexOption.IGNORE_CASE)
        ),
        Capability.DOCUMENT_READ to listOf(
            Regex("""(读取|查看|打开|看看).*(文件|文档)""", RegexOption.IGNORE_CASE),
            Regex("""(文件|文档).*(内容|里面|说了什么)""", RegexOption.IGNORE_CASE)
        ),
        Capability.DOCUMENT_WRITE to listOf(
            Regex("""^(修改|改写|润色|优化|重写|覆写|重构|调整|更新|扩写|缩写|续写|替换|改成|改为|加粗|斜体|删掉|删除|插入|补充|撤销|恢复|还原|改回|取消)"""),
            Regex("""^(算了|不改了|还是不改了|别改了|不用改了|先不改了|先别改了)"""),
            Regex("""(算了|不改了|别改了|不用改了|先不改了|先别改了)[\s\S]*(刚才|上次|前面|之前|修改|改动)"""),
            Regex("""(修改|改写|润色|优化|重写|覆写|重构|调整|更新|替换|改成|改为|加粗|斜体|删掉|删除|插入|补充|撤销|恢复|还原|改回|取消)[\s\S]*(文本|内容|文件|文档|段落|句子|选中|选择|tag|标签|上下文|这段|上面|前面|刚才)"""),
            Regex("""(文本|内容|文件|文档|段落|句子|选中|选择|tag|标签|上下文|这段|上面|前面|刚才)[\s\S]*(修改|改写|润色|优化|重写|覆写|重构|调整|更新|替换|改成|改为|加粗|斜体|删掉|删除|插入|补充|撤销|恢复|还原|改回|取消)"""),
            Regex("""^(帮我|请|把|将).*(修改|改写|润色|优化|重写|覆写|重构|调整|更新|替换|改成|改为|加粗|斜体|删掉|删除|插入|补充|撤销|恢复|还原|改回|取消)""")
        ),
        Capability.WEB to listOf(
            Regex("""^(搜索|查找|帮我搜|网上搜|联网搜)"""),
            Regex("""(?:网上|联网|互联网|最新|新闻|实时|今天).*(?:搜索|查找|搜|查询|资料|信息)"""),
            Regex("""(?:搜索|查找|查询|搜).*(?:网上|联网|互联网|最新|新闻|实时)"""),
            Regex("""(帮我|请|能不能).*(查一下|搜一下|搜索|查找|查询)"""),
            Regex("""(查一下|搜一下|搜索一下|查找一下).*(信息|资料|新闻|内容|话题)"""),
            Regex("""(最新|最近|今日|今天).*(新闻|资讯|消息|动态)""")
        ),
        Capability.TIME to listOf(
            Regex("""(现在|当前|此刻|今天).*(几点|时间|日期|几号|星期)"""),
            Regex("""(几点了|当前时间|当前日期|今天几号|今天星期)"""),
            Regex("""(今天|今日).*(是|几号|星期|周几)"""),
            Regex("""(现在|当前).*(是|几点|时间)"""),
            Regex("""(星期|周).*(几|几号)"""),
            Regex("""(时间|日期|几点).*(现在|当前|今天)""")
        )
    )

    fun detect(query: String, context: AppContext = AppContext()): IntentDetectionResult {
        val text = query.trim().lowercase()
        val capabilities = Capability.values()
        val scores = capabilities.map { scoreCapability(it, text, query, context) }
        val candidates = scores.filter { it.score > 0 }
            .sortedByDescending { it.score }
            .map { it.capability }
        val required = scores.filter { it.isRequired }.map { it.capability }
        return IntentDetectionResult(candidates, required, scores)
    }

    private fun scoreCapability(
        capability: Capability,
        text: String,          // 已 trim+lowercase，用于关键词子串匹配
        rawQuery: String,      // 原文，用于正则（正则自带 IGNORE_CASE）
        context: AppContext
    ): IntentScore {
        val config = KEYWORD_CONFIG[capability]!!
        val patterns = REGEX_PATTERNS[capability]!!
        val signals = mutableListOf<String>()
        var score = 0

        for (kw in config.weak) {
            if (text.contains(kw.lowercase())) {
                score += 1
                signals.add("weak:$kw")
            }
        }
        for (kw in config.strong) {
            if (text.contains(kw.lowercase())) {
                score += 3
                signals.add("strong:$kw")
            }
        }
        for (pattern in patterns) {
            if (pattern.containsMatchIn(rawQuery)) {
                score += 2
                signals.add("regex")
            }
        }

        // 上下文加成（与 guanmo 一致）
        if (capability == Capability.DOCUMENT_WRITE && context.hasRecentEdit) {
            score += 2
            signals.add("context:recent_edit")
        }
        if (capability == Capability.DOCUMENT_READ && context.hasOpenDocument) {
            score += 1
            signals.add("context:open_document")
        }
        if (capability == Capability.DOCUMENT_READ && context.hasContextTags &&
            Regex("""(总结|解释|分析|概述|归纳|这篇|这个文件|文章|全文|内容)""").containsMatchIn(rawQuery)
        ) {
            score += 2
            signals.add("context:tagged_read")
        }
        if (capability == Capability.MEMORY && context.hasContextTags) {
            score += 1
            signals.add("context:context_tags")
        }

        // TODO Phase 3：接入 classifyMemoryRetrievalIntent，对 memory 能力补 strong +4 / weak +2
        val isRequired = score >= 4
        return IntentScore(capability, score, signals, isRequired)
    }
}
