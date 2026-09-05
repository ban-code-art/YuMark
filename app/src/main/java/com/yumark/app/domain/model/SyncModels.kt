package com.yumark.app.domain.model

/**
 * WebDAV 同步配置。
 * - [password] 经加密存储（security-crypto），其余非敏感项走 DataStore。
 * - [remoteDir] 是相对 [baseUrl] 的同步子目录（库内所有根级文档同步到此目录下）。
 */
data class WebDavConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val remoteDir: String = "YuMark"
) {
    /** 连接所需字段是否齐全。 */
    val isValid: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

/**
 * 远端目录项（PROPFIND 解析结果）。
 * @param name 文件名（含扩展名），由 href 末段解码得到。
 * @param etag 远端版本标识，用于判断远端是否变化（可能为空）。已剥掉弱标记与包裹引号，
 *   只作为不透明字符串参与相等比较。
 * @param lastModifiedMs getlastmodified 解析为 epoch 毫秒（可能为空）。
 * @param etagWeak [etag] 原本是不是**弱验证器**（`W/"…"`）。剥完就认不出来了，所以单独带出来：
 *   弱验证器不能拿去当 `If-Match`（RFC 9110 的强比较下它连自己都不等于自己 → 永久 412），
 *   判「远端变了没」却照样能用。取值与用法见
 *   [com.yumark.app.data.remote.webdav.WebDavEtags] 与 `SyncRepositoryImpl.ifMatchEtagOf`。
 */
data class RemoteEntry(
    val name: String,
    val etag: String?,
    val lastModifiedMs: Long?,
    val isDirectory: Boolean,
    val etagWeak: Boolean = false
)

/**
 * 一次同步的结果计数。
 *
 * [deleted] 把两个方向的删除合成一个数：本地删除推到远端、远端删除落到本地，
 * 拆成两个计数对用户没有信息量，而「这次同步删掉了几篇」是必须看得见的——
 * 折进 [uploaded]/[downloaded] 会让「↑3」在用户明明只删了三篇时显得像是传了三篇内容。
 */
data class SyncOutcome(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deleted: Int = 0,
    val conflicts: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0
)
