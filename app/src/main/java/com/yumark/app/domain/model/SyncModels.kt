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
 * @param etag 远端版本标识，用于判断远端是否变化（可能为空）。
 * @param lastModifiedMs getlastmodified 解析为 epoch 毫秒（可能为空）。
 */
data class RemoteEntry(
    val name: String,
    val etag: String?,
    val lastModifiedMs: Long?,
    val isDirectory: Boolean
)

/** 一次同步的结果计数。 */
data class SyncOutcome(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val conflicts: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0
)
