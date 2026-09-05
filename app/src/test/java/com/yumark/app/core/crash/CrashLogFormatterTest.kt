package com.yumark.app.core.crash

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CrashLogFormatterTest {

    /** 造一个堆栈可控的异常：帧数由参数定，断言才能盯住上界逻辑而不受真实调用栈影响。 */
    private fun fakeThrowable(frames: Int = 3, message: String? = "boom"): Throwable =
        RuntimeException(message).apply {
            stackTrace = Array(frames) { StackTraceElement("com.yumark.Fake", "m$it", "Fake.kt", it + 1) }
        }

    private fun entry(
        throwable: Throwable = fakeThrowable(),
        fatal: Boolean = true,
        note: String = ""
    ) = CrashEntry(
        timeMillis = 1_700_000_000_000L,
        fatal = fatal,
        threadName = "main",
        throwable = throwable,
        appVersion = "0.9.1",
        versionCode = 20L,
        androidSdk = 34,
        deviceModel = "Pixel 7",
        note = note
    )

    // ---- 脱敏 ----

    @Test
    fun `sk 开头的密钥被抹掉`() {
        val out = CrashLogFormatter.redact("call failed with sk-AbCdEf0123456789xyz tail")

        assertThat(out).doesNotContain("AbCdEf0123456789xyz")
        assertThat(out).contains("sk-***")
        assertThat(out).contains("tail")
    }

    @Test
    fun `Bearer token 被抹掉`() {
        val out = CrashLogFormatter.redact("Authorization: Bearer eyJhbGciOi.JzdWIiOiIx.9_-abc")

        assertThat(out).doesNotContain("eyJhbGciOi")
        assertThat(out).contains("Bearer ***")
    }

    @Test
    fun `URL 里的 Basic 认证被抹掉`() {
        val out = CrashLogFormatter.redact("PROPFIND https://alice:s3cret@dav.example.com/YuMark failed")

        assertThat(out).doesNotContain("s3cret")
        assertThat(out).doesNotContain("alice")
        assertThat(out).contains("https://***:***@dav.example.com/YuMark")
    }

    @Test
    fun `Authorization 头里的 Basic 凭据被抹掉`() {
        // YWxpY2U6czNjcmV0 = base64("alice:s3cret")，WebDAV 同步失败时 Ktor 会把这个头带进异常
        val out = CrashLogFormatter.redact("401 Unauthorized, Authorization: Basic YWxpY2U6czNjcmV0")

        assertThat(out).doesNotContain("YWxpY2U6")
        assertThat(out).contains("Basic ***")
        assertThat(out).contains("401 Unauthorized")
    }

    @Test
    fun `basic 后面跟普通英文单词时不误伤`() {
        // 收紧判定的代价是可能漏抹全小写的 base64；把日志正文改坏比漏抹一次更难排查
        val text = "basic operation failed after 3 retries"

        assertThat(CrashLogFormatter.redact(text)).isEqualTo(text)
    }

    @Test
    fun `Gemini 查询串里的裸 key 被抹掉`() {
        val out = CrashLogFormatter.redact(
            "HttpRequestTimeoutException: https://generativelanguage.googleapis.com/v1beta" +
                "/models/gemini-2.0-flash:streamGenerateContent?key=AIzaSyD1234567890abcdefg&alt=sse"
        )

        assertThat(out).doesNotContain("AIzaSyD")
        assertThat(out).contains("key=***")
        // 端点留着，否则日志看不出是哪个 provider 出的问题
        assertThat(out).contains("generativelanguage.googleapis.com")
        assertThat(out).contains("alt=sse")
    }

    @Test
    fun `等号形式的敏感项被抹掉但保留键名`() {
        val out = CrashLogFormatter.redact("url=https://s.example.com/x?api_key=K1234567890&q=md")

        assertThat(out).doesNotContain("K1234567890")
        assertThat(out).contains("api_key=***")
        // 键名留着，才能看出是哪个字段出的问题
        assertThat(out).contains("q=md")
    }

    @Test
    fun `JSON 形式的敏感项被抹掉且引号成对`() {
        val out = CrashLogFormatter.redact("""{"model":"gpt-4","apiKey":"sk-live-9988776655","n":1}""")

        assertThat(out).doesNotContain("9988776655")
        // 分隔符原样保留（这里没有空格），只把值换掉，且补上闭引号
        assertThat(out).contains(""""apiKey":"***"""")
        assertThat(out).contains(""""model":"gpt-4"""")
    }

    @Test
    fun `password 与 token 两个键名都覆盖`() {
        val out = CrashLogFormatter.redact("password=hunter2; token=abcdef123456")

        assertThat(out).doesNotContain("hunter2")
        assertThat(out).doesNotContain("abcdef123456")
        assertThat(out).contains("password=***")
        assertThat(out).contains("token=***")
    }

    @Test
    fun `与密钥无关的文本原样保留`() {
        val text = "java.io.FileNotFoundException: /data/user/0/com.yumark.app/files/documents/a.md"

        assertThat(CrashLogFormatter.redact(text)).isEqualTo(text)
    }

    @Test
    fun `空串脱敏后仍是空串`() {
        assertThat(CrashLogFormatter.redact("")).isEmpty()
    }

    // ---- 截断顺序 ----

    @Test
    fun `先脱敏再截断_长填充后面的密钥不会留下前半段`() {
        // 反过来的话，截断会在第 512 个字符处把密钥切开，前半段照样是可用的凭据
        val message = "x".repeat(500) + " sk-ABCDEFGHIJKLMNOP"

        val out = CrashLogFormatter.describeThrowable(fakeThrowable(message = message))

        assertThat(out).doesNotContain("sk-ABC")
        assertThat(out).contains("sk-***")
    }

    @Test
    fun `消息为 null 时只输出类名`() {
        val out = CrashLogFormatter.describeThrowable(fakeThrowable(message = null))

        assertThat(out).isEqualTo("java.lang.RuntimeException")
    }

    @Test
    fun `超长消息被截断并带标记`() {
        val out = CrashLogFormatter.describeThrowable(fakeThrowable(message = "y".repeat(2000)))

        assertThat(out).contains("已截断")
        assertThat(out.length).isLessThan(2000)
    }

    // ---- 堆栈与 cause ----

    @Test
    fun `堆栈帧数超上限时给出省略条数`() {
        val out = CrashLogFormatter.renderStack(fakeThrowable(frames = CrashLogFormatter.MAX_FRAMES + 7))

        assertThat(out).contains("m0(Fake.kt:1)")
        assertThat(out).contains("省略 7 帧")
        // 第 25 帧（下标 24）不该出现
        assertThat(out).doesNotContain("m${CrashLogFormatter.MAX_FRAMES}(")
    }

    @Test
    fun `cause 链逐层展开`() {
        val root = fakeThrowable(frames = 1, message = "root cause")
        val wrapper = RuntimeException("wrapper", root).apply {
            stackTrace = arrayOf(StackTraceElement("com.yumark.Top", "call", "Top.kt", 9))
        }

        val out = CrashLogFormatter.renderStack(wrapper)

        assertThat(out).contains("java.lang.RuntimeException: wrapper")
        assertThat(out).contains("Caused by: java.lang.RuntimeException: root cause")
        assertThat(out).contains("at com.yumark.Top.call(Top.kt:9)")
    }

    @Test
    fun `cause 链深度超上限时截断`() {
        var chain: Throwable = fakeThrowable(frames = 1, message = "level0")
        for (level in 1..6) {
            chain = RuntimeException("level$level", chain).apply {
                stackTrace = arrayOf(StackTraceElement("C", "m", "C.kt", level))
            }
        }

        val out = CrashLogFormatter.renderStack(chain)

        assertThat(out).contains("已按 ${CrashLogFormatter.MAX_CAUSE_DEPTH} 层上限截断")
        assertThat(out).contains("level6")
        assertThat(out).doesNotContain("level0")
    }

    @Test
    fun `cause 链成环时停止展开而不死循环`() {
        // 包装异常互相持有是真实存在的形态；没有查重的话这里会挂死
        val a = RuntimeException("aaa")
        val b = RuntimeException("bbb", a)
        a.initCause(b)

        val out = CrashLogFormatter.renderStack(a)

        assertThat(out).contains("aaa")
        assertThat(out).contains("bbb")
        assertThat(out).contains("成环")
    }

    // ---- 整条记录 ----

    @Test
    fun `format 输出完整头部`() {
        val out = CrashLogFormatter.format(entry())

        assertThat(out).contains("崩溃记录")
        assertThat(out).contains("线程: main")
        assertThat(out).contains("应用: 0.9.1 (20)")
        assertThat(out).contains("系统: Android SDK 34")
        assertThat(out).contains("机型: Pixel 7")
        assertThat(out).contains("--- 异常 ---")
        assertThat(out).contains("java.lang.RuntimeException: boom")
    }

    @Test
    fun `非致命记录的标题与致命不同`() {
        val fatal = CrashLogFormatter.format(entry(fatal = true))
        val nonFatal = CrashLogFormatter.format(entry(fatal = false))

        assertThat(fatal).contains("崩溃记录")
        assertThat(nonFatal).contains("非致命错误记录")
    }

    @Test
    fun `场景为空白时不输出该行`() {
        assertThat(CrashLogFormatter.format(entry(note = "   "))).doesNotContain("场景")
        assertThat(CrashLogFormatter.format(entry(note = "导出 PDF"))).contains("场景: 导出 PDF")
    }

    @Test
    fun `场景文本也会被脱敏`() {
        val out = CrashLogFormatter.format(entry(note = "上传到 https://bob:pa55w0rd@dav.example.com"))

        assertThat(out).doesNotContain("pa55w0rd")
        assertThat(out).contains("***:***@")
    }

    // ---- 文件名 ----

    @Test
    fun `文件名定宽且带致命标记`() {
        val fatal = CrashLogFormatter.fileName(1_700_000_000_000L, fatal = true)
        val nonFatal = CrashLogFormatter.fileName(1_700_000_000_000L, fatal = false)

        // 定宽是轮转的前提：yyyyMMdd(8) - HHmmssSSS(9)
        assertThat(fatal).matches("""crash-\d{8}-\d{9}-fatal\.log""")
        assertThat(nonFatal).matches("""crash-\d{8}-\d{9}-nonfatal\.log""")
        assertThat(fatal).startsWith(CrashLogFormatter.FILE_PREFIX)
        assertThat(fatal).endsWith(CrashLogFormatter.FILE_SUFFIX)
    }

    @Test
    fun `文件名按字典序排即按时间排`() {
        // CrashLogStore 的轮转完全建立在这个性质上，不依赖 lastModified
        val earlier = CrashLogFormatter.fileName(1_700_000_000_000L, fatal = true)
        val later = CrashLogFormatter.fileName(1_700_000_001_000L, fatal = true)

        assertThat(earlier).isLessThan(later)
    }

    @Test
    fun `同一毫秒的致命与非致命不会撞名`() {
        val fatal = CrashLogFormatter.fileName(1_700_000_000_000L, fatal = true)
        val nonFatal = CrashLogFormatter.fileName(1_700_000_000_000L, fatal = false)

        assertThat(fatal).isNotEqualTo(nonFatal)
    }

    @Test
    fun `时间戳格式为定宽毫秒精度`() {
        assertThat(CrashLogFormatter.formatTime(1_700_000_000_000L))
            .matches("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")
    }
}
