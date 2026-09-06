package com.yumark.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import com.yumark.app.core.image.applyExifTransform
import com.yumark.app.core.image.readExifTransform
import com.yumark.app.data.local.db.dao.ImageDao
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.data.mapper.ImageMapper
import com.yumark.app.domain.model.Image
import com.yumark.app.domain.repository.ImageRepository
import com.yumark.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageDao: ImageDao,
    private val fileManager: FileManager,
    private val settingsRepository: SettingsRepository,
    private val mapper: ImageMapper
) : ImageRepository {

    override suspend fun getImageById(id: String): Result<Image> = runCatching {
        val entity = imageDao.getById(id) ?: throw Exception("Image not found: $id")
        mapper.toDomain(entity)
    }

    override suspend fun getImagesByDocument(documentId: String): Result<List<Image>> = runCatching {
        imageDao.getByDocument(documentId).map { mapper.toDomain(it) }
    }

    override suspend fun saveImage(
        documentId: String, uri: Uri, compress: Boolean
    ): Result<Image> = withContext(Dispatchers.IO) {
        var originalBitmap: Bitmap? = null
        var processedBitmap: Bitmap? = null

        try {
            val imageId = UUID.randomUUID().toString()
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Cannot open image"))

            // 两步解码：先量尺寸再按需下采样，避免超大图解码阶段就占满内存导致 OOM。
            val settings = if (compress) settingsRepository.getSettings() else null
            val targetMaxWidth = settings?.takeIf { it.autoCompressImages }?.maxImageWidth

            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, boundsOptions)
            inputStream.close()

            // EXIF 方向：竖拍照片的像素通常是横躺的，靠标签声明「显示时转 90°」
            // （详见 com.yumark.app.core.image.ExifOrientation）。
            //
            // 像素在解码之后才转正，但方向必须**在这里**先读出来：下面按宽度算下采样倍率，
            // 而 90°/270° 的照片 `outWidth` 量到的其实是显示时的高。
            val exif = context.contentResolver.readExifTransform(uri)
            val sourceDisplayWidth =
                if (exif.swapsDimensions) boundsOptions.outHeight else boundsOptions.outWidth

            // 绝对分辨率护栏：压缩关闭时 targetMaxWidth 为 null，20MP+ 的图会按原尺寸
            // 全量解码（ARGB_8888 下 20MP ≈ 80MB，更大的直接 OOM）——「保留原始观感」
            // 不等于「允许解码把进程打死」。4096 边界内单张位图 ≤ ~45MB，是下限保护；
            // 护栏只影响解码步长，压缩关闭时后续的精确缩放/原样复制逻辑不受影响。
            val sampleSize = when {
                targetMaxWidth != null && sourceDisplayWidth > targetMaxWidth -> {
                    // inSampleSize 向下取整为 2 的幂；按宽度比例粗略计算即可，后续再精确缩放
                    var sample = 1
                    while (sourceDisplayWidth / sample / 2 >= targetMaxWidth) sample *= 2
                    sample
                }
                sourceDisplayWidth > ABSOLUTE_MAX_DECODE_WIDTH -> {
                    var sample = 1
                    while (sourceDisplayWidth / sample / 2 >= ABSOLUTE_MAX_DECODE_WIDTH) sample *= 2
                    sample
                }
                else -> 1
            }

            val decodeInputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Cannot open image"))
            originalBitmap = BitmapFactory.decodeStream(
                decodeInputStream,
                null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            )
            decodeInputStream.close()

            if (originalBitmap == null) {
                return@withContext Result.failure(Exception("Cannot decode image"))
            }

            // 转正。位置必须在缩放判断**之前**：转正后宽高互换，拿未转正的宽度去比 maxImageWidth，
            // 竖图会被当成横图缩 —— 结果是把**短边**缩到 maxImageWidth，成图比预期小一圈。
            //
            // applyExifTransform 内部已回收被替换掉的那张，这里直接接住返回值即可；
            // finally 认的是这个变量的当前值，重新赋值后回收的就是新的那张。
            originalBitmap = originalBitmap.applyExifTransform(exif)

            // 使用 processedBitmap 指向当前要使用的 Bitmap
            processedBitmap = originalBitmap

            if (compress) {
                // settings 已在上方读取一次，这里复用，避免重复读
                if (settings != null && settings.autoCompressImages && originalBitmap.width > settings.maxImageWidth) {
                    val ratio = settings.maxImageWidth.toFloat() / originalBitmap.width
                    val newH = (originalBitmap.height * ratio).toInt()

                    // 创建缩放后的 Bitmap（scale 的 filter 默认为 true，与旧的
                    // Bitmap.createScaledBitmap(…, true) 行为一致）
                    val scaledBitmap = originalBitmap.scale(settings.maxImageWidth, newH)

                    // 回收原始 Bitmap，使用缩放后的
                    originalBitmap.recycle()
                    originalBitmap = null  // 防止 finally 块重复回收
                    processedBitmap = scaledBitmap
                }
            }

            // 落盘方案由源 MIME 一次性定死，扩展名与真实字节格式成对产生。
            // 从前这里是「先按 MIME 猜扩展名，再单独判断 extension == "png" 挑格式」，于是
            // webp/gif 源图落成 x.webp / x.gif 而内容是 JPEG 字节。那时导出侧只按扩展名声明
            // MIME，HTML/PDF 里于是出现 `data:image/webp;base64,<JPEG 字节>` —— 浏览器靠自己
            // 嗅探还画得出来，所以没人报障。
            //
            // 导出侧现在两条路都按字节判（`sniffInlineImageMime` / `sniffImageInfo`），谎报
            // MIME 这个后果已经不成立；这份配对仍然必须守住，因为扩展名是**离开应用之后**
            // 唯一还在的类型信息：分享出去的文件、文件管理器里的图标、别人拿去二次处理时的
            // 判断依据全看它。而字节与名字一旦允许分家，下一处「按名字做事」的代码就又会踩空。
            val encoding = imageEncodingForMime(context.contentResolver.getType(uri))

            // 「这次要写的位图就是原图本身」：下采样倍率为 1、没做过 EXIF 转正，且解码后的尺寸与
            // bounds 量出来的原图尺寸逐项相等（走过缩放分支就必然不等）。用尺寸比对而不是一个
            // "缩放过没有"的布尔量：布尔量要靠每条新增的处理路径记得置位，尺寸是结果本身，漏不掉。
            //
            // `exif.isIdentity` 是尺寸比对盖不住的那一半：90°/270° 转正后宽高互换，尺寸自然对不
            // 上；但 180° 与纯镜像（Orientation 3/2/4）转完尺寸一模一样，只看尺寸就会把「像素已经
            // 改过」误判成原图，把源字节原样复制过去 —— 文件里的方向标签还在，读它的（Chromium
            // 预览 / PDF / 长图）再转一次，不读它的（Word）不转，同一张图两个方向。
            //
            // 代价是带方向标签的照片（Orientation 6 是竖拍最常见的取值）从此必然重编码一次。
            // 认这个代价：JPEG 一代有损换来的是六种导出方向一致，而 GIF 不受影响 —— GIF 没有
            // EXIF 段，`readExifTransform` 对它恒为 NONE，动图仍走原样复制那条路。
            val isPristinePixels = sampleSize == 1 && exif.isIdentity &&
                processedBitmap.width == boundsOptions.outWidth &&
                processedBitmap.height == boundsOptions.outHeight

            // 能原样复制就不重编码：像素一个字节都不动，GIF 的多帧也留得住（Bitmap 只解得出
            // 第一帧，重编码等于把动图压成静图）。
            // compress 之外还要看 isPristinePixels：眼下 compress 为假时 settings 就是 null，
            // sampleSize 必然 1、缩放分支必然不走，这个判断是冗余的；但一旦以后有人把下采样
            // 从 compress 开关里拆出来，少了它就会把降过采样的位图当"原图字节"复制回去，
            // 落盘分辨率与 Image.width/height 从此不一致，而没有任何地方会报错。
            val copyExtension = encoding.copyExtension?.takeIf { !compress && isPristinePixels }

            // fileName 是扩展名的唯一出口：磁盘文件名、DB 的 file_name/file_path、正文里的
            // `images/<uuid>.<ext>` 全部由它派生。谁在别处另算一遍扩展名，正文引用就会指向
            // 一个不存在的文件，而表现只是预览里一个碎图标，没有任何报错。
            val extension = copyExtension ?: encoding.reencodeExtension
            val fileName = "$imageId.$extension"
            val file = File(fileManager.getImagesDir(), fileName)

            if (copyExtension != null) {
                // 上面那趟全尺寸解码在这条路上仍然不能省：它同时是「这确实是一张解得开的图」的
                // 校验（getType 报的 MIME 未必可信，少了它就会把一个改名的文本文件原样收进
                // images/），也是 Image.width/height 的唯一来源。GIF 只解得出第一帧，
                // 但第一帧的尺寸就是动图的逻辑尺寸，用来填 width/height 是对的。
                //
                // 流不可回绕，前两趟解码已经把它读完了，这里必须重新开一个。
                val sourceStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(Exception("Cannot open image"))
                sourceStream.use { input -> FileOutputStream(file).use { out -> input.copyTo(out) } }
            } else {
                FileOutputStream(file).use { out ->
                    val quality = if (compress) {
                        settings?.imageCompressionQuality?.value?.coerceIn(0, 100) ?: 90
                    } else {
                        90
                    }
                    processedBitmap.compress(compressFormatOf(encoding), quality, out)
                }
            }

            val image = Image(
                id = imageId, documentId = documentId, fileName = fileName,
                filePath = "images/$fileName", width = processedBitmap.width,
                height = processedBitmap.height, fileSize = file.length(),
                createdAt = kotlinx.datetime.Clock.System.now()
            )

            imageDao.insert(mapper.toEntity(image))
            Result.success(image)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消不是失败：包进 Result.failure 会让上层弹出"插入图片失败"，
            // 而实际只是用户退出了页面；同时会吞掉取消、破坏结构化并发。
            // finally 里的 Bitmap 回收照样执行。
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // 确保所有 Bitmap 都被回收
            originalBitmap?.recycle()
            processedBitmap?.recycle()
        }
    }

    override suspend fun deleteImage(id: String): Result<Unit> = runCatching {
        val entity = imageDao.getById(id)
        if (entity != null) {
            File(fileManager.getImagesDir(), entity.fileName).let { if (it.exists()) it.delete() }
            imageDao.deleteById(id)
        }
    }

    override suspend fun deleteImagesByDocument(documentId: String): Result<Unit> = runCatching {
        imageDao.getByDocument(documentId).forEach { entity ->
            File(fileManager.getImagesDir(), entity.fileName).let { if (it.exists()) it.delete() }
        }
        imageDao.deleteByDocument(documentId)
    }

    override suspend fun getOrphanedImages(): Result<List<Image>> = runCatching {
        imageDao.getOrphanedImages().map { mapper.toDomain(it) }
    }

    override suspend fun cleanOrphanedImages(): Result<Int> = runCatching {
        val orphaned = imageDao.getOrphanedImages()
        orphaned.forEach { entity ->
            File(fileManager.getImagesDir(), entity.fileName).let { if (it.exists()) it.delete() }
            imageDao.deleteById(entity.id)
        }
        orphaned.size
    }
}

// ---- 纯逻辑（可单测，不依赖 Android 框架） ----

/**
 * 源 MIME 决定的落盘方案：两个扩展名都在这张表里定死，落盘字节的真实格式因此永远与文件名
 * 对得上。
 *
 * 返回值刻意不带 [Bitmap.CompressFormat]：JVM 单测里 android.jar 只是空壳，
 * `CompressFormat.PNG` 取到的是 null（`app/build.gradle.kts` 的
 * `unitTests.isReturnDefaultValues = true`），把格式塞进这张表就一行也测不了。
 * 格式在 [compressFormatOf] 里按枚举 when 出来。
 *
 * @param reencodeExtension 重编码落盘时的扩展名，与 [compressFormatOf] 给的格式配对
 * @param copyExtension 原样复制源字节时的扩展名；null = 这个 MIME 说不出可信的扩展名，
 *   只能重编码，否则等于把「扩展名与字节不一致」换个入口重犯一遍
 */
internal enum class ImageEncoding(
    val reencodeExtension: String,
    val copyExtension: String?
) {
    PNG("png", "png"),
    JPEG("jpg", "jpg"),
    WEBP("webp", "webp"),

    /**
     * GIF 重编码降级成 PNG，扩展名一起变成 `png`、不留 `.gif`：Bitmap 根本编不出 GIF，
     * 而降级到 JPEG 会把 GIF 常见的透明区填成黑块。动图只能靠原样复制那条路保住，
     * 重编码出来的必然只剩第一帧。
     */
    GIF("png", "gif"),

    /**
     * `ContentResolver.getType` 给出别的类型（heic/avif/bmp…）或干脆是 null 时落这里。
     * 不给 copyExtension：说不出扩展名却复制原字节，只能随便挑一个名字写上去，
     * 那就是 `data:image/jpeg;base64,<HEIC 字节>`，与要修的缺陷同一个形状。
     * 重编码成 jpg 反而两头都对——字节是真 JPEG，扩展名也没说谎，且比 heic/avif 通用。
     */
    UNKNOWN("jpg", null)
}

/**
 * MIME → 落盘方案。只认这四种，其余一律 [ImageEncoding.UNKNOWN]。
 *
 * `lowercase()` 与 `ImageProcessor.isSupportedImageMime` 口径一致：部分 provider 报的是
 * `IMAGE/PNG`，不归一化的话 png 源图会被当成认不出的类型重编码成 jpg（不算错，但白丢一次
 * 无损与透明通道）。
 */
/** 解码侧的绝对分辨率上限（最长边）。压缩关闭时的 OOM 护栏，理由见 saveImage 内注释。 */
private const val ABSOLUTE_MAX_DECODE_WIDTH = 4096

internal fun imageEncodingForMime(mime: String?): ImageEncoding = when (mime?.lowercase()) {
    "image/png" -> ImageEncoding.PNG
    "image/jpeg" -> ImageEncoding.JPEG
    "image/webp" -> ImageEncoding.WEBP
    "image/gif" -> ImageEncoding.GIF
    else -> ImageEncoding.UNKNOWN
}

/**
 * 落盘方案 → Bitmap 写得出的格式。与 [ImageEncoding.reencodeExtension] 是一对，
 * when 刻意穷尽（没有 else）：往枚举里加条目时编译器会在这里报错，逼着把格式和扩展名一起定，
 * 而不是像从前那样在调用点单独猜一次格式。
 *
 * WEBP 的版本判断就地内联，不抽成布尔属性：`NewApi` 在本项目是 error 级、会阻断构建，
 * 而 lint 不认「另一个类里的 boolean」当 SDK 闸门，抽出去还得补 `@ChecksSdkIntAtLeast`
 * 才能过。`CompressFormat.WEBP` 自 API 30 起弃用，minSdk 是 26 所以 else 分支必须留着，
 * `@Suppress` 只盖住这个只有一条 when 的函数，别往外扩。
 */
@Suppress("DEPRECATION")
private fun compressFormatOf(encoding: ImageEncoding): Bitmap.CompressFormat = when (encoding) {
    ImageEncoding.PNG, ImageEncoding.GIF -> Bitmap.CompressFormat.PNG
    ImageEncoding.WEBP ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
    ImageEncoding.JPEG, ImageEncoding.UNKNOWN -> Bitmap.CompressFormat.JPEG
}
