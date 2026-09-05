package com.yumark.app.domain.usecase.config

import android.content.Context
import android.net.Uri
import com.yumark.app.BuildConfig
import com.yumark.app.R
import com.yumark.app.core.config.ConfigBackupCodec
import com.yumark.app.core.config.ConfigBackupFormatException
import com.yumark.app.core.util.FriendlyIOException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.domain.model.ConfigBackup
import com.yumark.app.domain.model.ConfigImportSummary
import com.yumark.app.domain.repository.AiConfigRepository
import com.yumark.app.domain.repository.SettingsRepository
import com.yumark.app.domain.repository.SyncRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** 正常配置文件不到 4 KB；这个上限只用来挡「选错了一个大文件」，避免整份读进内存。 */
private const val MAX_CONFIG_BYTES = 1 shl 20

/**
 * 把三份配置（外观/编辑器、AI、WebDAV）导出为一个 JSON 文件到用户选定的 SAF 位置。
 *
 * [includeSecrets] 默认由调用方给 false：导出文件会经过聊天、云盘、工单流转，
 * 明文 API Key / WebDAV 密码必须是用户显式勾选后才写进去。
 */
class ExportConfigUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val aiConfigRepository: AiConfigRepository,
    private val syncRepository: SyncRepository
) {
    suspend operator fun invoke(uri: Uri, includeSecrets: Boolean): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val text = ConfigBackupCodec.encode(
                settings = settingsRepository.getSettings(),
                ai = aiConfigRepository.observeConfig().first(),
                sync = syncRepository.observeConfig().first(),
                includeSecrets = includeSecrets,
                appVersion = BuildConfig.VERSION_NAME,
                exportedAt = System.currentTimeMillis()
            )
            // "wt" 截断写：用户在 SAF 里选中同名旧文件时，不截断会残留旧内容的尾巴
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw FriendlyIOException(UiMessage.Res(R.string.saf_error_write_target))
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
    }

    /** 建议文件名。用 Locale.US 定格式，否则某些区域设置会生成非 ASCII 数字的文件名。 */
    fun suggestFileName(now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        return "${ConfigBackup.FILE_PREFIX}-$stamp.json"
    }
}

/**
 * 从用户选定的 JSON 文件导入配置。
 *
 * 语义是**按分段合并**而不是整体替换：
 * - 文件里缺失的分段跳过，缺失的字段落回本机现值；
 * - 不带密钥的文件不会清空本机已保存的 API Key / WebDAV 密码；
 * - 越界/无法识别的取值夹取或忽略，并在 [ConfigImportSummary.warnings] 里如实回报。
 */
class ImportConfigUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val aiConfigRepository: AiConfigRepository,
    private val syncRepository: SyncRepository
) {
    suspend operator fun invoke(uri: Uri): Result<ConfigImportSummary> = runCatching {
        withContext(Dispatchers.IO) {
            val backup = ConfigBackupCodec.decode(readTextBounded(uri))
            val warnings = mutableListOf<String>()

            val mergedSettings = ConfigBackupCodec.mergeSettings(
                current = settingsRepository.getSettings(),
                payload = backup.settings,
                warnings = warnings
            )
            mergedSettings?.let { settingsRepository.updateSettings(it).getOrThrow() }

            val mergedAi = ConfigBackupCodec.mergeAi(
                current = aiConfigRepository.observeConfig().first(),
                payload = backup.ai,
                warnings = warnings
            )
            mergedAi?.let { aiConfigRepository.updateConfig(it) }

            val mergedSync = ConfigBackupCodec.mergeSync(
                current = syncRepository.observeConfig().first(),
                payload = backup.sync,
                warnings = warnings
            )
            mergedSync?.let { syncRepository.saveConfig(it) }

            ConfigImportSummary(
                settingsApplied = mergedSettings != null,
                aiApplied = mergedAi != null,
                syncApplied = mergedSync != null,
                secretsApplied = ConfigBackupCodec.carriesSecrets(backup),
                warnings = warnings
            )
        }
    }

    /**
     * 边读边计长度。不用 readBytes()：那是对用户任选的 URI 无条件全量读入，
     * 选到一个大文件就是一次 OOM。
     */
    private fun readTextBounded(uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw FriendlyIOException(UiMessage.Res(R.string.saf_error_read_source))
        val bytes = stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (buffer.size() + read > MAX_CONFIG_BYTES) {
                    throw ConfigBackupFormatException(
                        UiMessage.of(R.string.config_error_too_large, MAX_CONFIG_BYTES / 1024)
                    )
                }
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
