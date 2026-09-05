package com.yumark.app.core.config

import com.yumark.app.R
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserFacingMessage
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiPayload
import com.yumark.app.domain.model.ConfigBackup
import com.yumark.app.domain.model.SettingsPayload
import com.yumark.app.domain.model.SyncPayload
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.model.WebDavConfig
import kotlinx.serialization.json.Json

/**
 * 配置文件格式/内容不合法。消息说明「哪儿不对」而不是抛底层异常，可直接示人（见 [UserFacingMessage]）。
 *
 * 两个构造器：收 [UiMessage] 的那个可翻译（本文件在 `core`，拿不到 Context，只给资源 id）；
 * 收 String 的那个留给文案在抛出点由运行期数据拼成的场合。
 */
class ConfigBackupFormatException private constructor(
    message: String?,
    override val uiMessage: UiMessage?
) : Exception(message), UserFacingMessage {

    constructor(message: String) : this(message, null)

    constructor(uiMessage: UiMessage) : this(uiMessage.toString(), uiMessage)
}

/**
 * 配置备份的编解码与字段合并——**纯 Kotlin，不碰 Android**，因此可以在 JVM 单元测试里全覆盖。
 *
 * 合并（而不是整体替换）是刻意的：
 * - 文件是用户可手改、可跨版本的外部输入，缺字段必须落回本机现值而不是领域模型的默认值，
 *   否则「只想改字号」的一份精简文件会把其它设置全部重置。
 * - 不带密钥的文件（默认导出形态）绝不能用空串覆盖本机已有的 API Key / WebDAV 密码。
 */
object ConfigBackupCodec {

    // 取值范围：与 UI 控件保持一致（字号对应设置页 Slider 的 12–24），其余取工程上合理的边界。
    private val FONT_SIZE = 12..24
    private val AUTO_SAVE_INTERVAL = 5..3600
    private val MAX_IMAGE_WIDTH = 320..8192
    private val MAX_TOKENS = 1..1_048_576
    private const val TEMPERATURE_MIN = 0f
    private const val TEMPERATURE_MAX = 2f
    private val DARK_MODES = setOf("system", "light", "dark")

    /** UTF-8 BOM。用码点构造，避免源码里出现不可见字符。 */
    private val BOM_CHAR = Char(0xFEFF)

    private val json = Json {
        prettyPrint = true
        // 高版本文件里的新字段不能让导入整体失败
        ignoreUnknownKeys = true
        // 默认值也要写进文件，便于用户直接手改
        encodeDefaults = true
        // null 字段整条省略：不带密钥导出时不留 "apiKey": null 这种噪音
        explicitNulls = false
    }

    fun encode(
        settings: UserSettings,
        ai: AiConfig,
        sync: WebDavConfig,
        includeSecrets: Boolean,
        appVersion: String,
        exportedAt: Long
    ): String = json.encodeToString(
        ConfigBackup(
            format = ConfigBackup.FORMAT_ID,
            schema = ConfigBackup.SCHEMA_VERSION,
            appVersion = appVersion,
            exportedAt = exportedAt,
            includesSecrets = includeSecrets,
            settings = SettingsPayload(
                lightThemeId = settings.lightThemeId,
                darkThemeId = settings.darkThemeId,
                themeId = settings.themeId,
                darkMode = settings.darkMode,
                fontSize = settings.fontSize,
                autoSaveEnabled = settings.autoSaveEnabled,
                autoSaveInterval = settings.autoSaveInterval,
                autoCompressImages = settings.autoCompressImages,
                imageCompressionQuality = settings.imageCompressionQuality.name,
                maxImageWidth = settings.maxImageWidth,
                defaultPreviewMode = settings.defaultPreviewMode
            ),
            ai = AiPayload(
                enabled = ai.enabled,
                provider = ai.provider.name,
                baseUrl = ai.baseUrl,
                modelName = ai.modelName,
                availableModels = ai.availableModels,
                temperature = ai.temperature,
                maxTokens = ai.maxTokens,
                streamEnabled = ai.streamEnabled,
                webSearchEnabled = ai.webSearchEnabled,
                webSearchProvider = ai.webSearchProvider.name,
                webSearchCustomUrl = ai.webSearchCustomUrl,
                embeddingModel = ai.embeddingModel,
                ragUseMainEndpoint = ai.ragUseMainEndpoint,
                ragBaseUrl = ai.ragBaseUrl,
                ragAvailableModels = ai.ragAvailableModels,
                apiKey = ai.apiKey.takeIf { includeSecrets },
                webSearchApiKey = ai.webSearchApiKey.takeIf { includeSecrets },
                ragApiKey = ai.ragApiKey.takeIf { includeSecrets }
            ),
            sync = SyncPayload(
                enabled = sync.enabled,
                baseUrl = sync.baseUrl,
                username = sync.username,
                remoteDir = sync.remoteDir,
                password = sync.password.takeIf { includeSecrets }
            )
        )
    )

    /** @throws ConfigBackupFormatException 解析失败、魔数不符、版本过高或没有任何可导入分段 */
    fun decode(text: String): ConfigBackup {
        // Windows 记事本等编辑器保存 UTF-8 会加 BOM，kotlinx 会在第一个字符上就报错，
        // 而用户看到的文件内容完全正常——先剥掉，别让手改配置的人卡在这里。
        val cleaned = if (text.startsWith(BOM_CHAR)) text.substring(1) else text
        val backup = runCatching { json.decodeFromString<ConfigBackup>(cleaned) }.getOrElse {
            // kotlinx 的解析错误会回显出错位置附近的原文，而这份文件里可能正躺着 API Key /
            // WebDAV 密码（用户勾了「包含密钥」导出的那种），所以先经 safeDetail 脱敏再拼进消息。
            throw ConfigBackupFormatException(
                UiMessage.of(R.string.config_error_not_json, ErrorHandler.safeDetail(it))
            )
        }
        if (backup.format != ConfigBackup.FORMAT_ID) {
            throw ConfigBackupFormatException(
                UiMessage.of(R.string.config_error_not_yumark, ConfigBackup.FORMAT_ID)
            )
        }
        if (backup.schema < 1) {
            throw ConfigBackupFormatException(
                UiMessage.of(R.string.config_error_bad_schema, backup.schema)
            )
        }
        if (backup.schema > ConfigBackup.SCHEMA_VERSION) {
            throw ConfigBackupFormatException(
                UiMessage.of(
                    R.string.config_error_schema_too_new,
                    backup.schema,
                    ConfigBackup.SCHEMA_VERSION
                )
            )
        }
        if (backup.settings == null && backup.ai == null && backup.sync == null) {
            throw ConfigBackupFormatException(UiMessage.Res(R.string.config_error_no_sections))
        }
        return backup
    }

    /** @return null 表示文件没带这一段，调用方应跳过写入 */
    fun mergeSettings(
        current: UserSettings,
        payload: SettingsPayload?,
        warnings: MutableList<String>
    ): UserSettings? {
        if (payload == null) return null
        return current.copy(
            lightThemeId = payload.lightThemeId.ifBlank { current.lightThemeId },
            darkThemeId = payload.darkThemeId.ifBlank { current.darkThemeId },
            themeId = payload.themeId.ifBlank { current.themeId },
            darkMode = when {
                payload.darkMode.isBlank() -> current.darkMode
                payload.darkMode in DARK_MODES -> payload.darkMode
                else -> {
                    warnings += "深色模式取值「${payload.darkMode}」无法识别，保留原值"
                    current.darkMode
                }
            },
            fontSize = clampInt(payload.fontSize, FONT_SIZE, current.fontSize, "字体大小", warnings),
            autoSaveEnabled = payload.autoSaveEnabled ?: current.autoSaveEnabled,
            autoSaveInterval = clampInt(
                payload.autoSaveInterval, AUTO_SAVE_INTERVAL, current.autoSaveInterval, "自动保存间隔", warnings
            ),
            autoCompressImages = payload.autoCompressImages ?: current.autoCompressImages,
            imageCompressionQuality = enumOrKeep(
                payload.imageCompressionQuality, current.imageCompressionQuality, "图片压缩质量", warnings
            ),
            maxImageWidth = clampInt(
                payload.maxImageWidth, MAX_IMAGE_WIDTH, current.maxImageWidth, "图片最大宽度", warnings
            ),
            defaultPreviewMode = payload.defaultPreviewMode ?: current.defaultPreviewMode
        )
    }

    /** @return null 表示文件没带这一段 */
    fun mergeAi(
        current: AiConfig,
        payload: AiPayload?,
        warnings: MutableList<String>
    ): AiConfig? {
        if (payload == null) return null
        return current.copy(
            enabled = payload.enabled ?: current.enabled,
            provider = enumOrKeep(payload.provider, current.provider, "AI Provider", warnings),
            baseUrl = payload.baseUrl.ifBlank { current.baseUrl },
            modelName = payload.modelName.ifBlank { current.modelName },
            availableModels = payload.availableModels ?: current.availableModels,
            temperature = clampFloat(
                payload.temperature, TEMPERATURE_MIN, TEMPERATURE_MAX, current.temperature, "temperature", warnings
            ),
            maxTokens = clampInt(payload.maxTokens, MAX_TOKENS, current.maxTokens, "maxTokens", warnings),
            streamEnabled = payload.streamEnabled ?: current.streamEnabled,
            webSearchEnabled = payload.webSearchEnabled ?: current.webSearchEnabled,
            webSearchProvider = enumOrKeep(
                payload.webSearchProvider, current.webSearchProvider, "联网搜索 Provider", warnings
            ),
            webSearchCustomUrl = payload.webSearchCustomUrl.ifBlank { current.webSearchCustomUrl },
            embeddingModel = payload.embeddingModel.ifBlank { current.embeddingModel },
            ragUseMainEndpoint = payload.ragUseMainEndpoint ?: current.ragUseMainEndpoint,
            ragBaseUrl = payload.ragBaseUrl.ifBlank { current.ragBaseUrl },
            ragAvailableModels = payload.ragAvailableModels ?: current.ragAvailableModels,
            apiKey = secretOrKeep(payload.apiKey, current.apiKey),
            webSearchApiKey = secretOrKeep(payload.webSearchApiKey, current.webSearchApiKey),
            ragApiKey = secretOrKeep(payload.ragApiKey, current.ragApiKey)
        )
    }

    /** @return null 表示文件没带这一段 */
    fun mergeSync(
        current: WebDavConfig,
        payload: SyncPayload?,
        warnings: MutableList<String>
    ): WebDavConfig? {
        if (payload == null) return null
        return current.copy(
            enabled = payload.enabled ?: current.enabled,
            baseUrl = payload.baseUrl.ifBlank { current.baseUrl },
            username = payload.username.ifBlank { current.username },
            remoteDir = payload.remoteDir.ifBlank { current.remoteDir },
            password = secretOrKeep(payload.password, current.password)
        )
    }

    /**
     * 文件是否真的携带了至少一个明文密钥。
     * 只看字段实际内容，不看 [ConfigBackup.includesSecrets] 声明——那个字段是给用户看的提示，
     * 手改过的文件里它和内容对不上很正常。
     */
    fun carriesSecrets(backup: ConfigBackup): Boolean = listOf(
        backup.ai?.apiKey,
        backup.ai?.webSearchApiKey,
        backup.ai?.ragApiKey,
        backup.sync?.password
    ).any { !it.isNullOrBlank() }

    // ---- 校验辅助 ----

    /**
     * 密钥字段：缺失或空白都当作「文件没提供」，保留本机现值。
     *
     * 不带密钥导出的文件里这些字段整条省略，所以这条规则同时保证了
     * 「导出一份不含密钥的配置再导入」不会把本机已存的 Key 洗成空串。
     * 想清空密钥请在 AI/同步设置页里做，那里是明确的破坏性操作。
     */
    private fun secretOrKeep(incoming: String?, current: String): String =
        incoming?.takeIf { it.isNotBlank() } ?: current

    private fun clampInt(
        value: Int?,
        range: IntRange,
        fallback: Int,
        label: String,
        warnings: MutableList<String>
    ): Int {
        if (value == null) return fallback
        if (value in range) return value
        val clamped = value.coerceIn(range)
        warnings += "$label $value 超出 ${range.first}–${range.last}，已夹到 $clamped"
        return clamped
    }

    private fun clampFloat(
        value: Float?,
        min: Float,
        max: Float,
        fallback: Float,
        label: String,
        warnings: MutableList<String>
    ): Float {
        if (value == null || value.isNaN()) return fallback
        if (value in min..max) return value
        val clamped = value.coerceIn(min, max)
        warnings += "$label $value 超出 $min–$max，已夹到 $clamped"
        return clamped
    }

    private inline fun <reified T : Enum<T>> enumOrKeep(
        name: String,
        fallback: T,
        label: String,
        warnings: MutableList<String>
    ): T {
        if (name.isBlank()) return fallback
        return runCatching { enumValueOf<T>(name) }.getOrElse {
            warnings += "$label 取值「$name」无法识别，保留原值"
            fallback
        }
    }
}
