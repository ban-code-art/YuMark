package com.yumark.app.data.repository

import com.yumark.app.data.local.prefs.SettingsDataStore
import com.yumark.app.domain.model.CompressionQuality
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: SettingsDataStore
) : SettingsRepository {

    override fun observeSettings(): Flow<UserSettings> = dataStore.settingsFlow

    override suspend fun getSettings(): UserSettings {
        return dataStore.settingsFlow.first()
    }

    override suspend fun updateTheme(lightThemeId: String, darkThemeId: String): Result<Unit> = runCatching {
        val current = getSettings()
        dataStore.updateSettings(current.copy(lightThemeId = lightThemeId, darkThemeId = darkThemeId))
    }

    override suspend fun updateFontSize(fontSize: Int): Result<Unit> = runCatching {
        dataStore.updateFontSize(fontSize)
    }

    override suspend fun updateAutoSave(enabled: Boolean, interval: Int): Result<Unit> = runCatching {
        dataStore.updateAutoSave(enabled, interval)
    }

    override suspend fun updateCompressionSettings(
        autoCompress: Boolean,
        quality: CompressionQuality,
        maxWidth: Int
    ): Result<Unit> = runCatching {
        val current = getSettings()
        dataStore.updateSettings(
            current.copy(
                autoCompressImages = autoCompress,
                imageCompressionQuality = quality,
                maxImageWidth = maxWidth
            )
        )
    }

    override suspend fun updateSettings(settings: UserSettings): Result<Unit> = runCatching {
        dataStore.updateSettings(settings)
    }

    override suspend fun resetToDefaults(): Result<Unit> = runCatching {
        dataStore.updateSettings(UserSettings())
    }
}
