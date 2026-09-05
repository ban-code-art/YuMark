package com.yumark.app.presentation.theme

import androidx.compose.material3.Typography as M3Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * 钉住字阶只在「断行策略 + 行高分配 + 正文行高」这三处偏离 M3 基线。
 *
 * 这个测试存在的理由是原先那份 Typography.kt：104 行把 15 档逐个写全，而每个数值都与 M3
 * 默认一模一样——看着像定制，实际什么也没改，升版后规范调了值还会被这边压回旧数。
 * 现在改成从 `Typography()` copy，那种「抄一遍默认值」的退化必须有东西拦着。
 *
 * 基线值同样不写死，而是当场 `M3Typography()` 取（与 AppThemesTest 同一个思路）：
 * 写死等于把库的常量抄进测试，升版就过期；而「字号仍等于基线」这个关系不随版本变。
 */
class TypographyTest {

    private val baseline = M3Typography()

    /** 15 档字阶 → (本应用的值, 基线的值)。新增字阶忘了调 cjk() 时，下面每条断言都会抓到。 */
    private val allStyles: List<Triple<String, TextStyle, TextStyle>> = listOf(
        Triple("displayLarge", Typography.displayLarge, baseline.displayLarge),
        Triple("displayMedium", Typography.displayMedium, baseline.displayMedium),
        Triple("displaySmall", Typography.displaySmall, baseline.displaySmall),
        Triple("headlineLarge", Typography.headlineLarge, baseline.headlineLarge),
        Triple("headlineMedium", Typography.headlineMedium, baseline.headlineMedium),
        Triple("headlineSmall", Typography.headlineSmall, baseline.headlineSmall),
        Triple("titleLarge", Typography.titleLarge, baseline.titleLarge),
        Triple("titleMedium", Typography.titleMedium, baseline.titleMedium),
        Triple("titleSmall", Typography.titleSmall, baseline.titleSmall),
        Triple("bodyLarge", Typography.bodyLarge, baseline.bodyLarge),
        Triple("bodyMedium", Typography.bodyMedium, baseline.bodyMedium),
        Triple("bodySmall", Typography.bodySmall, baseline.bodySmall),
        Triple("labelLarge", Typography.labelLarge, baseline.labelLarge),
        Triple("labelMedium", Typography.labelMedium, baseline.labelMedium),
        Triple("labelSmall", Typography.labelSmall, baseline.labelSmall)
    )

    private val bodyStyleNames = setOf("bodyLarge", "bodyMedium", "bodySmall")

    @Test
    fun `字号字重字距一律沿用 M3 基线`() {
        // 这三项是规范给准了的，没有理由为中文改。真要改也不该悄悄改：这条断言会拦下来。
        allStyles.forEach { (name, actual, base) ->
            assertWithMessage("$name 的 fontSize").that(actual.fontSize).isEqualTo(base.fontSize)
            assertWithMessage("$name 的 fontWeight").that(actual.fontWeight).isEqualTo(base.fontWeight)
            assertWithMessage("$name 的 letterSpacing")
                .that(actual.letterSpacing).isEqualTo(base.letterSpacing)
        }
    }

    @Test
    fun `每一档都设了行高分配方式`() {
        // 不设的话多出来的行高会按字体度量堆到首行上方：整段往下掉一截，段内反而没松开。
        val expected = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        )
        allStyles.forEach { (name, actual, _) ->
            assertWithMessage("$name 的 lineHeightStyle").that(actual.lineHeightStyle)
                .isEqualTo(expected)
        }
    }

    @Test
    fun `断行策略按用途分三档`() {
        // 中文没有词间空格，默认的贪心断行会把标题断出末行孤字。三档各有理由，见 Typography.kt。
        val expected = allStyles.associate { (name, _, _) ->
            name to when {
                name in bodyStyleNames -> LineBreak.Paragraph
                name.startsWith("label") -> LineBreak.Simple
                else -> LineBreak.Heading
            }
        }
        allStyles.forEach { (name, actual, _) ->
            assertWithMessage("$name 的 lineBreak").that(actual.lineBreak).isEqualTo(expected[name])
        }
        // 三档都真的用上了：全写成同一个值时上面那圈断言照样能过
        assertThat(expected.values.toSet()).hasSize(3)
    }

    @Test
    fun `正文三档行高抬到约 1_6 倍`() {
        // 中文字形上下顶满 em 框，没有拉丁小写字母那样的天然余白，1.5 倍读起来偏挤。
        allStyles.filter { it.first in bodyStyleNames }.forEach { (name, actual, _) ->
            val ratio = actual.lineHeight.value / actual.fontSize.value
            assertWithMessage("$name 的行高倍数（%s / %s）", actual.lineHeight, actual.fontSize)
                .that(ratio).isAtLeast(1.5f)
        }
        assertThat(bodyStyleNames).hasSize(3)
    }

    @Test
    fun `正文行高确实比基线大而其余各档不动`() {
        // 只抬正文：display/headline/title 都是短文本，抬高只会把顶栏和列表行撑高。
        allStyles.forEach { (name, actual, base) ->
            if (name in bodyStyleNames) {
                assertWithMessage("$name 的行高应当比基线大").that(actual.lineHeight.value)
                    .isGreaterThan(base.lineHeight.value)
            } else {
                assertWithMessage("$name 的行高不该动").that(actual.lineHeight)
                    .isEqualTo(base.lineHeight)
            }
        }
    }
}
