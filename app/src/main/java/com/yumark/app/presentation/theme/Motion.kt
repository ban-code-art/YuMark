package com.yumark.app.presentation.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/**
 * 动效时长与缓动。
 *
 * 为什么需要它：`presentation` 下的 `tween(…)` 共 12 处，写着 180 / 220 / 260 / 900 / 1200 /
 * 1400 / 2000 七种时长，其中只有四处显式给了缓动（三处 `FastOutSlowInEasing`、一处
 * `LinearEasing`），其余靠 `tween` 的默认值。同一个应用里「展开一段设置」和「切换一个页面」
 * 用不同的节奏，肉眼看是不齐的。
 *
 * 而更常见的问题是**根本没有动效**：`animateContentSize`、`Crossfade`、`spring` 三个 API 在
 * 这一层建立之前的使用数都是 0，条件区块的显隐、加载态切内容态全是硬切——内容凭空出现，
 * 用户读不出「是我刚才那一下让它出来的」。
 *
 * 分两档而不是一档：**出场比入场快**。元素离开时用户已经不看它了，让它慢慢消失只是在拖慢
 * 下一步操作。现网 `YuMarkNavGraph.kt:41-46` 的 220/180 就是这个比例，这里取 [EnterMs] = 200
 * 与 UI 升级要求钉的 200ms 对齐；导航那两个值属于导航层，等改到那一层再并过来，本轮不动
 * 别人的文件。
 *
 * 刻意**不**封装 `spring`：弹性手感由 `dampingRatio` 与 `stiffness` 的组合决定，抽一个「标准
 * 弹簧」只会让每个调用点都想再传参数覆盖它，白多一层。需要弹性的地方直接写 `spring(…)`
 * 并注明理由。
 */
object AppMotion {
    /** 入场 / 展开：200ms。M3 的 short4，也是本项目 UI 升级要求钉住的默认值。 */
    const val EnterMs = 200

    /** 出场 / 收起：150ms。比入场快一档，让「关掉」显得利落。 */
    const val ExitMs = 150

    /** 整块内容替换（加载态 → 内容态）的交叉淡入淡出。与入场同长，两段首尾相接不留空档。 */
    const val CrossfadeMs = 200

    /**
     * 标准缓动：先快后慢。
     *
     * `tween` 的默认值本就是它，显式写出来是为了让「需要传缓动的调用点」有唯一来源，
     * 而不是各自 import 一遍 `FastOutSlowInEasing`。
     */
    val StandardEasing: Easing = FastOutSlowInEasing

    /**
     * 入场用的 [TweenSpec]。
     *
     * 泛型由调用处的参数类型定：`fadeIn` 要 `Float`，`expandVertically` 要 `IntSize`，
     * 所以这里不能写死类型。
     */
    fun <T> enter(durationMs: Int = EnterMs): TweenSpec<T> =
        tween(durationMs, easing = StandardEasing)

    /** 出场用的 [TweenSpec]，默认 [ExitMs]。 */
    fun <T> exit(durationMs: Int = ExitMs): TweenSpec<T> =
        tween(durationMs, easing = StandardEasing)
}
