package com.yumark.app.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * 钉住「主题必须把颜色角色声明完整」这一条。
 *
 * M3 的 `lightColorScheme()` / `darkColorScheme()` 每个参数都有默认值，默认值来自 M3 **基线**
 * 调色板——那是一套紫色。只声明一半角色，编译照样通过，剩下一半悄悄拿紫色去画：分割线走
 * `outlineVariant`、卡片和对话框走 `surfaceContainer*`、抬升色调走 `surfaceTint`，
 * 于是一个「克制、几乎无色」的灰白主题会在分割线和卡片底色上泛紫，而代码里找不到任何紫色。
 *
 * 基线值不写死在测试里，而是当场用零参数的 `lightColorScheme()` / `darkColorScheme()` 取：
 * 写死等于把库的内部常量抄一遍，升版就过期。「某个角色的值恰好等于零参数基线」== 「这个角色
 * 没被声明过」，这个等价关系不随版本变。
 *
 * 只挑会画到屏幕上、且基线值是紫系（不可能和主题色偶然相等）的角色断言。像 `onPrimary`
 * 这类主题里本来就给白色、基线也是白色的角色不参与——那种相等是巧合，不是缺陷。
 *
 * 有三处「等于基线但完全正确」必须显式豁免，否则这个测试会把对的调色板报成缺陷：
 * - `scrim`（对话框/抽屉背后的遮罩）在 M3 每一套调色板里都是纯黑，相等证明不了任何事，
 *   所以整条断言删掉；`AppThemes` 里也就不必声明它。
 * - 浅色基线的 `surfaceContainerLowest` 是纯白，而「最低层容器用纯白」正是两个浅色主题想要的
 *   效果。
 * - 浅色基线的 `onTertiary` 也是纯白——浅色方案里画在强调色上的文字本来就该是白的，和
 *   `onPrimary`/`onSecondary` 同一类巧合。
 *
 * 后两条只在浅色方向豁免。深色方向照查：深色基线的这两档都不是白色，真忘了声明仍然抓得出来。
 */
class AppThemesTest {

    /**
     * 返回所有「值仍等于基线」的角色名；空表示声明完整。
     *
     * [allowedBaselineMatches] 里的角色跳过不查——见类注释里那两处正当的相等。
     */
    private fun unthemedRoles(
        scheme: ColorScheme,
        baseline: ColorScheme,
        allowedBaselineMatches: Set<String> = emptySet()
    ): List<String> = buildList {
        fun check(name: String, actual: Color, base: Color) {
            if (name !in allowedBaselineMatches && actual == base) add(name)
        }
        check("tertiary", scheme.tertiary, baseline.tertiary)
        check("onTertiary", scheme.onTertiary, baseline.onTertiary)
        check("tertiaryContainer", scheme.tertiaryContainer, baseline.tertiaryContainer)
        check("onTertiaryContainer", scheme.onTertiaryContainer, baseline.onTertiaryContainer)
        check("errorContainer", scheme.errorContainer, baseline.errorContainer)
        check("onErrorContainer", scheme.onErrorContainer, baseline.onErrorContainer)
        check("inversePrimary", scheme.inversePrimary, baseline.inversePrimary)
        check("inverseSurface", scheme.inverseSurface, baseline.inverseSurface)
        check("inverseOnSurface", scheme.inverseOnSurface, baseline.inverseOnSurface)
        check("surfaceTint", scheme.surfaceTint, baseline.surfaceTint)
        check("outlineVariant", scheme.outlineVariant, baseline.outlineVariant)
        check("surfaceBright", scheme.surfaceBright, baseline.surfaceBright)
        check("surfaceDim", scheme.surfaceDim, baseline.surfaceDim)
        check("surfaceContainer", scheme.surfaceContainer, baseline.surfaceContainer)
        check("surfaceContainerLow", scheme.surfaceContainerLow, baseline.surfaceContainerLow)
        check("surfaceContainerLowest", scheme.surfaceContainerLowest, baseline.surfaceContainerLowest)
        check("surfaceContainerHigh", scheme.surfaceContainerHigh, baseline.surfaceContainerHigh)
        check("surfaceContainerHighest", scheme.surfaceContainerHighest, baseline.surfaceContainerHighest)
    }

    @Test
    fun `每个主题的浅色方案都不残留基线紫色角色`() {
        val baseline = lightColorScheme()
        // 浅色基线的这两档本来就是纯白，而两个浅色主题也确实想要纯白，豁免（见类注释）。
        val allowed = setOf("surfaceContainerLowest", "onTertiary")
        AppThemes.all.forEach { theme ->
            assertWithMessage("主题 ${theme.id} 的 light 方案里未声明的角色")
                .that(unthemedRoles(theme.light, baseline, allowed))
                .isEmpty()
        }
    }

    @Test
    fun `每个主题的深色方案都不残留基线紫色角色`() {
        val baseline = darkColorScheme()
        AppThemes.all.forEach { theme ->
            assertWithMessage("主题 ${theme.id} 的 dark 方案里未声明的角色")
                .that(unthemedRoles(theme.dark, baseline))
                .isEmpty()
        }
    }

    @Test
    fun `抬升色调取自主题自己的 primary`() {
        // surfaceTint 是 Surface/Card/TopAppBar 在 tonalElevation > 0 时叠上去的那层颜色。
        // 它必须来自主题的 primary，否则抬升越高越偏离主题。
        AppThemes.all.forEach { theme ->
            assertThat(theme.light.surfaceTint).isEqualTo(theme.light.primary)
            assertThat(theme.dark.surfaceTint).isEqualTo(theme.dark.primary)
        }
    }

    @Test
    fun `byId 对未知与 null 都回退默认主题`() {
        // DataStore 里可能留着旧版本删掉的主题 id，回退必须是默认主题而不是抛异常。
        assertThat(AppThemes.byId(null).id).isEqualTo(AppThemes.DEFAULT_ID)
        assertThat(AppThemes.byId("").id).isEqualTo(AppThemes.DEFAULT_ID)
        assertThat(AppThemes.byId("已经删掉的主题").id).isEqualTo(AppThemes.DEFAULT_ID)
        assertThat(AppThemes.byId(AppThemes.CLAUDE_ID).id).isEqualTo(AppThemes.CLAUDE_ID)
    }

    @Test
    fun `主题 id 唯一且 all 非空`() {
        // byId 用 find 取第一个匹配，id 重复会让后一个主题永远选不中。
        assertThat(AppThemes.all).isNotEmpty()
        assertThat(AppThemes.all.map { it.id }).containsNoDuplicates()
        assertThat(AppThemes.all.map { it.labelRes }).containsNoDuplicates()
    }

    // ---------- 动态取色（Material You） ----------

    @Test
    fun `只有动态取色那一个主题带 dynamic 标记`() {
        // 其余主题的配色都写在源码里，误标 dynamic 会让 appColorScheme 拿壁纸色去画它
        assertThat(AppThemes.all.filter { it.dynamic }.map { it.id })
            .containsExactly(AppThemes.DYNAMIC_ID)
    }

    @Test
    fun `本机不支持动态取色时 resolve 折叠成默认主题`() {
        // 配置备份从 Android 12 手机还原到 Android 8 平板：DataStore 里躺着 "dynamic"
        assertThat(AppThemes.resolve(AppThemes.DYNAMIC_ID, dynamicAvailable = false).id)
            .isEqualTo(AppThemes.DEFAULT_ID)
    }

    @Test
    fun `本机支持时 resolve 原样返回动态主题`() {
        val resolved = AppThemes.resolve(AppThemes.DYNAMIC_ID, dynamicAvailable = true)
        assertThat(resolved.id).isEqualTo(AppThemes.DYNAMIC_ID)
        assertThat(resolved.dynamic).isTrue()
    }

    @Test
    fun `resolve 不动其余主题也保留 byId 的回退`() {
        for (available in listOf(true, false)) {
            assertThat(AppThemes.resolve(AppThemes.CLAUDE_ID, available).id)
                .isEqualTo(AppThemes.CLAUDE_ID)
            assertThat(AppThemes.resolve(null, available).id).isEqualTo(AppThemes.DEFAULT_ID)
            assertThat(AppThemes.resolve("已经删掉的主题", available).id)
                .isEqualTo(AppThemes.DEFAULT_ID)
        }
    }

    @Test
    fun `不支持动态取色的机器上设置页不列出这一项`() {
        // 列出来点了没反应比不列出更糟
        assertThat(AppThemes.selectable(dynamicAvailable = false).map { it.id })
            .containsExactly(AppThemes.DEFAULT_ID, AppThemes.CLAUDE_ID)
            .inOrder()
        assertThat(AppThemes.selectable(dynamicAvailable = true)).isEqualTo(AppThemes.all)
    }

    @Test
    fun `动态主题的兜底配色就是默认主题那一套`() {
        // 兜底必须是一套完整的真配色：拿不到动态配色时（appColorScheme 走 else 分支、
        // 或者别处直接读 theme.light）用户看到的是默认主题，而不是 M3 基线紫。
        val dynamic = AppThemes.byId(AppThemes.DYNAMIC_ID)
        val default = AppThemes.byId(AppThemes.DEFAULT_ID)
        assertThat(dynamic.light).isEqualTo(default.light)
        assertThat(dynamic.dark).isEqualTo(default.dark)
    }
}
