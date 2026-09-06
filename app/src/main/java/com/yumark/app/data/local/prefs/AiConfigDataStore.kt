// androidx.security-crypto 1.1.0 起整套 API 被标记 Deprecated（Jetpack Security 停止维护，
// 官方未给直接替代品，指引是自行基于 Android Keystore 做 AES-GCM 加解密）。
// 用文件级抑制而非就地改写：替换密钥容器涉及**已有用户凭据的读旧写新迁移**，属于独立任务，
// 不能夹带在工具链升级里做——做错就是用户存的 API Key 直接丢失。
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
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yumark.app.R
import com.yumark.app.core.util.FriendlyIOException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiProvider
import com.yumark.app.domain.model.WebSearchProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
 *
 * 见文件顶部关于 security-crypto 弃用抑制的说明。
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
        val WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        val WEB_SEARCH_PROVIDER = stringPreferencesKey("web_search_provider")
        val WEB_SEARCH_CUSTOM_URL = stringPreferencesKey("web_search_custom_url")
        val EMBEDDING_MODEL = stringPreferencesKey("embedding_model")
        val RAG_USE_MAIN_ENDPOINT = booleanPreferencesKey("rag_use_main_endpoint")
        val RAG_BASE_URL = stringPreferencesKey("rag_base_url")
        val RAG_AVAILABLE_MODELS = stringPreferencesKey("rag_available_models")
        val CONSENT_ACKNOWLEDGED = booleanPreferencesKey("ai_consent_acknowledged")
    }

    private object EncryptedKeys {
        const val API_KEY = "api_key"
        const val WEB_SEARCH_API_KEY = "web_search_api_key"
        const val RAG_API_KEY = "rag_api_key"
    }

    /**
     * 读一个密文字段，读不出来当空串。
     *
     * `encryptedPrefs` 是 `by lazy`，真正建容器（拿 Keystore 主密钥、解密文件头）发生在**第一次
     * 取值时**，也就是在下面 `map` 的 lambda 里面。这一步会失败，而且不是理论上的：
     * 主密钥被系统作废（改/清屏幕锁、恢复出厂、换机还原把加密文件搬过来但 Keystore 里的密钥
     * 没跟过来）抛 `GeneralSecurityException`，prefs 文件被截断抛 `IOException`。
     *
     * 兜住它的代价是「密钥读不出来」和「没配过密钥」在下游长得一样。认这个代价：另一头是
     * 每次收集这个 Flow 都抛，而收集点在 `viewModelScope` 里 —— 未捕获的异常直接崩进程，
     * 且清数据之前每次进 AI 设置页都崩，功能永久不可用。
     */
    private fun readSecret(key: String): String =
        runCatching { encryptedPrefs.getString(key, "").orEmpty() }
            .onFailure { Log.w(TAG, "读取密文配置失败：${key}", it) }
            .getOrDefault("")

    /**
     * 写若干密文字段，真落盘了才返回 true。与 [readSecret] 对称，但**不能像它那样静默兜住**。
     *
     * 读失败退化成「未配置」是可接受的：用户看到空表单，重填一次就恢复。写失败若也咽下去，
     * 用户看到的是「已保存」而盘上还是旧值——下次进来密钥不对，中间发生了什么无从得知。
     * 所以这里只负责不让异常炸掉调用者，成败交回 [updateConfig] 报给用户。
     *
     * 三个密钥共用一个 editor 一次 `commit()`：分几次提交会出现「API Key 写进去了、联网搜索或
     * embedding 的那个没写进去」的半截状态，而这几个字段在界面上是同一次「保存」。
     *
     * 用 `commit()` 而不是 `apply()`：后者立刻返回、落盘在后台线程，落盘失败没有任何人知道，
     * 也就拿不到「到底写进去了没有」这个答案。代价是一次同步磁盘 I/O，故套在 [Dispatchers.IO] 里。
     *
     * 抛点仍是 `encryptedPrefs` 的 `by lazy`（成因见 [readSecret]）。从前这一步没有任何保护，
     * 而调用链顶端是 `AiConfigViewModel.save()` 里的 `viewModelScope.launch`——未捕获异常直接
     * 崩进程。读路径修好之后这条反而成了唯一的崩点，且触发方式再普通不过：填完 Key 按返回键。
     */
    private suspend fun writeSecrets(vararg entries: Pair<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val editor = encryptedPrefs.edit()
                entries.forEach { (key, value) -> editor.putString(key, value) }
                editor.commit()
            }
                .onFailure { Log.w(TAG, "写入密文配置失败：${entries.joinToString { it.first }}", it) }
                .getOrDefault(false)
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
                apiKey = readSecret(EncryptedKeys.API_KEY),
                baseUrl = prefs[Keys.BASE_URL] ?: "",
                modelName = prefs[Keys.MODEL_NAME] ?: "",
                availableModels = prefs[Keys.AVAILABLE_MODELS]?.let {
                    runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
                } ?: emptyList(),
                temperature = prefs[Keys.TEMPERATURE] ?: 0.7f,
                maxTokens = prefs[Keys.MAX_TOKENS] ?: 2048,
                streamEnabled = prefs[Keys.STREAM_ENABLED] ?: true,
                webSearchEnabled = prefs[Keys.WEB_SEARCH_ENABLED] ?: false,
                webSearchProvider = runCatching {
                    WebSearchProvider.valueOf(prefs[Keys.WEB_SEARCH_PROVIDER] ?: WebSearchProvider.DUCKDUCKGO.name)
                }.getOrDefault(WebSearchProvider.DUCKDUCKGO),
                webSearchApiKey = readSecret(EncryptedKeys.WEB_SEARCH_API_KEY),
                webSearchCustomUrl = prefs[Keys.WEB_SEARCH_CUSTOM_URL] ?: "",
                embeddingModel = prefs[Keys.EMBEDDING_MODEL] ?: "",
                ragUseMainEndpoint = prefs[Keys.RAG_USE_MAIN_ENDPOINT] ?: true,
                ragBaseUrl = prefs[Keys.RAG_BASE_URL] ?: "",
                ragApiKey = readSecret(EncryptedKeys.RAG_API_KEY),
                ragAvailableModels = prefs[Keys.RAG_AVAILABLE_MODELS]?.let {
                    runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
                } ?: emptyList(),
                consentAcknowledged = prefs[Keys.CONSENT_ACKNOWLEDGED] ?: false
            )
        }
        // 再收一层，位置必须在 map **之后**：上面那个 .catch 只在 map 的上游，看得见的只有
        // DataStore 读盘的异常；map 自己抛的（见 readSecret 的说明，以及以后往这个构造器里
        // 加字段的人可能忘了 runCatching）越过它直接打到收集者身上，那就是崩进程。
        //
        // 退化成默认 AiConfig（enabled = false）而不是继续抛：用户看到「AI 未启用」，重填一次
        // 就恢复；而崩溃循环连「重填」的入口都进不去。
        .catch { e ->
            Log.w(TAG, "AI 配置读取失败，退回默认值", e)
            emit(AiConfig())
        }

    /**
     * 写配置。密文与明文两半**分开成败**。
     *
     * 密钥写不进去也要把 [DataStore] 那半边写完：否则主密钥一坏，连「关掉 AI」「换个模型」这种
     * 与密钥无关的操作都做不到，用户被锁在一个改不动的配置页里。
     *
     * 明文写完之后再抛 [FriendlyIOException]，由调用侧报成一条具体提示（见 [writeSecrets]）。
     * 副作用是配置导入那条路（`ImportConfigUseCase`）会报「导入失败」而其实非密文部分已生效——
     * 认这个代价：keystore 坏掉时报「成功」才是真误导，且修好后重新导入是幂等的。
     */
    suspend fun updateConfig(config: AiConfig) {
        val secretOk = writeSecrets(
            EncryptedKeys.API_KEY to config.apiKey,
            EncryptedKeys.WEB_SEARCH_API_KEY to config.webSearchApiKey,
            EncryptedKeys.RAG_API_KEY to config.ragApiKey
        )

        context.aiConfigDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = config.enabled
            prefs[Keys.PROVIDER] = config.provider.name
            prefs[Keys.BASE_URL] = config.baseUrl
            prefs[Keys.MODEL_NAME] = config.modelName
            prefs[Keys.AVAILABLE_MODELS] = json.encodeToString(config.availableModels)
            prefs[Keys.TEMPERATURE] = config.temperature
            prefs[Keys.MAX_TOKENS] = config.maxTokens
            prefs[Keys.STREAM_ENABLED] = config.streamEnabled
            prefs[Keys.WEB_SEARCH_ENABLED] = config.webSearchEnabled
            prefs[Keys.WEB_SEARCH_PROVIDER] = config.webSearchProvider.name
            prefs[Keys.WEB_SEARCH_CUSTOM_URL] = config.webSearchCustomUrl
            prefs[Keys.EMBEDDING_MODEL] = config.embeddingModel
            prefs[Keys.RAG_USE_MAIN_ENDPOINT] = config.ragUseMainEndpoint
            prefs[Keys.RAG_BASE_URL] = config.ragBaseUrl
            prefs[Keys.RAG_AVAILABLE_MODELS] = json.encodeToString(config.ragAvailableModels)
            prefs[Keys.CONSENT_ACKNOWLEDGED] = config.consentAcknowledged
        }
        if (!secretOk) throw FriendlyIOException(UiMessage.of(R.string.secret_write_failed))
    }

    /**
     * 清掉 API Key。目前只有仓库接口在转发，没有界面入口——仍然照 [updateConfig] 的路数兜住并
     * 上报，免得将来接上「注销 / 清除密钥」按钮时又是一个直接崩进程的写入点。
     */
    suspend fun clearApiKey() {
        val ok = withContext(Dispatchers.IO) {
            runCatching { encryptedPrefs.edit().remove(EncryptedKeys.API_KEY).commit() }
                .onFailure { Log.w(TAG, "清除密文配置失败：${EncryptedKeys.API_KEY}", it) }
                .getOrDefault(false)
        }
        if (!ok) throw FriendlyIOException(UiMessage.of(R.string.secret_write_failed))
    }

    private companion object {
        const val TAG = "AiConfigDataStore"
    }
}
