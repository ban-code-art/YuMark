package com.yumark.app.core.update

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

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
 */
class ApkDownloader(private val context: Context) {

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /**
     * 下载 APK 文件
     * @param url 下载链接
     * @param version 版本号，用于文件命名
     * @return 下载状态 Flow
     */
    fun download(url: String, version: String): Flow<DownloadState> = callbackFlow {
        trySend(DownloadState.Idle)

        val fileName = "YuMark-v$version.apk"
        val request = DownloadManager.Request(url.toUri()).apply {
            setTitle("YuMark 更新")
            setDescription("正在下载 v$version")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
        }

        val downloadId = downloadManager.enqueue(request)

        // 监听下载完成
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)

                    if (cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = cursor.getInt(statusIndex)

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                val uri = cursor.getString(uriIndex)
                                trySend(DownloadState.Success(uri ?: ""))
                            }
                            DownloadManager.STATUS_FAILED -> {
                                trySend(DownloadState.Failed("下载失败"))
                            }
                        }
                    }
                    cursor.close()
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        // 轮询下载进度
        val progressJob = launch {
            while (true) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)

                if (cursor.moveToFirst()) {
                    val bytesIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                    val bytes = cursor.getLong(bytesIndex)
                    val total = cursor.getLong(totalIndex)

                    if (total > 0) {
                        val progress = ((bytes * 100) / total).toInt()
                        trySend(DownloadState.Downloading(progress))
                    }
                }
                cursor.close()
                delay(500.milliseconds)
            }
        }

        awaitClose {
            progressJob.cancel()
            context.unregisterReceiver(receiver)
        }
    }

    /**
     * 安装 APK
     */
    fun installApk(filePath: String) {
        val file = File(filePath.toUri().path ?: return)
        if (!file.exists()) return

        val intent = Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            setDataAndType(uri, "application/vnd.android.package-archive")
        }

        context.startActivity(intent)
    }
}
