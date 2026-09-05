package com.yumark.app.data.ai

/**
 * 三家服务商「因为达到输出上限而停下」的说法，归一化成一个判断。
 *
 * | 服务商 | 字段 | 截断时的取值 |
 * |---|---|---|
 * | OpenAI 及其兼容实现 | `choices[].finish_reason` | `length` |
 * | Claude | `message_delta.delta.stop_reason` | `max_tokens` |
 * | Gemini | `candidates[].finishReason` | `MAX_TOKENS` |
 *
 * 为什么要单独抽一份：三个适配器各自解析一次的话，这套等价关系就散成三份，
 * 而它们只在「某一家改了拼写」时才会露出差异——那时错的那一家会静默地退回
 * 「没截断」，正好是最危险的那个方向（见 [com.yumark.app.domain.model.StreamEvent.Done] 的注释）。
 * 放在这里也顺带让它能在 JVM 单测里直接跑，不必起 Ktor mock engine。
 *
 * 判定刻意只管**长度**截断，不含 `content_filter` / `SAFETY` / `RECITATION`：
 * 那些是内容被拦，补救办法（换措辞）和这里的（调大 max tokens、让模型继续写）完全不同，
 * 混进来只会让提示语指错方向。
 */
internal fun isTruncatedStopReason(raw: String?): Boolean {
    val normalized = normalizeStopReason(raw) ?: return false
    // 用「包含」而不是全等：真实世界里还有 length_limit、max_tokens_reached 这类变体，
    // 全等匹配遇到它们就漏报。这几个片段本身足够长，不会误伤 stop / end_turn / tool_calls。
    return TRUNCATION_MARKERS.any { normalized.contains(it) }
}

private val TRUNCATION_MARKERS = listOf(
    "length",
    "maxtoken",             // max_tokens、MAX_TOKENS
    "maxoutputtoken",       // maxOutputTokens 系（注意它不含 "maxtoken" 子串）
    "maxcompletiontoken"    // 部分 OpenAI 兼容网关的说法
)

/**
 * 归一化一个 stop reason：去首尾空白、转小写、剔掉分隔符。
 *
 * `max_tokens` / `MAX-TOKENS` / `"max tokens"` 都落到同一串上，省得为每种拼写各写一个字面量。
 * 空/空白（含只由分隔符组成的取值）返回 null —— 「服务端没说」和「说了但不认识」是两回事，
 * 前者不该让任何一个 marker 表参与匹配。
 */
private fun normalizeStopReason(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val normalized = raw.trim().lowercase().filterNot { it == '_' || it == '-' || it == ' ' }
    return normalized.ifEmpty { null }
}

/**
 * 三家服务商「内容被策略拦下、所以没有正文」的说法，归一化成一个判断。
 *
 * | 服务商 | 字段 | 被拦时的取值 |
 * |---|---|---|
 * | OpenAI 及其兼容实现 | `choices[].finish_reason` | `content_filter` |
 * | Claude | `message_delta.delta.stop_reason` | `refusal` |
 * | Gemini | `candidates[].finishReason` | `SAFETY`、`RECITATION`、`BLOCKLIST`、`PROHIBITED_CONTENT`、`SPII`、`IMAGE_SAFETY` |
 * | Gemini | `promptFeedback.blockReason` | `SAFETY`、`BLOCKLIST`、`PROHIBITED_CONTENT`、`IMAGE_SAFETY` |
 *
 * 与 [isTruncatedStopReason] 是**姐妹而非分支**：两者的补救办法相反 —— 截断要调大 max tokens
 * 或让模型接着写，被拦要改写措辞或换模型。合成一个判断，提示语必然对一半的人指错方向。
 *
 * 刻意**不**收进来的几个取值，因为它们的补救办法都不是「改写措辞」：
 *  * `STOP` / `end_turn` / `tool_calls` —— 正常收尾；
 *  * `OTHER` / `BLOCK_REASON_UNSPECIFIED` —— 服务端自己也没说是什么，当成被拦会把
 *    「模型不支持本次请求」这类真正常见的成因说成内容违规；
 *  * `LANGUAGE`（Gemini：响应语言不受支持）—— 该做的是换模型，正好是现有空响应文案说的话；
 *  * `MALFORMED_FUNCTION_CALL` / `UNEXPECTED_TOOL_CALL` —— 工具调用格式问题，与内容无关。
 */
internal fun isBlockedStopReason(raw: String?): Boolean {
    val normalized = normalizeStopReason(raw) ?: return false
    return BLOCK_MARKERS.any { normalized.contains(it) }
}

private val BLOCK_MARKERS = listOf(
    "safety",               // Gemini SAFETY / IMAGE_SAFETY
    "contentfilter",        // OpenAI content_filter
    "recitation",           // Gemini RECITATION：命中受版权保护的原文
    "blocklist",            // Gemini BLOCKLIST：命中术语黑名单
    "prohibitedcontent",    // Gemini PROHIBITED_CONTENT
    "spii",                 // Gemini SPII：敏感个人可识别信息
    "refusal"               // Claude refusal
)

/**
 * 把服务商给的拦截原因整理成「能直接拼进用户提示里」的一段。
 *
 * 两件事：
 *  * 信息量为零的取值（`OTHER`、`BLOCK_REASON_UNSPECIFIED`）返回 null，让上层用不带括号的
 *    那句通用文案 —— 给用户看一串 `BLOCK_REASON_UNSPECIFIED` 不如什么都不说；
 *  * 其余取值做**净化**后回传：这串是网络来的，最终落在 Snackbar 的一句话里。控制符会把
 *    单行提示撑成多行（`\n` 在 Compose 的 Text 里照样换行），异常长的取值会把真正要说的
 *    「改写措辞后重试」顶出可视区。
 *
 * 只做净化不做翻译：`SAFETY` / `content_filter` 这些是服务商文档里的原词，保留原样用户才搜得到。
 */
internal fun presentableBlockReason(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    val normalized = normalizeStopReason(trimmed) ?: return null
    if (UNINFORMATIVE_BLOCK_REASONS.any { normalized.contains(it) }) return null
    return trimmed.filterNot { it.isISOControl() }.take(MAX_BLOCK_REASON_LENGTH).ifBlank { null }
}

/** 够装下最长的已知取值（`MALFORMED_FUNCTION_CALL` 21 字符）还有余量，又不至于占满一屏。 */
private const val MAX_BLOCK_REASON_LENGTH = 40

private val UNINFORMATIVE_BLOCK_REASONS = listOf("unspecified", "other")
