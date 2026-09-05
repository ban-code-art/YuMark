package com.yumark.app.core.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 离屏导出渲染的**收敛判定**（纯逻辑，无 Android 依赖，可在 JVM 单测里跑）。
 *
 * 拆出来的原因：判定"页面渲完了没有"是一段有状态的时序逻辑（高度还在跳 / mermaid 还没出 svg /
 * 图还没解码 / 超时了但页面已有内容），而它原先只是 `WebViewDocumentRenderer` 里一句
 * `postDelayed(1500ms)`——固定睡眠在慢设备上导出白图、在快设备上白等，且完全无法测试。
 */

/** 收敛轮询间隔：足够密集到不明显拖慢导出，又不至于把主线程刷成忙等。 */
internal const val SETTLE_POLL_INTERVAL_MS = 120L

/** 收敛总超时。超时不等于失败，见 [SettleDecision]。 */
internal const val SETTLE_TIMEOUT_MS = 10_000L

/** 高度需连续稳定的探针次数：KaTeX/表格重排会让高度跳变，跳变期间分页必错位。 */
internal const val SETTLE_STABLE_PROBES = 2

/**
 * 解码 [android.webkit.WebView.evaluateJavascript] 的回传值。
 *
 * 回传是**JSON 编码**后的值（字符串会带外层引号与转义）。原先是手写 `replace` 链反转义，
 * 它处理不了 `\uXXXX`、代理对、控制字符，且 `\\n` 这种"字面反斜杠+n"会被错误还原成换行——
 * 导出的 HTML 里只要出现一个 Windows 路径或正则，正文就开始错位。
 *
 * 主路径用平台 JSON 解析器（`JSONArray("[$raw]")` 是把任意 JSON 值当数组元素解析的标准手法）；
 * 平台类不可用时（JVM 单测里的 `org.json` 是 stub，调用即抛）退到 kotlinx-serialization，
 * 保证这段逻辑在单测中真的被执行到，而不是只在设备上才有行为。
 *
 * 返回 null 表示"没有拿到字符串"（JS 抛异常 / 返回 null / 回传不是字符串），
 * 调用方必须按失败处理，不能当成空内容。
 */
internal fun decodeEvaluateJavascriptString(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null
    runCatching { org.json.JSONArray("[$trimmed]").getString(0) }
        .getOrNull()?.let { return it }
    return runCatching {
        (Json.parseToJsonElement(trimmed) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }.getOrNull()
}

/**
 * 页面渲染状态快照，对应注入探针回传的 `{len,mermaid,images,height}`。
 *
 * @param contentLength `#content` 的 innerHTML 长度，判"有没有渲出东西"
 * @param pendingMermaid 尚未长出 `<svg>` 的 mermaid 容器数
 * @param pendingImages `complete !== true` 的 `<img>` 数
 * @param heightPx `document.body.scrollHeight`
 */
internal data class RenderProbe(
    val contentLength: Int,
    val pendingMermaid: Int,
    val pendingImages: Int,
    val heightPx: Int
)

/** 解析探针 JSON；字段缺失按 0 处理，整体不是对象则返回 null（按"这次探针没读到"处理）。 */
internal fun parseRenderProbe(json: String?): RenderProbe? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(json).jsonObject
        RenderProbe(
            contentLength = obj["len"]?.jsonPrimitive?.int ?: 0,
            pendingMermaid = obj["mermaid"]?.jsonPrimitive?.int ?: 0,
            pendingImages = obj["images"]?.jsonPrimitive?.int ?: 0,
            heightPx = obj["height"]?.jsonPrimitive?.int ?: 0
        )
    }.getOrNull()
}

/** 一次探针之后该干什么。 */
internal sealed interface SettleDecision {
    /** 渲染已收敛，可以出图。 */
    object Settled : SettleDecision

    /**
     * 超时但页面已有内容：**带着未完成的部分继续导出**。
     *
     * 与本模块对子资源失败的既有取舍一致（缺一张图的 PDF 也比没有 PDF 好）；
     * 一张画不出来的 mermaid 不该让整篇文档导不出来。
     */
    object TimedOutWithContent : SettleDecision

    /** 超时且页面始终空白，而源文档非空：这是真失败，必须报错而不是产出白纸。 */
    object TimedOutEmpty : SettleDecision

    /** 继续等 [delayMs] 毫秒后再探。 */
    data class Wait(val delayMs: Long) : SettleDecision
}

/**
 * 收敛状态机：喂进探针快照，回答"出图 / 再等 / 超时"。
 *
 * @param expectContent 源 Markdown 非空。空文档渲染出空 `#content` 是合法结果，
 *   若不区分就会把"导出一篇空文档"判成渲染失败。
 * @param nowMs 时钟注入点，单测里用可控时间推进，避免出现真实等待
 */
internal class RenderSettleTracker(
    private val expectContent: Boolean,
    private val timeoutMs: Long = SETTLE_TIMEOUT_MS,
    private val pollIntervalMs: Long = SETTLE_POLL_INTERVAL_MS,
    private val stableProbesRequired: Int = SETTLE_STABLE_PROBES,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val startedAt = nowMs()
    private var lastHeightPx = -1
    private var stableProbes = 0

    /** 只要见过一次非空内容就记住：超时那一刻可能正赶上一次读不到的探针。 */
    private var sawContent = false

    fun onProbe(probe: RenderProbe?): SettleDecision {
        if (probe != null) {
            if (probe.contentLength > 0) sawContent = true
            stableProbes = if (probe.heightPx == lastHeightPx) stableProbes + 1 else 1
            lastHeightPx = probe.heightPx
            val quiet = probe.pendingMermaid == 0 && probe.pendingImages == 0
            val contentOk = probe.contentLength > 0 || !expectContent
            if (quiet && contentOk && stableProbes >= stableProbesRequired) {
                return SettleDecision.Settled
            }
        }
        if (nowMs() - startedAt >= timeoutMs) {
            return if (sawContent || !expectContent) SettleDecision.TimedOutWithContent
            else SettleDecision.TimedOutEmpty
        }
        return SettleDecision.Wait(pollIntervalMs)
    }
}
