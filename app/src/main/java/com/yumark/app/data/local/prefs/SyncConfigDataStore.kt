// androidx.security-crypto 1.1.0 起整套 API 被标记 Deprecated（Jetpack Security 停止维护，
// 官方未给直接替代品，指引是自行基于 Android Keystore 做 AES-GCM 加解密）。
// 用文件级抑制而非就地改写：替换密钥容器涉及**已有用户凭据的读旧写新迁移**，属于独立任务，
// 不能夹带在工具链升级里做——做错就是用户存的密码直接丢失。
// 现状仍安全（AES256-GCM + Keystore 主密钥），迁移已列入 backlog。
// 抑制放在文件级是因为 import 处的弃用警告无法由类级注解覆盖。
@file:Suppress("DEPRECATION")

package com.yumark.app.data.local.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
import com.yumark.app.R
import com.yumark.app.core.util.FriendlyIOException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.domain.model.WebDavConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// 扩展属性必须声明在文件顶层
private val Context.syncConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_config")

/**
 * WebDAV 同步配置存储（仿 [AiConfigDataStore]）。
 * - password 走 [EncryptedSharedPreferences]（AES256，基于 Android Keystore）
 * - 其余非敏感配置与上次同步时间走 [DataStore]
 *
 * 见文件顶部关于 security-crypto 弃用抑制的说明。
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

    /**
     * 读密文密码，读不出来当空串。理由与 [AiConfigDataStore.readSecret] 同：`encryptedPrefs`
     * 的懒初始化发生在下面 `map` 的 lambda 里，主密钥被系统作废（改屏幕锁 / 恢复出厂 /
     * 换机还原）时抛 `GeneralSecurityException`，越过上游的 `.catch` 直接崩收集者。
     *
     * 密码读不出来时 [WebDavConfig.isValid] 为假，同步会停在「未配置」而不是拿空密码去撞
     * 服务器 —— 后者在部分 WebDAV 实现上会累计失败次数并锁账号。
     */
    private fun readSecret(key: String): String =
        runCatching { encryptedPrefs.getString(key, "").orEmpty() }
            .onFailure { Log.w(TAG, "读取密文配置失败：${key}", it) }
            .getOrDefault("")

    /**
     * 写一个密文字段，真落盘了才返回 true。与 [readSecret] 对称，但**不能像它那样静默兜住**。
     *
     * 读失败退化成「未配置」是可接受的：用户看到空表单，重填一次就恢复。写失败若也咽下去，
     * 用户看到的是「已保存」而盘上还是旧值——下次进来密码不对，中间发生了什么无从得知。
     * 所以这里只负责不让异常炸掉调用者，成败交回 [updateConfig] 报给用户。
     *
     * 用 `commit()` 而不是 `apply()`：后者立刻返回、落盘在后台线程，落盘失败没有任何人知道，
     * 也就拿不到「到底写进去了没有」这个答案。代价是一次同步磁盘 I/O，故套在 [Dispatchers.IO] 里。
     *
     * 抛点仍是 `encryptedPrefs` 的 `by lazy`：主密钥被作废（改/清屏幕锁、恢复出厂、换机还原）时
     * 它在这里抛 `GeneralSecurityException`。从前这一步没有任何保护，而调用链的顶端是
     * `SyncSettingsViewModel.save()` 里的 `viewModelScope.launch`——未捕获异常直接崩进程。
     * 读路径修好之后这条反而成了唯一的崩点，且触发方式再普通不过：填完密码按返回键。
     */
    private suspend fun writeSecret(key: String, value: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { encryptedPrefs.edit().putString(key, value).commit() }
                .onFailure { Log.w(TAG, "写入密文配置失败：${key}", it) }
                .getOrDefault(false)
        }

    val configFlow: Flow<WebDavConfig> = context.syncConfigDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            WebDavConfig(
                enabled = prefs[Keys.ENABLED] ?: false,
                baseUrl = prefs[Keys.BASE_URL] ?: "",
                username = prefs[Keys.USERNAME] ?: "",
                password = readSecret(EncryptedKeys.PASSWORD),
                remoteDir = prefs[Keys.REMOTE_DIR] ?: "YuMark"
            )
        }
        // 兜底在 map 之后：上面那个 .catch 只看得见 DataStore 读盘的异常，map 里抛的越过它。
        .catch { e ->
            Log.w(TAG, "WebDAV 配置读取失败，退回默认值", e)
            emit(WebDavConfig())
        }

    val lastSyncedAtFlow: Flow<Long?> = context.syncConfigDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[Keys.LAST_SYNCED_AT] }

    /**
     * 写配置。密文与明文两半**分开成败**。
     *
     * 密码写不进去也要把 [DataStore] 那半边写完：否则主密钥一坏，连「关掉同步」「改个服务器
     * 地址」这种与密码无关的操作都做不到，用户被锁在一个改不动的设置页里。
     *
     * 明文写完之后再抛 [FriendlyIOException]，由调用侧报成一条具体提示（见 [writeSecret]）。
     * 副作用是配置导入那条路（`ImportConfigUseCase`）会报「导入失败」而其实非密文部分已生效——
     * 认这个代价：keystore 坏掉时报「成功」才是真误导，且修好后重新导入是幂等的。
     */
    suspend fun updateConfig(config: WebDavConfig) {
        val secretOk = writeSecret(EncryptedKeys.PASSWORD, config.password)
        context.syncConfigDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = config.enabled
            prefs[Keys.BASE_URL] = config.baseUrl
            prefs[Keys.USERNAME] = config.username
            prefs[Keys.REMOTE_DIR] = config.remoteDir
        }
        if (!secretOk) throw FriendlyIOException(UiMessage.of(R.string.secret_write_failed))
    }

    suspend fun setLastSyncedAt(millis: Long) {
        context.syncConfigDataStore.edit { it[Keys.LAST_SYNCED_AT] = millis }
    }

    private companion object {
        const val TAG = "SyncConfigDataStore"
    }
}
