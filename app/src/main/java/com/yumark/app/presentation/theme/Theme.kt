package com.yumark.app.presentation.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 动态取色方案的对比度纠正。
 *
 * 两个静态主题的色值已经在 [AppThemes] 里改到达标，动态取色的配色来自壁纸、编译期不存在，
 * 只能在这里运行期纠。这个不对称是刻意的：能在源头写死的就写死（改动看得见、可 review、
 * 有单测钉住），写不死的才现算。
 *
 * 只纠两个角色，正是静态主题里需要动手的那两个：
 * - `outline`：28 个 `OutlinedTextField` 未聚焦边框的取色，属 WCAG 1.4.11 的「有意义的控件
 *   边界」，需 ≥3:1。
 * - `onSurfaceVariant`：全部次级文字（时间戳、提示语、说明文字），需 ≥4.5:1。
 *
 * `outlineVariant`（44 处分隔线）刻意不纠，理由见 [AppThemes] 的类注释。已达标时
 * [ensureContrast] 原样返回，所以在配色本就合规的机器上这就是个空操作。
 */
private fun ColorScheme.withAccessibleContrast(isDark: Boolean): ColorScheme {
    val surfaces = listOf(
        surface, background, surfaceVariant,
        surfaceContainer, surfaceContainerHigh, surfaceContainerHighest
    )
    return copy(
        outline = ensureContrast(outline, surfaces, CONTRAST_UI, towardWhite = isDark),
        onSurfaceVariant = ensureContrast(onSurfaceVariant, surfaces, CONTRAST_TEXT, towardWhite = isDark)
    )
}

/**
 * 主题 + 深浅 → 实际生效的 ColorScheme。
 *
 * 动态取色（[AppTheme.dynamic]）的配色在编译期不存在，只能在这里拿 Context 现算，所以取色
 * 这一步不能留在 [AppThemes] 里（那是个拿不到 Context 的单例）。`dynamicLightColorScheme` /
 * `dynamicDarkColorScheme` 都是 `@RequiresApi(S)`，minSdk 26 下这个版本判断是必须的。
 *
 * 单独抽成 public 函数而不是塞进 [YuMarkTheme]：设置页那颗主题色点要显示「这个主题长什么样」，
 * 必须和这里走同一条取色路径，否则动态取色那一行永远显示默认主题的灰蓝色。
 */
@Composable
fun appColorScheme(theme: AppTheme, darkTheme: Boolean): ColorScheme {
    val context = LocalContext.current
    if (theme.dynamic && AppThemes.isDynamicColorAvailable) {
        val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return remember(dynamic, darkTheme) { dynamic.withAccessibleContrast(darkTheme) }
    }
    return if (darkTheme) theme.dark else theme.light
}

@Composable
fun YuMarkTheme(
    themeId: String = AppThemes.DEFAULT_ID,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // resolve 而不是 byId：配置从新手机搬到 API 31 以下的机器时，themeId 里可能躺着 "dynamic"
    val theme = AppThemes.resolve(themeId)
    val colorScheme = appColorScheme(theme, darkTheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 不再设置 window.statusBarColor：该 API 自 API 35 起被弃用且在 targetSdk ≥ 35 时
            // 完全失效（强制 edge-to-edge，系统栏一律透明，由应用自绘背景）。
            // MainActivity 已调用 enableEdgeToEdge()，此处只需同步状态栏图标明暗。
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    // 扩展语义色全部从生效的 colorScheme 现算，所以动态取色也有达标的成功/警告/品牌前景色。
    // remember 挂在 colorScheme 上：一次主题或深浅切换才重算一次，几十次浮点运算可以忽略。
    val extended = remember(colorScheme) {
        extendedColorsFor(colorScheme, isDark = darkTheme)
    }

    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            // 不传 shapes 时 M3 用基线的 4/8/12/16/28dp，而应用里又零散写着 8/14/20dp：
            // 同一屏能出现五种圆角。见 AppShapeScheme 的注释。
            shapes = AppShapeScheme,
            content = content
        )
    }
}
