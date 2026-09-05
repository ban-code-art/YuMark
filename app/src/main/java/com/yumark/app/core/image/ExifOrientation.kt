package com.yumark.app.core.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/**
 * EXIF 方向的读取与应用：**落盘 / 上传前把像素转正**。
 *
 * 手机竖拍时绝大多数机型并不旋转像素，而是按传感器方向（横躺）原样存下，再往 `Orientation`
 * 标签里写一句「显示时请转 90°」。`BitmapFactory` 只解像素、不看这个标签，于是：
 *
 * - **重编码落盘**（[ImageProcessor.processForVision] 发给多模态模型的字节、
 *   `ImageRepositoryImpl.saveImage` 的压缩分支）把横躺的像素写进一个**不带 EXIF** 的新文件，
 *   方向信息就此永久消失 —— 图从此横躺，且无从恢复；
 * - **原样复制落盘**时标签还在，Chromium 会照它转（`image-orientation: from-image` 是 CSS
 *   初值，所以预览 / PDF / 长图是正的），但 `Image.width`/`height` 记的是未旋转的像素宽高，
 *   `DocxExporter` 按它算 `wp:extent`，Word 里那张竖图于是被拉成横的宽高比。
 *
 * 同一张照片在六种导出里有的正、有的躺、有的变形，全程没有任何报错。因此口径定为
 * **像素一律转正**：转正之后方向标签与内容无关，谁读它谁不读它都得到同一个结果。
 */

/**
 * 一次方向变换：**先顺时针转 [rotationDegrees]，再按需水平镜像**。
 *
 * 两步顺序不可交换：含镜像的 `TRANSPOSE`(5) / `TRANSVERSE`(7) 反过来做会差一个 180°。
 * 这里的口径与 Glide `TransformationUtils.getExifTransformationMatrix` 一致
 * （`setRotate` 之后 `postScale(-1, 1)`），[applyExifTransform] 就是照它拼的矩阵。
 */
internal data class ExifTransform(val rotationDegrees: Int, val mirrorX: Boolean) {
    /** 不需要动像素。 */
    val isIdentity: Boolean get() = rotationDegrees == 0 && !mirrorX

    /**
     * 转正会让宽高互换。
     *
     * 在**解码之前**就要用到它：`inJustDecodeBounds` 量出来的是传感器方向的宽高，对一张竖拍
     * 照片来说 `outWidth` 其实是显示时的高，拿它去和「最大宽度」比会把竖图当横图，多降一档
     * 下采样（见 `ImageRepositoryImpl.saveImage`）。
     */
    val swapsDimensions: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

    companion object {
        val NONE = ExifTransform(0, false)
    }
}

/**
 * EXIF `Orientation`（TIFF tag 0x0112）→ [ExifTransform]。
 *
 * 1..8 之外的取值（0 = UNDEFINED、缺标签、GIF/BMP 这类根本没有 EXIF 段的格式）一律
 * [ExifTransform.NONE] —— 「读不出方向」和「方向是正的」在结果上必须等价，不能靠猜。
 *
 * 用字面量而不是 `ExifInterface.ORIENTATION_*`：这一段是纯逻辑、JVM 单测直接跑，不引
 * androidx 的类；数值由 EXIF 2.3 §4.6.4 定死，二十年没变过。
 */
internal fun exifTransformOf(orientation: Int): ExifTransform = when (orientation) {
    2 -> ExifTransform(0, true)      // FLIP_HORIZONTAL
    3 -> ExifTransform(180, false)   // ROTATE_180
    4 -> ExifTransform(180, true)    // FLIP_VERTICAL = 转 180 再水平镜像
    5 -> ExifTransform(90, true)     // TRANSPOSE
    6 -> ExifTransform(90, false)    // ROTATE_90 —— 竖拍照片最常见的一种
    7 -> ExifTransform(270, true)    // TRANSVERSE
    8 -> ExifTransform(270, false)   // ROTATE_270
    else -> ExifTransform.NONE       // 1 = NORMAL，以及 0 / 缺失 / 非法值
}

/**
 * 读源图的方向标签。
 *
 * 读不出来（不支持的格式、截断的文件、provider 抛错）一律按 [ExifTransform.NONE] 处理：
 * 方向是锦上添花，不能因为它让「插入图片」整体失败。
 *
 * 必须用 `InputStream` 那个构造器：源是 `content://`，拿不到文件路径。
 */
internal fun ContentResolver.readExifTransform(uri: Uri): ExifTransform {
    val orientation = runCatching {
        openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
        }
    }.getOrNull() ?: return ExifTransform.NONE
    return exifTransformOf(orientation)
}

/**
 * 按 [t] 把像素转正；返回的位图**可能就是** `this`（无需变换，或分配失败）。
 *
 * 不是 `this` 时旧位图已在这里 `recycle()`：调用方拿到的永远是「当前有效的那一张」，
 * 不必自己判断该回收哪个 —— 那正是最容易漏、漏了又不报错的一步。
 *
 * `runCatching` 连 `OutOfMemoryError` 一起兜住（它捕的是 `Throwable`）：旋转要再分配一整张
 * 同尺寸位图，一张 12MP 图就是额外 48MB。分配失败时退回未转正的原图 —— 图躺着远好过插图崩溃。
 *
 * 转正后**不需要**再去清 `Orientation` 标签：走到这里的位图接下来只有两种去向，重编码
 * （`Bitmap.compress` 写出的是全新 JFIF/PNG，一个 EXIF 段都没有）或直接发给模型；
 * 「转了像素却留着旧标签」那种双重旋转在这条路上不可能发生。
 */
internal fun Bitmap.applyExifTransform(t: ExifTransform): Bitmap {
    if (t.isIdentity) return this
    val matrix = Matrix().apply {
        setRotate(t.rotationDegrees.toFloat())
        if (t.mirrorX) postScale(-1f, 1f)
    }
    val out = runCatching {
        Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }.getOrNull() ?: return this
    if (out !== this) recycle()
    return out
}
