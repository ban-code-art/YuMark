package com.yumark.app.core.util

import com.yumark.app.R
import com.yumark.app.core.crash.CrashLogFormatter
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/**
 * 「这个异常的文案可以直接给用户看」的标记。
 *
 * 实现方保证文案是**面向用户、不含路径 / URL / 凭证**的一句话，[ErrorHandler] 见到标记就原样
 * 透出，不再套一层泛化文案。文案有两条来路：
 * - 覆写 [uiMessage] 给出资源化文案（可翻译，新代码优先走这条）；
 * - 不覆写则退回 [Throwable.message]（抛出那一刻就拼好的字符串，不可翻译）。
 *
 * 反之——**没有这个标记的异常，message 一律按技术细节处理**：只进崩溃日志，绝不上界面。
 * 这条规则是本文件存在的理由：`it.message ?: "操作失败"` 这种写法会把
 * `/data/user/0/com.yumark.app/files/documents/<uuid>.md`、WebDAV URL（可能内嵌密码）、
 * Ktor 带 `?key=` 的请求地址直接弹到 Snackbar 上。
 */
interface UserFacingMessage {
    /** 资源化文案。为 null 时 [ErrorHandler.classify] 退回 [Throwable.message]。 */
    val uiMessage: UiMessage? get() = null
}

/**
 * 自带用户可读文案的 IO 异常。
 *
 * 给「抛出的那一刻就知道该跟用户说什么」的失败用（如 WebDAV 返回 HTTP 401）。
 * 仍然是 [IOException] 的子类，原有按 IOException 分流的调用方不受影响。
 *
 * 两个构造器：收 [UiMessage] 的那个可翻译（`core` / `data` 层拿不到 Context 也能给出资源文案），
 * 收 String 的那个留给「文案在抛出点由运行时数据拼成」的场合。
 */
class FriendlyIOException private constructor(
    message: String?,
    cause: Throwable?,
    override val uiMessage: UiMessage?
) : IOException(message, cause), UserFacingMessage {

    constructor(message: String, cause: Throwable? = null) : this(message, cause, null)

    // message 传 uiMessage.toString()（形如 `Res(id=2131755123, args=[])`）而不是留空：
    // 崩溃日志里那串 id 可以直接 grep 回 R.string，比一条没有 message 的异常好查。
    constructor(uiMessage: UiMessage, cause: Throwable? = null) :
        this(uiMessage.toString(), cause, uiMessage)
}

/**
 * 自带用户可读文案的输入/前置条件校验失败。
 *
 * 存在的理由：`require(size <= MAX) { "图片过大（超过 10MB）…" }` 抛的是
 * [IllegalArgumentException]，而 [ErrorHandler.classify] 把它归到「未知问题」——因为这个类型
 * 绝大多数时候携带的是给开发者看的英文断言。可校验的业务规则（图片格式、移动到自身、
 * WebDAV 配置不完整）文案本来就写给用户，必须带上标记才透得出去。
 *
 * 两个构造器的分工同 [FriendlyIOException]：新代码走 [UiMessage] 那条（可翻译），
 * 收 String 的那条留给文案由运行期数据拼成、暂时没法资源化的场合。
 */
class FriendlyValidationException private constructor(
    message: String?,
    override val uiMessage: UiMessage?
) : IllegalArgumentException(message), UserFacingMessage {

    constructor(message: String) : this(message, null)

    constructor(uiMessage: UiMessage) : this(uiMessage.toString(), uiMessage)
}

/**
 * 用户可见错误文案的唯一出处。
 *
 * 抽成常量是为了让 [AppError] 与 [AiErrorMapper] 共用同一句话——同一种故障在
 * 编辑器里叫「网络连接失败」、在 AI 面板里叫「网络异常」是最容易攒出来的不一致。
 *
 * 类型是 [UiMessage] 而不是 String：本文件在 `core`，拿不到 Context，解析要等到界面层
 * （见 `presentation.common.resolve`）。也正因为只持有资源 id，本文件仍能在 JVM 测试里跑。
 */
object ErrorMessages {
    val NETWORK: UiMessage = UiMessage.Res(R.string.error_reason_network)
    val TIMEOUT: UiMessage = UiMessage.Res(R.string.error_reason_timeout)
    val STORAGE: UiMessage = UiMessage.Res(R.string.error_reason_storage)
    val DATABASE: UiMessage = UiMessage.Res(R.string.error_reason_database)
    val PERMISSION: UiMessage = UiMessage.Res(R.string.error_reason_permission)
    val UNKNOWN: UiMessage = UiMessage.Res(R.string.error_reason_unknown)
}

/**
 * 非致命错误的落盘出口。
 *
 * 抽成函数接口而不是直接依赖 `CrashReporter`，是为了让 [ErrorHandler] 保持**纯 Kotlin**
 * （不碰 Context / Build），从而能在 JVM 单元测试里完整验证分类与留痕逻辑。
 */
fun interface NonFatalSink {
    fun record(throwable: Throwable, note: String)
}

/**
 * 归类后的错误。
 *
 * [userMessage] 是**唯一**允许显示给用户的文本；异常类名、堆栈、路径等技术细节全部留在
 * `cause` 里，由崩溃日志承接（导出时还会再脱敏一次）。
 *
 * 传给 `Exception` 的 message 是 [scene] 而不是 [userMessage]：后者已经不是字符串了，
 * 而崩溃日志需要一个能直接打印的分诊标签。
 */
sealed class AppError(
    val userMessage: UiMessage,
    /** 记进崩溃日志的场景标签，用于分诊 */
    val scene: String,
    cause: Throwable?
) : Exception(scene, cause) {

    /**
     * 抛出方自带文案（见 [UserFacingMessage]）。这类失败是预期内的，不占崩溃日志配额。
     *
     * 收 String 的构造器留给「文案在抛出点拼好」的旧路径，内部包成 [UiMessage.Raw]。
     */
    class Friendly(message: UiMessage, cause: Throwable? = null) :
        AppError(message, "已知失败", cause) {
        constructor(message: String, cause: Throwable? = null) :
            this(UiMessage.Raw(message), cause)
    }

    class Storage(cause: Throwable?) : AppError(ErrorMessages.STORAGE, "存储读写", cause)
    class Database(cause: Throwable?) : AppError(ErrorMessages.DATABASE, "数据库", cause)
    class Network(cause: Throwable?) : AppError(ErrorMessages.NETWORK, "网络", cause)
    class Timeout(cause: Throwable?) : AppError(ErrorMessages.TIMEOUT, "网络超时", cause)
    class Permission(cause: Throwable?) : AppError(ErrorMessages.PERMISSION, "权限/路径校验", cause)
    class Unknown(cause: Throwable?) : AppError(ErrorMessages.UNKNOWN, "未分类", cause)

    /**
     * 是否值得留一条非致命记录。
     *
     * [Friendly] 是「已经想清楚该说什么」的预期失败（HTTP 401、用户选的目录没权限…），
     * 记进去只会把 20 条的日志配额挤满，把真正的崩溃挤出去。
     */
    val worthRecording: Boolean get() = this !is Friendly
}

/**
 * 异常 → 归类 → 用户文案 → 留痕，界面层处理失败的统一入口。
 *
 * 四条约束：
 * - **取消不是错误**：[CancellationException] 原样抛出。吞掉它既会让用户看到一条莫名的
 *   「操作失败」，也会破坏结构化并发（父作用域以为子任务正常结束了）。
 * - **原始 message 不上界面**：只有带 [UserFacingMessage] 标记的异常才透出自己的文案。
 * - **不产出字符串**：出口是 [UiMessage]，解析交给界面层。本层没有 Context，也不该有——
 *   在这里 `getString` 意味着把整套资源体系拖进 data/domain。
 * - **纯 Kotlin**：Android 相关类型（`android.database.*`、Ktor 的 socket 异常）一律按
 *   **类名**判定，不 import。这样整个文件都能在 JVM 测试里跑（`R.string.*` 只是 int，不例外）。
 */
object ErrorHandler {

    @Volatile
    private var sink: NonFatalSink? = null

    /** 装上落盘出口（在 Application 里接到 `CrashReporter`）。未装时只是不留痕，不影响文案。 */
    fun install(sink: NonFatalSink) {
        this.sink = sink
    }

    /** 测试用：拆掉出口，避免用例之间互相污染。 */
    fun reset() {
        sink = null
    }

    /**
     * 归类。**[CancellationException] 原样抛出，不会变成一条 [AppError]。**
     */
    fun classify(throwable: Throwable): AppError {
        if (throwable is CancellationException) throw throwable
        val name = throwable.javaClass.name
        return when {
            throwable is AppError -> throwable
            // 先看资源化文案，没有再退回抛出点拼好的字符串；两者都空则不算「自带文案」
            throwable is UserFacingMessage ->
                (
                    throwable.uiMessage
                        ?: throwable.message?.takeIf { it.isNotBlank() }?.let { UiMessage.Raw(it) }
                    )
                    ?.let { AppError.Friendly(it, throwable) }
                    ?: AppError.Unknown(throwable)
            throwable is SecurityException -> AppError.Permission(throwable)
            // 顺序敏感：SocketTimeoutException 是 IOException 的子类，必须先判超时
            throwable is SocketTimeoutException || isTimeoutName(name) -> AppError.Timeout(throwable)
            throwable is UnknownHostException ||
                throwable is ConnectException ||
                throwable is SocketException ||
                throwable is SSLException ||
                isNetworkName(name) -> AppError.Network(throwable)
            isDatabaseName(name) -> AppError.Database(throwable)
            // 裸 IOException 按存储解释。网络域的调用方（AI 流式请求）有自己的映射，
            // 见 [AiErrorMapper.mapException]——差别来自调用域，不是分类分歧。
            throwable is IOException -> AppError.Storage(throwable)
            // IllegalArgument / IllegalState 不再当「校验失败」透出原文：那多半是内部不变量
            // 被破坏，message 是给开发者看的英文断言，直接示人只会让用户更困惑。
            else -> AppError.Unknown(throwable)
        }
    }

    /**
     * Ktor 的超时异常只继承 [IOException]（`io.ktor.client.network.sockets.ConnectTimeoutException`、
     * `io.ktor.client.plugins.HttpRequestTimeoutException` 早期版本），靠类型判不出来，
     * 而它们的类名都以 `TimeoutException` 结尾。
     */
    internal fun isTimeoutName(className: String): Boolean = className.endsWith("TimeoutException")

    /** Ktor / JDK 的网络异常命名空间。`java.net.*` 里除超时外都是连不上的各种形态。 */
    internal fun isNetworkName(className: String): Boolean =
        className.startsWith("java.net.") ||
            className.startsWith("javax.net.") ||
            className.startsWith("io.ktor.")

    /** Room 抛的是 `android.database.sqlite.*`；用类名判定以免把 Android 类型引进本文件。 */
    internal fun isDatabaseName(className: String): Boolean =
        className.startsWith("android.database.") || className.startsWith("androidx.room.")

    /** [safeDetail] 默认带出的技术细节字符数上限 */
    const val MAX_DETAIL_CHARS = 120

    private val WHITESPACE = Regex("""\s+""")

    /**
     * 异常原文的**受限**呈现：脱敏 → 压平空白 → 截断；message 为空时退回类名。
     *
     * 默认路线（[report] / [message]）永远不透出原文。但少数界面离了细节就没法自救——自定义
     * AI Base URL 写错时 provider 抛的 `SerializationException: Unexpected JSON token at
     * offset 42`、手改配置文件被 kotlinx 拒收的那一行，换成「出现未知问题」用户无从下手。
     * 这类地方用它，而不是直接读 [Throwable.message]：
     *
     * 1. 先过 [CrashLogFormatter.redact]——Ktor 的异常消息带完整请求地址，Gemini 的密钥就藏在
     *    `?key=` 里，而 kotlinx 的解析错误会回显出错位置附近的原文（配置文件里正躺着 API Key）；
     * 2. 压掉换行与连续空白——多行原文会把 Snackbar 撑成一整屏；
     * 3. 截断——异常消息可以长到几 KB，把整个响应体塞进 message 的 provider 是存在的。
     */
    fun safeDetail(throwable: Throwable, maxChars: Int = MAX_DETAIL_CHARS): String {
        // 兜底用异常类名，取不到就用 "Throwable"——这里已经是技术细节的展示位，
        // 塞一句中文（旧实现是「未知异常」）在英文语区反而更突兀。
        val fallback = throwable::class.simpleName ?: "Throwable"
        val raw = throwable.message?.takeIf { it.isNotBlank() } ?: return fallback
        val flat = CrashLogFormatter.redact(raw).replace(WHITESPACE, " ").trim()
        if (flat.isEmpty()) return fallback
        return if (flat.length > maxChars) flat.take(maxChars) + "…" else flat
    }

    /**
     * 只要文案、不留痕。用于「预期内、不值得记账」的场合。
     *
     * @param action 正在做的事，如 [UserAction.MOVE_FOLDER]，会拼成「移动文件夹失败：<原因>」。
     */
    fun message(throwable: Throwable, action: UserAction? = null): UiMessage =
        compose(classify(throwable), action)

    /**
     * 归类 + 记一笔非致命 + 返回可直接显示的文案。**界面层应当只用这一个入口。**
     */
    fun report(throwable: Throwable, action: UserAction? = null): UiMessage {
        val error = classify(throwable)
        if (error.worthRecording) {
            val note = listOfNotNull(action?.tag, error.scene).joinToString(" / ")
            // 记原始异常而不是包装后的 AppError：堆栈要指向真正出事的那一行。
            // sink 自己已经不抛异常了，这里再兜一层——记日志失败绝不能反过来打断业务流程。
            runCatching { sink?.record(throwable, note) }
        }
        return compose(error, action)
    }

    /**
     * 「<动作>失败：<原因>」。
     *
     * 两段都是资源 id，靠 [UiMessage.Res] 的嵌套实参在界面层一次解析完成——所以这里既不需要
     * Context，也不会在英文语区拼出「Save失败：…」这种半中半英的句子。
     */
    private fun compose(error: AppError, action: UserAction?): UiMessage =
        if (action == null) error.userMessage
        else UiMessage.of(
            R.string.error_action_failed,
            UiMessage.Res(action.labelRes),
            error.userMessage
        )
}

/**
 * 失败时记一笔并把文案交给 [onMessage]，成功时什么都不做；返回值原样透传便于串联。
 *
 * 把 `.onFailure { setError(it.message ?: "移动文件夹失败") }` 收敛成
 * `.onFailureReport(UserAction.MOVE_FOLDER) { setError(it) }`——顺带堵掉原始 message 的外泄。
 * 取消异常照旧向上抛（见 [ErrorHandler.classify]）。
 */
inline fun <T> Result<T>.onFailureReport(
    action: UserAction? = null,
    onMessage: (UiMessage) -> Unit
): Result<T> {
    exceptionOrNull()?.let { onMessage(ErrorHandler.report(it, action)) }
    return this
}
