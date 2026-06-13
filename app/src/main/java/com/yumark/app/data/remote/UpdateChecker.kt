package com.yumark.app.data.remote

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.yumark.app.domain.model.GitHubRelease
import com.yumark.app.domain.model.UpdateInfo
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用更新检查器
 * 通过 GitHub Releases API 检查更新
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val context: Context
) {
    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    // GitHub 仓库信息
    private val githubOwner = "ban-code-art"
    private val githubRepo = "YuMark"

    /**
     * 检查更新
     * @return 如果有新版本返回 UpdateInfo，否则返回 null
     */
    suspend fun checkUpdate(): UpdateInfo? {
        return try {
            val release = fetchLatestRelease()
            val latestVersion = release.tag_name.removePrefix("v")
            val currentVersion = getCurrentVersion()

            // 比较版本号
            if (isNewerVersion(latestVersion, currentVersion)) {
                // 找到 APK 资源
                val apkAsset = release.assets.firstOrNull {
                    it.name.endsWith(".apk", ignoreCase = true)
                }

                if (apkAsset != null) {
                    UpdateInfo(
                        version = latestVersion,
                        versionCode = parseVersionCode(latestVersion),
                        changelog = release.body,
                        downloadUrl = apkAsset.browser_download_url,
                        fileSize = apkAsset.size,
                        publishDate = release.published_at
                    )
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("UpdateChecker", "检查更新失败", e)
            null
        }
    }

    /**
     * 从 GitHub API 获取最新 Release
     */
    private suspend fun fetchLatestRelease(): GitHubRelease {
        val url = "https://api.github.com/repos/$githubOwner/$githubRepo/releases/latest"
        return httpClient.get(url).body()
    }

    /**
     * 获取当前应用版本
     */
    private fun getCurrentVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * 比较版本号
     * @return 如果 newVersion > currentVersion 返回 true
     */
    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        val newParts = newVersion.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLength = maxOf(newParts.size, currentParts.size)
        for (i in 0 until maxLength) {
            val newPart = newParts.getOrNull(i) ?: 0
            val currentPart = currentParts.getOrNull(i) ?: 0

            if (newPart > currentPart) return true
            if (newPart < currentPart) return false
        }
        return false
    }

    /**
     * 从版本字符串解析版本代码
     * 例如 "1.2.3" -> 10203
     */
    private fun parseVersionCode(version: String): Int {
        val parts = version.split(".").map { it.toIntOrNull() ?: 0 }
        return parts.getOrNull(0)?.let { major ->
            val minor = parts.getOrNull(1) ?: 0
            val patch = parts.getOrNull(2) ?: 0
            major * 10000 + minor * 100 + patch
        } ?: 0
    }

    fun close() {
        httpClient.close()
    }
}
