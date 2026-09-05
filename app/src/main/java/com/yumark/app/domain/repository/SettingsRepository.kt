package com.yumark.app.domain.repository

import com.yumark.app.domain.model.CompressionQuality
import com.yumark.app.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observeSettings(): Flow<UserSettings>
    suspend fun getSettings(): UserSettings

    // 别再加 updateTheme(lightThemeId, darkThemeId)：那两个字段不参与取色（见 UserSettings 的
    // 注释），换主题走 updateSettings(copy(themeId = …))。原来那个方法零调用方，已删。
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
