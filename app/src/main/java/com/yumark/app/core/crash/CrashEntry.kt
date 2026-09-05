package com.yumark.app.core.crash

/**
 * 一条崩溃 / 非致命异常记录。
 *
 * 刻意**不引用任何 Android 类型**：版本号、SDK、机型都由调用方在构造时填好传进来。
 * 这样格式化与脱敏逻辑（[CrashLogFormatter]）可以在 JVM 单元测试里完整覆盖——
 * 崩溃路径本身没法在真机上反复触发验证，能测的部分必须全测到。
 */
data class CrashEntry(
    /** 记录时刻（epoch 毫秒） */
    val timeMillis: Long,
    /** true = 未捕获异常导致进程终止；false = 业务层主动记录的非致命错误 */
    val fatal: Boolean,
    /** 抛异常的线程名。崩在主线程还是 IO 线程，是分诊时第一个要看的信息 */
    val threadName: String,
    val throwable: Throwable,
    /** BuildConfig.VERSION_NAME */
    val appVersion: String,
    /** BuildConfig.VERSION_CODE */
    val versionCode: Long,
    /** Build.VERSION.SDK_INT */
    val androidSdk: Int,
    /** Build.MODEL */
    val deviceModel: String,
    /**
     * 出问题时正在做什么，一句人话。
     * 非致命记录靠它定位场景（"导出 PDF" / "WebDAV 上传"）；致命崩溃通常拿不到，留空。
     */
    val note: String = ""
)
