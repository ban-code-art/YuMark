package com.yumark.app.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 应用主题：每个主题包含浅色与深色两套 ColorScheme，深浅由系统深色模式决定
 */
data class AppTheme(
    val id: String,
    val label: String,
    val light: ColorScheme,
    val dark: ColorScheme
)

object AppThemes {
    const val DEFAULT_ID = "default"
    const val CLAUDE_ID = "claude"

    /** 默认·灰白（Typora 风格：克制、几乎无色） */
    private val DefaultTheme = AppTheme(
        id = DEFAULT_ID,
        label = "默认·灰白",
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
            onSurfaceVariant = Color(0xFF6B6B6B),
            outline = Color(0xFFE0E0E0),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF)
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
            onSurfaceVariant = Color(0xFF9E9E9E),
            outline = Color(0xFF3D3D3D),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410)
        )
    )

    /** Claude（米白 + 赤陶橙） */
    private val ClaudeTheme = AppTheme(
        id = CLAUDE_ID,
        label = "Claude",
        light = lightColorScheme(
            primary = Color(0xFFD97757),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFF1E0D8),
            onPrimaryContainer = Color(0xFF8A4A2F),
            secondary = Color(0xFF8A8775),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFEAE8E0),
            onSecondaryContainer = Color(0xFF4A4738),
            background = Color(0xFFF5F4ED),
            onBackground = Color(0xFF3D3929),
            surface = Color(0xFFFAF9F5),
            onSurface = Color(0xFF3D3929),
            surfaceVariant = Color(0xFFEAE8E0),
            onSurfaceVariant = Color(0xFF87867F),
            outline = Color(0xFFDDD9CC),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF)
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
            outline = Color(0xFF4A4A46),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410)
        )
    )

    val all: List<AppTheme> = listOf(DefaultTheme, ClaudeTheme)

    /** 未知/null id 回退默认主题（容忍 DataStore 中的过期值） */
    fun byId(id: String?): AppTheme = all.find { it.id == id } ?: DefaultTheme
}
