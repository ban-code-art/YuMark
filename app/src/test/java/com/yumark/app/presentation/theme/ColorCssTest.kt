package com.yumark.app.presentation.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [toCssHex] 单测。
 *
 * 值得测的是「预览区注入的颜色确实等于应用配色」这条等式——从前它靠 EditorScreen 里两张
 * 手抄的 `when (themeId)` 表维持，抄错了没有任何东西会响。这里把两套内置深色配色的
 * background / onBackground 直接和从前写死的那四个字面量对上，改 [AppThemes] 又忘了看预览
 * 时这几条会红。
 */
class ColorCssTest {

    @Test
    fun `默认深色配色转出从前写死的那两个值`() {
        assertThat(AppThemes.byId("default").dark.background.toCssHex()).isEqualTo("#1E1E1E")
        assertThat(AppThemes.byId("default").dark.onBackground.toCssHex()).isEqualTo("#DADADA")
    }

    @Test
    fun `claude 深色配色转出从前写死的那两个值`() {
        assertThat(AppThemes.byId("claude").dark.background.toCssHex()).isEqualTo("#262624")
        assertThat(AppThemes.byId("claude").dark.onBackground.toCssHex()).isEqualTo("#F0EEE6")
    }

    @Test
    fun `输出恒为 7 字符大写串`() {
        // CSS 不区分大小写，但注入的字符串会进日志和快照断言，统一成大写省得两边不一致
        for (theme in AppThemes.all) {
            for (color in listOf(
                theme.light.background, theme.light.onBackground,
                theme.dark.background, theme.dark.onBackground,
                theme.light.primary, theme.dark.primary
            )) {
                val hex = color.toCssHex()
                assertThat(hex).hasLength(7)
                assertThat(hex).matches("#[0-9A-F]{6}")
            }
        }
    }

    @Test
    fun `低位分量不足两位时补零而不是塌成短串`() {
        // 0x0A0B0C 这类颜色，toString(16) 只给 "a0b0c"，不补零的话 CSS 拿到 6 位以外的串直接整条失效
        assertThat(Color(0xFF0A0B0C).toCssHex()).isEqualTo("#0A0B0C")
        assertThat(Color(0xFF000000).toCssHex()).isEqualTo("#000000")
        assertThat(Color(0xFFFFFFFF).toCssHex()).isEqualTo("#FFFFFF")
    }

    @Test
    fun `丢掉 alpha 而不是吐出 8 位串`() {
        // 半透明色按不透明处理：CSS 的 background 要的是底色，8 位 hex 在旧 WebView 上不认
        assertThat(Color(0x801E1E1E).toCssHex()).isEqualTo("#1E1E1E")
        assertThat(Color(0x001E1E1E).toCssHex()).isEqualTo("#1E1E1E")
    }
}
