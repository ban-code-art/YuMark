package com.yumark.app.presentation.editor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 划词 AI「这段回复该不该当成可应用的改写」的全部判定，从 [AiQuickViewModel] 里搬出来。
 *
 * 搬出来的唯一原因是**可测**：原先它们是 `@HiltViewModel` 的 private 方法，JVM 单测既够不到
 * private，也得先把 ViewModel 的一整串依赖 mock 出来才能构造它——于是这几条决定「用户会不会
 * 看到一张改写卡片」的启发式一条都没测过。它们本身是纯字符串函数，不读 ViewModel 的任何状态，
 * 搬家零行为差异。
 *
 * 判错的代价不对称，两个方向都得防：
 * - 该弹不弹（漏判）→ 弱模型明明改好了，用户只看到一段文字、没有「应用」按钮，只能手抄；
 * - 不该弹却弹（误判）→ 一篇总结被当成改写，用户点「应用」就把选中的正文替换成了总结。
 */
internal object QuickEditHeuristics {

    /** 去掉处理模式回复里的 [[EDIT]]/[[/EDIT]] 标记，用于气泡展示。 */
    fun stripEditMarkers(text: String): String =
        text.replace("[[EDIT]]", "").replace("[[/EDIT]]", "").trim()

    /**
     * 解析 `[[EDIT]]...[[/EDIT]]` 包裹的改写文本；无标记返回 null（表示这是普通问答/总结，不挂卡片）。
     *
     * 标记存在但内容为空（模型表示"删除"）时返回空串而非 null——空串代表删除，必须走审批门。
     */
    fun parseEditContent(text: String): String? {
        val start = text.indexOf("[[EDIT]]")
        if (start < 0) return null
        val afterStart = start + "[[EDIT]]".length
        val end = text.indexOf("[[/EDIT]]", afterStart)
        val inner = if (end >= 0) text.substring(afterStart, end) else text.substring(afterStart)
        return inner.trim()
    }

    /** 从 apply_edit 工具调用参数解析 new_text。空串=删除（非 null）；仅当缺字段/解析失败才返回 null。 */
    fun parseApplyEditArgs(argsJson: String): String? = runCatching {
        Json.parseToJsonElement(argsJson).jsonObject["new_text"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /**
     * 处理模式下决定回复是否应作为「可应用改写」。
     *
     * 1) 优先按 `[[EDIT]]` 标记解析（空串=删除）。
     * 2) apply_edit 工具调用已在上游处理（空串=删除）。
     * 3) 明确的删除意图（"删除/删掉/去掉 这一段"）且模型未给新文本 → 空替换 = 删除整段选中。
     * 4) 其余走兜底启发式：回复「看起来像改写」才当 editContent；否则按普通问答/总结处理。
     */
    fun resolveEdit(text: String, selected: String, userMessage: String): String? {
        parseEditContent(text)?.let { return it }
        if (isDeletionIntent(userMessage)) return ""   // 删除整段选中 = 空替换
        val reply = text.trim()
        if (!looksLikeRewrite(reply, selected.trim())) return null
        return reply.ifBlank { null }
    }

    /** 判定用户是否要求「删除整段选中文本」（模型未给新文本时的兜底，空替换=删除）。 */
    fun isDeletionIntent(userMessage: String): Boolean {
        val m = userMessage.lowercase()
        val deleteVerb = listOf(
            "删除", "删掉", "删去", "去掉", "移除", "清除", "抹掉", "删了",
            "delete", "remove", "erase"
        ).any { m.contains(it) }
        if (!deleteVerb) return false
        // 目标指向选区本身，而非文档别处
        return listOf(
            "这段", "这一段", "选中", "这段话", "这段文字", "这些", "那段",
            "this", "it", "selection", "paragraph"
        ).any { m.contains(it) }
    }

    /**
     * 兜底判定：回复是否「看起来像直接改写」而非问答/总结。
     *
     * - 含明显解释性结构（标题行、解释性引导语）→ 视为问答/总结，抑制。
     * - 长度远超选区（>2.5× 且选区非平凡）→ 视为扩写型解释，抑制。
     * - 其余视为改写。
     *
     * 偏向保守：拿不准时倾向当作改写，以救援弱模型的改写；但因有结构/长度双闸，
     * 典型的长篇总结仍不会误弹卡片。
     */
    fun looksLikeRewrite(reply: String, selected: String): Boolean {
        if (reply.isBlank()) return false
        if (hasExplanationStructure(reply)) return false
        if (selected.length > 16 && reply.length > selected.length * 2.5f) return false
        return true
    }

    /** 高精度识别「明显是解释/总结而非改写」的结构信号。 */
    fun hasExplanationStructure(reply: String): Boolean {
        val firstLine = reply.lineSequence().firstOrNull()?.trim().orEmpty()
        // 以 Markdown 标题开头 → 几乎不是直接改写
        if (firstLine.startsWith("#")) return true
        // 解释性引导语。命中方式是 any { reply.contains(it) }，所以两条约束：
        // 不要写重复项（"选中的文本" 原来出现了两次），也不要写被别的条目包含的项
        // （"作为一个" 被 "作为一" 完全覆盖）——两者都永远命中不到自己，只是噪声。
        val cues = listOf(
            "以下是", "建议如下", "总结一下", "总结：", "总结:", "原因如下", "修改建议",
            "这段话", "这段文字", "这段文本", "选中的文本",
            "我建议", "可以这样修改", "作为一"
        )
        return cues.any { reply.contains(it) }
    }
}

/**
 * 本轮发给模型的用户消息：选中文本放进围栏，后面跟用户那句话。
 *
 * 和 [QuickEditHeuristics] 同住一个文件、同一个理由——纯字符串逻辑，从 `AiQuickViewModel` 的
 * private 区搬出来才测得到。
 *
 * 原来的写法是把 `selectedText` 插进一段缩进 16 的 `"""…"""` 再 `.trimIndent()`，两头都错：
 * - `trimIndent()` 跑在**插值之后**，取所有非空行的最小公共缩进。选区多行且有一行顶格时最小
 *   缩进是 0，什么都不裁：模板连围栏 ``` 一起顶着 16 个空格发出去，模型看到的是一个缩进代码块，
 *   围栏标记退化成字面内容，第一行选区还多出 16 个空格。
 * - 选区每行都缩进时（Markdown 里的缩进代码块、嵌套列表，随手一选就是），最小缩进落在选区
 *   自己身上，`trimIndent()` 把这份缩进从**选区**上剥掉。处理模式下模型照剥过的文本改写，
 *   改写结果又替换回用户的选区——用户的缩进就这么被一个来回吃掉了。
 *
 * 所以全程只用 `append`，不插值、不 `trimIndent`。围栏长度也按选区算：比正文里最长的连续反引号
 * 串多一个，否则选中一段本身带 ``` 代码块的文本时，它自己的围栏会提前把外层围栏闭合。
 */
internal fun buildQuickUserMessage(
    selectedText: String,
    userMessage: String,
    mode: QuickAiMode
): String {
    val label = when (mode) {
        QuickAiMode.AI_QUERY -> "我的问题："
        QuickAiMode.AGENT_EDIT -> "我的需求："
    }
    val fence = "`".repeat(fenceLengthFor(selectedText))
    return buildString {
        append("选中的文本：\n")
        append(fence).append('\n')
        append(selectedText).append('\n')
        append(fence).append("\n\n")
        append(label).append('\n')
        append(userMessage)
    }
}

/**
 * 围栏反引号个数：至少 3，且严格多于 [text] 里最长的连续反引号串。
 *
 * CommonMark 规定闭合围栏只要不短于开启围栏就算闭合，所以「等长」是不够的——选区里一行
 * ``` 会当场把围栏关掉，后面的正文全部漏到围栏外面。
 */
private fun fenceLengthFor(text: String): Int {
    var longest = 0
    var run = 0
    for (ch in text) {
        if (ch == '`') {
            run++
            if (run > longest) longest = run
        } else {
            run = 0
        }
    }
    return maxOf(3, longest + 1)
}
