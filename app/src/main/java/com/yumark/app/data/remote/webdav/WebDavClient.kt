package com.yumark.app.data.remote.webdav

import com.yumark.app.domain.model.RemoteEntry
import com.yumark.app.domain.model.WebDavConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 轻量 WebDAV 客户端。
 *
 * 用 Ktor **CIO 引擎**（而非 Android 引擎）——Android 引擎基于 `HttpURLConnection`，不允许
 * `PROPFIND`/`MKCOL` 等自定义方法。Basic 认证按请求注入，凭证不入日志。
 *
 * 仅覆盖 P1 所需：列目录、下载、上传、建目录、删除、连接测试。
 */
@Singleton
class WebDavClient @Inject constructor() {

    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
            expectSuccess = false
        }
    }

    /** 用给定配置探测连通性（PROPFIND Depth:0）。 */
    suspend fun testConnection(config: WebDavConfig): Result<Unit> = io {
        val resp = propfind(config, depth = "0")
        when {
            resp.isMultiStatusOrSuccess() -> Unit
            // 404：服务器可达且已通过认证（认证失败会先返回 401/403），仅同步目录尚未创建——
            // 视为连接成功，首次同步时会自动建目录。
            resp.status.value == 404 -> Unit
            else -> throw IOException("连接失败：HTTP ${resp.status.value}")
        }
    }

    /** 列出同步目录下的条目；目录不存在(404)视为空。 */
    suspend fun list(config: WebDavConfig): Result<List<RemoteEntry>> = io {
        val resp = propfind(config, depth = "1")
        when {
            resp.status.value == 404 -> emptyList()
            resp.isMultiStatusOrSuccess() -> WebDavXml.parseMultistatus(resp.bodyAsText())
            else -> throw IOException("列目录失败：HTTP ${resp.status.value}")
        }
    }

    /** 下载文件正文。 */
    suspend fun download(config: WebDavConfig, fileName: String): Result<String> = io {
        val resp = client.get(fileUrl(config, fileName)) { basicAuth(config.username, config.password) }
        if (resp.status.isSuccess()) resp.bodyAsText()
        else throw IOException("下载失败：HTTP ${resp.status.value}")
    }

    /** 上传文件正文；返回服务器回的 ETag（若有）。 */
    suspend fun upload(config: WebDavConfig, fileName: String, content: String): Result<String?> = io {
        val resp = client.put(fileUrl(config, fileName)) {
            basicAuth(config.username, config.password)
            contentType(ContentType.Text.Plain)
            setBody(content)
        }
        if (resp.status.isSuccess()) resp.headers["ETag"]?.removePrefix("W/")?.trim()?.trim('"')
        else throw IOException("上传失败：HTTP ${resp.status.value}")
    }

    /** 建同步目录（已存在则忽略）。 */
    suspend fun ensureDir(config: WebDavConfig): Result<Unit> = io {
        val resp = client.request(dirUrl(config)) {
            method = HttpMethod("MKCOL")
            basicAuth(config.username, config.password)
        }
        // 201 创建成功；405/301 等表示已存在——均视为就绪，不抛错。
        if (resp.status.value in listOf(401, 403)) {
            throw IOException("无权创建目录：HTTP ${resp.status.value}")
        }
        Unit
    }

    /** 删除远端文件（用于改名后清理旧文件）。 */
    suspend fun delete(config: WebDavConfig, fileName: String): Result<Unit> = io {
        client.delete(fileUrl(config, fileName)) { basicAuth(config.username, config.password) }
        Unit
    }

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

    private fun dirUrl(config: WebDavConfig): String {
        val base = config.baseUrl.trim().trimEnd('/')
        val dir = config.remoteDir.trim().trim('/')
        return if (dir.isEmpty()) "$base/" else "$base/$dir/"
    }

    private fun fileUrl(config: WebDavConfig, fileName: String): String =
        dirUrl(config) + fileName.encodeURLPath()

    private suspend fun <T> io(block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching { block() } }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 60_000L
        private const val SOCKET_TIMEOUT_MS = 60_000L

        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:getetag/><d:getlastmodified/><d:resourcetype/>" +
                "</d:prop></d:propfind>"
    }
}
