package com.yumark.app.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * APK 下载状态
 */
sealed class DownloadState {

    object Idle : DownloadState()
    data class Downloading(val progress: Int) : DownloadState()
    data class Success(val filePath: String) : DownloadState()
    data class Failed(val error: String) : DownloadState()
}

/**
 * APK 下载管理器
 *
 * 直接用 Ktor 流式下载到 App 外部私有目录（getExternalFilesDir），
 * 不走系统 DownloadManager —— 避免模拟器/跳转链接下卡在 PENDING、
 * 以及之前重复下载留下的队列积压问题。下载目录被 file_paths.xml 的
 * external-files-path 覆盖，安装时 FileProvider 可正常授权。
 */
class ApkDownloader(private val context: Context) {

    private val client = HttpClient(Android) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000   // 连接超时：连不上（如被墙）15 秒后失败
            socketTimeoutMillis = 20_000    // 读取超时：每次有数据会重置，仅在长时间无数据时触发
            // 不设 requestTimeoutMillis：大文件慢速下载只要持续有数据就不应整体超时
        }
    }

    /**
     * 下载 APK 文件
     * @param url 原始下载链接（GitHub release 资源地址）
     * @param version 版本号，用于文件命名
     * @return 下载状态 Flow
     *
     * 国内直连 GitHub 下载 CDN 常常超时，这里按顺序尝试多个加速镜像，
     * 某个连不上/返回非 APK 就自动换下一个，最后再回退直连。
     */
    fun download(url: String, version: String): Flow<DownloadState> = callbackFlow {
        trySend(DownloadState.Idle)

        val outFile = File(context.getExternalFilesDir(null), "YuMark-v$version.apk")
        val candidates = buildCandidateUrls(url)

        val job = launch(Dispatchers.IO) {
            var lastError: String = "下载失败"
            try {
                for ((index, candidate) in candidates.withIndex()) {
                    runCatching { if (outFile.exists()) outFile.delete() }
                    android.util.Log.d("ApkDownloader", "尝试下载源 ${index + 1}/${candidates.size}: $candidate")
                    try {
                        fetchToFile(candidate, outFile) { progress ->
                            trySend(DownloadState.Downloading(progress))
                        }
                        android.util.Log.d("ApkDownloader", "下载完成: ${outFile.absolutePath} (${outFile.length()} bytes)")
                        trySend(DownloadState.Success(Uri.fromFile(outFile).toString()))
                        return@launch
                    } catch (e: Exception) {
                        lastError = e.message ?: "下载失败"
                        android.util.Log.w("ApkDownloader", "下载源失败(${index + 1}): $candidate -> $lastError")
                        runCatching { if (outFile.exists()) outFile.delete() }
                        // 继续尝试下一个源
                    }
                }
                // 所有源都失败
                android.util.Log.e("ApkDownloader", "所有下载源均失败，最后错误: $lastError")
                trySend(DownloadState.Failed("下载失败：所有下载源都连不上，请检查网络后重试"))
            } finally {
                close() // 终态：关闭 Flow，停止收集
            }
        }

        awaitClose { job.cancel() }
    }

    /**
     * 实际下载到文件；失败（连接异常、非 2xx、内容不完整）时抛异常，由上层决定是否换源。
     */
    private suspend fun fetchToFile(url: String, outFile: File, onProgress: (Int) -> Unit) {
        client.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) {
                throw java.io.IOException("HTTP ${response.status.value}")
            }
            val total = response.contentLength() ?: -1L
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            var lastProgress = -1

            outFile.outputStream().use { out ->
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        copied += read
                        val progress = if (total > 0) ((copied * 100) / total).toInt() else 0
                        if (progress != lastProgress) {
                            lastProgress = progress
                            android.util.Log.d("ApkDownloader", "下载进度: $progress% ($copied/$total)")
                            onProgress(progress)
                        }
                    }
                }
                out.flush()
            }

            // 校验：内容长度已知时必须下满；否则视为代理返回了错误页/不完整内容
            if (total > 0 && copied != total) {
                throw java.io.IOException("下载不完整: $copied/$total")
            }
            if (copied < 100_000) {
                throw java.io.IOException("文件过小($copied bytes)，疑似非 APK 内容")
            }
        }
    }

    /**
     * 构造候选下载地址：先走加速镜像，最后回退直连（用户自带代理时可用）。
     */
    private fun buildCandidateUrls(url: String): List<String> {
        return GITHUB_MIRRORS.map { prefix -> prefix + url } + url
    }

    /**
     * 安装 APK
     */
    fun installApk(filePath: String) {
        android.util.Log.d("ApkDownloader", "准备安装 APK: $filePath")


        try {
            // filePath 形如 "file:///storage/emulated/0/Android/data/.../YuMark-v0.5.4.apk"
            val fileUri = filePath.toUri()
            val file = File(fileUri.path ?: return)

            android.util.Log.d("ApkDownloader", "文件路径: ${file.absolutePath}")
            android.util.Log.d("ApkDownloader", "文件存在: ${file.exists()}")

            if (!file.exists()) {
                android.util.Log.e("ApkDownloader", "文件不存在: ${file.absolutePath}")
                return
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            android.util.Log.d("ApkDownloader", "FileProvider URI: $contentUri")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                setDataAndType(contentUri, "application/vnd.android.package-archive")
            }

            context.startActivity(intent)
            android.util.Log.d("ApkDownloader", "启动安装界面成功")
        } catch (e: Exception) {
            android.util.Log.e("ApkDownloader", "安装失败", e)
        }
    }

    companion object {
        /**
         * GitHub 加速镜像前缀（直接拼在完整 GitHub URL 前面）。
         * 这类公益代理可能失效/限速，按顺序尝试，全失败再回退直连。
         * 如需更新可维护此列表。
         */
        private val GITHUB_MIRRORS = listOf(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://mirror.ghproxy.com/"
        )
    }
}

