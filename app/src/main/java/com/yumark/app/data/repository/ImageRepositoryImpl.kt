package com.yumark.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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

            val sampleSize = if (targetMaxWidth != null && boundsOptions.outWidth > targetMaxWidth) {
                // inSampleSize 向下取整为 2 的幂；按宽度比例粗略计算即可，后续再精确缩放
                var sample = 1
                while (boundsOptions.outWidth / sample / 2 >= targetMaxWidth) sample *= 2
                sample
            } else 1

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

            // 使用 processedBitmap 指向当前要使用的 Bitmap
            processedBitmap = originalBitmap

            if (compress) {
                // settings 已在上方读取一次，这里复用，避免重复读
                if (settings != null && settings.autoCompressImages && originalBitmap.width > settings.maxImageWidth) {
                    val ratio = settings.maxImageWidth.toFloat() / originalBitmap.width
                    val newH = (originalBitmap.height * ratio).toInt()

                    // 创建缩放后的 Bitmap
                    val scaledBitmap = Bitmap.createScaledBitmap(
                        originalBitmap,
                        settings.maxImageWidth,
                        newH,
                        true
                    )

                    // 回收原始 Bitmap，使用缩放后的
                    originalBitmap.recycle()
                    originalBitmap = null  // 防止 finally 块重复回收
                    processedBitmap = scaledBitmap
                }
            }

            val extension = getExtension(uri)
            val fileName = "$imageId.$extension"
            val file = File(fileManager.getImagesDir(), fileName)

            FileOutputStream(file).use { out ->
                val format = if (extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val quality = if (compress) {
                    settings?.imageCompressionQuality?.value?.coerceIn(0, 100) ?: 90
                } else {
                    90
                }
                processedBitmap.compress(format, quality, out)
            }

            val image = Image(
                id = imageId, documentId = documentId, fileName = fileName,
                filePath = "images/$fileName", width = processedBitmap.width,
                height = processedBitmap.height, fileSize = file.length(),
                createdAt = kotlinx.datetime.Clock.System.now()
            )

            imageDao.insert(mapper.toEntity(image))
            Result.success(image)
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

    private fun getExtension(uri: Uri): String = when (context.contentResolver.getType(uri)) {
        "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg"
    }
}
