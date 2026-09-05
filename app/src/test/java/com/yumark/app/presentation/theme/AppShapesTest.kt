package com.yumark.app.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.yumark.app.presentation.ai.common.AiDesign
import org.junit.jupiter.api.Test

/**
 * 钉住圆角 token 与传给 MaterialTheme 的那份 Shapes 是同一套数。
 *
 * 这两者分开写就有分叉的余地：改了 [AppShapes] 却忘了 [AppShapeScheme]，编译照过，
 * 而 M3 组件（Card/AlertDialog/TextField 这些不接 shape 参数的）拿的全是后者，
 * 于是 token 变成一份没人读的文档。下面第一条断言就是为这个。
 */
class AppShapesTest {

    private val fiveSteps: List<Pair<String, Dp>> = listOf(
        "extraSmall" to AppShapes.ExtraSmall,
        "small" to AppShapes.Small,
        "medium" to AppShapes.Medium,
        "large" to AppShapes.Large,
        "extraLarge" to AppShapes.ExtraLarge
    )

    @Test
    fun `Shapes 的五档全部取自 AppShapes`() {
        assertThat(AppShapeScheme.extraSmall).isEqualTo(RoundedCornerShape(AppShapes.ExtraSmall))
        assertThat(AppShapeScheme.small).isEqualTo(RoundedCornerShape(AppShapes.Small))
        assertThat(AppShapeScheme.medium).isEqualTo(RoundedCornerShape(AppShapes.Medium))
        assertThat(AppShapeScheme.large).isEqualTo(RoundedCornerShape(AppShapes.Large))
        assertThat(AppShapeScheme.extraLarge).isEqualTo(RoundedCornerShape(AppShapes.ExtraLarge))
    }

    @Test
    fun `五档单调递增`() {
        // 名字承诺了大小关系。写反了不会有任何报错，只会让「small 比 medium 圆」这种怪事上线。
        fiveSteps.zipWithNext { (smallerName, smaller), (largerName, larger) ->
            assertWithMessage("%s(%s) 应当小于 %s(%s)", smallerName, smaller, largerName, larger)
                .that(smaller.value).isLessThan(larger.value)
        }
    }

    @Test
    fun `medium 与 AI 界面的卡片圆角同值`() {
        // 这一档是刻意对齐的：AI 界面按 AiDesign.CardCorner 画卡片，文件列表走 M3 的 medium，
        // 两边同值才看不出接缝。任一侧单独改动都会让同一屏出现两种卡片圆角。
        assertThat(AppShapes.Medium).isEqualTo(AiDesign.CardCorner)
    }

    @Test
    fun `对话框保持 M3 基线的 28dp`() {
        // 这一档规范给得准，没有理由为本应用改。留个断言是因为它看起来像个可以随手调的数。
        assertThat(AppShapes.ExtraLarge.value).isEqualTo(28f)
    }
}
