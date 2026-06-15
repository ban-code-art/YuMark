package com.yumark.app.data.local.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// 扩展属性必须声明在文件顶层
private val Context.aiConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_config")

/**
 * AI 配置存储。
 * - API Key 走 [EncryptedSharedPreferences]（AES256，基于 Android Keystore）
 * - 其余非敏感配置走 [DataStore]
 */
@Singleton
class AiConfigDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "ai_config_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("ai_enabled")
        val PROVIDER = stringPreferencesKey("ai_provider")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL_NAME = stringPreferencesKey("model_name")
        val AVAILABLE_MODELS = stringPreferencesKey("available_models")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val STREAM_ENABLED = booleanPreferencesKey("stream_enabled")
    }

    private object EncryptedKeys {
        const val API_KEY = "api_key"
    }

    val configFlow: Flow<AiConfig> = context.aiConfigDataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            AiConfig(
                enabled = prefs[Keys.ENABLED] ?: false,
                provider = runCatching {
                    AiProvider.valueOf(prefs[Keys.PROVIDER] ?: AiProvider.OPENAI.name)
                }.getOrDefault(AiProvider.OPENAI),
                apiKey = encryptedPrefs.getString(EncryptedKeys.API_KEY, "").orEmpty(),
                baseUrl = prefs[Keys.BASE_URL] ?: "",
                modelName = prefs[Keys.MODEL_NAME] ?: "",
                availableModels = prefs[Keys.AVAILABLE_MODELS]?.let {
                    runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
                } ?: emptyList(),
                temperature = prefs[Keys.TEMPERATURE] ?: 0.7f,
                maxTokens = prefs[Keys.MAX_TOKENS] ?: 2048,
                streamEnabled = prefs[Keys.STREAM_ENABLED] ?: true
            )
        }

    suspend fun updateConfig(config: AiConfig) {
        encryptedPrefs.edit().putString(EncryptedKeys.API_KEY, config.apiKey).apply()

        context.aiConfigDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = config.enabled
            prefs[Keys.PROVIDER] = config.provider.name
            prefs[Keys.BASE_URL] = config.baseUrl
            prefs[Keys.MODEL_NAME] = config.modelName
            prefs[Keys.AVAILABLE_MODELS] = json.encodeToString(config.availableModels)
            prefs[Keys.TEMPERATURE] = config.temperature
            prefs[Keys.MAX_TOKENS] = config.maxTokens
            prefs[Keys.STREAM_ENABLED] = config.streamEnabled
        }
    }

    suspend fun clearApiKey() {
        encryptedPrefs.edit().remove(EncryptedKeys.API_KEY).apply()
    }
}
