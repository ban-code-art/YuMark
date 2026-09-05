package com.yumark.app.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [isTruncatedStopReason]：三家服务商「输出被上限砍断」的说法归一。
 *
 * 这个判断是整条截断链路的源头，而它错在「漏报」方向的代价极不对称：
 * 漏报一次，Agent 就会把一段砍掉后半截的正文当成完整文档，整篇覆盖到用户已有文档上
 * （见 ExtractImplicitWriteActionTest 的截断保护那一组）。所以除了三家的标准取值，
 * 这里连大小写、分隔符、常见变体拼写一起钉住。
 */
class StopReasonTest {

    @Test
    fun `OpenAI length`() {
        assertThat(isTruncatedStopReason("length")).isTrue()
    }

    @Test
    fun `Claude max_tokens`() {
        assertThat(isTruncatedStopReason("max_tokens")).isTrue()
    }

    @Test
    fun `Gemini MAX_TOKENS`() {
        assertThat(isTruncatedStopReason("MAX_TOKENS")).isTrue()
    }

    @Test
    fun `分隔符与大小写不影响判定`() {
        // 归一化会把 _ - 空格一起去掉再比，省得为每种拼法各写一个字面量
        assertThat(isTruncatedStopReason("Max-Tokens")).isTrue()
        assertThat(isTruncatedStopReason("max tokens")).isTrue()
        assertThat(isTruncatedStopReason("  LENGTH  ")).isTrue()
        assertThat(isTruncatedStopReason("maxOutputTokens")).isTrue()
    }

    @Test
    fun `变体拼写也算截断`() {
        // 用「包含」而不是全等，就是为了这些网关/代理自己发明的说法
        assertThat(isTruncatedStopReason("length_limit")).isTrue()
        assertThat(isTruncatedStopReason("max_tokens_reached")).isTrue()
        assertThat(isTruncatedStopReason("max_completion_tokens")).isTrue()
    }

    @Test
    fun `正常结束不算截断`() {
        assertThat(isTruncatedStopReason("stop")).isFalse()
        assertThat(isTruncatedStopReason("STOP")).isFalse()
        assertThat(isTruncatedStopReason("end_turn")).isFalse()
        assertThat(isTruncatedStopReason("tool_calls")).isFalse()
        assertThat(isTruncatedStopReason("tool_use")).isFalse()
        assertThat(isTruncatedStopReason("stop_sequence")).isFalse()
        assertThat(isTruncatedStopReason("function_call")).isFalse()
    }

    @Test
    fun `内容被拦不算长度截断`() {
        // 刻意不含这些：补救办法（换措辞）和截断（调大 max tokens）完全不同，
        // 混进来只会让提示语指错方向。
        assertThat(isTruncatedStopReason("content_filter")).isFalse()
        assertThat(isTruncatedStopReason("SAFETY")).isFalse()
        assertThat(isTruncatedStopReason("RECITATION")).isFalse()
        assertThat(isTruncatedStopReason("refusal")).isFalse()
    }

    @Test
    fun `缺省与空白视为未截断`() {
        // 流式过程中大部分 chunk 的 finish_reason 就是 JSON null，
        // 归一化后为空也一样——这条路必须走「没截断」，否则每次请求都要弹提示。
        assertThat(isTruncatedStopReason(null)).isFalse()
        assertThat(isTruncatedStopReason("")).isFalse()
        assertThat(isTruncatedStopReason("   ")).isFalse()
        assertThat(isTruncatedStopReason("_-_")).isFalse()
    }

    @Test
    fun `未知取值视为未截断`() {
        // 宁可漏一次提示，也不要在正常结束时反复弹「回复被截断」——
        // 那会把这条提示训练成噪音，真出事时用户已经不看了。
        assertThat(isTruncatedStopReason("unknown")).isFalse()
        assertThat(isTruncatedStopReason("error")).isFalse()
        assertThat(isTruncatedStopReason("cancelled")).isFalse()
    }

    // ---- isBlockedStopReason：内容被策略拦下 ----
    // 与截断是姐妹判断而非分支：截断要调大 max tokens，被拦要改写措辞。认错一边，
    // 提示语就把用户推去把 max tokens 调到顶、再换一个模型，然后撞上同一堵墙。

    @Test
    fun `OpenAI content_filter 算被拦`() {
        assertThat(isBlockedStopReason("content_filter")).isTrue()
    }

    @Test
    fun `Claude refusal 算被拦`() {
        assertThat(isBlockedStopReason("refusal")).isTrue()
    }

    @Test
    fun `Gemini 的六种拦截取值都算被拦`() {
        // finishReason 与 promptFeedback.blockReason 两处的取值空间合起来就是这几个
        assertThat(isBlockedStopReason("SAFETY")).isTrue()
        assertThat(isBlockedStopReason("IMAGE_SAFETY")).isTrue()
        assertThat(isBlockedStopReason("RECITATION")).isTrue()
        assertThat(isBlockedStopReason("BLOCKLIST")).isTrue()
        assertThat(isBlockedStopReason("PROHIBITED_CONTENT")).isTrue()
        assertThat(isBlockedStopReason("SPII")).isTrue()
    }

    @Test
    fun `被拦判定同样不看大小写与分隔符`() {
        assertThat(isBlockedStopReason("Content-Filter")).isTrue()
        assertThat(isBlockedStopReason("content filter")).isTrue()
        assertThat(isBlockedStopReason("  SAFETY  ")).isTrue()
        // 变体拼写：用「包含」匹配就是为了这些自造说法
        assertThat(isBlockedStopReason("safety_block")).isTrue()
        assertThat(isBlockedStopReason("response_content_filter")).isTrue()
    }

    @Test
    fun `正常收尾与长度截断都不算被拦`() {
        assertThat(isBlockedStopReason("stop")).isFalse()
        assertThat(isBlockedStopReason("STOP")).isFalse()
        assertThat(isBlockedStopReason("end_turn")).isFalse()
        assertThat(isBlockedStopReason("tool_calls")).isFalse()
        assertThat(isBlockedStopReason("tool_use")).isFalse()
        assertThat(isBlockedStopReason("stop_sequence")).isFalse()
        // 截断那一族必须落在另一边，否则「调大 max tokens」这句话再也不会出现
        assertThat(isBlockedStopReason("length")).isFalse()
        assertThat(isBlockedStopReason("max_tokens")).isFalse()
        assertThat(isBlockedStopReason("MAX_TOKENS")).isFalse()
    }

    @Test
    fun `刻意排除的取值不算被拦`() {
        // 这四类的补救办法都不是「改写措辞」，收进来只会把成因说错：
        // OTHER / BLOCK_REASON_UNSPECIFIED 是服务端自己也没说，最常见的真实成因是
        // 「模型不支持本次请求」；LANGUAGE 该做的是换模型；两个 function call 是工具格式问题。
        assertThat(isBlockedStopReason("OTHER")).isFalse()
        assertThat(isBlockedStopReason("BLOCK_REASON_UNSPECIFIED")).isFalse()
        assertThat(isBlockedStopReason("LANGUAGE")).isFalse()
        assertThat(isBlockedStopReason("MALFORMED_FUNCTION_CALL")).isFalse()
        assertThat(isBlockedStopReason("UNEXPECTED_TOOL_CALL")).isFalse()
    }

    @Test
    fun `缺省与空白视为未被拦`() {
        assertThat(isBlockedStopReason(null)).isFalse()
        assertThat(isBlockedStopReason("")).isFalse()
        assertThat(isBlockedStopReason("   ")).isFalse()
        assertThat(isBlockedStopReason("_-_")).isFalse()
    }

    @Test
    fun `两个判断互斥 没有取值同时算截断和被拦`() {
        // 结构性保证：一个取值同时命中两边，用户会先收到「调大 max tokens」再收到
        // 「改写措辞」，两句话互相否定。全部已知取值走一遍。
        val all = listOf(
            "stop", "length", "tool_calls", "function_call", "content_filter",
            "end_turn", "max_tokens", "stop_sequence", "tool_use", "pause_turn", "refusal",
            "STOP", "MAX_TOKENS", "SAFETY", "RECITATION", "LANGUAGE", "OTHER", "BLOCKLIST",
            "PROHIBITED_CONTENT", "SPII", "MALFORMED_FUNCTION_CALL", "IMAGE_SAFETY",
            "UNEXPECTED_TOOL_CALL", "BLOCK_REASON_UNSPECIFIED"
        )
        all.forEach { reason ->
            assertThat(isTruncatedStopReason(reason) && isBlockedStopReason(reason)).isFalse()
        }
    }

    // ---- presentableBlockReason：拼进用户提示里的那一段 ----
    // 这串是网络来的，最终落在 Snackbar 的一句话里，所以既要保留原词（用户拿它去搜服务商文档），
    // 又必须净化（控制符会把单行提示撑成多行，超长取值会把「改写措辞后重试」顶出可视区）。

    @Test
    fun `原词原样保留 不翻译`() {
        assertThat(presentableBlockReason("SAFETY")).isEqualTo("SAFETY")
        assertThat(presentableBlockReason("content_filter")).isEqualTo("content_filter")
        assertThat(presentableBlockReason("PROHIBITED_CONTENT")).isEqualTo("PROHIBITED_CONTENT")
        assertThat(presentableBlockReason("refusal")).isEqualTo("refusal")
    }

    @Test
    fun `首尾空白剔掉但内部拼写不动`() {
        assertThat(presentableBlockReason("  RECITATION  ")).isEqualTo("RECITATION")
    }

    @Test
    fun `信息量为零的取值返回 null`() {
        // 给用户看一串 BLOCK_REASON_UNSPECIFIED 不如什么都不说：上层会改用不带括号的通用文案
        assertThat(presentableBlockReason("OTHER")).isNull()
        assertThat(presentableBlockReason("other")).isNull()
        assertThat(presentableBlockReason("BLOCK_REASON_UNSPECIFIED")).isNull()
        assertThat(presentableBlockReason("unspecified")).isNull()
    }

    @Test
    fun `缺省与空白返回 null`() {
        assertThat(presentableBlockReason(null)).isNull()
        assertThat(presentableBlockReason("")).isNull()
        assertThat(presentableBlockReason("   ")).isNull()
        // 只由分隔符组成：归一化后为空，等同于「服务端没说」
        assertThat(presentableBlockReason("_-_")).isNull()
    }

    @Test
    fun `控制符被剔掉`() {
        // 换行在 Compose 的 Text 里照样换行，会把一句话的提示撑成多行
        val reason = "SAFETY" + Char(10) + Char(9) + "X"
        assertThat(presentableBlockReason(reason)).isEqualTo("SAFETYX")
    }

    @Test
    fun `超长取值被截断到上限`() {
        // 异常长的取值（中转自己拼的一大段说明）会把真正要说的那句话顶出屏幕
        val long = "A".repeat(200)
        val presented = presentableBlockReason(long)
        assertThat(presented).isNotNull()
        assertThat(presented!!.length).isEqualTo(40)
    }
}
