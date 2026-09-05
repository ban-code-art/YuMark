package com.yumark.app.data.remote.webdav

import com.yumark.app.domain.model.RemoteEntry
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
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
            // 尾斜杠判目录必须看**未解码**的 href：那个斜杠是结构字符，永远不会被百分号编码，
            // 而解码后的名字里可能本来就带斜杠（`%2F`）。
            val isDir = hasCollection(response) || href.trim().endsWith("/")
            val name = WebDavPaths.nameFromHref(href)
            if (name.isEmpty()) continue
            // 弱/强这一位必须在规范化**之前**取：剥掉 `W/` 就再也认不出来了，而「条件上传能不能用」
            // 全看它——弱验证器拿去当 If-Match 是永久 412，理由写在 [WebDavEtags] 的注释里。
            val rawEtag = firstText(response, "getetag")
            result.add(
                RemoteEntry(
                    name = name,
                    etag = WebDavEtags.normalize(rawEtag),
                    lastModifiedMs = firstText(response, "getlastmodified")?.let(::parseHttpDate),
                    isDirectory = isDir,
                    etagWeak = WebDavEtags.isWeak(rawEtag)
                )
            )
        }
        return result
    }

    /**
     * 取 [scope] 下第一个**有内容**的同名元素文本。
     *
     * 不能只看 `item(0)`：一个 `<response>` 允许挂多个 `<propstat>`，服务器常把取到的属性放 200 那块、
     * 把取不到的放 404 那块（`<d:getetag/>` 空元素）。两块的先后顺序不保证，若 404 那块在前，
     * 取 item(0) 会拿到空串，于是「远端有 ETag」被误判成「服务器不给 ETag」，同步退化成只比内容哈希。
     */
    private fun firstText(scope: Element, localName: String): String? {
        val nodes = scope.getElementsByTagNameNS(DAV_NS, localName)
        for (i in 0 until nodes.length) {
            val text = nodes.item(i).textContent?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    private fun hasCollection(response: Element): Boolean =
        response.getElementsByTagNameNS(DAV_NS, "collection").length > 0

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
