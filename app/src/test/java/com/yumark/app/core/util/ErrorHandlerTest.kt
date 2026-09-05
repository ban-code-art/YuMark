package com.yumark.app.core.util

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ErrorHandler] 的分类 / 文案 / 留痕契约。
 *
 * 这里盯住的是三条容易被下一次改动破坏的性质：取消不算错误、没标记的异常原文不上界面、
 * 已知失败不占崩溃日志配额。Android 与 Ktor 的类型用**类名**判定，所以那部分只能直接测
 * 谓词——`android.jar` 的单测桩里 `SQLiteException` 一构造就抛 `Stub!`。
 */
class ErrorHandlerTest {

    private class RecordingSink : NonFatalSink {
        val records = mutableListOf<Pair<Throwable, String>>()
        override fun record(throwable: Throwable, note: String) {
            records += throwable to note
        }
    }

    /** 类名以 TimeoutException 结尾，模拟 Ktor 的 ConnectTimeoutException（它只继承 IOException） */
    private class FakeConnectTimeoutException : IOException("connect timed out")

    /**
     * 递归收集文案里的全部字符串片段。
     *
     * 异常原文（内部路径 / URL / 英文断言）只有两条路能漏上界面：[UiMessage.Raw]，或被塞进
     * [UiMessage.Res] 实参的 String。资源 id 只是个 int，泄不出任何东西——所以「原始 message
     * 不上界面」这条契约在文案变成 [UiMessage] 之后，就等价于「这里收出来的列表是空的」。
     */
    private fun rawTexts(message: UiMessage): List<String> = when (message) {
        is UiMessage.Raw -> listOf(message.text)
        is UiMessage.Res -> message.args.flatMap { rawTextsOfArg(it) }
        is UiMessage.Plural -> message.args.flatMap { rawTextsOfArg(it) }
    }

    private fun rawTextsOfArg(arg: Any): List<String> = when (arg) {
        is UiMessage -> rawTexts(arg)
        is String -> listOf(arg)
        else -> emptyList()
    }

    /**
     * 「<动作>失败：<原因>」的期望结构。
     *
     * 动作标签写成字面量 `R.string.action_*` 而不是 `action.labelRes`：后者会让「枚举项指错资源」
     * 这类改动照样通过。两段都是资源 id，靠 [UiMessage.Res] 的 data class 相等直接比较。
     */
    private fun actionFailed(labelRes: Int, reason: UiMessage): UiMessage =
        UiMessage.of(R.string.error_action_failed, UiMessage.Res(labelRes), reason)

    @AfterEach
    fun tearDown() {
        // ErrorHandler 是单例，装上的 sink 会串到下一个用例里
        ErrorHandler.reset()
    }

    // ---- 取消不是错误 ----

    @Test
    fun `classify 把取消异常原样抛出而不是归类`() {
        val cancellation = CancellationException("scope cleared")

        val thrown = assertThrows<CancellationException> { ErrorHandler.classify(cancellation) }

        assertThat(thrown).isSameInstanceAs(cancellation)
    }

    @Test
    fun `message 与 report 都不把取消当成一次失败`() {
        assertThrows<CancellationException> { ErrorHandler.message(CancellationException()) }
        assertThrows<CancellationException> {
            ErrorHandler.report(CancellationException(), UserAction.SAVE)
        }
    }

    @Test
    fun `取消不会被记进非致命日志`() {
        val sink = RecordingSink()
        ErrorHandler.install(sink)

        runCatching { ErrorHandler.report(CancellationException(), UserAction.SAVE) }

        assertThat(sink.records).isEmpty()
    }

    // ---- 只有带标记的异常能透出自己的文案 ----

    @Test
    fun `带标记的 IO 异常原样透出文案`() {
        val message = ErrorHandler.message(FriendlyIOException("账号或密码不正确（HTTP 401）"))

        // 收 String 的构造器不带 uiMessage，classify 走 message 兜底并包成 Raw
        assertThat(message).isEqualTo(UiMessage.Raw("账号或密码不正确（HTTP 401）"))
    }

    @Test
    fun `带标记的校验异常原样透出文案`() {
        val e = FriendlyValidationException("图片过大（超过 10MB），请选择更小的图片")

        assertThat(ErrorHandler.classify(e)).isInstanceOf(AppError.Friendly::class.java)
        // 同上：收 String 的那个构造器不带 uiMessage，落在 message 兜底那条路上
        assertThat(ErrorHandler.message(e))
            .isEqualTo(UiMessage.Raw("图片过大（超过 10MB），请选择更小的图片"))
    }

    @Test
    fun `带资源文案的校验异常优先用资源文案`() {
        // 校验异常的两个构造器与 FriendlyIOException 同构，这条是新代码走的路线
        val e = FriendlyValidationException(UiMessage.Res(R.string.image_error_decode_failed))

        assertThat(ErrorHandler.message(e))
            .isEqualTo(UiMessage.Res(R.string.image_error_decode_failed))
    }

    @Test
    fun `带资源文案的标记异常优先用资源文案`() {
        // uiMessage 非空时不再退回 message，这条路是新代码的默认路线（可翻译）
        val e = FriendlyIOException(UiMessage.Res(R.string.error_reason_permission))

        assertThat(ErrorHandler.message(e)).isEqualTo(UiMessage.Res(R.string.error_reason_permission))
    }

    @Test
    fun `标记异常的文案为空白时退回未知文案`() {
        // 有标记但没话说是实现方的疏漏，不能把空字符串弹给用户
        assertThat(ErrorHandler.message(FriendlyIOException("   "))).isEqualTo(ErrorMessages.UNKNOWN)
    }

    @Test
    fun `AppError 二次归类返回同一实例`() {
        val error = AppError.Storage(IOException("disk full"))

        assertThat(ErrorHandler.classify(error)).isSameInstanceAs(error)
    }

    // ---- 分类 ----

    @Test
    fun `SecurityException 归为权限`() {
        val error = ErrorHandler.classify(SecurityException("EPERM"))

        assertThat(error).isInstanceOf(AppError.Permission::class.java)
        assertThat(error.userMessage).isEqualTo(ErrorMessages.PERMISSION)
    }

    @Test
    fun `超时判定排在 IOException 之前`() {
        // SocketTimeoutException 是 IOException 的子类；顺序写反就会被当成存储故障
        val error = ErrorHandler.classify(SocketTimeoutException("timeout"))

        assertThat(error).isInstanceOf(AppError.Timeout::class.java)
    }

    @Test
    fun `类名以 TimeoutException 结尾的 IO 异常也算超时`() {
        val error = ErrorHandler.classify(FakeConnectTimeoutException())

        assertThat(error).isInstanceOf(AppError.Timeout::class.java)
    }

    @Test
    fun `连不上的各种形态归为网络`() {
        assertThat(ErrorHandler.classify(UnknownHostException("no dns")))
            .isInstanceOf(AppError.Network::class.java)
        assertThat(ErrorHandler.classify(ConnectException("ECONNREFUSED")))
            .isInstanceOf(AppError.Network::class.java)
    }

    @Test
    fun `裸 IOException 归为存储`() {
        val error = ErrorHandler.classify(IOException("No space left on device"))

        assertThat(error).isInstanceOf(AppError.Storage::class.java)
        assertThat(error.userMessage).isEqualTo(ErrorMessages.STORAGE)
    }

    @Test
    fun `未归类异常不把原文带上界面`() {
        val e = IllegalStateException(
            "Failed to load /data/user/0/com.yumark.app/files/documents/9f2c-doc.md"
        )

        val message = ErrorHandler.message(e)

        assertThat(message).isEqualTo(ErrorMessages.UNKNOWN)
        // 结构上就没有夹带原文的位置：没有 Raw，也没有 String 实参
        assertThat(rawTexts(message)).isEmpty()
    }

    @Test
    fun `不带标记的 IllegalArgumentException 同样不透出原文`() {
        // 曾经的写法是「校验失败就透出 message」，而这个类型绝大多数携带的是英文断言
        val message = ErrorHandler.message(IllegalArgumentException("baseUrl must not be blank"))

        assertThat(message).isEqualTo(ErrorMessages.UNKNOWN)
        assertThat(rawTexts(message)).isEmpty()
    }

    // ---- 类名谓词（Android / Ktor 类型不能在 JVM 单测里构造） ----

    @Test
    fun `Ktor 超时类名被识别`() {
        assertThat(ErrorHandler.isTimeoutName("io.ktor.client.network.sockets.ConnectTimeoutException")).isTrue()
        assertThat(ErrorHandler.isTimeoutName("io.ktor.client.plugins.HttpRequestTimeoutException")).isTrue()
        assertThat(ErrorHandler.isTimeoutName("java.io.IOException")).isFalse()
    }

    @Test
    fun `网络命名空间被识别`() {
        assertThat(ErrorHandler.isNetworkName("java.net.SocketException")).isTrue()
        assertThat(ErrorHandler.isNetworkName("javax.net.ssl.SSLHandshakeException")).isTrue()
        assertThat(ErrorHandler.isNetworkName("io.ktor.client.plugins.ResponseException")).isTrue()
        assertThat(ErrorHandler.isNetworkName("java.lang.IllegalStateException")).isFalse()
        // java.nio 与 java.net 只差一个字母，文件系统异常不能算成网络故障
        assertThat(ErrorHandler.isNetworkName("java.nio.file.NoSuchFileException")).isFalse()
    }

    @Test
    fun `数据库命名空间被识别`() {
        assertThat(ErrorHandler.isDatabaseName("android.database.sqlite.SQLiteFullException")).isTrue()
        assertThat(ErrorHandler.isDatabaseName("android.database.CursorWindowAllocationException")).isTrue()
        assertThat(ErrorHandler.isDatabaseName("androidx.room.RoomDatabase\$MigrationException")).isTrue()
        assertThat(ErrorHandler.isDatabaseName("android.os.DeadObjectException")).isFalse()
    }

    // ---- 留痕 ----

    @Test
    fun `report 记一笔非致命并带上动作与场景`() {
        val sink = RecordingSink()
        ErrorHandler.install(sink)
        val cause = IOException("No space left on device")

        ErrorHandler.report(cause, UserAction.SAVE)

        assertThat(sink.records).hasSize(1)
        // 记的是原始异常，堆栈才指向真正出事的那一行
        assertThat(sink.records.single().first).isSameInstanceAs(cause)
        // note 仍是字符串：UserAction.tag 与 AppError.scene 拼给崩溃日志看，不上界面
        assertThat(sink.records.single().second).isEqualTo("保存 / 存储读写")
    }

    @Test
    fun `没有动作时场景单独成注记`() {
        val sink = RecordingSink()
        ErrorHandler.install(sink)

        ErrorHandler.report(IOException("boom"))

        assertThat(sink.records.single().second).isEqualTo("存储读写")
    }

    @Test
    fun `已知失败不占崩溃日志配额`() {
        val sink = RecordingSink()
        ErrorHandler.install(sink)

        ErrorHandler.report(FriendlyIOException("账号或密码不正确（HTTP 401）"), UserAction.CONNECT)

        assertThat(sink.records).isEmpty()
    }

    @Test
    fun `message 只给文案不留痕`() {
        val sink = RecordingSink()
        ErrorHandler.install(sink)

        ErrorHandler.message(IOException("boom"), UserAction.CHECK_UPDATE)

        assertThat(sink.records).isEmpty()
    }

    @Test
    fun `未装 sink 时 report 照常返回文案`() {
        assertThat(ErrorHandler.report(IOException("boom"), UserAction.SAVE))
            .isEqualTo(actionFailed(R.string.action_save, ErrorMessages.STORAGE))
    }

    @Test
    fun `sink 自己抛异常不影响业务流程`() {
        ErrorHandler.install { _, _ -> throw IllegalStateException("日志目录满了") }

        // 记日志失败绝不能反过来打断正在处理错误的调用方
        assertThat(ErrorHandler.report(IOException("boom"), UserAction.SAVE))
            .isEqualTo(actionFailed(R.string.action_save, ErrorMessages.STORAGE))
    }

    @Test
    fun `worthRecording 只对已知失败为假`() {
        assertThat(AppError.Friendly("已经想清楚该说什么").worthRecording).isFalse()
        assertThat(AppError.Storage(null).worthRecording).isTrue()
        assertThat(AppError.Unknown(null).worthRecording).isTrue()
    }

    // ---- 文案拼接 ----

    @Test
    fun `动作为 null 时不拼前缀`() {
        // 动作是枚举了，「空白动作字符串」这种输入已经构造不出来；剩下的唯一变体是不传
        assertThat(ErrorHandler.message(IOException("boom"))).isEqualTo(ErrorMessages.STORAGE)
        assertThat(ErrorHandler.message(IOException("boom"), action = null))
            .isEqualTo(ErrorMessages.STORAGE)
    }

    @Test
    fun `动作非空时拼成动作失败冒号原因`() {
        assertThat(ErrorHandler.message(UnknownHostException("no dns"), UserAction.SYNC))
            .isEqualTo(actionFailed(R.string.action_sync, ErrorMessages.NETWORK))
    }

    @Test
    fun `六句文案互不相同且都是资源文案`() {
        val all = listOf(
            ErrorMessages.NETWORK, ErrorMessages.TIMEOUT, ErrorMessages.STORAGE,
            ErrorMessages.DATABASE, ErrorMessages.PERMISSION, ErrorMessages.UNKNOWN
        )

        assertThat(all.toSet()).hasSize(all.size)
        // 「非空」在资源化之后的等价物：每句都是 Res、id 不是 0（R 里不存在 0 号资源）、
        // 且不带实参——带实参就说明有运行期字符串能混进这六句里。
        all.forEach { message ->
            assertThat(message).isInstanceOf(UiMessage.Res::class.java)
            assertThat((message as UiMessage.Res).id).isNotEqualTo(0)
            assertThat(message.args).isEmpty()
        }
    }

    // ---- onFailureReport ----

    @Test
    fun `onFailureReport 成功时不回调`() {
        var called = false

        val result = Result.success("ok").onFailureReport(UserAction.SAVE) { called = true }

        assertThat(called).isFalse()
        assertThat(result.getOrNull()).isEqualTo("ok")
    }

    @Test
    fun `onFailureReport 失败时给出文案并原样透传 Result`() {
        val failure = Result.failure<String>(UnknownHostException("no dns"))
        var message: UiMessage? = null

        val result = failure.onFailureReport(UserAction.SYNC) { message = it }

        assertThat(message).isEqualTo(actionFailed(R.string.action_sync, ErrorMessages.NETWORK))
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `onFailureReport 遇到取消时向上抛而不回调`() {
        var called = false

        assertThrows<CancellationException> {
            Result.failure<String>(CancellationException())
                .onFailureReport(UserAction.SAVE) { called = true }
        }

        assertThat(called).isFalse()
    }

    // ---- safeDetail：唯一被允许的「原文出口」 ----

    @Test
    fun `safeDetail 先脱敏`() {
        val e = IllegalStateException(
            "Request timeout: https://generativelanguage.googleapis.com/v1beta/models" +
                "/gemini-2.0-flash:streamGenerateContent?key=AIzaSyD1234567890abcdefg"
        )

        val detail = ErrorHandler.safeDetail(e, maxChars = 400)

        assertThat(detail).doesNotContain("AIzaSyD")
        assertThat(detail).contains("key=***")
    }

    @Test
    fun `safeDetail 把多行压成一行`() {
        val e = IllegalStateException("第一行\n\n第二行\t 第三行")

        assertThat(ErrorHandler.safeDetail(e)).isEqualTo("第一行 第二行 第三行")
    }

    @Test
    fun `safeDetail 超长时截断并带省略号`() {
        val detail = ErrorHandler.safeDetail(IllegalStateException("a".repeat(200)))

        assertThat(detail).endsWith("…")
        assertThat(detail.length).isEqualTo(ErrorHandler.MAX_DETAIL_CHARS + 1)
    }

    @Test
    fun `safeDetail 的上限可以按调用点收紧`() {
        assertThat(ErrorHandler.safeDetail(IllegalStateException("a".repeat(50)), maxChars = 10))
            .isEqualTo("a".repeat(10) + "…")
    }

    @Test
    fun `safeDetail 在消息为空或空白时退回类名`() {
        assertThat(ErrorHandler.safeDetail(IOException())).isEqualTo("IOException")
        assertThat(ErrorHandler.safeDetail(IOException("   "))).isEqualTo("IOException")
    }
}
