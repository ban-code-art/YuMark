package com.yumark.app.core.export

import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * 导出侧的图片解析与内联（纯逻辑，无 Android 依赖，可在 JVM 单测里跑）。
 *
 * 导出与预览共用 `renderer.html` + `renderer.js`，但**只有预览调过 `setImageResolver`**：
 * 离屏导出的 baseURL 是 `file:///android_asset/`，正文里 `images/x.png` 这种相对引用会被解析到
 * assets 里，必然 404——于是导入库/外部工作区文档导出的 PDF、长图、HTML 全都缺图。
 */

/**
 * 相对图片引用的解析基址，字段与 `renderer.js` 的 `resolveImages()` 一一对应：
 * 最终 `src = prefix + encode(join(base, 相对引用))`。
 *
 * 与 presentation 层的 `ImageResolverConfig` 同形而不复用：core 不能反向依赖 presentation，
 * 调用方做一次字段搬运即可。
 *
 * @param encodeAll true = 整体 `encodeURIComponent`（SAF docId），false = 逐段编码（文件路径）
 * @param appPrefix 应用自管图片目录（`images/`）的前缀，`renderer.js` 里比 [prefix] 优先匹配。
 *   工具栏「从相册选择」存下来的图落在这里，与文档来自哪个目录无关；null = 本次导出不解析它们。
 */
data class ExportImageResolver(
    val prefix: String,
    val base: String,
    val encodeAll: Boolean,
    val appPrefix: String? = null
) {
    /**
     * 序列化成注入 `window.setImageResolver(...)` 的 JSON 实参。
     *
     * 手拼但用 [JsonPrimitive] 出字符串字面量：prefix 里是 `file://`/`content://` URI，
     * base 是用户起的文件夹名，可能含引号、反斜杠、换行——手写引号拼接会拼出语法错误的 JS，
     * 那时 setImageResolver 整句注入失败，表现是"图还是不出来"，且没有任何报错。
     *
     * [appPrefix] 为 null 时整个键不出现，而不是写 `"appPrefix":null`：JS 侧两种都判假，
     * 但省掉它让「没有应用图片要解析」的旧行为在字节层面与从前完全一致。
     */
    internal fun toJson(): String = buildString {
        append("{\"prefix\":").append(JsonPrimitive(prefix))
        append(",\"base\":").append(JsonPrimitive(base))
        append(",\"encodeAll\":").append(encodeAll)
        if (appPrefix != null) append(",\"appPrefix\":").append(JsonPrimitive(appPrefix))
        append("}")
    }
}

/**
 * 把 HTML 里的本地图片（`file://` / `content://` / 裸绝对路径）换成 base64 data URI。
 *
 * 为什么不用 [androidx.webkit.WebViewAssetLoader]：它要求页面挂在
 * `https://appassets.androidplatform.net` origin 下，而模板的 CSP 是 `script-src file:`、
 * 所有 `<script src>` 都是 `file:///android_asset/`，换 origin 等于把预览和导出两条路
 * 一起打断；data URI 只改 `src` 属性，不动加载 origin，也顺带让导出的 HTML 自包含——
 * 导出件是要发给别人的，应用私有目录和 SAF 授权都不跟着文件走。
 *
 * @param loadBytes 读字节的注入点（返回 null = 读不到/超限，保留原 URI 而不是让整次导出失败）
 */
internal fun inlineLocalImages(html: String, loadBytes: (String) -> ByteArray?): String =
    rewriteImageSrc(html) { raw -> inlineOne(raw, loadBytes) }

/**
 * 遍历 HTML 里每个 `<img>` 的 `src`，用 [transform] 换掉；返回 null 表示这一张保持原值。
 *
 * 用正则而不是拉一个 HTML 解析器进来：输入以 commonmark / DOMPurify 生成的规范 HTML 为主，
 * 唯一的变数是正文里手写的原生 HTML（`escapeHtml=false` 会原样透出），而它带来的差异只有
 * 「属性值可能不带引号」这一种，[SRC_ATTR_REGEX] 已经覆盖；多一个解析器就多一份体积和
 * 一套要跟着升级的行为差异。
 *
 * 这一层被两个用途共用（解析相对路径 → 绝对 URL，以及绝对 URL → base64 内联），两者是
 * 先后串起来的两遍替换。合成一遍会让「解析出来的 URL 读不到字节」这种情况失去中间状态，
 * 排查时看不出是基址错了还是文件不在。
 */
internal fun rewriteImageSrc(html: String, transform: (String) -> String?): String =
    IMG_TAG_REGEX.replace(html) { tag ->
        SRC_ATTR_REGEX.replace(tag.value) { m ->
            val quoted = m.groupValues[2]
            // 原本不带引号的一律补成双引号：换进去的 data URI 含 `,` `;` `=`，裸值形态下
            // 后面再挂一个属性就会被解析成 src 的一部分。补引号是无损的。
            val quote = quoted.ifEmpty { "\"" }
            val raw = if (quoted.isEmpty()) m.groupValues[4] else m.groupValues[3]
            m.groupValues[1] + quote + (transform(raw) ?: raw) + quote
        }
    }

/**
 * 单张：能内联返回 data URI，否则 null（调用方保留原值）。
 *
 * MIME 由**字节**判定（[sniffInlineImageMime]），不由扩展名。两条理由：
 * - 认不出的字节一律不内联。正文里的引用不全是应用自己写的（导入的 `.md`、WebDAV 同步下来的
 *   正文、AI Agent 改写出的正文都能带任意 `![](…)`），一个改名成 `.png` 的数据库文件从前会被
 *   照着扩展名声明成 `image/png` 内联进导出件，随 `ACTION_SEND` 一起发出去；
 * - 就算是真图片，扩展名也常常在说谎（微信下载的 `.jpg` 里躺着 WebP 字节）。谎报类型的
 *   data URI 在浏览器里是一张损坏图，字节判定顺手把这一类修好了。
 *
 * 与 [DocxExporter] 那侧同一套判定顺序（`sniffImageInfo(bytes) ?: return null`）——
 * 「导出件里能出现哪些图片」这件事有两条路，两条路必须用同一把尺。
 */
private fun inlineOne(rawSrc: String, loadBytes: (String) -> ByteArray?): String? {
    // 属性值是 HTML 转义过的：content:// URI 的查询串里 `&` 会是 `&amp;`，
    // 不还原就会拿着一个不存在的 URI 去 ContentResolver 里查。
    val url = rawSrc.replace("&amp;", "&").trim()
    if (!isInlinableLocalUrl(url)) return null
    val bytes = loadBytes(url) ?: return null
    if (bytes.isEmpty()) return null
    val mime = sniffInlineImageMime(bytes) ?: return null
    return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
}

/** 只内联本地来源；`data:` 已自包含，`http(s):` 保留网络地址（内联会把体积翻倍且不一定读得到）。 */
internal fun isInlinableLocalUrl(url: String): Boolean {
    val u = url.lowercase()
    return u.startsWith("file://") || u.startsWith("content://") || url.startsWith("/")
}

private val IMG_TAG_REGEX = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)

/**
 * `src` 属性。三种写法都要收：双引号、单引号、**不带引号**。
 *
 * 不带引号那一支不是洁癖：commonmark 的 `HtmlRenderer` 默认 `escapeHtml=false`（见
 * [HtmlExporter.buildHtml] 里模板 `<head>` 那段注释），用户在正文里手写的
 * `<img src=images/a.png>` 会**原样透进**渲染结果。只认带引号的话这一张既不会被解析成绝对
 * 路径、也不会被内联成 data URI，导出的 .html 里就是一张裂图，而且没有任何报错。
 */
private val SRC_ATTR_REGEX = Regex(
    """(\ssrc\s*=\s*)(?:(["'])(.*?)\2|([^\s"'=<>`]+))""",
    RegexOption.IGNORE_CASE
)
