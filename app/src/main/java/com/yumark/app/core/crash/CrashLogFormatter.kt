package com.yumark.app.core.crash

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录的文本化与脱敏——**纯 Kotlin，不碰 Android**，可在 JVM 单元测试里全覆盖。
 *
 * 三条硬约束：
 * - **脱敏先于截断**：先抹掉疑似密钥再截断，反过来会把一个 Key 切成两半、留下可用的前半段。
 * - **一切都有上界**：消息长度、每层堆栈帧数、cause 链深度。崩溃日志是用户可导出、
 *   很可能被贴进 issue 的东西，不能因为某个库抛了个巨型异常就撑成几百 KB。
 * - **cause 链可能成环**：包装异常互相持有的情况真实存在，遍历必须查重，否则死循环。
 */
object CrashLogFormatter {

    /** 单条异常消息的字符上限。够看清「哪儿错了」，又不至于把整篇文档正文带进日志。 */
    const val MAX_MESSAGE_CHARS = 512

    /** 每层异常保留的堆栈帧数。前 24 帧足以定位，再往下基本都是框架调用栈。 */
    const val MAX_FRAMES = 24

    /** cause 链最多向下展开几层 */
    const val MAX_CAUSE_DEPTH = 4

    /** 日志文件名前缀/后缀。[CrashLogStore] 靠这两个筛出自己的文件，别的东西不碰。 */
    const val FILE_PREFIX = "crash-"
    const val FILE_SUFFIX = ".log"

    private const val TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"
    private const val FILE_TIME_PATTERN = "yyyyMMdd-HHmmssSSS"

    // ---- 脱敏 ----
    // 崩溃消息里出现密钥不是假想：Ktor 的异常消息会带上请求 URL，WebDAV 认证失败会带 Authorization 头。

    /** OpenAI 风格 Key */
    private val SK_KEY = Regex("""\bsk-[A-Za-z0-9_-]{8,}""")

    /** Authorization: Bearer <token> */
    private val BEARER = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}""")

    /**
     * Authorization: Basic <base64(user:pass)>——WebDAV 同步走的就是 Basic。
     *
     * 判定条件刻意收紧：长度 ≥ 12 且**大小写混排**。否则 "basic operation failed" 这种
     * 平常句子会被误伤成 "Basic ***"，把日志改坏比漏抹一次更难排查。
     * `(?i:...)` 只作用于关键字，大小写混排的两个前瞻不能被全局忽略大小写抵消。
     */
    private val BASIC = Regex("""\b(?i:basic)\s+(?=\S*[A-Z])(?=\S*[a-z])[A-Za-z0-9+/]{12,}={0,2}""")

    /** URL 内嵌 Basic 认证：scheme://user:pass@host（WebDAV 配置里常见写法） */
    private val URL_BASIC_AUTH = Regex("""(?i)\b([a-z][a-z0-9+.\-]*://)[^/\s:@]+:[^/\s@]+@""")

    /**
     * key=value 与 "key": "value" 两种形态的敏感项。
     *
     * 光秃秃的 `key` 也算：Gemini 把密钥放在查询串里（`?key=AIza...`），而 Ktor 的异常
     * 消息会带上完整请求 URL。宁可多抹一个无关的 `key=`，也不能漏掉一把真钥匙。
     */
    private val KV_SECRET = Regex(
        """(?i)\b(api[_\-]?key|apikey|key|password|passwd|pwd|secret|token|access[_\-]?token)""" +
            """\b(\s*[:=]\s*"?|"\s*:\s*"?)[^\s,;&"'}\])]+"""
    )

    /** 抹掉文本里疑似密钥的片段。保留键名，方便看出「是哪个字段出的问题」。 */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = SK_KEY.replace(text, "sk-***")
        out = BEARER.replace(out, "Bearer ***")
        out = BASIC.replace(out, "Basic ***")
        out = URL_BASIC_AUTH.replace(out) { m -> m.groupValues[1] + "***:***@" }
        out = KV_SECRET.replace(out) { m ->
            val separator = m.groupValues[2]
            // 分隔符里含开引号时补上闭引号，别让脱敏后的日志出现不成对的引号
            val closing = if (separator.trimEnd().endsWith("\"")) "\"" else ""
            m.groupValues[1] + separator + "***" + closing
        }
        return out
    }

    /** 「类名: 脱敏并截断后的消息」。消息为 null 时只给类名。 */
    fun describeThrowable(t: Throwable): String {
        val name = t::class.java.name
        val raw = t.message ?: return name
        val safe = redact(raw)
        val body = if (safe.length > MAX_MESSAGE_CHARS) {
            safe.take(MAX_MESSAGE_CHARS) + "…（消息过长已截断）"
        } else {
            safe
        }
        return "$name: $body"
    }

    /** 异常本体 + 最多 [MAX_CAUSE_DEPTH] 层 cause 的有界堆栈。 */
    fun renderStack(throwable: Throwable): String = buildString {
        val seen = mutableListOf<Throwable>()
        var next: Throwable? = throwable
        var depth = 0
        while (true) {
            val current = next ?: break
            if (seen.any { it === current }) {
                appendLine("Caused by: <cause 链成环，已停止展开>")
                break
            }
            seen += current
            if (depth > 0) append("Caused by: ")
            appendLine(describeThrowable(current))

            val frames = current.stackTrace
            frames.take(MAX_FRAMES).forEach { appendLine("\tat $it") }
            if (frames.size > MAX_FRAMES) {
                appendLine("\t... 省略 ${frames.size - MAX_FRAMES} 帧")
            }

            val cause = current.cause
            if (cause != null && depth + 1 >= MAX_CAUSE_DEPTH) {
                appendLine("Caused by: <还有更深的 cause，已按 $MAX_CAUSE_DEPTH 层上限截断>")
                break
            }
            next = cause
            depth++
        }
    }

    /** 一条完整记录的文本形态。头部信息是分诊必需的最小集合，不含任何用户身份标识。 */
    fun format(entry: CrashEntry): String = buildString {
        appendLine("=== YuMark ${if (entry.fatal) "崩溃" else "非致命错误"}记录 ===")
        appendLine("时间: ${formatTime(entry.timeMillis)}")
        appendLine("线程: ${entry.threadName}")
        appendLine("应用: ${entry.appVersion} (${entry.versionCode})")
        appendLine("系统: Android SDK ${entry.androidSdk}")
        appendLine("机型: ${entry.deviceModel}")
        if (entry.note.isNotBlank()) appendLine("场景: ${redact(entry.note)}")
        appendLine("--- 异常 ---")
        append(renderStack(entry.throwable))
    }

    fun formatTime(timeMillis: Long): String =
        SimpleDateFormat(TIME_PATTERN, Locale.US).format(Date(timeMillis))

    /**
     * 日志文件名。用 Locale.US 定格式有两个原因：
     * 某些区域设置会输出非 ASCII 数字，某些（如 th-TH）用的还不是公历纪年；
     * 而这个名字要承担「按名字排序 = 按时间排序」的轮转语义，必须定宽、可比较。
     *
     * SimpleDateFormat 非线程安全，故每次新建而不做成字段。
     */
    fun fileName(timeMillis: Long, fatal: Boolean): String {
        val stamp = SimpleDateFormat(FILE_TIME_PATTERN, Locale.US).format(Date(timeMillis))
        return "$FILE_PREFIX$stamp-${if (fatal) "fatal" else "nonfatal"}$FILE_SUFFIX"
    }
}
