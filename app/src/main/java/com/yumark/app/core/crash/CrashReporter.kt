package com.yumark.app.core.crash

import android.content.Context
import android.os.Build
import com.yumark.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * 崩溃捕获与本地留存。
 *
 * 刻意**不接任何第三方上报 SDK**（Crashlytics / Sentry 一类）：本应用没有后端，
 * 而崩溃堆栈里可能夹着文档正文与配置内容，静默上传等于把用户笔记发给第三方。
 * 这里的取舍是「捕获 + 本地有界留存 + 用户主动导出」——不联网，因此不需要额外的授权开关；
 * 导出这一步由用户在设置页显式触发，[CrashLogFormatter] 还会先抹掉疑似密钥。
 *
 * 已知边界：[install] 之前发生的崩溃捕获不到，包括 Hilt 自身初始化失败。
 */
@Singleton
class CrashReporter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val store = CrashLogStore(File(context.filesDir, DIR_NAME))
    private val installed = AtomicBoolean(false)

    /**
     * 装上未捕获异常处理器。幂等，且应在 `Application.onCreate` 里尽早调用。
     */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 自己出错绝不能再抛：崩溃处理里的二次异常会顶掉原始堆栈，等于把现场毁了
            runCatching { persist(thread.name, throwable, fatal = true, note = "") }
            if (previous != null) {
                // 必须链回系统 handler。吞掉的话系统不弹「已停止运行」，进程会挂成僵尸态：
                // 界面卡死但进程还在，用户既看不到错误也没法重启。
                previous.uncaughtException(thread, throwable)
            } else {
                // Android 上系统 handler 始终存在，这条实际走不到；真走到了也不能留个半死的进程
                exitProcess(EXIT_CODE_UNHANDLED)
            }
        }
    }

    /**
     * 记录一个已捕获、不终止进程的错误。同步写盘，单条几 KB，IO 代价可忽略，
     * 因此不强制调用方切线程——异常发生的那一刻就是最该落盘的时刻。
     */
    fun recordNonFatal(throwable: Throwable, note: String = "") {
        runCatching { persist(Thread.currentThread().name, throwable, fatal = false, note = note) }
    }

    fun count(): Int = store.count()

    fun hasReports(): Boolean = store.latest() != null

    /** 最近一条记录的正文；没有记录时返回 null。 */
    fun latestText(): String? = store.latest()?.let { file -> runCatching { file.readText() }.getOrNull() }

    /** 全部记录（最新在前），用于导出到用户选定的文件。 */
    fun exportText(): String = store.exportText()

    /** 清空全部记录，返回删除条数。 */
    fun clear(): Int = store.clear()

    private fun persist(threadName: String, throwable: Throwable, fatal: Boolean, note: String) {
        val now = System.currentTimeMillis()
        val entry = CrashEntry(
            timeMillis = now,
            fatal = fatal,
            threadName = threadName,
            throwable = throwable,
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            androidSdk = Build.VERSION.SDK_INT,
            deviceModel = Build.MODEL ?: "unknown",
            note = note
        )
        store.write(
            fileName = CrashLogFormatter.fileName(now, fatal),
            text = CrashLogFormatter.format(entry)
        )
    }

    companion object {
        /** filesDir 下的日志目录名。备份规则里已按此名排除，改名要同步改那两个 xml。 */
        const val DIR_NAME = "crash"

        private const val EXIT_CODE_UNHANDLED = 2
    }
}
