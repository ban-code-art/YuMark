package com.yumark.app.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * 常驻的 WCAG 对比度门禁。取代人工复核色值——人眼判断不了 4.39:1 和 4.52:1 的区别。
 *
 * 覆盖两类配对，阈值来自 WCAG 2.1：
 * - **文字压在填充上**（`onPrimary`/`primary` 这类 M3 的 on-X / X 配对）→ 1.4.3 正文 4.5:1。
 * - **前景压在容器家族上**（次级文字、控件边框）→ 正文 4.5:1，控件边界按 1.4.11 的 3:1。
 *
 * 底色取**整个容器家族的最差一档**而不只是 `surface`。理由是同一个前景色会落在多种底上：M3 的
 * `AlertDialog` 容器是 `surfaceContainerHigh`、`ModalBottomSheet` 是 `surfaceContainerLow`、
 * `DropdownMenu` 是 `surfaceContainer`，而应用里 28 个 `OutlinedTextField` 就有落在对话框里的。
 * 只按 `surface` 求解，同一个边框搬进对话框就又不达标了。
 *
 * `surfaceBright`/`surfaceDim`/`surfaceContainerLowest` **不纳入**：这三个角色在本应用里显式引用
 * 数为 0，也没有任何 M3 组件的默认容器色映射到它们，所以永远不会有内容画在上面。纳入只会凭空
 * 收紧约束（浅色主题里 `surfaceDim` 比 `surfaceContainerHighest` 更深，会把所有次级文字再压深
 * 一档），换不来任何真实可读性。
 *
 * 动态取色主题不在这里断言——它的色值编译期不存在。它走 `Theme.kt` 的
 * `withAccessibleContrast` 运行期纠正，由 `ExtendedColorsTest` 用合成配色验证那条算法。
 */
class ThemeContrastTest {

    private data class Target(val name: String, val scheme: ColorScheme)

    /**
     * 参与断言的四套静态方案。
     *
     * 名字里带 light/dark 不是为了好看：断言失败时报的就是这个名字，得能一眼定位到
     * `AppThemes.kt` 里的哪一块。
     */
    private val targets: List<Target> = AppThemes.all
        .filterNot { it.dynamic }
        .flatMap {
            listOf(
                Target(it.id + "-light", it.light),
                Target(it.id + "-dark", it.dark)
            )
        }

    /**
     * 角色名 → 色值。用 Map 而不是直接写 `scheme.onPrimary to scheme.primary`：配对表因此变成
     * 一串字符串，加一对配对只需加一行，而且失败消息里能直接打出角色名。
     */
    private fun roles(s: ColorScheme): Map<String, Color> = mapOf(
        "primary" to s.primary,
        "onPrimary" to s.onPrimary,
        "primaryContainer" to s.primaryContainer,
        "onPrimaryContainer" to s.onPrimaryContainer,
        "secondary" to s.secondary,
        "onSecondary" to s.onSecondary,
        "secondaryContainer" to s.secondaryContainer,
        "onSecondaryContainer" to s.onSecondaryContainer,
        "tertiary" to s.tertiary,
        "onTertiary" to s.onTertiary,
        "tertiaryContainer" to s.tertiaryContainer,
        "onTertiaryContainer" to s.onTertiaryContainer,
        "error" to s.error,
        "onError" to s.onError,
        "errorContainer" to s.errorContainer,
        "onErrorContainer" to s.onErrorContainer,
        "background" to s.background,
        "onBackground" to s.onBackground,
        "surface" to s.surface,
        "onSurface" to s.onSurface,
        "surfaceVariant" to s.surfaceVariant,
        "onSurfaceVariant" to s.onSurfaceVariant,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
        "inverseSurface" to s.inverseSurface,
        "inverseOnSurface" to s.inverseOnSurface,
        "outline" to s.outline,
        "outlineVariant" to s.outlineVariant
    )

    /** M3 的 on-X / X 配对：文字直接压在填充上，一律 4.5:1。 */
    private val onPairs = listOf(
        "onPrimary" to "primary",
        "onSecondary" to "secondary",
        "onTertiary" to "tertiary",
        "onError" to "error",
        "onBackground" to "background",
        "onSurface" to "surface",
        "onSurfaceVariant" to "surfaceVariant",
        "onPrimaryContainer" to "primaryContainer",
        "onSecondaryContainer" to "secondaryContainer",
        "onTertiaryContainer" to "tertiaryContainer",
        "onErrorContainer" to "errorContainer",
        "inverseOnSurface" to "inverseSurface"
    )

    /** 一个前景色可能落在的全部底色，见类注释。 */
    private val surfaces = listOf(
        "surface", "background", "surfaceVariant",
        "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest"
    )

    /** 会落在 [surfaces] 上的前景角色及其阈值。 */
    private val foregrounds = listOf(
        "onSurface" to CONTRAST_TEXT,
        "onSurfaceVariant" to CONTRAST_TEXT,
        "outline" to CONTRAST_UI
    )

    /**
     * 明知不达标、**已经过确认保留**的豁免项。键是 `方案|前景|底色`。
     *
     * 写成显式清单而不是把断言注释掉：清单进得了失败消息，也进得了 code review，而注释掉的断言
     * 三个月后没人记得为什么。[exemptionsMustStillFail] 会盯着这份清单不让它腐烂。
     */
    private val exemptions = mapOf(
        "claude-light|onPrimary|primary" to
            "Claude 的赤陶橙是主题的视觉签名。达标只有两条路：压深 primary，或把按钮文字改成深色，" +
            "两者都会改掉这个主题的身份。品牌色当文字/图标/光标的真实可读性问题由 " +
            "ExtendedColors.primaryText 解决（同色相同饱和度、亮度达标）。",
        "claude-dark|onPrimary|primary" to
            "同上。Claude 的 primary 在深浅两套里是同一个值，所以这组配对两边同为 3.12:1。"
    )

    private fun key(target: String, fg: String, bg: String): String =
        target + "|" + fg + "|" + bg

    /** 收集全部不达标项再一次性报出来，而不是第一个失败就停——否则修一轮只能看见一个问题。 */
    private fun sweep(check: (Target, Map<String, Color>, MutableList<String>) -> Unit) {
        val failures = mutableListOf<String>()
        targets.forEach { target -> check(target, roles(target.scheme), failures) }
        assertWithMessage(
            "以下配对低于 WCAG 阈值。改 AppThemes.kt 的色值修它；若判定为可接受的偏差，" +
                "加进 exemptions 并写清理由，不要注释掉断言。\n" + failures.joinToString("\n")
        ).that(failures).isEmpty()
    }

    @Test
    fun `on-X 压在 X 上达到正文 4-5 比 1`() = sweep { target, r, failures ->
        onPairs.forEach { (fg, bg) ->
            if (key(target.name, fg, bg) in exemptions) return@forEach
            val actual = contrastRatio(r.getValue(fg), r.getValue(bg))
            if (actual < CONTRAST_TEXT) {
                failures += "  %s %s/%s = %.2f (需 %.1f)".format(
                    target.name, fg, bg, actual, CONTRAST_TEXT
                )
            }
        }
    }

    @Test
    fun `次级文字与控件边框压在整个容器家族上都达标`() = sweep { target, r, failures ->
        foregrounds.forEach { (fg, threshold) ->
            surfaces.forEach { bg ->
                if (key(target.name, fg, bg) in exemptions) return@forEach
                val actual = contrastRatio(r.getValue(fg), r.getValue(bg))
                if (actual < threshold) {
                    failures += "  %s %s/%s = %.2f (需 %.1f)".format(
                        target.name, fg, bg, actual, threshold
                    )
                }
            }
        }
    }

    /**
     * 豁免清单不许腐烂：某项一旦真的达标了，就该把它从清单里删掉，而不是留着一条谎。
     *
     * 反向断言看着别扭，作用很实在——它让「Claude 的按钮文字仍然只有 3.12:1」这件事在测试报告里
     * 有名有姓，而不是靠一条注释和一份三个月前的会议记录。
     */
    @Test
    fun exemptionsMustStillFail() {
        val byName = targets.associateBy { it.name }
        val stale = mutableListOf<String>()
        exemptions.forEach { (k, reason) ->
            val (name, fg, bg) = k.split("|")
            val r = roles(byName.getValue(name).scheme)
            val actual = contrastRatio(r.getValue(fg), r.getValue(bg))
            val threshold = if (fg == "outline") CONTRAST_UI else CONTRAST_TEXT
            if (actual >= threshold) {
                stale += "  %s 现在是 %.2f，已达标 %.1f —— 从 exemptions 里删掉这条。理由原文：%s"
                    .format(k, actual, threshold, reason)
            }
        }
        assertWithMessage("豁免清单里有已经达标的项：\n" + stale.joinToString("\n"))
            .that(stale).isEmpty()
    }

    /**
     * `outlineVariant` **刻意**不达 3:1，这条断言把这个决定钉住。
     *
     * 它是 44 处 `HorizontalDivider`/`VerticalDivider` 的实际取色（M3 `DividerDefaults.color`
     * 就是它）。装饰性分隔线在 WCAG 1.4.11 里明确豁免，M3 官方基线的 `outlineVariant` 同样不
     * 满足 3:1——低对比度是规范的意图。强行提到 3:1，应用里每一条发丝线都会变成粗黑线。
     *
     * 断言方向是反的（要求**不**达标）：谁把它提上去了，这里就会失败，提醒他连同 `AppThemes` 的
     * 类注释一起改，而不是悄悄让全应用的分隔线变重。真正需要 3:1 的控件边界走 `outline`。
     */
    @Test
    fun `outlineVariant 保持发丝观感`() {
        val tooStrong = mutableListOf<String>()
        targets.forEach { target ->
            val r = roles(target.scheme)
            val actual = contrastRatio(r.getValue("outlineVariant"), r.getValue("surface"))
            if (actual >= CONTRAST_UI) {
                tooStrong += "  %s outlineVariant/surface = %.2f".format(target.name, actual)
            }
        }
        assertWithMessage(
            "outlineVariant 被提到了 3:1 以上。它喂的是 44 处分隔线，装饰性元素在 WCAG 1.4.11 里" +
                "豁免；若这是有意的改动，请同时更新 AppThemes 的类注释和本断言。\n" +
                tooStrong.joinToString("\n")
        ).that(tooStrong).isEmpty()
    }
}
