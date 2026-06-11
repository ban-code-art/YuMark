package com.yumark.app.domain.model

data class UserSettings(
    val lightThemeId: String = "default-light",
    val darkThemeId: String = "default-dark",
    val fontSize: Int = 16,
    val autoSaveEnabled: Boolean = true,
    val autoSaveInterval: Int = 30,
    val autoCompressImages: Boolean = true,
    val imageCompressionQuality: CompressionQuality = CompressionQuality.MEDIUM,
    val maxImageWidth: Int = 1920,
    val defaultPreviewMode: Boolean = true,
    val themeId: String = "default",
    val darkMode: String = "system"  // system | light | dark
)

enum class CompressionQuality(val value: Int) {
    LOW(60),
    MEDIUM(80),
    HIGH(90)
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
    PDF("pdf", "application/pdf"),
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    IMAGE("png", "image/png")
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
