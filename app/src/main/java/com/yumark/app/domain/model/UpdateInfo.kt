package com.yumark.app.domain.model

/**
 * 应用更新信息
 *
 * [sha256] 与 [sha256Url] 是摘要校验的**唯一数据来源**：两者都缺时下载链路只剩包名/签名校验
 * （见 ApkDownloader.verifyDownloadedApk）。缺失不算失败——发布流程还没稳定产出校验和时，
 * 因为「拿不到摘要」就拒绝升级只会把用户卡在旧版本上。
 */
data class UpdateInfo(
    val version: String,           // 版本号，如 "1.2.0"
    val versionCode: Int,          // 版本代码
    val changelog: String,         // 更新日志 (Markdown 格式)
    val downloadUrl: String,       // APK 下载链接
    val fileSize: Long,            // 文件大小 (bytes)
    val publishDate: String,       // 发布日期
    val isForceUpdate: Boolean = false,  // 是否强制更新
    /**
     * 期望的 SHA-256（小写十六进制 64 位），来自 release asset JSON 的 `digest` 字段。
     * 优先于 [sha256Url]：它随元数据一起到手，下载完不必再发一次请求。
     */
    val sha256: String? = null,
    /**
     * `*.apk.sha256` 旁 asset 的**下载地址**（不是内容）。
     * 只在 [sha256] 缺失时才会被真正请求，取不到就退回「APK 地址拼 .sha256」的猜法。
     */
    val sha256Url: String? = null
)

/**
 * GitHub Release API 响应
 */
@kotlinx.serialization.Serializable
data class GitHubRelease(
    val tag_name: String,          // "v1.2.0"
    val name: String,              // 版本标题
    val body: String,              // 更新日志
    val published_at: String,      // 发布时间
    val assets: List<GitHubAsset>
)

@kotlinx.serialization.Serializable
data class GitHubAsset(
    val name: String,                    // "YuMark-v1.2.0.apk"
    val size: Long,                      // 文件大小
    val browser_download_url: String,    // 下载链接
    /**
     * GitHub 较新的 release API 会给出 `"digest": "sha256:abcd..."`。
     *
     * **必须有默认值 null**：老的响应（以及公益代理缓存下来的旧 JSON）里根本没有这个键，
     * 没默认值会让整条更新检查在反序列化阶段直接失败。解析见
     * [com.yumark.app.data.remote.parseAssetDigest]。
     */
    val digest: String? = null
)
