package com.yumark.app.domain.model

import kotlinx.serialization.Serializable

/**
 * 配置备份文件的 JSON 载荷。
 *
 * 设计约束：
 * - **每个字段都有默认值、每个分段都可为 null**。旧版本导出的文件缺字段、新版本文件多字段都必须能导入，
 *   所以解码端配 `ignoreUnknownKeys`，缺失字段落到默认值，缺失分段整段跳过而不是整体失败。
 * - **枚举一律用 String 存名字**，不用 kotlinx 的枚举序列化。手改过的文件里出现未知枚举值时，
 *   我们要「保留用户当前值 + 给一条告警」，而不是让整个导入抛异常。
 * - **不镜像领域模型的字段名**：这里是对外文件格式，改 [UserSettings] / [AiConfig] 不应静默改文件格式。
 *   两者之间的映射集中在 ConfigBackupCodec，字段对不上时编译期就会暴露。
 */
@Serializable
data class ConfigBackup(
    /**
     * 文件类型魔数，固定 [FORMAT_ID]。
     * 没有它的话任意一段 JSON（甚至 `{}`）都会解成「全默认值」而被当作合法配置文件，
     * 用户选错文件时我们必须报错，而不是静默导入一堆默认值。
     */
    val format: String = "",
    /** 文件格式版本。大于当前 [SCHEMA_VERSION] 时拒绝导入（不猜新格式的语义）。 */
    val schema: Int = SCHEMA_VERSION,
    /** 导出时的应用版本，仅用于排查问题，不参与任何判断。 */
    val appVersion: String = "",
    /** 导出时刻（epoch 毫秒）。 */
    val exportedAt: Long = 0L,
    /**
     * 该文件是否携带明文密钥（API Key / WebDAV 密码），导出时如实写入，供用户判断这份文件敏不敏感。
     * 导入端**不以此为准**：是否覆盖密钥只看字段本身有没有非空内容，
     * 因为手改过的文件里这个标记和内容对不上是常态。
     */
    val includesSecrets: Boolean = false,
    val settings: SettingsPayload? = null,
    val ai: AiPayload? = null,
    val sync: SyncPayload? = null
) {
    companion object {
        const val FORMAT_ID = "yumark-config"
        const val SCHEMA_VERSION = 1
        const val MIME_TYPE = "application/json"
        const val FILE_PREFIX = "yumark-config"
    }
}

/** 编辑器与外观设置，对应 [UserSettings]。 */
@Serializable
data class SettingsPayload(
    val lightThemeId: String = "",
    val darkThemeId: String = "",
    val themeId: String = "",
    /** system | light | dark */
    val darkMode: String = "",
    val fontSize: Int? = null,
    val autoSaveEnabled: Boolean? = null,
    val autoSaveInterval: Int? = null,
    val autoCompressImages: Boolean? = null,
    /** [CompressionQuality] 的名字：LOW / MEDIUM / HIGH */
    val imageCompressionQuality: String = "",
    val maxImageWidth: Int? = null,
    val defaultPreviewMode: Boolean? = null
)

/**
 * AI 配置，对应 [AiConfig]。
 * apiKey / webSearchApiKey / ragApiKey 仅在导出时勾选「包含密钥」才出现；缺失或空白表示「不改动本机现值」。
 *
 * 新增字段（如 rag* 这一组）不抬 [ConfigBackup.SCHEMA_VERSION]：它们全部可缺省，
 * 旧文件导入时落到默认值，旧版本读新文件时被 `ignoreUnknownKeys` 跳过。抬版本号反而会让
 * 旧版本把新文件整份拒掉（`schema > SCHEMA_VERSION` 是硬拒），代价远大于收益。
 */
@Serializable
data class AiPayload(
    val enabled: Boolean? = null,
    /** [AiProvider] 的名字 */
    val provider: String = "",
    val baseUrl: String = "",
    val modelName: String = "",
    val availableModels: List<String>? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val streamEnabled: Boolean? = null,
    val webSearchEnabled: Boolean? = null,
    /** [WebSearchProvider] 的名字 */
    val webSearchProvider: String = "",
    val webSearchCustomUrl: String = "",
    val embeddingModel: String = "",
    /** embedding 端点是否复用 chat 的 [baseUrl]/[apiKey]，见 [AiConfig.ragUseMainEndpoint] */
    val ragUseMainEndpoint: Boolean? = null,
    val ragBaseUrl: String = "",
    val ragAvailableModels: List<String>? = null,
    val apiKey: String? = null,
    val webSearchApiKey: String? = null,
    val ragApiKey: String? = null
)

/** WebDAV 同步配置，对应 [WebDavConfig]。password 仅在导出时勾选「包含密钥」才出现；缺失或空白表示「不改动本机现值」。 */
@Serializable
data class SyncPayload(
    val enabled: Boolean? = null,
    val baseUrl: String = "",
    val username: String = "",
    val remoteDir: String = "",
    val password: String? = null
)

/**
 * 导入结果摘要。三个 applied 标志按分段给，任一分段缺失/无效时其余分段照常应用——
 * 部分成功比整体回滚更符合「手写/跨版本文件」的实际使用场景。
 */
data class ConfigImportSummary(
    val settingsApplied: Boolean,
    val aiApplied: Boolean,
    val syncApplied: Boolean,
    /** 文件里带了明文密钥且已写入本机 */
    val secretsApplied: Boolean,
    /** 被夹取/被忽略的字段说明，用于回显给用户 */
    val warnings: List<String>
)
