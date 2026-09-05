package com.yumark.app.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Compose 颜色 → CSS 十六进制串（`#RRGGBB`）。
 *
 * 存在的理由是预览区那个 WebView：它的正文样式由 Kotlin 侧注入一段 `:root{…}`，注入的值必须
 * 和应用自己的配色是同一个来源。以前那两处（预览注入色、WebView 原生背景色）各写着一张
 * `when (themeId) { "claude" -> "#262624" … }` 的表，等于把 [AppThemes] 的深色背景抄了两遍：
 * 加一个主题要顺手改这两张表，漏改不报错也不崩，只是预览区继续用上一个主题的底色；改
 * [AppThemes] 里的深色背景更糟，两处根本不会跟着变。改成从 `MaterialTheme.colorScheme` 现取
 * 现转之后，动态取色（配色在编译期根本不存在，见 [AppThemes.DYNAMIC_ID]）也自动跟着走。
 *
 * 丢掉 alpha 是有意的：CSS 那边要的是 `background` 的不透明底色，而 `colorScheme` 里的
 * background / onBackground 本来就是全不透明色。真传进来一个半透明色时按不透明处理，也比
 * 输出一个 8 位十六进制串让 CSS 整条声明失效好。
 *
 * 不用 `"#%06X".format(…)`：`java.util.Formatter` 的整数转换会按默认 Locale 挑数字字形，
 * 阿拉伯语等语区下可能吐出非 ASCII 数字，那串东西 CSS 不认。[Int.toString] 与
 * [String.uppercase] 都与 Locale 无关。
 */
fun Color.toCssHex(): String =
    "#" + (toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
