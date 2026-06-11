package com.yumark.app.domain.usecase.image

import android.net.Uri
import com.yumark.app.domain.model.Image
import com.yumark.app.domain.repository.ImageRepository
import com.yumark.app.domain.repository.SettingsRepository
import javax.inject.Inject

class ProcessImageUseCase @Inject constructor(
    private val imageRepository: ImageRepository,
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(documentId: String, sourceUri: Uri): Result<Image> {
        val settings = settingsRepository.getSettings()
        return imageRepository.saveImage(
            documentId = documentId,
            uri = sourceUri,
            compress = settings.autoCompressImages
        )
    }
}
