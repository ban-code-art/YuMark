package com.yumark.app.data.remote.webdav

import java.io.ByteArrayOutputStream

/**
 * WebDAV 请求路径的百分号编解码。
 *
 * 为什么不用 Ktor 的 `String.encodeURLPath()`：它按 RFC 3986 的 **path** 规则放行 sub-delims，
 * `+` 与 `/` 都在白名单里，形如 `%20` 的既有转义也原样保留。对**单个路径段**这三条都是错的：
 * - `+`：一部分服务器按 form-urlencoded 语义解成空格，`a+b.md` 于是变成 `a b.md`；
 * - `/`：段内斜杠会凭空多出一层目录；
 * - `%`：文件名里真实存在的 `%20` 会被服务器解成空格，PUT 与 GET 从此指向不同的文件。
 * 这里只放行 RFC 3986 的 unreserved 集合（`A-Za-z0-9-._~`），其余一律转义——多编码永远安全，
 * 少编码才会指错文件。
 *
 * 解码侧同样不能用 `URLDecoder.decode(href, "UTF-8")`：那是 form-urlencoded 语义，`+` 会变成空格，
 * 于是远端已有的 `a+b.md` 永远匹配不上本地的 `a+b.md`，每次同步都重复上传一份并新建一篇本地文档。
 *
 * 纯字符串逻辑，独立成 object 是为了能在 JVM 单测里跑（见 WebDavPathsTest）。
 */
internal object WebDavPaths {

    private const val HEX = "0123456789ABCDEF"

    /** 编码单个路径段：unreserved 之外逐 UTF-8 字节转成大写 `%XX`。 */
    fun encodeSegment(segment: String): String {
        val sb = StringBuilder(segment.length + 8)
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            if (v < 0x80 && isUnreserved(Char(v))) sb.append(Char(v))
            else sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 逐段编码一条相对路径，段间 `/` 保留。
     *
     * 空段直接丢掉：用户把同步目录填成 `/YuMark//子目录/` 是常态，拼进 URL 前顺手规整掉，
     * 免得服务器收到 `//` 时各自发挥。
     */
    fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }

    /**
     * 解码一个路径段：只认 `%XX`，`+` 保持字面量。
     *
     * 非法转义（`%zz`、结尾被截断的 `%2`）原样保留而不抛异常——href 是服务器给的外部输入，
     * 一个畸形转义不该让整次 PROPFIND 解析失败。
     */
    fun decodeSegment(encoded: String): String {
        if ('%' !in encoded) return encoded
        val out = ByteArrayOutputStream(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            val hi = if (c == '%' && i + 2 < encoded.length) hexDigit(encoded[i + 1]) else -1
            val lo = if (hi >= 0) hexDigit(encoded[i + 2]) else -1
            if (lo >= 0) {
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                // 未编码的非 ASCII 也走这条路：确有服务器直接在 href 里回中文原文。
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * 从 PROPFIND 的 `<href>` 取出条目名。
     *
     * href 可能是完整 URL（`https://host/dav/YuMark/a.md`），也可能只是绝对路径（`/dav/YuMark/a.md`），
     * 目录还会带尾斜杠。**先切末段再解码**：顺序反了的话名字里的 `%2F` 会解出一个假的段边界，
     * `a%2Fb.md` 就被当成目录 a 下的 b.md。
     */
    fun nameFromHref(href: String): String {
        val trimmed = href.trim()
        val schemeAt = trimmed.indexOf("://")
        val path =
            if (schemeAt >= 0) trimmed.substring(schemeAt + 3).substringAfter('/', "") else trimmed
        return decodeSegment(path.trimEnd('/').substringAfterLast('/'))
    }

    private fun isUnreserved(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            c == '-' || c == '.' || c == '_' || c == '~'

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
