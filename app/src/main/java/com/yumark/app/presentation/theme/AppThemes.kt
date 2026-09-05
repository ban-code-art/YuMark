package com.yumark.app.presentation.theme

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.yumark.app.R

/**
 * 应用主题：每个主题包含浅色与深色两套 ColorScheme，深浅由系统深色模式决定
 *
 * [labelRes] 是资源 id 而不是成句的 String：这个对象是个拿不到 Context 的单例，
 * 存字符串等于在这里把语言定死，取名的地方（设置页）再也翻不了。
 *
 * [dynamic] 标记「配色不在这里，运行期从壁纸算」。这种主题的 [light]/[dark] 仍然填着一套
 * 真配色，作用是拿不到动态配色时的兜底（本对象拿不到 Context，算不了动态配色，所以字段
 * 不能留空）。真正取色的地方是 [appColorScheme]，它有 Context；直接读 `theme.light` 的地方
 * （如设置页的色点）拿到的是兜底色，需要动态色就得走 [appColorScheme]。
 */
data class AppTheme(
    val id: String,
    @StringRes val labelRes: Int,
    val light: ColorScheme,
    val dark: ColorScheme,
    val dynamic: Boolean = false
)

/**
 * 三个内置主题。**id 一个都不能改、不能删**——用户的 DataStore 里存的就是这些字符串。
 *
 * 色值的对比度纪律（由 `ThemeContrastTest` 常驻把关，不靠人复核）：
 * - 文字类配对 ≥4.5:1（WCAG AA 正文），控件边界 ≥3:1（WCAG 1.4.11）。
 * - `outlineVariant` **刻意不达标**：它是 44 处 `HorizontalDivider` 的取色，装饰性分隔线在
 *   1.4.11 里明确豁免，M3 官方基线同样是低对比度的。强行提到 3:1 会让全应用的发丝线变粗黑。
 * - `onPrimary`/`primary` 在 Claude 主题是 3.12:1，**已知且经确认保留**：达标只能压深赤陶色
 *   或把按钮文字改成深色，两者都会改掉这个主题的视觉签名。真正的读性问题（品牌色当文字、
 *   图标、光标）由 [ExtendedColors.primaryText] 解决——它是从 `primary` 现算出来的达标版本，
 *   色相与饱和度和 `primary` 完全一致。
 *
 * 动态取色主题的色值编译期不存在，纠正在 [appColorScheme] 里运行期做。
 */
object AppThemes {
    const val DEFAULT_ID = "default"
    const val CLAUDE_ID = "claude"

    /** 动态取色（Material You，跟随壁纸）。配色在编译期不存在，见 [AppTheme.dynamic]。 */
    const val DYNAMIC_ID = "dynamic"

    /** 默认·灰白（Typora 风格：克制、几乎无色） */
    private val DefaultTheme = AppTheme(
        id = DEFAULT_ID,
        labelRes = R.string.theme_default_label,
        light = lightColorScheme(
            primary = Color(0xFF4B5A68),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE8ECEF),
            onPrimaryContainer = Color(0xFF2A3540),
            secondary = Color(0xFF757575),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFEEEEEE),
            onSecondaryContainer = Color(0xFF424242),
            background = Color(0xFFFCFCFC),
            onBackground = Color(0xFF333333),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF333333),
            surfaceVariant = Color(0xFFF3F3F3),
            // 原值 0xFF6B6B6B 压在 surfaceContainerHighest 上是 4.39:1，差一点点
            onSurfaceVariant = Color(0xFF696969),
            // outline 是 28 个 OutlinedTextField 边框的取色，属 WCAG 1.4.11 的「有意义的控件
            // 边界」，需 ≥3:1。原值 0xFFE0E0E0 对最深的一档容器底（surfaceContainerHighest）
            // 只有 1.09:1。输入框不只出现在 surface 上——M3 的 AlertDialog 容器是
            // surfaceContainerHigh，所以按整个容器家族的最差一档求解。见 ThemeContrastTest。
            outline = Color(0xFF858585),
            // outlineVariant 刻意保持发丝观感：它喂的是 44 处 HorizontalDivider（M3
            // DividerDefaults 默认值），装饰性分隔线在 1.4.11 里豁免，M3 官方基线同样不达标。
            outlineVariant = Color(0xFFECECEC),
            tertiary = Color(0xFF60707E),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFE3E8EC),
            onTertiaryContainer = Color(0xFF2F3A44),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFFAE3E1),
            onErrorContainer = Color(0xFF7A1C16),
            inversePrimary = Color(0xFF8FA1B3),
            inverseSurface = Color(0xFF303030),
            inverseOnSurface = Color(0xFFF4F4F4),
            surfaceBright = Color(0xFFFFFFFF),
            surfaceDim = Color(0xFFDDDDDD),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFFAFAFA),
            surfaceContainer = Color(0xFFF5F5F5),
            surfaceContainerHigh = Color(0xFFEFEFEF),
            surfaceContainerHighest = Color(0xFFE9E9E9)
        ),
        dark = darkColorScheme(
            primary = Color(0xFF8FA1B3),
            onPrimary = Color(0xFF1B2733),
            primaryContainer = Color(0xFF37424D),
            onPrimaryContainer = Color(0xFFD5DEE6),
            secondary = Color(0xFF9E9E9E),
            onSecondary = Color(0xFF1E1E1E),
            secondaryContainer = Color(0xFF333333),
            onSecondaryContainer = Color(0xFFCFCFCF),
            background = Color(0xFF1E1E1E),
            onBackground = Color(0xFFDADADA),
            surface = Color(0xFF252526),
            onSurface = Color(0xFFDADADA),
            surfaceVariant = Color(0xFF2D2D2D),
            // 原值 0xFF9E9E9E 压在 surfaceContainerHighest 上是 4.24:1（同浅色）
            onSurfaceVariant = Color(0xFFA3A3A3),
            // 原值 0xFF3D3D3D 对最深的一档容器底只有 1.05:1，提到 3:1（同浅色，见上方注释）
            outline = Color(0xFF848484),
            outlineVariant = Color(0xFF333333),
            tertiary = Color(0xFFA3B2C0),
            onTertiary = Color(0xFF23303B),
            tertiaryContainer = Color(0xFF3B4650),
            onTertiaryContainer = Color(0xFFD9E2EA),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
            errorContainer = Color(0xFF5C1A16),
            onErrorContainer = Color(0xFFF8D7D4),
            inversePrimary = Color(0xFF4B5A68),
            inverseSurface = Color(0xFFDADADA),
            inverseOnSurface = Color(0xFF2D2D2D),
            surfaceBright = Color(0xFF3B3B3B),
            surfaceDim = Color(0xFF161616),
            surfaceContainerLowest = Color(0xFF171717),
            surfaceContainerLow = Color(0xFF202020),
            surfaceContainer = Color(0xFF252526),
            surfaceContainerHigh = Color(0xFF2F2F30),
            surfaceContainerHighest = Color(0xFF3A3A3B)
        )
    )

    /** Claude（米白 + 赤陶橙） */
    private val ClaudeTheme = AppTheme(
        id = CLAUDE_ID,
        labelRes = R.string.theme_claude_label,
        light = lightColorScheme(
            primary = Color(0xFFD97757),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFF1E0D8),
            onPrimaryContainer = Color(0xFF8A4A2F),
            // 原值 0xFF8A8775 配白色 onSecondary 只有 3.62:1
            secondary = Color(0xFF7A7767),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFEAE8E0),
            onSecondaryContainer = Color(0xFF4A4738),
            background = Color(0xFFF5F4ED),
            onBackground = Color(0xFF3D3929),
            surface = Color(0xFFFAF9F5),
            onSurface = Color(0xFF3D3929),
            surfaceVariant = Color(0xFFEAE8E0),
            // 次级文字（时间戳、提示语、说明文字）。原值 0xFF87867F 压在 surfaceVariant 上
            // 只有 2.82:1，是本主题最广的可读性缺陷；4.5:1 按它可能落在的全部容器底色求解。
            onSurfaceVariant = Color(0xFF666560),
            // 原值 0xFFDDD9CC 对最深的一档容器底只有 1.09:1（同默认主题，见上方注释）
            outline = Color(0xFF848179),
            outlineVariant = Color(0xFFE7E3D6),
            // 原值 0xFF7D7B5E 配白色 onTertiary 是 4.31:1，差一点点
            tertiary = Color(0xFF7A785B),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFE6E4D2),
            onTertiaryContainer = Color(0xFF44432F),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFF6E2DC),
            onErrorContainer = Color(0xFF7E2A1C),
            inversePrimary = Color(0xFFF0C9B8),
            inverseSurface = Color(0xFF33322C),
            inverseOnSurface = Color(0xFFF5F4ED),
            surfaceBright = Color(0xFFFDFCF9),
            surfaceDim = Color(0xFFD9D6C9),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFFAF9F5),
            surfaceContainer = Color(0xFFF2F0E8),
            surfaceContainerHigh = Color(0xFFECEAE1),
            surfaceContainerHighest = Color(0xFFE5E2D8)
        ),
        dark = darkColorScheme(
            primary = Color(0xFFD97757),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFF4F352A),
            onPrimaryContainer = Color(0xFFF0C9B8),
            secondary = Color(0xFFA8A595),
            onSecondary = Color(0xFF262624),
            secondaryContainer = Color(0xFF3F3F3C),
            onSecondaryContainer = Color(0xFFDDDACE),
            background = Color(0xFF262624),
            onBackground = Color(0xFFF0EEE6),
            surface = Color(0xFF30302E),
            onSurface = Color(0xFFF0EEE6),
            surfaceVariant = Color(0xFF3A3A37),
            onSurfaceVariant = Color(0xFFB8B5A9),
            // 原值 0xFF4A4A46 对最深的一档容器底只有 1.08:1（同浅色，见上方注释）
            outline = Color(0xFF90908E),
            outlineVariant = Color(0xFF3E3E3A),
            tertiary = Color(0xFFBEBB9C),
            onTertiary = Color(0xFF32311F),
            tertiaryContainer = Color(0xFF4A4936),
            onTertiaryContainer = Color(0xFFDBD8BA),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
            errorContainer = Color(0xFF5E241B),
            onErrorContainer = Color(0xFFF3D9D0),
            inversePrimary = Color(0xFFB35A3C),
            inverseSurface = Color(0xFFF0EEE6),
            inverseOnSurface = Color(0xFF30302E),
            surfaceBright = Color(0xFF454541),
            surfaceDim = Color(0xFF1C1C1A),
            surfaceContainerLowest = Color(0xFF1E1E1C),
            surfaceContainerLow = Color(0xFF2A2A28),
            surfaceContainer = Color(0xFF30302E),
            surfaceContainerHigh = Color(0xFF3A3A37),
            surfaceContainerHighest = Color(0xFF454541)
        )
    )

    /**
     * 动态取色（Material You）：真配色由 [appColorScheme] 在运行期从壁纸算。
     *
     * 这里的 light/dark 直接复用默认主题，是拿不到动态配色时诚实的兜底而不是占位垃圾色：
     * API 31 以下 [resolve] 会把它折叠成默认主题，用户看到的就是默认主题本身。
     */
    private val DynamicTheme = AppTheme(
        id = DYNAMIC_ID,
        labelRes = R.string.theme_dynamic_label,
        light = DefaultTheme.light,
        dark = DefaultTheme.dark,
        dynamic = true
    )

    /** 全部主题。动态取色排在最后：新增项落在列表底部，老用户已经熟悉的两行不挪位置。 */
    val all: List<AppTheme> = listOf(DefaultTheme, ClaudeTheme, DynamicTheme)

    /** 未知/null id 回退默认主题（容忍 DataStore 中的过期值） */
    fun byId(id: String?): AppTheme = all.find { it.id == id } ?: DefaultTheme

    /**
     * 本机能否动态取色。`dynamicLightColorScheme`/`dynamicDarkColorScheme` 是 `@RequiresApi(S)`，
     * 而 minSdk 是 26，所以这个判断不是可选的优化——少了它 lint 的 NewApi 直接报错，
     * 真跑到 API 30 上则是 NoClassDefFoundError。
     *
     * `@ChecksSdkIntAtLeast` 是给 lint 看的：判断被搬进这个属性以后，调用点上就只剩一个
     * 普通的布尔取值，lint 认不出那是版本判断，会照样在 `dynamic*ColorScheme(...)` 上报
     * NewApi（error 级，直接挂）。有了注解 lint 才知道「这个属性为真等价于 SDK_INT >= S」。
     */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val isDynamicColorAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 按 id 取主题，并把本机不支持的动态取色折叠成默认主题。
     *
     * 存在的理由是配置可以跨机器搬：用户在 Android 12 手机上选了动态取色，配置备份还原到一台
     * Android 8 平板上，DataStore 里就躺着一个 `"dynamic"`。不折叠的话 [byId] 会返回
     * [DynamicTheme]，取色走兜底（视觉上没错），但设置页里那一行根本不显示，于是三个单选圈
     * 一个都不选中——用户看到的是「我没选主题」。折叠成默认主题后，选中的是默认那一行。
     *
     * [dynamicAvailable] 可注入：`Build.VERSION.SDK_INT` 在纯 JVM 单测里恒为 0，
     * 不开这个口子这两条分支一行也测不到。
     */
    fun resolve(id: String?, dynamicAvailable: Boolean = isDynamicColorAvailable): AppTheme {
        val theme = byId(id)
        return if (theme.dynamic && !dynamicAvailable) DefaultTheme else theme
    }

    /** 设置页可选的主题：本机不支持动态取色时不列出来（列了点了没反应更糟）。 */
    fun selectable(dynamicAvailable: Boolean = isDynamicColorAvailable): List<AppTheme> =
        if (dynamicAvailable) all else all.filterNot { it.dynamic }
}
