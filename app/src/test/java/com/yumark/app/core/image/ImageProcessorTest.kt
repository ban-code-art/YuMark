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
}
