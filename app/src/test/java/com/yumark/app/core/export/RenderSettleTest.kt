package com.yumark.app.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 收敛判定与 evaluateJavascript 回传解码的单测。
 *
 * 这里跑到的是 [decodeEvaluateJavascriptString] 的 **kotlinx 退路**：JVM 单测里
 * `org.json` 来自 mockable android.jar（`isReturnDefaultValues = true`，调用返回默认值），
 * 平台解析器那条分支只有在设备上才会真正生效。两条分支的输入输出契约相同。
 */
class RenderSettleTest {

    @Test
    fun `解码基本转义`() {
        assertThat(decodeEvaluateJavascriptString("\"a\\nb\"")).isEqualTo("a\nb")
        assertThat(decodeEvaluateJavascriptString("\"a\\/b\"")).isEqualTo("a/b")
        assertThat(decodeEvaluateJavascriptString("\"\\t\"")).isEqualTo("\t")
    }

    @Test
    fun `解码 unicode 转义与代理对`() {
        assertThat(decodeEvaluateJavascriptString("\"\\u4f60\\u597d\"")).isEqualTo("你好")
        // U+1F600 😀 在 JSON 里是一对代理转义，手写 replace 链完全处理不了
        assertThat(decodeEvaluateJavascriptString("\"\\ud83d\\ude00\"")).isEqualTo("\uD83D\uDE00")
    }

    @Test
    fun `字面反斜杠加 n 不会被错当成换行`() {
        // JSON 源码 "C:\\path\\n" 代表的字符串是 C:\path\n（反斜杠 + 字母 n），不含换行。
        // 旧的手写解码先做 replace("\\n","\n")，会把它变成 C:\path + 换行。
        val decoded = decodeEvaluateJavascriptString("\"C:\\\\path\\\\n\"")
        assertThat(decoded).isEqualTo("C:\\path\\n")
        assertThat(decoded).doesNotContain("\n")
    }

    @Test
    fun `拿不到字符串一律返回 null 供调用方判失败`() {
        assertThat(decodeEvaluateJavascriptString(null)).isNull()
        assertThat(decodeEvaluateJavascriptString("null")).isNull()
        assertThat(decodeEvaluateJavascriptString("")).isNull()
        assertThat(decodeEvaluateJavascriptString("{\"len\":1}")).isNull()
    }

    @Test
    fun `解析探针 JSON`() {
        val p = parseRenderProbe("""{"len":120,"mermaid":2,"images":1,"height":800}""")
        assertThat(p).isEqualTo(RenderProbe(120, 2, 1, 800))
    }

    @Test
    fun `探针缺字段按 0，坏 JSON 返回 null`() {
        assertThat(parseRenderProbe("""{"len":5}""")).isEqualTo(RenderProbe(5, 0, 0, 0))
        assertThat(parseRenderProbe("not json")).isNull()
        assertThat(parseRenderProbe(null)).isNull()
    }

    @Test
    fun `未完成的 mermaid 与跳变的高度都不算收敛`() {
        var now = 0L
        val tracker = RenderSettleTracker(expectContent = true, nowMs = { now })
        // mermaid 还没出 svg
        assertThat(tracker.onProbe(RenderProbe(10, 1, 0, 500)))
            .isEqualTo(SettleDecision.Wait(SETTLE_POLL_INTERVAL_MS))
        now = 120
        assertThat(tracker.onProbe(RenderProbe(10, 1, 0, 600)))
            .isEqualTo(SettleDecision.Wait(SETTLE_POLL_INTERVAL_MS))
        // mermaid 完成但高度刚变过，仍要再确认一次
        now = 240
        assertThat(tracker.onProbe(RenderProbe(10, 0, 0, 700)))
            .isEqualTo(SettleDecision.Wait(SETTLE_POLL_INTERVAL_MS))
        now = 360
        assertThat(tracker.onProbe(RenderProbe(10, 0, 0, 700))).isEqualTo(SettleDecision.Settled)
    }

    @Test
    fun `图片没解码完不出图`() {
        var now = 0L
        val tracker = RenderSettleTracker(expectContent = true, nowMs = { now })
        tracker.onProbe(RenderProbe(10, 0, 1, 700))
        now = 120
        assertThat(tracker.onProbe(RenderProbe(10, 0, 1, 700)))
            .isEqualTo(SettleDecision.Wait(SETTLE_POLL_INTERVAL_MS))
        now = 240
        assertThat(tracker.onProbe(RenderProbe(10, 0, 0, 700))).isEqualTo(SettleDecision.Settled)
    }

    @Test
    fun `超时但页面有内容则带着未完成部分导出`() {
        var now = 0L
        val tracker = RenderSettleTracker(expectContent = true, timeoutMs = 1_000, nowMs = { now })
        tracker.onProbe(RenderProbe(10, 1, 0, 500))
        now = 1_000
        assertThat(tracker.onProbe(RenderProbe(10, 1, 0, 500)))
            .isEqualTo(SettleDecision.TimedOutWithContent)
    }

    @Test
    fun `超时且页面始终空白必须判失败而不是导出白纸`() {
        var now = 0L
        val tracker = RenderSettleTracker(expectContent = true, timeoutMs = 1_000, nowMs = { now })
        assertThat(tracker.onProbe(RenderProbe(0, 0, 0, 0)))
            .isEqualTo(SettleDecision.Wait(SETTLE_POLL_INTERVAL_MS))
        now = 1_000
        assertThat(tracker.onProbe(RenderProbe(0, 0, 0, 0))).isEqualTo(SettleDecision.TimedOutEmpty)
    }

    @Test
    fun `探针读不到时继续等，超时按空白失败`() {
        var now = 0L
        val tracker = RenderSettleTracker(expectContent = true, timeoutMs = 500, nowMs = { now })
        assertThat(tracker.onProbe(null)).isEqualTo(SettleDecision.Wait(SETTLE_POLL_INTERVAL_MS))
        now = 500
        assertThat(tracker.onProbe(null)).isEqualTo(SettleDecision.TimedOutEmpty)
    }

    @Test
    fun `空文档渲染出空内容也算收敛`() {
        var now = 0L
        val tracker = RenderSettleTracker(expectContent = false, nowMs = { now })
        assertThat(tracker.onProbe(RenderProbe(0, 0, 0, 0)))
            .isEqualTo(SettleDecision.Wait(SETTLE_POLL_INTERVAL_MS))
        now = 120
        assertThat(tracker.onProbe(RenderProbe(0, 0, 0, 0))).isEqualTo(SettleDecision.Settled)
    }
}
