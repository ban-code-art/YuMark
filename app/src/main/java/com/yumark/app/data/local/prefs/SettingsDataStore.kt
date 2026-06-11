package com.yumark.app.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yumark.app.domain.model.CompressionQuality
import com.yumark.app.domain.model.UserSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val LIGHT_THEME_ID = stringPreferencesKey("light_theme_id")
        val DARK_THEME_ID = stringPreferencesKey("dark_theme_id")
        val FONT_SIZE = intPreferencesKey("font_size")
        val AUTO_SAVE_ENABLED = booleanPreferencesKey("auto_save_enabled")
        val AUTO_SAVE_INTERVAL = intPreferencesKey("auto_save_interval")
        val AUTO_COMPRESS = booleanPreferencesKey("auto_compress_images")
        val COMPRESSION_QUALITY = stringPreferencesKey("compression_quality")
        val MAX_IMAGE_WIDTH = intPreferencesKey("max_image_width")
        val DEFAULT_PREVIEW_MODE = booleanPreferencesKey("default_preview_mode")
        val THEME_ID = stringPreferencesKey("theme_id")
        val DARK_MODE = stringPreferencesKey("dark_mode")
    }

    val settingsFlow: Flow<UserSettings> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            UserSettings(
                lightThemeId = prefs[Keys.LIGHT_THEME_ID] ?: "default-light",
                darkThemeId = prefs[Keys.DARK_THEME_ID] ?: "default-dark",
                fontSize = prefs[Keys.FONT_SIZE] ?: 16,
                autoSaveEnabled = prefs[Keys.AUTO_SAVE_ENABLED] ?: true,
                autoSaveInterval = prefs[Keys.AUTO_SAVE_INTERVAL] ?: 30,
                autoCompressImages = prefs[Keys.AUTO_COMPRESS] ?: true,
                imageCompressionQuality = CompressionQuality.valueOf(
                    prefs[Keys.COMPRESSION_QUALITY] ?: "MEDIUM"
                ),
                maxImageWidth = prefs[Keys.MAX_IMAGE_WIDTH] ?: 1920,
                defaultPreviewMode = prefs[Keys.DEFAULT_PREVIEW_MODE] ?: true,
                themeId = prefs[Keys.THEME_ID] ?: "default",
                darkMode = prefs[Keys.DARK_MODE] ?: "system"
            )
        }

    suspend fun updateSettings(settings: UserSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.LIGHT_THEME_ID] = settings.lightThemeId
            prefs[Keys.DARK_THEME_ID] = settings.darkThemeId
            prefs[Keys.FONT_SIZE] = settings.fontSize
            prefs[Keys.AUTO_SAVE_ENABLED] = settings.autoSaveEnabled
            prefs[Keys.AUTO_SAVE_INTERVAL] = settings.autoSaveInterval
            prefs[Keys.AUTO_COMPRESS] = settings.autoCompressImages
            prefs[Keys.COMPRESSION_QUALITY] = settings.imageCompressionQuality.name
            prefs[Keys.MAX_IMAGE_WIDTH] = settings.maxImageWidth
            prefs[Keys.DEFAULT_PREVIEW_MODE] = settings.defaultPreviewMode
            prefs[Keys.THEME_ID] = settings.themeId
            prefs[Keys.DARK_MODE] = settings.darkMode
        }
    }

    suspend fun updateFontSize(fontSize: Int) {
        context.settingsDataStore.edit { it[Keys.FONT_SIZE] = fontSize }
    }

    suspend fun updateAutoSave(enabled: Boolean, interval: Int) {
        context.settingsDataStore.edit {
            it[Keys.AUTO_SAVE_ENABLED] = enabled
            it[Keys.AUTO_SAVE_INTERVAL] = interval
        }
    }
}
