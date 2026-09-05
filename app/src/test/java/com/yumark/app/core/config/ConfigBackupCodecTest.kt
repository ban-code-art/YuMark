package com.yumark.app.core.config

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiPayload
import com.yumark.app.domain.model.AiProvider
import com.yumark.app.domain.model.CompressionQuality
import com.yumark.app.domain.model.ConfigBackup
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.model.WebDavConfig
import com.yumark.app.domain.model.WebSearchProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfigBackupCodecTest {

    // 与领域默认值刻意不同：这样「回退到本机现值」和「回退到模型默认值」两种行为在断言里可区分
    private val localSettings = UserSettings(
        lightThemeId = "local-light",
        darkThemeId = "local-dark",
        themeId = "claude",
        darkMode = "dark",
        fontSize = 20,
        autoSaveEnabled = false,
        autoSaveInterval = 120,
        autoCompressImages = false,
        imageCompressionQuality = CompressionQuality.HIGH,
        maxImageWidth = 2560,
        defaultPreviewMode = false
    )

    private val localAi = AiConfig(
        enabled = true,
        provider = AiProvider.CLAUDE,
        apiKey = "local-key",
        baseUrl = "https://local.example.com",
        modelName = "local-model",
        availableModels = listOf("local-model"),
        temperature = 1.5f,
        maxTokens = 4096,
        streamEnabled = false,
        webSearchEnabled = true,
        webSearchProvider = WebSearchProvider.TAVILY,
        webSearchApiKey = "local-search-key",
        webSearchCustomUrl = "https://local.search",
        embeddingModel = "local-embed",
        ragUseMainEndpoint = false,
        ragBaseUrl = "https://embed.local",
        ragApiKey = "local-rag-key",
        ragAvailableModels = listOf("local-embed")
    )

    private val localSync = WebDavConfig(
        enabled = true,
        baseUrl = "https://dav.local",
        username = "local-user",
        password = "local-pass",
        remoteDir = "LocalDir"
    )

    private fun encoded(includeSecrets: Boolean) = ConfigBackupCodec.encode(
        settings = localSettings,
        ai = localAi,
        sync = localSync,
        includeSecrets = includeSecrets,
        appVersion = "0.9.1",
        exportedAt = 1_700_000_000_000L
    )

    // ---- 编码 ----

    @Test
    fun `默认导出不写任何明文密钥`() {
        val json = encoded(includeSecrets = false)

        assertThat(json).doesNotContain("local-key")
        assertThat(json).doesNotContain("local-search-key")
        assertThat(json).doesNotContain("local-rag-key")
        assertThat(json).doesNotContain("local-pass")
        // explicitNulls=false：整条字段省略，不留 "apiKey": null 这种噪音
        assertThat(json).doesNotContain("apiKey")
        assertThat(json).doesNotContain("ragApiKey")
        assertThat(json).doesNotContain("password")
        assertThat(json).contains("\"includesSecrets\": false")
    }

    @Test
    fun `勾选后导出携带密钥并标记 includesSecrets`() {
        val json = encoded(includeSecrets = true)

        assertThat(json).contains("local-key")
        assertThat(json).contains("local-search-key")
        assertThat(json).contains("local-rag-key")
        assertThat(json).contains("local-pass")
        assertThat(json).contains("\"includesSecrets\": true")
    }

    @Test
    fun `导出带上魔数与当前格式版本`() {
        val json = encoded(includeSecrets = false)

        assertThat(json).contains("\"format\": \"${ConfigBackup.FORMAT_ID}\"")
        assertThat(json).contains("\"schema\": ${ConfigBackup.SCHEMA_VERSION}")
        assertThat(json).contains("\"appVersion\": \"0.9.1\"")
        assertThat(json).contains("\"exportedAt\": 1700000000000")
    }

    @Test
    fun `枚举以名字导出`() {
        val json = encoded(includeSecrets = false)

        assertThat(json).contains("\"provider\": \"CLAUDE\"")
        assertThat(json).contains("\"webSearchProvider\": \"TAVILY\"")
        assertThat(json).contains("\"imageCompressionQuality\": \"HIGH\"")
    }

    // ---- 往返 ----

    @Test
    fun `不含密钥往返后非密钥字段完全一致`() {
        val backup = ConfigBackupCodec.decode(encoded(includeSecrets = false))
        val warnings = mutableListOf<String>()

        val settings = ConfigBackupCodec.mergeSettings(UserSettings(), backup.settings, warnings)
        val ai = ConfigBackupCodec.mergeAi(AiConfig(), backup.ai, warnings)
        val sync = ConfigBackupCodec.mergeSync(WebDavConfig(), backup.sync, warnings)

        assertThat(settings).isEqualTo(localSettings)
        assertThat(ai).isEqualTo(localAi.copy(apiKey = "", webSearchApiKey = "", ragApiKey = ""))
        assertThat(sync).isEqualTo(localSync.copy(password = ""))
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `含密钥往返后三份配置逐字段一致`() {
        val backup = ConfigBackupCodec.decode(encoded(includeSecrets = true))
        val warnings = mutableListOf<String>()

        assertThat(ConfigBackupCodec.mergeSettings(UserSettings(), backup.settings, warnings))
            .isEqualTo(localSettings)
        assertThat(ConfigBackupCodec.mergeAi(AiConfig(), backup.ai, warnings)).isEqualTo(localAi)
        assertThat(ConfigBackupCodec.mergeSync(WebDavConfig(), backup.sync, warnings)).isEqualTo(localSync)
        assertThat(warnings).isEmpty()
    }

    // ---- 解码防线 ----

    @Test
    fun `缺魔数的 JSON 被拒绝`() {
        val e = assertThrows<ConfigBackupFormatException> {
            ConfigBackupCodec.decode("""{"schema":1,"settings":{"fontSize":14}}""")
        }
        // 钉资源 id + 实参而不是文案字样：文案随语区变，资源 id 是编译期常量。
        // 魔数作为实参带走，用户仍能在提示里看到该找哪个标记。
        assertThat(e.uiMessage)
            .isEqualTo(UiMessage.of(R.string.config_error_not_yumark, ConfigBackup.FORMAT_ID))
    }

    @Test
    fun `空对象被拒绝而不是当成一份全默认配置`() {
        assertThrows<ConfigBackupFormatException> { ConfigBackupCodec.decode("{}") }
    }

    @Test
    fun `非 JSON 文本给出可读错误`() {
        val e = assertThrows<ConfigBackupFormatException> { ConfigBackupCodec.decode("# 这是一篇笔记") }
        val res = e.uiMessage as UiMessage.Res
        assertThat(res.id).isEqualTo(R.string.config_error_not_json)
        // 唯一带原文的分支：原文必须已过 safeDetail（脱敏 + 压平 + 截断）
        assertThat(res.args).hasSize(1)
        assertThat(res.args.single() as String).doesNotContain("\n")
    }

    @Test
    fun `更高的格式版本被拒绝`() {
        val e = assertThrows<ConfigBackupFormatException> {
            ConfigBackupCodec.decode(
                """{"format":"${ConfigBackup.FORMAT_ID}","schema":${ConfigBackup.SCHEMA_VERSION + 1},
                   "settings":{"fontSize":14}}"""
            )
        }
        assertThat(e.uiMessage).isEqualTo(
            UiMessage.of(
                R.string.config_error_schema_too_new,
                ConfigBackup.SCHEMA_VERSION + 1,
                ConfigBackup.SCHEMA_VERSION
            )
        )
    }

    @Test
    fun `非法的格式版本被拒绝`() {
        assertThrows<ConfigBackupFormatException> {
            ConfigBackupCodec.decode("""{"format":"${ConfigBackup.FORMAT_ID}","schema":0,"ai":{}}""")
        }
    }

    @Test
    fun `三段全缺时拒绝导入`() {
        val e = assertThrows<ConfigBackupFormatException> {
            ConfigBackupCodec.decode("""{"format":"${ConfigBackup.FORMAT_ID}","schema":1}""")
        }
        assertThat(e.uiMessage).isEqualTo(UiMessage.Res(R.string.config_error_no_sections))
    }

    @Test
    fun `高版本文件的未知字段被忽略而不是整体失败`() {
        val backup = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,"futureTop":1,
               "settings":{"fontSize":18,"futureField":"x"}}"""
        )

        assertThat(backup.settings?.fontSize).isEqualTo(18)
    }

    @Test
    fun `带 UTF-8 BOM 的文件能解析`() {
        val bom = Char(0xFEFF)
        val backup = ConfigBackupCodec.decode(bom + encoded(includeSecrets = false))

        assertThat(backup.format).isEqualTo(ConfigBackup.FORMAT_ID)
    }

    // ---- 合并语义 ----

    @Test
    fun `缺失分段返回 null 以便调用方跳过写入`() {
        val warnings = mutableListOf<String>()

        assertThat(ConfigBackupCodec.mergeSettings(localSettings, null, warnings)).isNull()
        assertThat(ConfigBackupCodec.mergeAi(localAi, null, warnings)).isNull()
        assertThat(ConfigBackupCodec.mergeSync(localSync, null, warnings)).isNull()
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `只带一个字段的精简文件不重置其它设置`() {
        val backup = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,"settings":{"fontSize":14}}"""
        )
        val warnings = mutableListOf<String>()

        val merged = ConfigBackupCodec.mergeSettings(localSettings, backup.settings, warnings)

        assertThat(merged).isEqualTo(localSettings.copy(fontSize = 14))
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `不含密钥的文件不覆盖本机已存密钥`() {
        val backup = ConfigBackupCodec.decode(encoded(includeSecrets = false))
        val warnings = mutableListOf<String>()

        val ai = ConfigBackupCodec.mergeAi(localAi, backup.ai, warnings)
        val sync = ConfigBackupCodec.mergeSync(localSync, backup.sync, warnings)

        assertThat(ai?.apiKey).isEqualTo("local-key")
        assertThat(ai?.webSearchApiKey).isEqualTo("local-search-key")
        assertThat(sync?.password).isEqualTo("local-pass")
    }

    @Test
    fun `空串密钥视为未提供而不是清空`() {
        val backup = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,"includesSecrets":true,
               "ai":{"apiKey":"","webSearchApiKey":"  ","ragApiKey":""},"sync":{"password":""}}"""
        )
        val warnings = mutableListOf<String>()

        assertThat(ConfigBackupCodec.mergeAi(localAi, backup.ai, warnings)?.apiKey).isEqualTo("local-key")
        assertThat(ConfigBackupCodec.mergeAi(localAi, backup.ai, warnings)?.webSearchApiKey)
            .isEqualTo("local-search-key")
        assertThat(ConfigBackupCodec.mergeAi(localAi, backup.ai, warnings)?.ragApiKey)
            .isEqualTo("local-rag-key")
        assertThat(ConfigBackupCodec.mergeSync(localSync, backup.sync, warnings)?.password)
            .isEqualTo("local-pass")
    }

    @Test
    fun `手改的文件即使漏写 includesSecrets 也会应用密钥`() {
        val backup = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,"ai":{"apiKey":"hand-written"}}"""
        )
        val warnings = mutableListOf<String>()

        assertThat(backup.includesSecrets).isFalse()
        assertThat(ConfigBackupCodec.mergeAi(localAi, backup.ai, warnings)?.apiKey).isEqualTo("hand-written")
        assertThat(ConfigBackupCodec.carriesSecrets(backup)).isTrue()
    }

    /** ragApiKey 是第三把密钥，screener 必须与另外两把同等对待。 */
    @Test
    fun `仅携带 ragApiKey 的文件也算携带密钥`() {
        val backup = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,"ai":{"ragApiKey":"embed-key"}}"""
        )

        assertThat(ConfigBackupCodec.carriesSecrets(backup)).isTrue()
    }

    @Test
    fun `carriesSecrets 对不含密钥的导出为 false`() {
        assertThat(ConfigBackupCodec.carriesSecrets(ConfigBackupCodec.decode(encoded(false)))).isFalse()
        assertThat(ConfigBackupCodec.carriesSecrets(ConfigBackupCodec.decode(encoded(true)))).isTrue()
    }

    @Test
    fun `空白字符串字段回退本机现值`() {
        val backup = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,
               "settings":{"themeId":"","darkMode":"  ","lightThemeId":""},
               "ai":{"baseUrl":"","modelName":"","embeddingModel":"","webSearchCustomUrl":"","ragBaseUrl":""},
               "sync":{"baseUrl":"","username":"","remoteDir":""}}"""
        )
        val warnings = mutableListOf<String>()

        assertThat(ConfigBackupCodec.mergeSettings(localSettings, backup.settings, warnings))
            .isEqualTo(localSettings)
        assertThat(ConfigBackupCodec.mergeAi(localAi, backup.ai, warnings)).isEqualTo(localAi)
        assertThat(ConfigBackupCodec.mergeSync(localSync, backup.sync, warnings)).isEqualTo(localSync)
        assertThat(warnings).isEmpty()
    }

    // ---- 越界与非法取值 ----

    @Test
    fun `越界的数值被夹取并逐条告警`() {
        val backup = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,
               "settings":{"fontSize":999,"autoSaveInterval":0,"maxImageWidth":100000},
               "ai":{"temperature":9.5,"maxTokens":-1}}"""
        )
        val warnings = mutableListOf<String>()

        val settings = ConfigBackupCodec.mergeSettings(localSettings, backup.settings, warnings)
        val ai = ConfigBackupCodec.mergeAi(localAi, backup.ai, warnings)

        assertThat(settings?.fontSize).isEqualTo(24)
        assertThat(settings?.autoSaveInterval).isEqualTo(5)
        assertThat(settings?.maxImageWidth).isEqualTo(8192)
        assertThat(ai?.temperature).isEqualTo(2f)
        assertThat(ai?.maxTokens).isEqualTo(1)
        assertThat(warnings).hasSize(5)
        assertThat(warnings.joinToString()).contains("字体大小")
    }

    @Test
    fun `范围边界值原样接受`() {
        val backup = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,
               "settings":{"fontSize":12,"autoSaveInterval":3600,"maxImageWidth":320},
               "ai":{"temperature":0.0,"maxTokens":1048576}}"""
        )
        val warnings = mutableListOf<String>()

        val settings = ConfigBackupCodec.mergeSettings(localSettings, backup.settings, warnings)
        val ai = ConfigBackupCodec.mergeAi(localAi, backup.ai, warnings)

        assertThat(settings?.fontSize).isEqualTo(12)
        assertThat(settings?.autoSaveInterval).isEqualTo(3600)
        assertThat(settings?.maxImageWidth).isEqualTo(320)
        assertThat(ai?.temperature).isEqualTo(0f)
        assertThat(ai?.maxTokens).isEqualTo(1_048_576)
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `设置页控件能选到的每个值都原样通过校验`() {
        // 设置页那三个控件的取值域全表（见 SettingsScreen 的档位表与滑块刻度）：
        //   自动保存间隔 FilterChip = 5 / 15 / 30 / 60 / 300
        //   最大图片宽度 Slider   = 640..3840 步长 160
        //   压缩质量 FilterChip   = CompressionQuality 的全部取值
        // 逐个走一遍 merge：任何一个被夹取或带出告警，都等于「界面上调好、导出再导进来变了值」。
        // 这条断言把界面的取值域与本类里那几个 private 区间常量锁在一起——改任何一边都会在这里红。
        val warnings = mutableListOf<String>()

        listOf(5, 15, 30, 60, 300).forEach { sec ->
            val merged = ConfigBackupCodec.mergeSettings(
                localSettings,
                ConfigBackupCodec.decode(
                    """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,
                       "settings":{"autoSaveInterval":$sec}}"""
                ).settings,
                warnings
            )
            assertThat(merged?.autoSaveInterval).isEqualTo(sec)
        }

        (640..3840 step 160).forEach { px ->
            val merged = ConfigBackupCodec.mergeSettings(
                localSettings,
                ConfigBackupCodec.decode(
                    """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,
                       "settings":{"maxImageWidth":$px}}"""
                ).settings,
                warnings
            )
            assertThat(merged?.maxImageWidth).isEqualTo(px)
        }

        CompressionQuality.entries.forEach { q ->
            val merged = ConfigBackupCodec.mergeSettings(
                localSettings,
                ConfigBackupCodec.decode(
                    """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,
                       "settings":{"imageCompressionQuality":"${q.name}"}}"""
                ).settings,
                warnings
            )
            assertThat(merged?.imageCompressionQuality).isEqualTo(q)
        }

        assertThat(warnings).isEmpty()
    }

    @Test
    fun `无法识别的枚举保留原值并告警`() {
        val backup = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,
               "settings":{"imageCompressionQuality":"ULTRA"},
               "ai":{"provider":"GROK","webSearchProvider":"BING"}}"""
        )
        val warnings = mutableListOf<String>()

        val settings = ConfigBackupCodec.mergeSettings(localSettings, backup.settings, warnings)
        val ai = ConfigBackupCodec.mergeAi(localAi, backup.ai, warnings)

        assertThat(settings?.imageCompressionQuality).isEqualTo(CompressionQuality.HIGH)
        assertThat(ai?.provider).isEqualTo(AiProvider.CLAUDE)
        assertThat(ai?.webSearchProvider).isEqualTo(WebSearchProvider.TAVILY)
        assertThat(warnings).hasSize(3)
        assertThat(warnings.joinToString()).contains("GROK")
    }

    @Test
    fun `无法识别的深色模式保留原值并告警`() {
        val backup = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,"settings":{"darkMode":"amoled"}}"""
        )
        val warnings = mutableListOf<String>()

        val settings = ConfigBackupCodec.mergeSettings(localSettings, backup.settings, warnings)

        assertThat(settings?.darkMode).isEqualTo("dark")
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("amoled")
    }

    @Test
    fun `合法的深色模式三个取值都被接受`() {
        listOf("system", "light", "dark").forEach { mode ->
            val backup = ConfigBackupCodec.decode(
                """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,"settings":{"darkMode":"$mode"}}"""
            )
            val warnings = mutableListOf<String>()

            assertThat(ConfigBackupCodec.mergeSettings(localSettings, backup.settings, warnings)?.darkMode)
                .isEqualTo(mode)
            assertThat(warnings).isEmpty()
        }
    }

    @Test
    fun `显式 null 的 temperature 回退本机现值`() {
        val warnings = mutableListOf<String>()
        val payload = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,"ai":{"temperature":null}}"""
        ).ai

        assertThat(ConfigBackupCodec.mergeAi(localAi, payload, warnings)?.temperature).isEqualTo(1.5f)
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `NaN 的 temperature 回退本机现值且不告警`() {
        // JSON 里写不出 NaN，但 payload 可能由别处构造；夹取前必须先挡掉，否则 coerceIn 会把 NaN 传下去
        val warnings = mutableListOf<String>()

        val ai = ConfigBackupCodec.mergeAi(localAi, AiPayload(temperature = Float.NaN), warnings)

        assertThat(ai?.temperature).isEqualTo(1.5f)
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `空的 availableModels 数组会覆盖本机列表`() {
        // 显式给了空数组就是「清空模型列表」；缺字段才是「别动」
        val backup = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,"ai":{"availableModels":[]}}"""
        )
        val warnings = mutableListOf<String>()

        assertThat(ConfigBackupCodec.mergeAi(localAi, backup.ai, warnings)?.availableModels).isEmpty()

        val without = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,"ai":{"modelName":"m"}}"""
        )
        assertThat(ConfigBackupCodec.mergeAi(localAi, without.ai, warnings)?.availableModels)
            .containsExactly("local-model")
    }

    @Test
    fun `布尔开关能被显式关闭`() {
        val backup = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,
               "settings":{"autoSaveEnabled":true,"autoCompressImages":true,"defaultPreviewMode":true},
               "ai":{"enabled":false,"streamEnabled":true,"webSearchEnabled":false,
                      "ragUseMainEndpoint":true,"ragAvailableModels":[]},
               "sync":{"enabled":false}}"""
        )
        val warnings = mutableListOf<String>()

        val settings = ConfigBackupCodec.mergeSettings(localSettings, backup.settings, warnings)
        val ai = ConfigBackupCodec.mergeAi(localAi, backup.ai, warnings)
        val sync = ConfigBackupCodec.mergeSync(localSync, backup.sync, warnings)

        assertThat(settings?.autoSaveEnabled).isTrue()
        assertThat(settings?.autoCompressImages).isTrue()
        assertThat(settings?.defaultPreviewMode).isTrue()
        assertThat(ai?.enabled).isFalse()
        assertThat(ai?.streamEnabled).isTrue()
        assertThat(ai?.webSearchEnabled).isFalse()
        assertThat(ai?.ragUseMainEndpoint).isTrue()
        // 显式空数组 = 「清空」，与 availableModels 同语义
        assertThat(ai?.ragAvailableModels).isEmpty()
        assertThat(sync?.enabled).isFalse()
        assertThat(warnings).isEmpty()
    }

    /**
     * rag 这组字段从「复用 chat 端点」迁移到「单独配置」时的关键回合：
     * `ragUseMainEndpoint` 是 Boolean?，显式 false 必须能落进结果——它不是密钥、
     * 不是「缺失即不动」的开关，而是与 enabled/streamEnabled 同类的方向性设置。
     */
    @Test
    fun `ragUseMainEndpoint 可被显式翻转为 false 并带动 ragBaseUrl 生效`() {
        val backup = ConfigBackupCodec.decode(
            """{"format":"${ConfigBackup.FORMAT_ID}","schema":1,
               "ai":{"ragUseMainEndpoint":false,"ragBaseUrl":"https://embed.remote/v1"}}"""
        )
        val warnings = mutableListOf<String>()

        val ai = ConfigBackupCodec.mergeAi(localAi, backup.ai, warnings)

        assertThat(ai?.ragUseMainEndpoint).isFalse()
        assertThat(ai?.ragBaseUrl).isEqualTo("https://embed.remote/v1")
        assertThat(warnings).isEmpty()
    }
}
