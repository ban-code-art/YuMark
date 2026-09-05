package com.yumark.app.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 钉住扩展语义色的推导算法。
 *
 * 这套色值全部是**现算**的而不是写死的，所以能覆盖动态取色主题——它的配色来自壁纸，编译期
 * 根本不存在。代价是算法本身成了会出错的地方，于是需要这个测试：既验证结果达标，也验证
 * 「色相饱和度一个像素不偏」这条前提真的成立。
 *
 * 参考值来自 `scripts/wcag_solve.py`（float64 + 同一套二分），Kotlin 侧走 Float，所以按每通道
 * ±1/255 的容差比对，而不是要求 8 位色深下完全相等。
 */
class ExtendedColorsTest {

    private val surfaceRoles: (ColorScheme) -> List<Pair<String, Color>> = {
        listOf(
            "surface" to it.surface,
            "background" to it.background,
            "surfaceVariant" to it.surfaceVariant,
            "surfaceContainer" to it.surfaceContainer,
            "surfaceContainerHigh" to it.surfaceContainerHigh,
            "surfaceContainerHighest" to it.surfaceContainerHighest
        )
    }

    private fun Color.channels(): Triple<Int, Int, Int> = Triple(
        (red * 255f).roundToInt(), (green * 255f).roundToInt(), (blue * 255f).roundToInt()
    )

    private fun Color.hex(): String {
        val (r, g, b) = channels()
        return "0xFF%02X%02X%02X".format(r, g, b)
    }

    /** 每通道容差 1，够吸收 float32 与 float64 的差异，又不至于放过真正跑偏的实现。 */
    private fun assertNear(actual: Color, expected: Long, label: String) {
        val (ar, ag, ab) = actual.channels()
        val er = ((expected shr 16) and 0xFF).toInt()
        val eg = ((expected shr 8) and 0xFF).toInt()
        val eb = (expected and 0xFF).toInt()
        val off = maxOf(abs(ar - er), abs(ag - eg), abs(ab - eb))
        assertWithMessage(
            label + " 期望 0xFF%02X%02X%02X 附近，实际 ".format(er, eg, eb) + actual.hex()
        ).that(off).isAtMost(1)
    }

    private fun lin(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f
        else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    /**
     * [source] 的线性通道比值是否与 [actual] 相符——返回空串表示相符，否则返回带数值的说明。
     *
     * 判据不含容差常数：8 位整数只承诺真值落在 ±0.5/255 之内，把这个区间推到线性空间，就得到
     * 比值的允许区间。要求原色的比值落在区间内，等价于「存在某个纯亮度缩放能量化成这个返回值」。
     *
     * 以最暗通道作分母，所以它不能为 0（应用两套调色板里没有这种色）。
     */
    private fun chromaticityDrift(source: Color, actual: Color): String {
        val (r, g, b) = actual.channels()
        fun edge(ch: Int, delta: Float): Float = lin(((ch + delta) / 255f).coerceIn(0f, 1f))
        fun band(hi: Int, lo: Int): Pair<Float, Float> =
            edge(hi, -0.5f) / edge(lo, +0.5f) to edge(hi, +0.5f) / edge(lo, -0.5f)
        return listOf(
            Triple("红/蓝", lin(source.red) / lin(source.blue), band(r, b)),
            Triple("绿/蓝", lin(source.green) / lin(source.blue), band(g, b))
        ).mapNotNull { (name, want, range) ->
            val (low, high) = range
            if (want in low..high) null
            else "%s 比值 %.5f 落在 %s 的量化区间 [%.5f, %.5f] 之外".format(
                name, want, actual.hex(), low, high
            )
        }.joinToString("；")
    }

    @Test
    fun `已达标的颜色原样返回`() {
        // Default-light 的 primary 压在最深的一档容器底上就有 5.84:1，不该被动。
        val primary = Color(0xFF4B5A68)
        val result = ensureContrast(
            primary,
            listOf(Color(0xFFFFFFFF), Color(0xFFE9E9E9)),
            CONTRAST_TEXT,
            towardWhite = false
        )
        assertThat(result).isEqualTo(primary)
    }

    @Test
    fun `底色为空时原样返回`() {
        val c = Color(0xFF808080)
        assertThat(ensureContrast(c, emptyList(), CONTRAST_TEXT, towardWhite = true)).isEqualTo(c)
    }

    @Test
    fun `推导出的品牌前景色复现求解器的参考值`() {
        val claudeLight = listOf(
            Color(0xFFFAF9F5), Color(0xFFF5F4ED), Color(0xFFEAE8E0),
            Color(0xFFF2F0E8), Color(0xFFECEAE1), Color(0xFFE5E2D8)
        )
        val claudeDark = listOf(
            Color(0xFF30302E), Color(0xFF262624), Color(0xFF3A3A37), Color(0xFF454541)
        )
        val terracotta = Color(0xFFD97757)
        assertNear(
            ensureContrast(terracotta, claudeLight, CONTRAST_TEXT, towardWhite = false),
            0x98513B,
            "Claude-light primaryText"
        )
        assertNear(
            ensureContrast(terracotta, claudeDark, CONTRAST_TEXT, towardWhite = true),
            0xE2A292,
            "Claude-dark primaryText"
        )
        assertNear(
            ensureContrast(
                Color(0xFF8FA1B3),
                listOf(
                    Color(0xFF252526), Color(0xFF1E1E1E), Color(0xFF2D2D2D),
                    Color(0xFF2F2F30), Color(0xFF3A3A3B)
                ),
                CONTRAST_TEXT,
                towardWhite = true
            ),
            0x95A5B6,
            "Default-dark primaryText"
        )
    }

    /**
     * 「色相饱和度不变」这条前提的实测。
     *
     * 在**线性**空间等比缩放三个通道，色度坐标严格不动。检验方式是通道间的线性比值：
     * `lin(r):lin(g):lin(b)` 缩放前后必须一致。若实现改成在 sRGB 空间缩放（更直觉、也更错），
     * gamma 曲线会把比值拉偏，赤陶橙缩成脏褐色。
     *
     * 比值**不能**按固定百分比比对。Compose 的 sRGB `Color` 按每通道 8 位打包，返回值必然落在
     * 整数网格上；最暗那个通道（这里蓝 = 59）的 ±0.5/255 经 2.4 次幂放大，在线性空间就是近 3%
     * 的相对误差——量化噪声本身比不少真实缺陷还大。所以判据交给 [chromaticityDrift]：算法本身
     * 是精确的（float64 复算偏差为 0），偏差全部来自那一次取整。
     */
    @Test
    fun `压深不改变色相与饱和度`() {
        val terracotta = Color(0xFFD97757)
        val darkened = ensureContrast(
            terracotta,
            listOf(Color(0xFFFAF9F5), Color(0xFFE5E2D8)),
            CONTRAST_TEXT,
            towardWhite = false
        )
        assertThat(darkened).isNotEqualTo(terracotta)

        val drift = chromaticityDrift(terracotta, darkened)
        assertWithMessage("压深后色度跑偏：" + drift).that(drift).isEmpty()

        // 判据的自证。缩放因子取自 darkened 的红通道，于是这个对照组压到同一个明度档位，只是把
        // 缩放改在 sRGB 空间做；结果是 0xFF98533D，两个比值都落在量化区间外。缺了这条反向断言，
        // 上面那句就分不清「实现正确」和「区间宽到什么都放过」。
        val k = darkened.red / terracotta.red
        val wrongSpace = Color(terracotta.red * k, terracotta.green * k, terracotta.blue * k)
        assertWithMessage("量化区间宽到放过了 sRGB 空间缩放的结果 " + wrongSpace.hex() + "，判据失去意义")
            .that(chromaticityDrift(terracotta, wrongSpace)).isNotEmpty()
    }

    /** 一套方案里全部推导色的达标检查，失败项收集齐再一次性报出来。 */
    private fun checkDerived(name: String, scheme: ColorScheme, isDark: Boolean): List<String> {
        val e = extendedColorsFor(scheme, isDark)
        val failures = mutableListOf<String>()
        val fgs = listOf(
            "primaryText" to e.primaryText,
            "success" to e.success,
            "warning" to e.warning
        )
        fgs.forEach { (fgName, fg) ->
            surfaceRoles(scheme).forEach { (bgName, bg) ->
                val r = contrastRatio(fg, bg)
                if (r < CONTRAST_TEXT) {
                    failures += "  %s %s/%s = %.2f (需 %.1f)".format(
                        name, fgName, bgName, r, CONTRAST_TEXT
                    )
                }
            }
        }
        listOf(
            Triple("onSuccessContainer/successContainer", e.onSuccessContainer, e.successContainer),
            Triple("onWarningContainer/warningContainer", e.onWarningContainer, e.warningContainer)
        ).forEach { (label, fg, bg) ->
            val r = contrastRatio(fg, bg)
            if (r < CONTRAST_TEXT) {
                failures += "  %s %s = %.2f (需 %.1f)".format(name, label, r, CONTRAST_TEXT)
            }
        }
        listOf(
            Triple("success/successContainer", e.success, e.successContainer),
            Triple("warning/warningContainer", e.warning, e.warningContainer)
        ).forEach { (label, fg, bg) ->
            val r = contrastRatio(fg, bg)
            if (r < CONTRAST_UI) {
                failures += "  %s %s = %.2f (需 %.1f)".format(name, label, r, CONTRAST_UI)
            }
        }
        return failures
    }

    @Test
    fun `四套静态方案推导出的语义色全部达标`() {
        val failures = AppThemes.all.filterNot { it.dynamic }.flatMap {
            checkDerived(it.id + "-light", it.light, false) +
                checkDerived(it.id + "-dark", it.dark, true)
        }
        assertWithMessage("推导出的扩展语义色不达标：\n" + failures.joinToString("\n"))
            .that(failures).isEmpty()
    }

    /**
     * 动态取色主题的真正验收：拿一套**故意难搞**的合成配色跑推导。
     *
     * 淡黄色 `primary` 压在近白底上大约 1.3:1，是壁纸取色能给出的最糟情况之一。这条断言证明
     * `Theme.kt` 里那条运行期纠正在动态取色下确实能把语义色救回达标，而不是只在两个手写主题上
     * 碰巧成立。整个 surface 家族都显式赋值，免得断言跟着 M3 基线调色板的版本漂。
     */
    @Test
    fun `合成的壁纸配色也能推出达标语义色`() {
        val wallpaper = lightColorScheme(
            primary = Color(0xFFFFE082),
            surface = Color(0xFFFFFBF0),
            background = Color(0xFFFFFDF7),
            surfaceVariant = Color(0xFFF3EEDF),
            surfaceContainer = Color(0xFFF7F2E4),
            surfaceContainerHigh = Color(0xFFF1ECDE),
            surfaceContainerHighest = Color(0xFFEBE6D8)
        )
        val failures = checkDerived("synthetic-wallpaper", wallpaper, isDark = false)
        assertWithMessage("合成配色推导失败：\n" + failures.joinToString("\n"))
            .that(failures).isEmpty()

        // 顺带证明它真的动了手：淡黄 primary 在浅底上不可能原样达标。
        assertThat(extendedColorsFor(wallpaper, false).primaryText).isNotEqualTo(wallpaper.primary)
    }
}
