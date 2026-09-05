package com.yumark.app.core.image

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** ImageProcessor 的纯逻辑（Bitmap 解码/压缩属 Android 框架，无 Robolectric 不单测）。 */
class ImageProcessorTest {

    @Test
    fun `target size keeps within-bound images unchanged`() {
        assertThat(computeVisionTargetSize(800, 600, 1568)).isEqualTo(800 to 600)
        assertThat(computeVisionTargetSize(1568, 1000, 1568)).isEqualTo(1568 to 1000)
    }

    @Test
    fun `target size downscales by longest edge preserving aspect`() {
        assertThat(computeVisionTargetSize(3136, 1568, 1568)).isEqualTo(1568 to 784)
        assertThat(computeVisionTargetSize(1000, 2000, 1568)).isEqualTo(784 to 1568)
    }

    @Test
    fun `supported mimes recognized, others rejected`() {
        listOf("image/jpeg", "image/png", "image/gif", "image/webp").forEach {
            assertThat(isSupportedImageMime(it)).isTrue()
        }
        assertThat(isSupportedImageMime("image/bmp")).isFalse()
        assertThat(isSupportedImageMime("application/pdf")).isFalse()
        assertThat(isSupportedImageMime(null)).isFalse()
    }

    @Test
    fun `base64 has no data prefix and decodes back`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val b64 = encodeImageBase64(bytes)
        assertThat(b64).doesNotContain("data:")
        assertThat(java.util.Base64.getDecoder().decode(b64)).isEqualTo(bytes)
    }

    @Test
    fun `极端长条图缩放后短边至少留一像素`() {
        // 1×20000 这类分隔条真实存在。等比缩放后短边算成 0，
        // Bitmap.scale(0, h) 会抛 IllegalArgumentException —— 插一张图直接崩。
        // 断言写成不变量而不是精确值：比例是 Float 运算，长边落在 1567/1568 都算对。
        listOf(1 to 20000, 20000 to 1, 3 to 40000, 40000 to 3).forEach { (sw, sh) ->
            val (w, h) = computeVisionTargetSize(sw, sh, 1568)
            assertThat(w).isAtLeast(1)
            assertThat(h).isAtLeast(1)
            assertThat(maxOf(w, h)).isAtMost(1568)
            assertThat(maxOf(w, h)).isAtLeast(1560)
        }
    }

    @Test
    fun `下采样倍率是 2 的幂且解码后最长边不低于目标`() {
        // 倍率只能取 2 的幂：BitmapFactory 对非幂值会自己向下取整，算不准等于白算。
        assertThat(computeInSampleSize(800, 600, 1568)).isEqualTo(1)
        assertThat(computeInSampleSize(1568, 1000, 1568)).isEqualTo(1)
        assertThat(computeInSampleSize(3136, 1568, 1568)).isEqualTo(2)
        assertThat(computeInSampleSize(12000, 9000, 1568)).isEqualTo(4)
        // 解码后最长边必须仍 ≥ 目标，否则精确缩放那一步会变成放大，画质凭空掉一档
        listOf(1600 to 1200, 3000 to 4000, 12000 to 9000, 20000 to 3).forEach { (w, h) ->
            val sample = computeInSampleSize(w, h, 1568)
            assertThat(maxOf(w, h) / sample).isAtLeast(1568)
        }
    }

    @Test
    fun `非法尺寸不产生 0 或负倍率`() {
        // 0 会让 BitmapFactory 抛，负数更糟；坏尺寸退回 1 让解码去报真正的失败原因。
        assertThat(computeInSampleSize(0, 0, 1568)).isEqualTo(1)
        assertThat(computeInSampleSize(-5, 100, 1568)).isEqualTo(1)
        assertThat(computeInSampleSize(4000, 3000, 0)).isEqualTo(1)
    }
}
