package com.yumark.app.core.image

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * EXIF 方向映射的纯逻辑（矩阵与位图变换属 Android 框架，无 Robolectric 不单测）。
 *
 * 表里的数值直接对着 EXIF 2.3 §4.6.4 的 `Orientation` 取值写：1..8 一个不落，
 * 因为 `applyExifTransform` 是「只要不是 identity 就重编码」的唯一判据 —— 少认一个取值
 * 就有一类照片继续躺着，而且不会报错。
 */
class ExifOrientationTest {

    @Test
    fun `全部八种取向逐项映射`() {
        assertThat(exifTransformOf(1)).isEqualTo(ExifTransform(0, false))    // NORMAL
        assertThat(exifTransformOf(2)).isEqualTo(ExifTransform(0, true))     // FLIP_HORIZONTAL
        assertThat(exifTransformOf(3)).isEqualTo(ExifTransform(180, false))  // ROTATE_180
        assertThat(exifTransformOf(4)).isEqualTo(ExifTransform(180, true))   // FLIP_VERTICAL
        assertThat(exifTransformOf(5)).isEqualTo(ExifTransform(90, true))    // TRANSPOSE
        assertThat(exifTransformOf(6)).isEqualTo(ExifTransform(90, false))   // ROTATE_90
        assertThat(exifTransformOf(7)).isEqualTo(ExifTransform(270, true))   // TRANSVERSE
        assertThat(exifTransformOf(8)).isEqualTo(ExifTransform(270, false))  // ROTATE_270
    }

    @Test
    fun `未定义与非法取值一律不动像素`() {
        // 0 = UNDEFINED（GIF/BMP 没有 EXIF 段时 getAttributeInt 的默认值），
        // 其余是防御：厂商写坏过这个标签，不能因为读到 9 就随便转一个角度。
        listOf(0, 9, -1, Int.MAX_VALUE, Int.MIN_VALUE).forEach { raw ->
            assertThat(exifTransformOf(raw)).isEqualTo(ExifTransform.NONE)
            assertThat(exifTransformOf(raw).isIdentity).isTrue()
        }
    }

    @Test
    fun `纯镜像不是 identity`() {
        // 2/4 转完宽高一模一样，只靠尺寸比对是看不出「像素改过」的 —— ImageRepositoryImpl
        // 的 isPristinePixels 因此必须额外看 isIdentity，否则原字节会被原样复制走，
        // 落盘文件里的方向标签还在，读它的和不读它的两个方向。
        assertThat(exifTransformOf(2).isIdentity).isFalse()
        assertThat(exifTransformOf(4).isIdentity).isFalse()
        assertThat(exifTransformOf(3).isIdentity).isFalse()
        assertThat(exifTransformOf(1).isIdentity).isTrue()
    }

    @Test
    fun `只有 90 与 270 互换宽高`() {
        // 解码前算下采样倍率要用它挑「显示时的宽」那条边。
        listOf(5, 6, 7, 8).forEach { assertThat(exifTransformOf(it).swapsDimensions).isTrue() }
        listOf(0, 1, 2, 3, 4, 9).forEach { assertThat(exifTransformOf(it).swapsDimensions).isFalse() }
    }
}
