package com.yumark.app.data.local.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yumark.app.domain.model.WebDavConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// 扩展属性必须声明在文件顶层
private val Context.syncConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_config")

/**
 * WebDAV 同步配置存储（仿 [AiConfigDataStore]）。
 * - password 走 [EncryptedSharedPreferences]（AES256，基于 Android Keystore）
 * - 其余非敏感配置与上次同步时间走 [DataStore]
 */
@Singleton
class SyncConfigDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "sync_config_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("sync_enabled")
        val BASE_URL = stringPreferencesKey("base_url")
        val USERNAME = stringPreferencesKey("username")
        val REMOTE_DIR = stringPreferencesKey("remote_dir")
        val LAST_SYNCED_AT = longPreferencesKey("last_synced_at")
    }

    private object EncryptedKeys {
        const val PASSWORD = "password"
    }

    val configFlow: Flow<WebDavConfig> = context.syncConfigDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            WebDavConfig(
                enabled = prefs[Keys.ENABLED] ?: false,
                baseUrl = prefs[Keys.BASE_URL] ?: "",
                username = prefs[Keys.USERNAME] ?: "",
                password = encryptedPrefs.getString(EncryptedKeys.PASSWORD, "").orEmpty(),
                remoteDir = prefs[Keys.REMOTE_DIR] ?: "YuMark"
            )
        }

    val lastSyncedAtFlow: Flow<Long?> = context.syncConfigDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[Keys.LAST_SYNCED_AT] }

    suspend fun updateConfig(config: WebDavConfig) {
        encryptedPrefs.edit().putString(EncryptedKeys.PASSWORD, config.password).apply()
        context.syncConfigDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = config.enabled
            prefs[Keys.BASE_URL] = config.baseUrl
            prefs[Keys.USERNAME] = config.username
            prefs[Keys.REMOTE_DIR] = config.remoteDir
        }
    }

    suspend fun setLastSyncedAt(millis: Long) {
        context.syncConfigDataStore.edit { it[Keys.LAST_SYNCED_AT] = millis }
    }
}
