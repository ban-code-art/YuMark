package com.yumark.app.domain.usecase.ai.agent

import com.yumark.app.domain.model.ChatMessage

/**
 * Agent 上下文窗口管理：把发给模型的消息列表裁剪进 token 预算。
 *
 * **为什么需要**：`workingMessages` 是「全部历史 + 本轮累积的工具消息」。长会话或长任务
 * 运行必然超出模型上下文，API 直接报 400（context_length_exceeded），整轮 Agent 失败——
 * 这是与 Claude Code 一类实现的核心差距：它们都会在超限前裁剪/压缩历史。
 *
 * **裁剪单位是「回合组」**：一条 user 消息开启一个组，其后连续的 assistant（含工具调用）与
 * tool 消息都归属该组。约束是硬性的——OpenAI 协议要求 tool 消息必须紧跟带 tool_calls 的
 * assistant 消息，按组整体丢弃才能保证配对永不拆散。
 *
 * **保留策略**：从最新的组往最旧累计，装得下的组全部保留；最近 [minKeepMessages] 条消息
 * 所在的组即使超预算也强制保留（当前回合的工具链必须完整落在其中）。
 *
 * **token 估算刻意与 [com.yumark.app.core.util.ContextBudget.estimateTokens] 分开**：
 * 那个按 char/4 对英文合理，对中文偏低 3~4 倍——中文场景按 char/4 裁剪等于没裁。
 * 这里按「CJK 字符 ≈ 1 token、其余 ≈ 4 字符 1 token」估算，宁高勿低（裁多了只损失
 * 一点历史，裁少了整轮失败）。
 *
 * 无法裁剪的极端情形（保护区自身仍超预算，例如单条超长正文）：原样返回，由 API 侧报错——
 * 裁剪器不承担截断正文的责任，那会静默改写用户数据。
 */
object AgentContextTrimmer {

    /** 默认 token 预算：覆盖 32k 窗口（调用方再扣除系统提示与响应余量）。 */
    const val DEFAULT_BUDGET_TOKENS = 24_000

    /** 最近消息保护数：至少保留的尾部消息条数。 */
    const val MIN_KEEP_MESSAGES = 12

    /** 非 CJK 字符的「每 token 字符数」。 */
    private const val OTHER_CHARS_PER_TOKEN = 4

    /** 预算下限：扣除保留额后的预算不为负。 */
    private const val MINIMUM_BUDGET = 0

    /** CJK 统一表意 / 韩文谚文 / 兼容表意的码位区间，这些字符按 1 字符 1 token 估。 */
    // 码位区间本身就是具名定义（查表），表项数值不是「魔法数字」而是数据。
    @Suppress("MagicNumber")
    private val CJK_CODE_RANGES = listOf(
        IntRange(0x2E80, 0x9FFF),
        IntRange(0xAC00, 0xD7AF),
        IntRange(0xF900, 0xFAFF)
    )

    fun trim(
        messages: List<ChatMessage>,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
        reservedTokens: Int = 0,
        minKeepMessages: Int = MIN_KEEP_MESSAGES
    ): List<ChatMessage> {
        val effectiveBudget = (budgetTokens - reservedTokens).coerceAtLeast(MINIMUM_BUDGET)

        // 按回合组切分：user 消息开启新组，assistant/tool 归入当前组。
        // 首条非 user（协议上不该出现）防御式并入第一组。
        val groups = ArrayList<List<ChatMessage>>()
        for (message in messages) {
            if (message.role == "user" || groups.isEmpty()) {
                groups.add(listOf(message))
            } else {
                groups[groups.size - 1] = groups[groups.size - 1] + message
            }
        }
        val groupTokens = groups.map { group -> group.sumOf { estimateTokens(it.content.orEmpty()) } }
        val total = groupTokens.sum()

        // 快路径：消息条数太少（不值得裁）或总量本就在预算内，原样返回
        if (messages.size <= minKeepMessages || total <= effectiveBudget) return messages

        // 从最新的组往最旧累计：预算装不下且保护区已满时停下，其余最旧的组全部牺牲。
        var oldestKept = groups.size
        var keptCount = 0
        var keptTokens = 0
        for (index in groups.indices.reversed()) {
            val wouldExceedBudget = keptTokens + groupTokens[index] > effectiveBudget
            if (wouldExceedBudget && keptCount >= minKeepMessages) break
            oldestKept = index
            keptTokens += groupTokens[index]
            keptCount += groups[index].size
        }

        // 保护区自身仍超预算（单条超长正文）：不做正文截断，原样返回交由 API 侧报错
        return if (oldestKept == 0) messages else groups.subList(oldestKept, groups.size).flatten()
    }

    /**
     * 压缩切分（与 [trim] 同一套分组逻辑）：返回 (待压缩部分, 保留部分)。
     *
     * 触发条件与 trim 判丢弃完全一致（保护区之外且超预算）——保证「会被裁掉的部分」
     * 恰好是「拿去压缩的部分」，压缩成功则摘要替代丢弃，失败回退 trim（丢弃）。
     * null = 无需压缩（未超预算或保护区即全部）。
     */
    fun splitForCompression(
        messages: List<ChatMessage>,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
        reservedTokens: Int = 0,
        minKeepMessages: Int = MIN_KEEP_MESSAGES
    ): Pair<List<ChatMessage>, List<ChatMessage>>? {
        // 三类「无需压缩」合并为一个守卫出口：条数不足 / 未超预算 / 保护区即全部
        val groups = groupByTurn(messages)
        val effectiveBudget = (budgetTokens - reservedTokens).coerceAtLeast(MINIMUM_BUDGET)
        val groupTokens = groups.map { group -> group.sumOf { estimateTokens(it.content.orEmpty()) } }
        val oldestKept = oldestKeptGroupIndex(groups, groupTokens, effectiveBudget, minKeepMessages)
        val noSplit = messages.size <= minKeepMessages ||
            groupTokens.sum() <= effectiveBudget ||
            oldestKept <= 0
        if (noSplit) return null
        val oldPart = groups.subList(0, oldestKept).flatten()
        val keptPart = groups.subList(oldestKept, groups.size).flatten()
        return oldPart.takeIf { it.isNotEmpty() }?.let { it to keptPart }
    }

    /** 回合分组：user 开组，assistant/tool 归入当前组（trim 与 splitForCompression 共用）。 */
    private fun groupByTurn(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val groups = ArrayList<List<ChatMessage>>()
        for (message in messages) {
            if (message.role == "user" || groups.isEmpty()) {
                groups.add(listOf(message))
            } else {
                groups[groups.size - 1] = groups[groups.size - 1] + message
            }
        }
        return groups
    }

    /** 从最新往最旧累计的保留判定：返回首个保留组下标（0 = 保护区即全部）。 */
    private fun oldestKeptGroupIndex(
        groups: List<List<ChatMessage>>,
        groupTokens: List<Int>,
        effectiveBudget: Int,
        minKeepMessages: Int
    ): Int {
        var oldestKept = groups.size
        var keptCount = 0
        var keptTokens = 0
        for (index in groups.indices.reversed()) {
            val wouldExceedBudget = keptTokens + groupTokens[index] > effectiveBudget
            if (wouldExceedBudget && keptCount >= minKeepMessages) break
            oldestKept = index
            keptTokens += groupTokens[index]
            keptCount += groups[index].size
        }
        return oldestKept
    }

    /** CJK 感知的 token 估算：CJK 字符 ≈ 1 token，其余按 4 字符 ≈ 1 token。宁高勿低。 */
    fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (CJK_CODE_RANGES.any { it.contains(ch.code) }) cjk++ else other++
        }
        return cjk + other / OTHER_CHARS_PER_TOKEN
    }
}
