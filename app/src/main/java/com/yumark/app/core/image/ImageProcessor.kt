package com.yumark.app.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.scale
import com.yumark.app.R
import com.yumark.app.core.util.FriendlyValidationException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.domain.model.MessageAttachment
import com.yumark.app.domain.model.MessageContent
import com.yumark.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 视觉附件处理：校验 → 下采样 → 落盘 → 回读为裸 Base64。
 * 不写文档 images 表（AI 附件是临时的，见附件设计 §2），落 ai_attachments 私有目录。
 */
@Singleton
class ImageProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val fileManager: FileManager
) {
    companion object {
        const val MAX_SOURCE_BYTES = 10_000_000L  // 源文件硬上限 10MB
        const val VISION_MAX_EDGE = 1568           // 发送前最长边下采样目标
    }

    /** 校验 mime 与源文件大小（不解码，便宜）。 */
    suspend fun validate(uri: Uri): Result<ImageInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri)
            // 用 FriendlyValidationException 而不是 require：这两句是写给用户看的，
            // 裸 IllegalArgumentException 会被归类成「出现未知问题」，用户就不知道该换张图了。
            if (!isSupportedImageMime(mime)) {
                throw FriendlyValidationException(
                    UiMessage.Res(R.string.image_error_unsupported_format)
                )
            }
            val size = querySize(uri)
            if (size > MAX_SOURCE_BYTES) {
                throw FriendlyValidationException(
                    UiMessage.of(R.string.image_error_too_large, MAX_SOURCE_BYTES / 1_000_000)
                )
            }
            ImageInfo(uri = uri, mimeType = mime!!, sizeBytes = size)
        }
    }

    /** 解码 → 按最长边下采样到 ≤1568（GIF 不缩放，避免丢帧）→ 压缩为字节流。 */
    suspend fun processForVision(uri: Uri): Result<ProcessedImage> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (mime == "image/gif") {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw FriendlyValidationException(
                        UiMessage.Res(R.string.image_error_decode_failed)
                    )
                return@runCatching ProcessedImage(bytes, mime, 0, 0)
            }
            var bmp = decodeSampled(uri, VISION_MAX_EDGE)
                ?: throw FriendlyValidationException(UiMessage.Res(R.string.image_error_decode_failed))
            val (tw, th) = computeVisionTargetSize(bmp.width, bmp.height, VISION_MAX_EDGE)
            if (tw != bmp.width || th != bmp.height) {
                val scaled = bmp.scale(tw, th)
                if (scaled != bmp) bmp.recycle()
                bmp = scaled
            }
            val quality = runCatching { settingsRepository.getSettings().imageCompressionQuality.value }
                .getOrNull()?.coerceIn(1, 100) ?: 85
            val outMime = if (mime == "image/png") "image/png" else "image/jpeg"
            val format = if (mime == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val out = ByteArrayOutputStream()
            bmp.compress(format, quality, out)
            val w = bmp.width; val h = bmp.height
            bmp.recycle()
            ProcessedImage(out.toByteArray(), outMime, w, h)
        }
    }

    /** 落盘到 ai_attachments，返回持久化引用（不含 Base64）。 */
    suspend fun save(processed: ProcessedImage): Result<MessageAttachment> = withContext(Dispatchers.IO) {
        runCatching {
            val ext = when (processed.mimeType) {
                "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg"
            }
            val name = "${UUID.randomUUID()}.$ext"
            File(fileManager.getAiAttachmentsDir(), name).writeBytes(processed.bytes)
            MessageAttachment(
                path = "ai_attachments/$name",
                mimeType = processed.mimeType,
                width = processed.width.takeIf { it > 0 },
                height = processed.height.takeIf { it > 0 }
            )
        }
    }

    /** 回读已落盘附件 → API 用的裸 Base64 图片片段。 */
    suspend fun readForVision(attachment: MessageAttachment): Result<MessageContent.Image> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = File(context.filesDir, attachment.path).readBytes()
                MessageContent.Image(base64 = encodeImageBase64(bytes), mimeType = attachment.mimeType)
            }
        }

    /**
     * 两趟解码：先只读尺寸算下采样倍率，再按倍率真正解码，最后按 EXIF 方向转正。
     *
     * 一趟直接 `decodeStream` 的话，源文件上限 10MB 之内的 12000×9000 JPEG 会按 ARGB_8888
     * 在堆上展开成 ~430MB，缩放那一行还没执行就 OOM —— 用户看到的是「插一张图把应用带崩」。
     *
     * 转正必须在这里而不是调用方：`processForVision` 之后按 `bmp.width/height` 算下采样目标，
     * 转正会让竖拍照片的宽高互换，先算后转就会按横图的比例去缩竖图（见 [ExifOrientation]）。
     * 更要紧的是那条路终点是 `Bitmap.compress`，写出的字节**不带 EXIF**，不在这里转就永久躺着。
     */
    private fun decodeSampled(uri: Uri, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        // 流不可回绕，第二趟必须重新打开
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        return bmp.applyExifTransform(context.contentResolver.readExifTransform(uri))
    }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
            }
        }
        return context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
    }
}

data class ImageInfo(val uri: Uri, val mimeType: String, val sizeBytes: Long)

data class ProcessedImage(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int)

// ---- 纯逻辑（可单测，不依赖 Android 框架） ----

internal val SUPPORTED_IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

internal fun isSupportedImageMime(mime: String?): Boolean =
    mime != null && mime.lowercase() in SUPPORTED_IMAGE_MIMES

/** 按最长边等比下采样的目标尺寸；最长边 ≤ maxEdge 时原样返回。 */
internal fun computeVisionTargetSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return width to height
    val ratio = maxEdge.toFloat() / longest
    // 至少留 1px：极端长条图（如 1×20000 的分隔条）等比缩放后短边会算成 0，
    // 而 Bitmap.scale(0, h) 直接抛 IllegalArgumentException，插图变成崩溃。
    return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
}

/**
 * 解码下采样倍率。只取 2 的幂：[BitmapFactory] 对非幂值会自己向下取整到幂，
 * 与其让它悄悄换个倍率，不如这里就算准。
 *
 * 口径是「解码后最长边仍 ≥ maxEdge」，剩下的零头交给之后的精确缩放，
 * 先粗后精两步下来画质不掉档，峰值内存却只有原来的 1/sample²。
 */
internal fun computeInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= maxEdge) {
        longest /= 2
        sample *= 2
    }
    return sample
}

/** 裸 Base64（无 data: 前缀）；minSdk 26 起 java.util.Base64 可用。 */
internal fun encodeImageBase64(bytes: ByteArray): String =
    java.util.Base64.getEncoder().encodeToString(bytes)
