package com.yumark.app.data.remote.webdav

import com.yumark.app.domain.model.RemoteEntry
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 解析 WebDAV PROPFIND 的 `multistatus` 响应为 [RemoteEntry] 列表。
 *
 * 用命名空间感知的 javax.xml DOM（纯 JVM，可单测），兼容不同服务器的命名空间前缀（`d:`/`D:`/无前缀）。
 * 禁用 DOCTYPE 以防 XXE。
 */
object WebDavXml {

    private const val DAV_NS = "DAV:"

    fun parseMultistatus(xml: String): List<RemoteEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // 安全加固：禁外部 DTD / 实体，防 XXE
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val responses = document.getElementsByTagNameNS(DAV_NS, "response")

        val result = ArrayList<RemoteEntry>(responses.length)
        for (i in 0 until responses.length) {
            val response = responses.item(i) as? Element ?: continue
            val href = firstText(response, "href") ?: continue
            val decoded = decodeHref(href)
            val isDir = hasCollection(response) || decoded.endsWith("/")
            val name = nameFromPath(decoded)
            if (name.isEmpty()) continue
            result.add(
                RemoteEntry(
                    name = name,
                    etag = firstText(response, "getetag")?.let(::normalizeEtag),
                    lastModifiedMs = firstText(response, "getlastmodified")?.let(::parseHttpDate),
                    isDirectory = isDir
                )
            )
        }
        return result
    }

    private fun firstText(scope: Element, localName: String): String? {
        val nodes = scope.getElementsByTagNameNS(DAV_NS, localName)
        if (nodes.length == 0) return null
        return nodes.item(0).textContent?.trim()?.ifEmpty { null }
    }

    private fun hasCollection(response: Element): Boolean =
        response.getElementsByTagNameNS(DAV_NS, "collection").length > 0

    private fun decodeHref(href: String): String =
        runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)

    /** 取路径末段为文件名；目录（末尾 '/'）先去尾斜杠。 */
    private fun nameFromPath(path: String): String =
        path.trimEnd('/').substringAfterLast('/')

    /** 去掉弱标记 `W/` 与包裹引号。 */
    private fun normalizeEtag(raw: String): String =
        raw.removePrefix("W/").trim().trim('"')

    /** RFC 1123（HTTP-date）→ epoch 毫秒；解析失败返回 null。 */
    private fun parseHttpDate(raw: String): Long? {
        // 先按标准 RFC 1123 解析。
        runCatching {
            return ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }
        // 兜底：去掉星期前缀再解析，容忍服务器给出与日期不一致的星期（RFC_1123 会因此拒绝）。
        val withoutWeekday = raw.substringAfter(',', raw).trim()
        return runCatching {
            ZonedDateTime.parse(withoutWeekday, FALLBACK_DATE_FORMAT).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private val FALLBACK_DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.US)
}
