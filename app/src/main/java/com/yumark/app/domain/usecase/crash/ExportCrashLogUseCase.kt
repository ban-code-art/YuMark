package com.yumark.app.domain.usecase.crash

import android.content.Context
import android.net.Uri
import com.yumark.app.R
import com.yumark.app.core.crash.CrashReporter
import com.yumark.app.core.util.FriendlyIOException
import com.yumark.app.core.util.UiMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 把本地崩溃日志导出到用户选定的 SAF 位置。
 *
 * 崩溃记录刻意不联网上报（理由见 [CrashReporter] 的类注释），所以这里是它到开发者手上的
 * **唯一**途径——因此导出的是完整快照而不只是最近一条，且必须由用户显式触发。
 * 文本在写盘时已由 [com.yumark.app.core.crash.CrashLogFormatter] 抹掉疑似密钥。
 */
class ExportCrashLogUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reporter: CrashReporter
) {

    /** 成功时返回写出的记录条数。 */
    suspend operator fun invoke(uri: Uri): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            val count = reporter.count()
            val text = reporter.exportText()
            // "wt" 截断写：用户在 SAF 里选中同名旧文件时，不截断会残留旧内容的尾巴
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw FriendlyIOException(UiMessage.Res(R.string.saf_error_write_target))
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            count
        }
    }

    /** 建议文件名。Locale.US 定格式，否则某些区域设置会生成非 ASCII 数字的文件名。 */
    fun suggestFileName(now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        return "yumark-crash-$stamp.log"
    }
}
