package com.yumark.app.data.remote.webdav

import com.yumark.app.R
import com.yumark.app.core.util.AiErrorMapper
import com.yumark.app.core.util.FriendlyIOException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserAction
import com.yumark.app.domain.model.RemoteEntry
import com.yumark.app.domain.model.WebDavConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 轻量 WebDAV 客户端。
 *
 * 用 Ktor **CIO 引擎**（而非 Android 引擎）——Android 引擎基于 `HttpURLConnection`，不允许
 * `PROPFIND`/`MKCOL` 等自定义方法。Basic 认证按请求注入，凭证不入日志。
 *
 * 失败一律抛 [FriendlyIOException]：HTTP 状态码是**预期内**的失败，文案在抛出点就已经想清楚，
 * 界面层可直接透出（见 [com.yumark.app.core.util.UserFacingMessage]）。仍是 IOException 子类，
 * 按 IOException 分流的调用方不受影响。瞬时故障（429/5xx/408/423 与网络抖动）先按指数退避重试
 * [MAX_ATTEMPTS] 次，重试耗尽才成为一次失败。
 *
 * 仅覆盖 P1 所需：列目录、下载、上传、建目录、删除、连接测试。**没有 MOVE/COPY**——改名走
 * 「上传新名 + 删旧名」，所以本文件不存在 `Destination` 头需要编码的问题。
 */
@Singleton
class WebDavClient @Inject constructor() {

    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                // 只是元数据请求（PROPFIND/MKCOL/DELETE）的兜底：它是**墙上时钟**，
                // 正文传输不能受它管，download/upload 用 timeout{} 单独解除，见 noRequestTimeout。
                requestTimeoutMillis = METADATA_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
            expectSuccess = false
        }
    }

    /** 用给定配置探测连通性（PROPFIND Depth:0）。 */
    suspend fun testConnection(config: WebDavConfig): Result<Unit> = io {
        retrying(UserAction.CONNECT) {
            val resp = propfind(config, depth = "0")
            when {
                resp.isMultiStatusOrSuccess() -> Unit
                // 404：服务器可达且已通过认证（认证失败会先返回 401/403），仅同步目录尚未创建——
                // 视为连接成功，首次同步时会自动建目录。
                resp.status.value == 404 -> Unit
                else -> throw failure(UserAction.CONNECT, resp.status.value)
            }
        }
    }

    /** 列出同步目录下的条目；目录不存在(404)视为空。 */
    suspend fun list(config: WebDavConfig): Result<List<RemoteEntry>> = io {
        retrying(UserAction.LIST_REMOTE_DIR) {
            val resp = propfind(config, depth = "1")
            when {
                resp.status.value == 404 -> emptyList()
                resp.isMultiStatusOrSuccess() -> WebDavXml.parseMultistatus(resp.bodyAsText())
                else -> throw failure(UserAction.LIST_REMOTE_DIR, resp.status.value)
            }
        }
    }

    /**
     * 列出同步目录下**子目录** [subDir] 的条目；目录不存在(404)视为空。
     *
     * 服务媒体同步（`_media/`）：文档同步只认 `.md`，子目录条目天然被它的过滤器忽略，
     * 两条通道互不干扰。[subDir] 必须是单个路径段（内部常量，不接受用户输入）。
     */
    suspend fun listSubDir(config: WebDavConfig, subDir: String): Result<List<RemoteEntry>> = io {
        retrying(UserAction.LIST_REMOTE_DIR) {
            val resp = propfindIn(config, subDir, depth = "1")
            when {
                resp.status.value == 404 -> emptyList()
                resp.isMultiStatusOrSuccess() -> WebDavXml.parseMultistatus(resp.bodyAsText())
                else -> throw failure(UserAction.LIST_REMOTE_DIR, resp.status.value)
            }
        }
    }

    /**
     * 下载文件正文。
     *
     * 正文**硬按 UTF-8 解**而不是 `bodyAsText()`：有服务器给 .md 回 `text/plain; charset=ISO-8859-1`，
     * 照那个字符集解出来的中文全是乱码，而这段乱码紧接着就被写回本地文档——同步把文档改坏了。
     * 上传侧同样显式声明 UTF-8，两头对齐。
     */
    suspend fun download(config: WebDavConfig, fileName: String): Result<String> = io {
        retrying(UserAction.DOWNLOAD_REMOTE_FILE) {
            val resp = client.get(fileUrl(config, fileName)) {
                basicAuth(config.username, config.password)
                noRequestTimeout()
            }
            if (resp.status.isSuccess()) decodeMarkdown(resp.readBytes())
            else throw failure(UserAction.DOWNLOAD_REMOTE_FILE, resp.status.value)
        }
    }

    /**
     * 下载同步目录下子目录 [subDir] 里的文件，返回**原始字节**（图片二进制不做任何解码）。
     * 供媒体同步使用；[subDir] 必须是单个路径段（内部常量）。
     */
    suspend fun downloadBytes(config: WebDavConfig, subDir: String, fileName: String): Result<ByteArray> = io {
        retrying(UserAction.DOWNLOAD_REMOTE_FILE) {
            val resp = client.get(subFileUrl(config, subDir, fileName)) {
                basicAuth(config.username, config.password)
                noRequestTimeout()
            }
            if (resp.status.isSuccess()) resp.readBytes()
            else throw failure(UserAction.DOWNLOAD_REMOTE_FILE, resp.status.value)
        }
    }

    /**
     * 上传字节到子目录 [subDir]（不存在则先逐级创建）。二进制直传、不碰 If-Match：
     * 媒体文件按内容寻址（本地 images/ 里的文件名是不可变的 uuid.ext），
     * 同名即同物，条件请求与 ETag 记账对它没有意义。
     *
     * [ensureDir] 给批量上传用：每张图都探测一次子目录是 50 张 = 50 次多余的
     * PROPFIND，批量调用方应先 [ensureSubDir] 一次，再逐张传 [ensureDir] = false。
     */
    suspend fun uploadBytes(
        config: WebDavConfig,
        subDir: String,
        fileName: String,
        bytes: ByteArray,
        ensureDir: Boolean = true
    ): Result<Unit> = io {
        retrying(UserAction.UPLOAD_REMOTE_FILE) {
            if (ensureDir) ensureSubDir(config, subDir)
            val resp = client.put(subFileUrl(config, subDir, fileName)) {
                basicAuth(config.username, config.password)
                contentType(ContentType.Application.OctetStream)
                noRequestTimeout()
                setBody(bytes)
            }
            if (!resp.status.isSuccess()) throw failure(UserAction.UPLOAD_REMOTE_FILE, resp.status.value)
        }
    }

    /**
     * 上传文件正文；返回服务器回的 ETag（若有）。
     *
     * [ifMatchEtag] 非空时带 `If-Match` 条件请求：从 PROPFIND 读到 etag 到 PUT 发出之间有个窗口，
     * 另一台设备正好在这期间改了同一篇，无条件 PUT 会把对方的修改静默冲掉。条件请求把这个窗口
     * 交给服务器守——412 表示远端确实变了，本次放弃，下次同步会按冲突处理（远端版本存成副本）。
     *
     * 退化路径：少数服务器不认条件 PUT，回 400/501。这两个状态码退回一次无条件 PUT，否则同步在
     * 这类服务器上会彻底失效；412 绝不退化，那正是要拦的情况。
     *
     * 正因为 412 不退化，[ifMatchEtag] 只接受**强** ETag：弱验证器（`W/"…"`）在 RFC 9110 的强比较下
     * 与任何标签都不相等，连它自己都不等，条件 PUT 于是**永久** 412——那台服务器上凡是远端已存在的
     * 文档，上传每一轮都失败。筛选放在调用方，见 `SyncRepositoryImpl.ifMatchEtagOf`。
     */
    suspend fun upload(
        config: WebDavConfig,
        fileName: String,
        content: String,
        ifMatchEtag: String? = null
    ): Result<String?> = io {
        retrying(UserAction.UPLOAD_REMOTE_FILE) {
            val resp = put(config, fileName, content, ifMatchEtag)
            when {
                resp.status.isSuccess() -> etagOf(resp)
                ifMatchEtag != null && resp.status.value in PRECONDITION_UNSUPPORTED -> {
                    val plain = put(config, fileName, content, ifMatchEtag = null)
                    if (plain.status.isSuccess()) etagOf(plain)
                    else throw failure(UserAction.UPLOAD_REMOTE_FILE, plain.status.value)
                }
                else -> throw failure(UserAction.UPLOAD_REMOTE_FILE, resp.status.value)
            }
        }
    }

    /**
     * 建同步目录（已存在则忽略），**逐级**创建。
     *
     * MKCOL 不补父目录：remoteDir 填成 `apps/YuMark/笔记` 而中间层不存在时服务器回 409，
     * 而旧实现只把 401/403 当失败——「目录已就绪」这个前提是假的，紧随其后的 PROPFIND/PUT
     * 全部 404，用户看到的却是「列目录失败：HTTP 404」，指不到真正的原因。
     */
    suspend fun ensureDir(config: WebDavConfig): Result<Unit> = io {
        val segments = WebDavPaths.encodePath(config.remoteDir).split('/').filter { it.isNotEmpty() }
        var url = baseUrl(config)
        for (segment in segments) {
            url += "$segment/"
            mkcol(config, url)
        }
    }

    private suspend fun mkcol(config: WebDavConfig, url: String) {
        retrying(UserAction.CREATE_REMOTE_DIR) {
            val resp = client.request(url) {
                method = HttpMethod("MKCOL")
                basicAuth(config.username, config.password)
            }
            val status = resp.status.value
            // 201 建成；405 已存在（多数服务器）；301/302 已存在但规范路径不同——均视为就绪。
            if (resp.status.isSuccess() || status in DIR_ALREADY_EXISTS) Unit
            else throw failure(UserAction.CREATE_REMOTE_DIR, status)
        }
    }

    /**
     * 删除远端文件（改名后清理旧文件）。
     *
     * 404 视为成功——目标已经不在了，正是想要的终态。其余非 2xx 必须抛：旧实现连状态码都不看，
     * 于是「上传新名 + 删旧名」里删失败会被当成成功，远端留下一份内容陈旧的孤儿文件，
     * 而它下一次同步会被当成「远端独有」拉回来，变成一篇重复文档。
     */
    suspend fun delete(config: WebDavConfig, fileName: String): Result<Unit> = io {
        retrying(UserAction.DELETE_DOCUMENT) {
            val resp = client.delete(fileUrl(config, fileName)) {
                basicAuth(config.username, config.password)
            }
            if (resp.status.isSuccess() || resp.status.value == 404) Unit
            else throw failure(UserAction.DELETE_DOCUMENT, resp.status.value)
        }
    }

    private suspend fun put(
        config: WebDavConfig,
        fileName: String,
        content: String,
        ifMatchEtag: String?
    ): HttpResponse = client.put(fileUrl(config, fileName)) {
        basicAuth(config.username, config.password)
        // 显式带 charset：只写 text/plain 的话，服务器回给别的客户端时得自己猜字符集。
        contentType(MARKDOWN_UTF8)
        // If-Match 的语法要求带引号；我们内部存的是剥了引号的规范形式。
        // 传进来的必须是**强** ETag：弱验证器在 RFC 9110 的强比较下永不相等，发出去就是永久 412，
        // 而 412 不在下面的退化名单里。筛选在调用方（`SyncRepositoryImpl.ifMatchEtagOf`）。
        ifMatchEtag?.let { header(HttpHeaders.IfMatch, "\"$it\"") }
        noRequestTimeout()
        setBody(content.toByteArray(Charsets.UTF_8))
    }

    // 与 PROPFIND 那条通道共用同一套剥法。两边各写一份的话，同一版内容会算出两个不同的标记，
    // 每次上传后都被判成「远端变了」——理由写在 [WebDavEtags] 的注释里。
    private fun etagOf(resp: HttpResponse): String? =
        WebDavEtags.normalize(resp.headers[HttpHeaders.ETag])

    private suspend fun propfind(config: WebDavConfig, depth: String): HttpResponse =
        client.request(dirUrl(config)) {
            method = HttpMethod("PROPFIND")
            basicAuth(config.username, config.password)
            header("Depth", depth)
            contentType(ContentType.Application.Xml)
            setBody(PROPFIND_BODY)
        }

    private fun HttpResponse.isMultiStatusOrSuccess(): Boolean =
        status.value == 207 || status.isSuccess()

    /**
     * 非 2xx → 异常：瞬时故障抛 [RetryableHttpFailure] 交给 [retrying] 退避重试，
     * 其余抛已成句的 [FriendlyIOException]（文案见 [webDavStatusMessage]）。
     */
    private fun failure(action: UserAction, status: Int): Throwable =
        if (isRetryableWebDavStatus(status)) RetryableHttpFailure(status)
        else FriendlyIOException(webDavStatusMessage(action, status))

    /**
     * 指数退避重试，最多 [MAX_ATTEMPTS] 次。
     *
     * 只重试**瞬时**故障：网络抖动（IOException）与服务端临时状态（429/5xx/408/423）。
     * 已成句的 [FriendlyIOException] 是确定性失败（401/403/404/412…），重发一百次都是同一个答案，
     * 只会把「同步失败」的等待时间乘以三。
     *
     * [CancellationException] 必须原样上抛：它不是失败，吞掉会让「用户退出同步页」被记成一次
     * 同步失败，也会破坏结构化并发。
     */
    private suspend fun <T> retrying(action: UserAction, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                // 「已成句」的 FriendlyIOException 由 isRetryableException 自己排掉（它带
                // UserFacingMessage 标记），这里不再抄一遍那条策略——从前两处各写一份，
                // 结果 AI 流那一侧漏掉了，同一个确定性失败在那边照样重试三次。
                val transient = e is RetryableHttpFailure || AiErrorMapper.isRetryableException(e)
                if (!transient || attempt >= MAX_ATTEMPTS - 1) throw e.asFriendly(action)
                delay(AiErrorMapper.backoffMillis(attempt))
                attempt++
            }
        }
    }

    /** [RetryableHttpFailure] 是内部信号，绝不出这个文件——出去前换成带文案的异常。 */
    private fun Throwable.asFriendly(action: UserAction): Throwable =
        if (this is RetryableHttpFailure) {
            FriendlyIOException(webDavStatusMessage(action, status))
        } else {
            this
        }

    /**
     * 解除这一个请求的整体超时。
     *
     * 全局 [METADATA_TIMEOUT_MS] 是墙上时钟，对元数据请求是合理兜底，对正文传输却是定时炸弹：
     * 慢网下一篇稍大的文档传到 60s 就被掐断，重试同样被掐断，于是「同步永远失败」。
     * 正文传输改由 [SOCKET_TIMEOUT_MS] 兜底——那个计时器只在「连续 60s 一个字节都没动」时触发，
     * 正是真正要防的卡死。
     */
    private fun HttpRequestBuilder.noRequestTimeout() {
        timeout { requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS }
    }

    private fun baseUrl(config: WebDavConfig): String = config.baseUrl.trim().trimEnd('/') + "/"

    /**
     * 同步目录 URL。remoteDir **逐段**百分号编码。
     *
     * 旧实现只编码文件名、把 remoteDir 原样拼进 URL：目录名一带中文或空格，
     * 请求行里就出现了非法字符，多数服务器直接 400，而报错只说「列目录失败：HTTP 400」。
     * 编码不能对整条路径做（会把段间的 `/` 也编成 `%2F`），所以走 [WebDavPaths.encodePath]。
     */
    private fun dirUrl(config: WebDavConfig): String {
        val dir = WebDavPaths.encodePath(config.remoteDir)
        return if (dir.isEmpty()) baseUrl(config) else baseUrl(config) + dir + "/"
    }

    /** 文件 URL。文件名是**一个**路径段，用 encodeSegment：段内的 `/`、`+`、`%` 都得转义。 */
    private fun fileUrl(config: WebDavConfig, fileName: String): String =
        dirUrl(config) + WebDavPaths.encodeSegment(fileName)

    /** 子目录 URL（remoteDir/subDir/，subDir 单段）。 */
    private fun subDirUrl(config: WebDavConfig, subDir: String): String =
        dirUrl(config) + WebDavPaths.encodeSegment(subDir) + "/"

    /** 子目录内文件的 URL。 */
    private fun subFileUrl(config: WebDavConfig, subDir: String, fileName: String): String =
        subDirUrl(config, subDir) + WebDavPaths.encodeSegment(fileName)

    /** 子目录的 PROPFIND。 */
    private suspend fun propfindIn(config: WebDavConfig, subDir: String, depth: String): HttpResponse =
        client.request(subDirUrl(config, subDir)) {
            method = HttpMethod("PROPFIND")
            basicAuth(config.username, config.password)
            header("Depth", depth)
            contentType(ContentType.Application.Xml)
            setBody(PROPFIND_BODY)
        }

    /**
     * 确保子目录存在：先 PROPFIND Depth:0 探测，404 才 MKCOL。
     * 公开给批量上传方（[MediaSync.pushLocalImages]）：整轮探测一次，
     * 之后逐张传 [uploadBytes] 的 [ensureDir] = false，省掉每张图的往返。
     * MKCOL 已存在时会回 405，也在这里一并当成功。
     */
    suspend fun ensureSubDir(config: WebDavConfig, subDir: String): Result<Unit> = io {
        val probe = propfindIn(config, subDir, depth = "0")
        if (probe.status.value == 404) {
            mkcol(config, subDirUrl(config, subDir))
            return@io
        }
        if (!probe.isMultiStatusOrSuccess()) {
            throw failure(UserAction.CREATE_REMOTE_DIR, probe.status.value)
        }
    }

    /**
     * 统一切到 IO 线程并把异常收进 [Result]。
     *
     * `runCatching` 连 [CancellationException] 一起吞——那会让「用户中途退出同步页」被当成
     * 一次同步失败弹提示，也会破坏结构化并发（父作用域以为子任务正常结束了）。
     * 所以捕获之后先把取消原样抛回去。
     */
    private suspend fun <T> io(block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching { block() }.onFailure { if (it is CancellationException) throw it }
        }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** 元数据请求（PROPFIND/MKCOL/DELETE）的整体上限；正文传输不受它管，见 noRequestTimeout。 */
        private const val METADATA_TIMEOUT_MS = 60_000L

        /** 「连续多久一个字节都没动」才算卡死。正文传输的实际兜底就是它。 */
        private const val SOCKET_TIMEOUT_MS = 60_000L

        /** 含首次在内的总尝试次数。3 次配合 500/1000ms 退避，最坏多等 ~1.5s。 */
        private const val MAX_ATTEMPTS = 3

        /** 服务器不认条件 PUT 的表现：退回一次无条件 PUT，见 [upload]。 */
        private val PRECONDITION_UNSUPPORTED = setOf(400, 501)

        /** MKCOL 遇到「目录已存在」的各种说法。 */
        private val DIR_ALREADY_EXISTS = setOf(301, 302, 405)

        /** 上传正文的 Content-Type。带 charset，别让服务器猜。 */
        private val MARKDOWN_UTF8 = ContentType.Text.Plain.withCharset(Charsets.UTF_8)

        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:getetag/><d:getlastmodified/><d:resourcetype/>" +
                "</d:prop></d:propfind>"
    }
}

/** 内部信号：这次 HTTP 失败值得重试。不出 [WebDavClient]，出去前会换成带文案的异常。 */
private class RetryableHttpFailure(val status: Int) : Exception()

/**
 * WebDAV 的 HTTP 状态码 → 用户可读文案。纯函数，可单测（见 WebDavStatusTest）。
 *
 * 401/403 单独拎出来：这两种情况服务器**是**连上了，报「连接失败：HTTP 401」会把用户推去查网络，
 * 而真正要改的是账号密码。响应体一律不带进文案——WebDAV 服务器的错误页动辄是一整篇 HTML。
 *
 * 409/412/423/429 同样各自成句而不落到兜底的「上传失败：HTTP 412」：那句话指不出用户该做什么，
 * 而这四条各指向一件具体的事（建上级目录 / 什么都不用做 / 等一会儿再来）。
 */
internal fun webDavStatusMessage(action: UserAction, status: Int): UiMessage = when (status) {
    401 -> UiMessage.Res(R.string.webdav_error_unauthorized)
    403 -> UiMessage.Res(R.string.webdav_error_forbidden)
    507, 413 -> UiMessage.of(R.string.webdav_error_insufficient_storage, status)
    409 -> UiMessage.Res(R.string.webdav_error_conflict_path)
    412 -> UiMessage.Res(R.string.webdav_error_precondition_failed)
    423 -> UiMessage.Res(R.string.webdav_error_locked)
    429 -> UiMessage.Res(R.string.webdav_error_rate_limited)
    // 兜底走 error_action_failed 的两段式：「上传失败：HTTP 500」。原因半句是嵌套的 UiMessage，
    // 解析时会先各自变成字符串再填进外层占位符（见 UiMessageResolve）。
    else -> UiMessage.of(
        R.string.error_action_failed,
        UiMessage.Res(action.labelRes),
        UiMessage.of(R.string.webdav_error_http_status, status)
    )
}

/**
 * 这个状态码值得退避重试吗——服务端瞬时状态才算。
 *
 * 501 明确排除：服务器说「不支持这个方法」，重发一百次都是同一个答案，只会让失败来得更慢。
 * 4xx 里只有 408（请求超时）和 423（被锁）会自己好转，其余 4xx 是请求本身有问题。
 */
internal fun isRetryableWebDavStatus(status: Int): Boolean = when (status) {
    408, 423, 429 -> true
    501 -> false
    else -> status in 500..599
}

/**
 * 远端正文字节 → 文本：硬按 UTF-8，并去掉 BOM。
 *
 * BOM 必须去：Windows 编辑器写出的 U+FEFF 会进正文，既在编辑器里显示成一个看不见的字符，
 * 又改变内容哈希——每次同步都判成「本地有变化」，于是无限上传。
 */
internal fun decodeMarkdown(bytes: ByteArray): String =
    String(bytes, Charsets.UTF_8).removePrefix(UTF8_BOM)

/** U+FEFF。用码点构造而不是把字符本体敲进源码——那是个看不见的字符，编辑器随手就能吃掉它。 */
private val UTF8_BOM = Char(0xFEFF).toString()
