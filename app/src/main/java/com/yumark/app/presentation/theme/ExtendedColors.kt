package com.yumark.app.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * M3 的 34 个颜色角色之外，应用真正需要而 M3 没有的几个语义色。
 *
 * 为什么必须扩展，而不是凑合用现成角色：
 * - **成功态**：`AiConfigScreen` 的「连接测试成功」和 `DiffView` 的新增行色条原先都借用
 *   `primary`。在 Claude 主题下 `primary` 是赤陶橙，于是「成功」显示成橙色，和旁边红色的
 *   失败态并列时读起来像警告。M3 里没有 success 角色，`tertiary` 是「第三强调色」而不是
 *   语义色，借它一样是错的（而且它另有装饰用途，两边会互相拉扯）。
 * - **品牌色当前景**：`primary` 的职责是**填充**（按钮底、色块），M3 从不保证它压在 surface
 *   上可读。Claude 的赤陶橙压在米白底上是 2.41:1，而应用里有 40 处把它当文字/图标/光标色。
 *   [primaryText] 是同色相同饱和度、亮度压到 4.5:1 的版本，专供前景用途。
 *
 * **危险态直接复用 M3 的 `error`/`errorContainer`**，不在这里重复造一份——那是 M3 唯一
 * 给全了的语义色，两套并存只会让人不知道该用哪个。
 *
 * 所有值都是从传入的 [ColorScheme] **现算**的，不写死。这一条不是洁癖：动态取色主题的配色
 * 来自壁纸，编译期根本不存在，写死就等于动态取色下这几个语义色永远达不到标。算的代价是每次
 * 主题切换跑几十次浮点运算，在 `remember` 里做一次，可以忽略。
 */
data class ExtendedColors(
    /** 品牌色的前景安全版：文字、图标、光标。色相/饱和度与 `primary` 一致，亮度达标 4.5:1。 */
    val primaryText: Color,
    /** 成功态前景：文字、图标、diff 新增色条。 */
    val success: Color,
    /** 成功态填充：徽章底、行高亮底。 */
    val successContainer: Color,
    /** 画在 [successContainer] 上的文字。 */
    val onSuccessContainer: Color,
    /** 警告态前景。 */
    val warning: Color,
    /** 警告态填充。 */
    val warningContainer: Color,
    /** 画在 [warningContainer] 上的文字。 */
    val onWarningContainer: Color
)

/** WCAG 2.1 相对亮度。sRGB 分段传递函数，阈值与系数照规范原文。 */
internal fun relativeLuminance(color: Color): Float {
    fun lin(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * lin(color.red) + 0.7152f * lin(color.green) + 0.0722f * lin(color.blue)
}

/** WCAG 2.1 对比度，1.0（同色）～21.0（纯黑白）。参数顺序无关。 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * 把 [color] 往深或往浅推到刚好满足 [target]，色相与饱和度不变。
 *
 * 关键在于**在线性空间里等比缩放**：三个通道同乘一个系数，色度坐标严格不动，所以只有亮度
 * 变、色相饱和度一个像素不偏。若在 sRGB 空间缩放（更直觉的写法）会因为 gamma 曲线把颜色
 * 拉偏色相，赤陶橙会缩成脏褐色。
 *
 * 方向由 [towardWhite] 决定：深色主题上前景要提亮（往白推），浅色主题上要压深（往黑推）。
 * 已达标时原样返回，不做无意义的微调。
 *
 * [backgrounds] 是这个前景色可能落在的**全部**底色。取其中最差的一档求解——只按 `surface`
 * 算的话，同一个字压到 `surfaceVariant` 上就又不达标了。
 */
internal fun ensureContrast(
    color: Color,
    backgrounds: List<Color>,
    target: Float,
    towardWhite: Boolean
): Color {
    fun worst(c: Color): Float = backgrounds.minOf { contrastRatio(c, it) }
    if (backgrounds.isEmpty() || worst(color) >= target) return color

    fun at(t: Float): Color {
        fun lin(c: Float): Float =
            if (c <= 0.03928f) c / 12.92f
            else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

        fun srgb(v: Float): Float {
            val x = v.coerceIn(0f, 1f)
            return if (x <= 0.0031308f) x * 12.92f
            else (1.055f * Math.pow(x.toDouble(), 1.0 / 2.4).toFloat() - 0.055f)
        }

        fun ch(c: Float): Float {
            val l = lin(c)
            return srgb(if (towardWhite) l + (1f - l) * t else l * (1f - t))
        }
        return Color(ch(color.red), ch(color.green), ch(color.blue), color.alpha)
    }

    // 二分 60 次：单调函数上足够收敛到 8 位色深以下，比逐级试色可预测。
    var lo = 0f
    var hi = 1f
    repeat(60) {
        val mid = (lo + hi) / 2f
        if (worst(at(mid)) >= target) hi = mid else lo = mid
    }
    return at(hi)
}

/** 正文文字的 WCAG AA 阈值。 */
internal const val CONTRAST_TEXT = 4.5f

/** 控件边界与图形对象的阈值（WCAG 1.4.11 非文本对比度）。 */
internal const val CONTRAST_UI = 3.0f

/** 成功态的基准绿。深浅各一支，之后再按实际底色压到达标。 */
private val SuccessSeedLight = Color(0xFF2E7D32)
private val SuccessSeedDark = Color(0xFF81C995)

/** 警告态的基准琥珀色。同上。 */
private val WarningSeedLight = Color(0xFF9A6400)
private val WarningSeedDark = Color(0xFFF5BE5C)

/**
 * 从一套 [ColorScheme] 算出配套的 [ExtendedColors]。
 *
 * [scheme] 的六档容器底色全部纳入求解，所以算出来的前景色在卡片、列表行、弹层里都达标，
 * 而不只是在 `surface` 上达标。
 */
internal fun extendedColorsFor(scheme: ColorScheme, isDark: Boolean): ExtendedColors {
    val surfaces = listOf(
        scheme.surface,
        scheme.background,
        scheme.surfaceVariant,
        scheme.surfaceContainer,
        scheme.surfaceContainerHigh,
        scheme.surfaceContainerHighest
    )
    fun fg(seed: Color) = ensureContrast(seed, surfaces, CONTRAST_TEXT, towardWhite = isDark)

    val success = fg(if (isDark) SuccessSeedDark else SuccessSeedLight)
    val warning = fg(if (isDark) WarningSeedDark else WarningSeedLight)
    // 填充色走另一个方向：深色主题里容器要比前景更暗，浅色主题里要更亮。
    val successContainer = ensureContrast(success, listOf(success), 3.2f, towardWhite = !isDark)
    val warningContainer = ensureContrast(warning, listOf(warning), 3.2f, towardWhite = !isDark)
    return ExtendedColors(
        primaryText = fg(scheme.primary),
        success = success,
        successContainer = successContainer,
        onSuccessContainer = ensureContrast(success, listOf(successContainer), CONTRAST_TEXT, isDark),
        warning = warning,
        warningContainer = warningContainer,
        onWarningContainer = ensureContrast(warning, listOf(warningContainer), CONTRAST_TEXT, isDark)
    )
}

/**
 * 兜底值取默认主题的浅色方案，而不是一堆占位垃圾色。
 *
 * `staticCompositionLocalOf` 而非 `compositionLocalOf`：这套值一次主题切换才变一回，用静态版
 * 让读取处不参与细粒度重组失效跟踪，省掉每个消费点的订阅开销。
 */
val LocalExtendedColors = staticCompositionLocalOf {
    extendedColorsFor(AppThemes.byId(AppThemes.DEFAULT_ID).light, isDark = false)
}

/** `MaterialTheme.colorScheme` 的邻居：`MaterialTheme.extendedColors`。 */
val extendedColors: ExtendedColors
    @Composable @ReadOnlyComposable get() = LocalExtendedColors.current
