package com.yumark.app.domain.model

/**
 * 应用更新信息
 */
data class UpdateInfo(
    val version: String,           // 版本号，如 "1.2.0"
    val versionCode: Int,          // 版本代码
    val changelog: String,         // 更新日志 (Markdown 格式)
    val downloadUrl: String,       // APK 下载链接
    val fileSize: Long,            // 文件大小 (bytes)
    val publishDate: String,       // 发布日期
    val isForceUpdate: Boolean = false  // 是否强制更新
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
    val browser_download_url: String     // 下载链接
)
