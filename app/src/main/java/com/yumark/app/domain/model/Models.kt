package com.yumark.app.domain.model

data class UserSettings(
    /**
     * 历史遗留字段，**不参与取色**。实际生效的是 [themeId] + [darkMode]：一个 `AppTheme`
     * 自带亮暗两套配色（见 `AppThemes`，现有 id 只有 `default` / `claude`），根本没有
     * 「亮色主题 id」和「暗色主题 id」这一分为二的概念，所以这两个默认值也对不上任何真主题。
     *
     * 仍留着是因为它们已经写进了导出配置的 JSON（`ConfigBackup.SettingsPayload`）：删字段会让
     * 旧配置文件导入时多出未知键。查主题相关问题时别顺着这两个值找，它们只会误导。
     */
    val lightThemeId: String = "default-light",
    val darkThemeId: String = "default-dark",
    val fontSize: Int = 16,
    val autoSaveEnabled: Boolean = true,
    val autoSaveInterval: Int = 30,
    val autoCompressImages: Boolean = true,
    val imageCompressionQuality: CompressionQuality = CompressionQuality.MEDIUM,
    val maxImageWidth: Int = 1920,
    val defaultPreviewMode: Boolean = true,
    /** 当前主题 id，取值见 `AppThemes.all`；认不出的值由 `AppThemes.byId` 回退默认主题。 */
    val themeId: String = "default",
    val darkMode: String = "system"  // system | light | dark
)

enum class CompressionQuality(val value: Int) {
    LOW(60),
    MEDIUM(80),
    HIGH(90);

    /**
     * 与 [SortOption.localizedLabel] 同一套做法：枚举的界面文案跟着枚举走，而不是在设置页里
     * 现写一张 when 表。when 刻意穷尽（没有 else），往枚举里加档位时编译器会在这里报错。
     */
    @androidx.compose.runtime.Composable
    fun localizedLabel(): String {
        return androidx.compose.ui.res.stringResource(
            when (this) {
                LOW -> com.yumark.app.R.string.compression_quality_low
                MEDIUM -> com.yumark.app.R.string.compression_quality_medium
                HIGH -> com.yumark.app.R.string.compression_quality_high
            }
        )
    }
}

enum class SortOption {
    NAME_ASC, NAME_DESC,
    DATE_NEWEST, DATE_OLDEST,
    WORD_COUNT_ASC, WORD_COUNT_DESC;

    @androidx.compose.runtime.Composable
    fun localizedLabel(): String {
        return androidx.compose.ui.res.stringResource(
            when (this) {
                NAME_ASC -> com.yumark.app.R.string.sort_by_name_asc
                NAME_DESC -> com.yumark.app.R.string.sort_by_name_desc
                DATE_NEWEST -> com.yumark.app.R.string.sort_by_date_newest
                DATE_OLDEST -> com.yumark.app.R.string.sort_by_date_oldest
                WORD_COUNT_ASC -> com.yumark.app.R.string.sort_by_word_count_asc
                WORD_COUNT_DESC -> com.yumark.app.R.string.sort_by_word_count_desc
            }
        )
    }
}

data class SearchResult(
    val document: Document,
    val matchCount: Int,
    val snippets: List<String>
)

enum class ExportFormat(val extension: String, val mimeType: String) {
    MARKDOWN("md", "text/markdown"),
    HTML("html", "text/html"),
    RICH_HTML("html", "text/html"),
    PDF("pdf", "application/pdf"),
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    IMAGE("png", "image/png");

    companion object {
        /**
         * 扩展名 → mime。分享导出结果的那一步手里只有 File，没有 [ExportFormat]。
         *
         * 从前那处只认 md / html，PDF、DOCX、PNG 一律按 `text/plain` 分享：接收方要么只列出
         * 纯文本类目标（图库、PDF 阅读器根本不出现），要么把二进制当文本收下，附件到对面打不开。
         * 认不出的扩展名退回 `application/octet-stream` —— 比谎称 text/plain 老实。
         */
        fun mimeForExtension(extension: String): String {
            val ext = extension.lowercase().removePrefix(".")
            if (ext == "markdown") return MARKDOWN.mimeType
            return entries.firstOrNull { it.extension == ext }?.mimeType ?: "application/octet-stream"
        }
    }
}

data class ExportOptions(
    val outputDir: java.io.File,
    val inlineImages: Boolean = false,
    val includeTableOfContents: Boolean = false
)

data class EditorTheme(
    val id: String,
    val name: String,
    val isLight: Boolean,
    val cssFile: String,
    val typography: ThemeTypography,
    val codeTheme: CodeTheme
)

data class ThemeTypography(
    val fontFamily: String,
    val codeFontFamily: String,
    val baseFontSize: Int,
    val lineHeight: Float,
    val headingScale: List<Float>
)

data class CodeTheme(
    val name: String,
    val backgroundColor: Long,
    val textColor: Long
)
