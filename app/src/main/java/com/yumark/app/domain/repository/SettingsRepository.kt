package com.yumark.app.domain.repository

import com.yumark.app.domain.model.CompressionQuality
import com.yumark.app.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observeSettings(): Flow<UserSettings>
    suspend fun getSettings(): UserSettings

    suspend fun updateTheme(lightThemeId: String, darkThemeId: String): Result<Unit>
    suspend fun updateFontSize(fontSize: Int): Result<Unit>
    suspend fun updateAutoSave(enabled: Boolean, interval: Int): Result<Unit>
    suspend fun updateCompressionSettings(
        autoCompress: Boolean,
        quality: CompressionQuality,
        maxWidth: Int
    ): Result<Unit>
    suspend fun updateSettings(settings: UserSettings): Result<Unit>
    suspend fun resetToDefaults(): Result<Unit>
}
