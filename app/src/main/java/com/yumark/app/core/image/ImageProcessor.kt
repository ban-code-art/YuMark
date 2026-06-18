package com.yumark.app.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
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
            require(isSupportedImageMime(mime)) { "不支持的图片格式，请选择 JPG / PNG / GIF / WEBP" }
            val size = querySize(uri)
            require(size <= MAX_SOURCE_BYTES) { "图片过大（超过 10MB），请选择更小的图片" }
            ImageInfo(uri = uri, mimeType = mime!!, sizeBytes = size)
        }
    }

    /** 解码 → 按最长边下采样到 ≤1568（GIF 不缩放，避免丢帧）→ 压缩为字节流。 */
    suspend fun processForVision(uri: Uri): Result<ProcessedImage> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (mime == "image/gif") {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("图片读取失败，请重试")
                return@runCatching ProcessedImage(bytes, mime, 0, 0)
            }
            var bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalArgumentException("图片读取失败，请重试")
            val (tw, th) = computeVisionTargetSize(bmp.width, bmp.height, VISION_MAX_EDGE)
            if (tw != bmp.width || th != bmp.height) {
                val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
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
    return (width * ratio).toInt() to (height * ratio).toInt()
}

/** 裸 Base64（无 data: 前缀）；minSdk 26 起 java.util.Base64 可用。 */
internal fun encodeImageBase64(bytes: ByteArray): String =
    java.util.Base64.getEncoder().encodeToString(bytes)
