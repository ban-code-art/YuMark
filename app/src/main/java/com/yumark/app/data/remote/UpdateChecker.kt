package com.yumark.app.data.remote

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.yumark.app.core.update.GITHUB_MIRROR_PREFIXES
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.domain.model.GitHubAsset
import com.yumark.app.domain.model.GitHubRelease
import com.yumark.app.domain.model.UpdateInfo
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.HttpHeaders
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
        // 必须显式设超时：Android 引擎不设时走 HttpURLConnection 的默认值（实测能挂到 100s 以上），
        // 而这是个启动后台自动跑的检查，挂那么久等于把一个协程和一条连接白占一分多钟。
        // 元数据只有几 KB，整体超时给得起（下载那边才不能设，见 ApkDownloader）。
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
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

            android.util.Log.d(TAG, "当前版本: $currentVersion")
            android.util.Log.d(TAG, "最新版本: $latestVersion (tag: ${release.tag_name})")
            android.util.Log.d(TAG, "资源数量: ${release.assets.size}")
            release.assets.forEach { asset ->
                android.util.Log.d(TAG, "资源: ${asset.name}, URL: ${asset.browser_download_url}")
            }

            // 比较版本号
            val isNewer = isNewerVersion(latestVersion, currentVersion)
            android.util.Log.d(TAG, "是否有新版本: $isNewer")

            if (isNewer) {
                // 找到 APK 资源
                val apkAsset = release.assets.firstOrNull {
                    it.name.endsWith(".apk", ignoreCase = true)
                }

                if (apkAsset != null) {
                    android.util.Log.d(TAG, "找到 APK: ${apkAsset.name}")
                    // 摘要两路来源：asset 自带的 digest 优先（随元数据到手，不用多发请求），
                    // 拿不到再看有没有 *.apk.sha256 旁 asset。两者都缺就是 null——
                    // 下载链路会退化成只校验包名/签名，但绝不因此判失败。
                    val digest = parseAssetDigest(apkAsset.digest)
                    val sidecarUrl = findSha256SidecarUrl(release.assets, apkAsset.name)
                    // 两个标签先算好再进模板：CJK 是合法的 Kotlin 标识符字符，
                    // 紧贴汉字的 $x 会被当成另一个变量名。
                    val digestLabel = digest ?: NONE
                    val sidecarLabel = sidecarUrl ?: NONE
                    android.util.Log.d(TAG, "摘要来源 digest=$digestLabel sidecar=$sidecarLabel")
                    UpdateInfo(
                        version = latestVersion,
                        versionCode = parseVersionCode(latestVersion),
                        changelog = release.body,
                        downloadUrl = apkAsset.browser_download_url,
                        fileSize = apkAsset.size,
                        publishDate = release.published_at,
                        sha256 = digest,
                        sha256Url = sidecarUrl
                    )
                } else {
                    android.util.Log.w(TAG, "未找到 APK 资源")
                    null
                }
            } else {
                android.util.Log.d(TAG, "已是最新版本")
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消不是"检查失败"：吞掉会让调用方把它当成"已是最新版本"（返回 null），
            // 同时破坏结构化并发——协程被取消后本该立即向上传播。
            throw e
        } catch (e: Exception) {
            // 不把 e.message / throwable 原样打进日志：Ktor 的异常消息带完整请求 URL，
            // 镜像源的 URL 里还可能带查询参数形式的令牌。safeDetail 会脱敏并截断。
            android.util.Log.e(TAG, "检查更新失败: ${ErrorHandler.safeDetail(e, MAX_ERROR_DETAIL_CHARS)}")
            null
        }
    }

    /**
     * 从 GitHub API 获取最新 Release。
     *
     * 按 [buildReleaseApiCandidates] 的顺序逐个试：某个源连不上、或回的不是我们要的 JSON，
     * 就换下一个；全失败时抛**最后一个真实原因**（已脱敏），而不是笼统一句「检查失败」。
     */
    private suspend fun fetchLatestRelease(): GitHubRelease {
        val apiUrl = "https://api.github.com/repos/$githubOwner/$githubRepo/releases/latest"
        val candidates = buildReleaseApiCandidates(apiUrl)
        var lastError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            try {
                return httpClient.get(candidate) {
                    // GitHub API 要求请求带 User-Agent（不带会被 403 挡掉）；Accept 显式锁 v3 JSON，
                    // 否则代理源回一段 HTML 错误页时，报错会炸在反序列化里，看不出是源的问题。
                    header(HttpHeaders.Accept, GITHUB_ACCEPT)
                    header(HttpHeaders.UserAgent, USER_AGENT)
                }.body()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w(
                    TAG,
                    "元数据源失败(${index + 1}/${candidates.size}): " +
                        ErrorHandler.safeDetail(e, MAX_ERROR_DETAIL_CHARS)
                )
            }
        }
        throw lastError ?: java.io.IOException("没有可用的更新检查地址")
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
        } catch (e: PackageManager.NameNotFoundException) {
            // 查自己的包名理论上不会走到这里；真发生了也只影响"当前版本"显示，不该让检查更新整体挂掉
            android.util.Log.w(TAG, "读取自身版本号失败", e)
            "1.0.0"
        }
    }

    fun close() {
        httpClient.close()
    }

    companion object {
        private const val TAG = "UpdateChecker"

        /** 连不上（例如被墙）多久算失败。 */
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** 整体上限。几 KB 的 JSON，超过 20s 只剩「挂着」这一种可能。 */
        private const val REQUEST_TIMEOUT_MS = 20_000L

        /** 「连续多久一个字节都没动」才算卡死。 */
        private const val SOCKET_TIMEOUT_MS = 20_000L

        /** 进日志的技术细节长度上限，与 ApkDownloader 一致。 */
        private const val MAX_ERROR_DETAIL_CHARS = 120

        /** 锁 v3 JSON 结构，避免代理源回 HTML 时报错炸在反序列化里。 */
        private const val GITHUB_ACCEPT = "application/vnd.github+json"

        /** GitHub API 不带 User-Agent 会被 403 挡掉；用固定串好在服务端日志里认出自己。 */
        private const val USER_AGENT = "YuMark-UpdateChecker"

        /** 日志里表示「这一路摘要来源没拿到」的占位串，只进 logcat，不给用户看。 */
        private const val NONE = "none"
    }
}

/**
 * 更新元数据的候选地址顺序：**直连优先**，镜像兜底。
 *
 * 和下载侧（[com.yumark.app.core.update.buildApkCandidateUrls]，镜像在前）刻意相反，两条理由：
 * 1. 被墙的是 release 下载 CDN；`api.github.com` 这个几 KB 的 JSON 在多数网络下直连是通的。
 *    把镜像排前面，等于每次开机自动检查都先白等一轮代理。
 * 2. 这些公益代理主要代理 `github.com` / `raw.githubusercontent.com`，对 `api.github.com`
 *    支持参差不齐，成不成事先不知道——只配当兜底，不配当首选。
 *
 * 去重且保序。纯函数，可单测（见 ReleaseApiCandidatesTest）。
 */
internal fun buildReleaseApiCandidates(
    apiUrl: String,
    mirrors: List<String> = GITHUB_MIRROR_PREFIXES
): List<String> {
    val direct = apiUrl.trim()
    if (direct.isEmpty()) return emptyList()
    return (listOf(direct) + mirrors.map { it + direct }).distinct()
}

/**
 * 版本号 → 数字段列表，容忍 `v` 前缀与 `-debug` / `-rc1` / `+build` 之类后缀。
 *
 * 为什么不能只 `split(".")` 再 `toIntOrNull() ?: 0`：debug 变体的 versionName 是 `0.9.1-debug`，
 * 第三段解析失败被算成 0，于是「已安装 0.9.1-debug」永远小于「线上 0.9.1」——每次启动都弹一次
 * 不存在的更新，点下去装的还是同一个版本。这里按分隔符切开后只认**每段开头的数字**，
 * 纯字母段（`debug` / `rc1`）整段丢弃，`0.9.1-debug` 与 `0.9.1` 于是等值。
 *
 * 代价：预发布标记不参与比较，`1.0.0-rc1` 与 `1.0.0` 视为同一版本。发布流程的 tag 不带预发布
 * 后缀，这条不影响真实链路。
 */
internal fun parseVersionParts(version: String): List<Int> =
    version.trim().removePrefix("v").removePrefix("V")
        .split('.', '-', '+', '_', ' ')
        .mapNotNull { segment -> LEADING_DIGITS.find(segment)?.value?.toIntOrNull() }

/**
 * `newVersion > currentVersion`？逐段比较，缺的段按 0 补（`1.2` 与 `1.2.0` 等值）。
 *
 * 一个数字段都解析不出来时返回 false：宁可漏报一次更新，也不能因为一个畸形 tag 天天弹窗。
 */
internal fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
    val newParts = parseVersionParts(newVersion)
    if (newParts.isEmpty()) return false
    val currentParts = parseVersionParts(currentVersion)
    for (i in 0 until maxOf(newParts.size, currentParts.size)) {
        val newPart = newParts.getOrElse(i) { 0 }
        val currentPart = currentParts.getOrElse(i) { 0 }
        if (newPart != currentPart) return newPart > currentPart
    }
    return false
}

/** 版本字符串 → 可比较整数，例如 `1.2.3` → 10203。只取前三段。 */
internal fun parseVersionCode(version: String): Int {
    val parts = parseVersionParts(version)
    if (parts.isEmpty()) return 0
    return parts[0] * 10000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}

/** 每段开头的连续数字。`rc1` 匹配不到（数字不在开头），于是整段被丢掉。 */
private val LEADING_DIGITS = Regex("^\\d+")

/**
 * 从 asset 列表里挑出与 APK 同名的校验和旁文件，返回它的**下载地址**（不读内容）。
 *
 * 认两种命名，按优先级：
 * 1. `YuMark-v1.0.apk.sha256`（`sha256sum` 输出重定向到「原名 + .sha256」，最常见）；
 * 2. `YuMark-v1.0.sha256`（把扩展名换掉的写法）。
 *
 * 刻意**不**去匹配任何 `*.sha256`：一个 release 里可能同时挂着别的产物的校验和
 * （源码包、mapping 文件），拿错一个的后果是摘要必然不一致、升级被自己拦下来。
 *
 * 名字比较忽略大小写（GitHub 的 asset 名大小写敏感，但发布脚本换过手就可能变），
 * `browser_download_url` 为空的 asset 直接跳过——空地址拼进请求只会白等一轮超时。
 *
 * 纯函数，可单测（见 Sha256AssetTest）。
 */
internal fun findSha256SidecarUrl(assets: List<GitHubAsset>, apkName: String): String? {
    val apk = apkName.trim()
    if (apk.isEmpty()) return null
    val base = if (apk.endsWith(APK_EXT, ignoreCase = true)) apk.dropLast(APK_EXT.length) else apk
    for (wanted in listOf(apk + SHA256_EXT, base + SHA256_EXT)) {
        val hit = assets.firstOrNull {
            it.name.trim().equals(wanted, ignoreCase = true) && it.browser_download_url.isNotBlank()
        }
        if (hit != null) return hit.browser_download_url.trim()
    }
    return null
}

/**
 * asset JSON 的 `digest` 字段 → 64 位小写十六进制；不是 SHA-256 就返回 null。
 *
 * 三条判定，每条都是「宁可没有摘要，也不要错的摘要」：
 * - 带算法前缀时只认 `sha256:` / `sha-256:`，`sha512:` 之类一律拒绝（把 SHA-512 当 SHA-256
 *   去比，结果是每次升级都报「摘要不一致」，用户永远装不上）；
 * - 没有前缀的裸十六进制照收，格式对得上就能用；
 * - 长度/字符不合法（截断、写成 MD5）也返回 null，交给下游走无摘要路径。
 *
 * 纯函数，可单测（见 Sha256AssetTest）。
 */
internal fun parseAssetDigest(digest: String?): String? {
    val raw = digest?.trim()?.lowercase().orEmpty()
    if (raw.isEmpty()) return null
    val hex = if (raw.contains(':')) {
        val algorithm = raw.substringBefore(':').trim()
        if (algorithm != "sha256" && algorithm != "sha-256") return null
        raw.substringAfter(':').trim()
    } else {
        raw
    }
    return if (hex.matches(SHA256_HEX)) hex else null
}

/** APK 与校验和旁文件的扩展名，只服务于 [findSha256SidecarUrl] 的命名匹配。 */
private const val APK_EXT = ".apk"
private const val SHA256_EXT = ".sha256"

/** 整串必须是 64 位小写十六进制；已 lowercase 过，所以不收大写。 */
private val SHA256_HEX = Regex("[0-9a-f]{64}")
