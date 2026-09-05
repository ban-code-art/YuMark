package com.yumark.app.data.remote.webdav

/**
 * ETag 的规范化，以及「这是不是**弱验证器**」这一位。
 *
 * 两件事必须待在同一处：
 *
 * - **规范化要跨通道一致。** 远端版本标记有两个来源——PROPFIND 的 `getetag`（[WebDavXml]）与 PUT
 *   响应头（[WebDavClient] 里的 `etagOf`）——而同步判「远端变了没」只比这两个字符串是否相等。
 *   两边剥法稍有差池，同一版内容就会算出两个不同的标记，于是每次上传之后都被判成「远端变了」：
 *   本地没改就白下载一遍，本地恰好也改过就**凭空多出一份「冲突副本」文档**，而两边内容一模一样。
 *   从前两边各写了一份 `removePrefix("W/")…`，正是等着某天分叉的形状。
 *
 * - **弱/强必须在剥掉 `W/` 之前记下来**，剥完就再也认不出来了。而这一位决定条件请求能不能用：
 *   RFC 9110 §13.1.1 规定 `If-Match` 用**强比较**，§8.8.3.2 又规定强比较要求两个标签都不是弱的
 *   ——弱标签在强比较下连自己都不等于自己。把弱标签（哪怕剥成 `abc` 冒充强的）拿去当 `If-Match`，
 *   服务器只能回 412，而 412 是**刻意不退化**的（见 [WebDavClient.upload]）：那台服务器上凡是远端
 *   已经存在的文档，上传从此**永久失败**，每一轮同步都失败，用户看到的只有一句「失败 N 篇」。
 *   弱 ETag 一点也不罕见：nginx 及各类反向代理一开 gzip 就会把强 ETag 改成弱的。
 *
 * 纯字符串处理，不碰 Android，可直接单测。
 */
internal object WebDavEtags {

    /**
     * 弱验证器前缀。RFC 9110 写的是大写 `W/`，这里的判断放宽到大小写不敏感：
     * 两个方向的代价不对称——把 `w/"abc"` 误当成弱的，最多是少用一次条件请求（退化成无条件 PUT，
     * 也就是没有 ETag 的服务器上一直在走的那条路）；反过来把它当成强的，就是上面那条「永久 412」。
     */
    private const val WEAK_PREFIX = "W/"

    /** 是不是弱验证器。必须在 [normalize] **之前**问，剥完 `W/` 就问不出来了。 */
    fun isWeak(raw: String?): Boolean =
        raw != null && raw.trimStart().startsWith(WEAK_PREFIX, ignoreCase = true)

    /**
     * 剥成不透明的实体部分：去掉弱标记与包裹引号。
     *
     * 它只作为不透明字符串参与相等比较，所以「剥掉了什么」不重要，「两条通道剥得一样」才重要。
     *
     * 空白与空串（`W/""` 也算）返回 null，让下游一律按「服务器没给 ETag」处理。返回空串会更糟：
     * 空串是非空引用，`canTrustPutEtag` 会当它是个能用的基线存进 `sync_state.remote_etag`，而下一轮
     * 比较侧的 [com.yumark.app.data.repository.remoteVersionTag] 见空串就退到 `mtime:…`，
     * 两个字符串必然不等 → 每轮都判「远端变了」。返回 null 则会进 `pendingBaseline`，
     * 由 `refreshMissingBaselines` 从 PROPFIND 补一个真标记回来。
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        val body = if (trimmed.startsWith(WEAK_PREFIX, ignoreCase = true)) {
            trimmed.substring(WEAK_PREFIX.length)
        } else {
            trimmed
        }
        return body.trim().trim('"').takeIf { it.isNotBlank() }
    }
}
