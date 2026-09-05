package com.yumark.app.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.yumark.app.R
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.UiMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * APK 下载状态
 *
 * [Failed.error] 是 [UiMessage] 而不是 String：这条链上的失败原因一半来自
 * [ApkVerdict]（校验结论）、一半来自网络异常，前者必须随语区翻译，而产出它的
 * [ApkDownloader] 只拿得到 Context 却进不了组合期。统一成 UiMessage 后由界面层
 * 用 `resolve()` 一次解析（见 presentation/common/UiMessageResolve.kt）。
 */
sealed class DownloadState {

    object Idle : DownloadState()
    data class Downloading(val progress: Int) : DownloadState()
    data class Success(val filePath: String) : DownloadState()
    data class Failed(val error: UiMessage) : DownloadState()
}

/**
 * APK 下载管理器
 *
 * 直接用 Ktor 流式下载到 App 外部私有目录（getExternalFilesDir），
 * 不走系统 DownloadManager —— 避免模拟器/跳转链接下卡在 PENDING、
 * 以及之前重复下载留下的队列积压问题。落地在 [UPDATES_DIR] 专用子目录而非
 * getExternalFilesDir 根下，好让 file_paths.xml 只把这一个子目录声明成
 * FileProvider 可授权根，安装时够用、又不把整个外部私有目录暴露成可授权范围。
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
     * @param expectedSha256 期望的 SHA-256（十六进制）。来自 release asset 的 `digest` 字段
     *   （见 UpdateInfo.sha256）。为 null 时才去取 `.sha256` 旁文件，取不到就只做包名/签名校验，
     *   见 [verifyDownloadedApk]
     * @param sidecarUrl 校验和旁文件的地址（见 UpdateInfo.sha256Url）。为 null 时退回
     *   「APK 地址拼 .sha256」的猜法，见 [buildSidecarCandidateUrls]
     * @return 下载状态 Flow
     *
     * 候选源顺序见 [buildApkCandidateUrls]：某个源连不上/返回的不是我们的包，就换下一个，
     * 全失败时报**最后一个真实原因**，而不是笼统一句「都连不上」——「签名不一致」和「网络不通」
     * 要用户做的事完全不同。
     */
    fun download(
        url: String,
        version: String,
        expectedSha256: String? = null,
        sidecarUrl: String? = null
    ): Flow<DownloadState> = callbackFlow {
        trySend(DownloadState.Idle)

        // getExternalFilesDir(dir) 会顺手建目录；dir 名必须与 file_paths.xml 的
        // external-files-path 保持一致，否则安装时 getUriForFile 找不到 root。
        // 返回 null = 外部存储不可用（未挂载/被弹出），交给下面的 job 统一报错：
        // 此处若照旧 File(null, name)，会退化成相对路径写进程工作目录、必然 EACCES，
        // 再被换源循环吞掉，最终报成"所有下载源都连不上"，把用户指向完全错误的方向。
        val outFile = context.getExternalFilesDir(UPDATES_DIR)
            ?.let { File(it, "$APK_PREFIX$version$APK_SUFFIX") }
        val candidates = buildApkCandidateUrls(url)

        val job = launch(Dispatchers.IO) {
            var lastError: UiMessage = UiMessage.Res(R.string.update_error_download_failed)
            try {
                if (outFile == null) {
                    android.util.Log.e(TAG, "外部私有目录不可用，放弃下载")
                    trySend(
                        DownloadState.Failed(
                            UiMessage.Res(R.string.update_error_storage_unavailable)
                        )
                    )
                    return@launch
                }
                // 清历史残留：迁到 updates/ 之前的版本把 APK 直接丢在 getExternalFilesDir 根下，
                // 新逻辑再也不会碰那批文件，几十 MB 一个地长期占着外部私有目录。顺带清掉 updates/ 里
                // 非本次版本的包——下面换源重试只删同名文件，跨版本连续升级会一层层堆积。
                // 放在 IO 线程执行：listFiles + delete 是真实文件系统访问。
                runCatching { purgeStaleApks(keep = outFile) }
                for ((index, candidate) in candidates.withIndex()) {
                    runCatching { if (outFile.exists()) outFile.delete() }
                    android.util.Log.d(TAG, "尝试下载源 ${index + 1}/${candidates.size}: $candidate")
                    try {
                        fetchToFile(candidate, outFile) { progress ->
                            trySend(DownloadState.Downloading(progress))
                        }
                        // 校验放在 Success 之前：一旦发出 Success，界面就直接去拉起安装器了。
                        // 校验不过按「这个源不可信」处理——换下一个源往往就好了（代理返回错误页最常见）。
                        val expected = expectedSha256 ?: fetchSidecarSha256(candidate, sidecarUrl)
                        val verdict = verifyDownloadedApk(outFile, expected)
                        if (verdict !== ApkVerdict.Ok) {
                            lastError = verdict.message
                            android.util.Log.e(TAG, "下载源 ${index + 1} 校验失败: ${verdict.logDetail}")
                            runCatching { if (outFile.exists()) outFile.delete() }
                            continue
                        }
                        android.util.Log.d(TAG, "下载完成: ${outFile.absolutePath} (${outFile.length()} bytes)")
                        trySend(DownloadState.Success(Uri.fromFile(outFile).toString()))
                        return@launch
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // 取消不是"下载失败"：必须原样重抛。被 catch(Exception) 吞掉的话，
                        // 换源循环会把每个源都立刻走一遍空转，最后还给用户报一次假的下载失败。
                        throw e
                    } catch (e: Exception) {
                        // 不要把 e.message 直接透给界面：Ktor 的异常消息带完整请求 URL。
                        // safeDetail 会脱敏 + 截断（core/util/ErrorHandler.kt）。
                        // 已脱敏的异常原文没有对应资源，用 Raw 直接带走。
                        val detail = ErrorHandler.safeDetail(e, MAX_ERROR_DETAIL_CHARS)
                        lastError = UiMessage.Raw(detail)
                        android.util.Log.w(TAG, "下载源失败(${index + 1}): $candidate -> $detail")
                        runCatching { if (outFile.exists()) outFile.delete() }
                        // 继续尝试下一个源
                    }
                }
                // 所有源都失败：把最后一个真实原因带出去
                android.util.Log.e(TAG, "所有下载源均失败，最后错误: $lastError")
                trySend(
                    DownloadState.Failed(
                        UiMessage.of(
                            R.string.update_error_all_sources_failed,
                            candidates.size,
                            lastError
                        )
                    )
                )
            } finally {
                close() // 终态：关闭 Flow，停止收集
            }
        }

        awaitClose { job.cancel() }
    }

    /**
     * 实际下载到文件；失败（连接异常、非 2xx、内容不完整）时抛异常，由上层决定是否换源。
     *
     * 连接超时按候选**单独**给（[CANDIDATE_CONNECT_TIMEOUT_MS] 比全局的 15s 短）：候选表最长 5 项，
     * 挨个等满 15s 才轮到直连，用户盯着"准备下载"能等一分钟；而连接阶段本来就是"几秒内握上手
     * 或者根本连不上"。整体超时依旧不设——大文件慢速下载只要持续有数据就不该被掐断。
     */
    private suspend fun fetchToFile(url: String, outFile: File, onProgress: (Int) -> Unit) {
        client.prepareGet(url) {
            timeout { connectTimeoutMillis = CANDIDATE_CONNECT_TIMEOUT_MS }
        }.execute { response ->
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
                            android.util.Log.d(TAG, "下载进度: $progress% ($copied/$total)")
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
     * 取校验和旁文件里的期望摘要；取不到返回 null（**不**因此判失败）。
     *
     * 地址顺序由 [buildSidecarCandidateUrls] 定：release 元数据给的地址优先，
     * 「拼 .sha256 后缀」只当猜法兜底。最多两个地址，每个都用很短的超时——
     * 这是个几十字节的文本文件，为它把安装前的等待拖长几十秒不值得。
     *
     * @param apkUrl 刚刚**成功下载** APK 的那个地址：它已经证明可达，旁文件优先问它，
     *   不必把候选表再走一遍
     * @param sidecarUrl release 元数据里带来的旁 asset 地址，可能为 null
     */
    private suspend fun fetchSidecarSha256(apkUrl: String, sidecarUrl: String?): String? {
        for (candidate in buildSidecarCandidateUrls(apkUrl, sidecarUrl)) {
            val hex = fetchSha256Text(candidate)
            if (hex != null) return hex
        }
        android.util.Log.d(TAG, "未取到 .sha256 旁文件（将只做包名/签名校验）")
        return null
    }

    /** 请求一个校验和文本地址；非 2xx、内容里没有 64 位十六进制、或请求异常都返回 null。 */
    private suspend fun fetchSha256Text(url: String): String? = runCatching {
        val response = client.get(url) {
            timeout {
                connectTimeoutMillis = SIDECAR_CONNECT_TIMEOUT_MS
                requestTimeoutMillis = SIDECAR_REQUEST_TIMEOUT_MS
            }
        }
        if (!response.status.isSuccess()) null
        else ApkVerification.parseSha256Sidecar(response.bodyAsText())
    }.onFailure {
        // 取消要原样上抛，否则换源循环会带着已取消的作用域继续跑。
        if (it is kotlinx.coroutines.CancellationException) throw it
        android.util.Log.d(TAG, "取校验和失败，换下一个地址")
    }.getOrNull()

    /**
     * 装之前把下载下来的文件核一遍：内容摘要、包名、签名证书。
     *
     * 为什么签名这一条是主力：`getPackageArchiveInfo` 走的是平台自己的 APK 校验器，v2/v3 签名块
     * 覆盖整个包内容，改一个字节签名就不成立——所以「签名指纹和已安装版本有交集」几乎等价于
     * 「这个包确实是我们签的、且没被动过」。摘要校验则依赖发布方公布校验和，属于额外一层。
     *
     * 调试版（FLAG_DEBUGGABLE）单独放宽包名/签名两条：debug 变体的 applicationId 带 `.debug`
     * 后缀、又是 debug 签名，和线上包必然不一致，严格判定会让开发机永远装不上。
     * 摘要不一致与解析失败**不放宽**——那两条和构建变体无关。
     */
    private fun verifyDownloadedApk(file: File, expectedSha256: String?): ApkVerdict {
        // 没有期望摘要就别算：verify 在 expectedSha256 == null 时压根不看实际摘要，
        // 几十 MB 的 SHA-256 白算一遍只是凭空多几百毫秒（安装点已经在 IO 上，但仍然是等待）。
        val actualSha = if (expectedSha256 == null) "" else {
            runCatching { ApkVerification.sha256Hex(file) }.getOrElse { return ApkVerdict.Unreadable }
        }
        val installed = installedPackageInfo()
        val archive = archivePackageInfo(file.absolutePath)
        if (expectedSha256 == null) {
            android.util.Log.w(TAG, "数据源未提供 SHA-256，本次跳过摘要校验（包名/签名仍会校验）")
        }
        val verdict = ApkVerification.verify(
            expectedPackage = installed?.packageName ?: context.packageName,
            actualPackage = archive?.packageName,
            installedSigners = signerFingerprints(installed),
            downloadedSigners = signerFingerprints(archive),
            expectedSha256 = expectedSha256,
            actualSha256 = actualSha
        )
        val relaxable = verdict is ApkVerdict.PackageMismatch || verdict is ApkVerdict.SignerMismatch
        if (relaxable && isDebuggableBuild()) {
            android.util.Log.w(TAG, "调试版放行安装包校验失败（发布版会直接拒绝）：${verdict.logDetail}")
            return ApkVerdict.Ok
        }
        return verdict
    }

    /** 已安装的自己；读不到时 [signerFingerprints] 会给空集合，校验按失败处理。 */
    private fun installedPackageInfo(): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(signingFlags().toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, signingFlags())
        }
    }.getOrNull()

    /** 解析一个**尚未安装**的 APK 文件。解析失败（不是 APK/签名不成立）返回 null。 */
    private fun archivePackageInfo(path: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                path,
                PackageManager.PackageInfoFlags.of(signingFlags().toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(path, signingFlags())
        }
    }.getOrNull()

    /** API 28 起才有 GET_SIGNING_CERTIFICATES；minSdk 是 26，26/27 只能用已弃用的 GET_SIGNATURES。 */
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /**
     * 证书指纹集合。单签名应用取 `signingCertificateHistory`（含轮换掉的旧证书），
     * 多签名才取 `apkContentsSigners`——这是平台文档给的判定顺序，反过来会在轮换后误判。
     */
    private fun signerFingerprints(info: PackageInfo?): List<String> {
        if (info == null) return emptyList()
        val signatures: Array<out Signature?>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.let {
                    if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
        return signatures?.filterNotNull()
            ?.map { ApkVerification.fingerprintOf(it.toByteArray()) }
            ?.distinct()
            ?: emptyList()
    }

    private fun isDebuggableBuild(): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * 删掉除 [keep] 以外的所有 YuMark-v*.apk。
     *
     * 覆盖两处：`updates/` 子目录（跨版本升级堆积的旧包）与 getExternalFilesDir 根下
     * （迁到 updates/ 之前的版本留下的包，新逻辑再也不会经过那里）。
     * 整体 best-effort：删不掉只是继续占空间，绝不能让升级流程因清理失败而中断，
     * 所以每次 delete 单独 runCatching，一个失败不影响后续。
     */
    private fun purgeStaleApks(keep: File) {
        val keepPath = keep.absolutePath
        val dirs = listOfNotNull(
            context.getExternalFilesDir(UPDATES_DIR),
            context.getExternalFilesDir(null)
        )
        for (dir in dirs) {
            // listFiles 在目录不存在/不可读时返回 null
            val stale = dir.listFiles()?.filter { f ->
                f.isFile &&
                    f.name.startsWith(APK_PREFIX) &&
                    f.name.endsWith(APK_SUFFIX) &&
                    f.absolutePath != keepPath
            } ?: continue
            for (f in stale) {
                val ok = runCatching { f.delete() }.getOrDefault(false)
                android.util.Log.d(TAG, "清理旧安装包 ${f.name}: $ok")
            }
        }
    }

    /**
     * 安装 APK。返回 null 表示安装界面已拉起，非 null 是给界面看的失败原因（日志里已记过）。
     *
     * suspend 不是为了好看：安装前的校验要走 `PackageManager.getPackageArchiveInfo` 真解析一遍
     * 几十 MB 的包（外加文件存在性检查），而调用点在 Compose 的 LaunchedEffect 里——同步版本
     * 等于在主线程上做这件事，用户在「下载完成」到安装器弹出之间看到的是卡住的界面。
     * 现在文件 IO 与解析整段在 [Dispatchers.IO] 上，只有拉起安装器回主线程。
     */
    suspend fun installApk(filePath: String): UiMessage? {
        android.util.Log.d(TAG, "准备安装 APK: $filePath")

        return try {
            // filePath 形如 "file:///storage/emulated/0/Android/data/.../files/updates/YuMark-v0.5.4.apk"
            val path = filePath.toUri().path
                ?: return UiMessage.Res(R.string.update_error_install_bad_path)
            val file = File(path)

            android.util.Log.d(TAG, "文件路径: ${file.absolutePath}")

            // 存在性检查与校验都是真实文件访问，整段挪到 IO 线程，见 [checkInstallable]。
            val refusal = withContext(Dispatchers.IO) { checkInstallable(file) }
            if (refusal != null) return refusal

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            android.util.Log.d(TAG, "FileProvider URI: $contentUri")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                setDataAndType(contentUri, "application/vnd.android.package-archive")
            }

            // 拉起安装器必须在有 Looper 的线程上。调用点本来就是主协程，这里仍显式回一次主调度器，
            // 免得哪天从 IO 作用域调过来时炸在 startActivity 上。
            withContext(Dispatchers.Main) { context.startActivity(intent) }
            android.util.Log.d(TAG, "启动安装界面成功")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 改成 suspend 之后 withContext 会把取消以 CancellationException 抛出来：
            // 被下面的 catch(Exception) 吞掉的话，对话框会显示一句假的「安装失败」。
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "安装失败", e)
            UiMessage.of(
                R.string.update_error_install_failed_detail,
                ErrorHandler.safeDetail(e, MAX_ERROR_DETAIL_CHARS)
            )
        }
    }

    /**
     * 安装前的文件检查，**只在 IO 线程上调用**：`exists()` 是文件系统访问，
     * [verifyDownloadedApk] 还要让 PackageManager 完整解析一遍几十 MB 的安装包。
     * 返回 null 表示可以拉起安装器，非 null 是拒绝理由（已记日志、已删文件）。
     *
     * 这里**再校验一遍**包名与签名，不是重复劳动：[installApk] 收的是一个任意路径，
     * 而外部私有目录上一个持有旧版存储权限的应用是碰得到的。校验不过就把文件删掉并拒绝拉起
     * 安装器——把一个来路不明的包递给系统安装器，用户看到的是我们的更新提示、装上的是别的东西。
     * 摘要在这一步无从校验（期望值只在下载时拿得到），所以只核包名与签名。
     */
    private fun checkInstallable(file: File): UiMessage? {
        if (!file.exists()) {
            android.util.Log.e(TAG, "文件不存在: ${file.absolutePath}")
            return UiMessage.Res(R.string.update_error_install_missing_file)
        }
        val verdict = verifyDownloadedApk(file, expectedSha256 = null)
        if (verdict !== ApkVerdict.Ok) {
            android.util.Log.e(TAG, "安装前校验失败，已拒绝安装: ${verdict.logDetail}")
            runCatching { file.delete() }
            // 套一层「已阻止安装」：同一句校验结论在下载期只是「换下一个源」，
            // 在这一步却是「不会拉起安装器」，两件事对用户的含义不同。
            return UiMessage.of(R.string.update_error_install_refused, verdict.message)
        }
        return null
    }

    companion object {
        private const val TAG = "ApkDownloader"

        /**
         * 升级 APK 在 getExternalFilesDir 下的专用子目录名。
         * 与 res/xml/file_paths.xml 里 external-files-path 的 path 强耦合：
         * 只有这个子目录是 FileProvider 可授权根，改名要两边一起改。
         */
        private const val UPDATES_DIR = "updates"

        /** 升级包文件名前缀/后缀，仅用于 [purgeStaleApks] 识别自己产生的文件，避免误删同目录其它内容。 */
        private const val APK_PREFIX = "YuMark-v"
        private const val APK_SUFFIX = ".apk"

        /**
         * 单个候选源的连接超时。比全局 15s 短：候选表最长 5 项，挨个等满才轮到直连，
         * 「准备下载」能卡将近一分钟。连接阶段本就是「几秒内握上手或根本连不上」。
         */
        private const val CANDIDATE_CONNECT_TIMEOUT_MS = 8_000L

        /** 旁文件是几十字节的文本，给足 5s/8s 已经很宽裕，拿不到就走无摘要路径。 */
        private const val SIDECAR_CONNECT_TIMEOUT_MS = 5_000L
        private const val SIDECAR_REQUEST_TIMEOUT_MS = 8_000L

        /** 透出到界面的技术细节长度上限：错误栏只有两三行，再长就把关键信息挤出屏幕。 */
        private const val MAX_ERROR_DETAIL_CHARS = 120
    }
}

/**
 * GitHub 加速镜像前缀（直接拼在完整 GitHub URL 前面）。
 * 这类公益代理可能失效/限速，按顺序尝试，全失败再回退直连。如需更新可维护此列表。
 */
internal val GITHUB_MIRROR_PREFIXES = listOf(
    "https://ghfast.top/",
    "https://gh-proxy.com/",
    "https://ghproxy.net/",
    "https://mirror.ghproxy.com/"
)

/**
 * 候选下载地址的**顺序**，纯函数，可单测（见 ApkCandidateUrlsTest）。
 *
 * 三条规则，都是踩出来的：
 * 1. **不是 GitHub 的地址就只有直连**。旧实现无条件给每个 URL 套四层镜像前缀，自建服务器或
 *    第三方直链会先收到四个必然 404/连不上的请求，白等四轮超时，最后报的还是最后那个直连的错。
 * 2. **地址本身已经带镜像前缀时，直连（即那个镜像）排最前**，不再往上叠第二层代理——
 *    `https://ghfast.top/https://gh-proxy.com/https://github.com/...` 没有服务器认得。
 * 3. 其余（真正的 GitHub 直链）保持既定策略：镜像在前、直连兜底。国内直连 GitHub 的下载 CDN
 *    常年超时，把直连排前面等于让大多数用户先白等一轮。
 *
 * 最后统一去重且**保序**：镜像列表哪天出现重复项，不该变成重复下载两遍。
 */
internal fun buildApkCandidateUrls(
    url: String,
    mirrors: List<String> = GITHUB_MIRROR_PREFIXES
): List<String> {
    val direct = url.trim()
    if (direct.isEmpty()) return emptyList()
    val alreadyMirrored = mirrors.any { direct.startsWith(it, ignoreCase = true) }
    if (alreadyMirrored || !isGitHubDownloadUrl(direct)) return listOf(direct)
    return (mirrors.map { it + direct } + direct).distinct()
}

/** 只有这些 host 上的资源才值得套 GitHub 加速镜像。 */
private fun isGitHubDownloadUrl(url: String): Boolean {
    val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
    return host == "github.com" || host.endsWith(".github.com") ||
        host.endsWith(".githubusercontent.com")
}

/** 校验和旁文件的后缀（拼在 APK 地址后面猜出来的那个地址）。 */
private const val SHA256_URL_SUFFIX = ".sha256"

/**
 * 校验和旁文件的候选地址顺序，纯函数，可单测（见 SidecarCandidateUrlsTest）。
 *
 * @param sourceUrl 刚刚**成功下载** APK 的那个地址（可能已带镜像前缀）
 * @param sidecarUrl release 元数据里带来的旁 asset 地址；null/空白视为没有
 *
 * 两条规则：
 * 1. **元数据给的地址优先**，「拼 .sha256 后缀」只是猜法兜底——发布流程完全可以把校验和挂成
 *    别的名字，猜法在那种命名下必然 404。
 * 2. 元数据给的是 GitHub 直连地址，而刚刚成功的源是某个镜像时，**给它套上同一层镜像**：
 *    需要镜像才下得动 APK 的网络，拿一个直连地址去取旁文件就是白等一轮超时。
 *    已经带镜像前缀的地址不再叠第二层（没有服务器认得双层前缀）。
 *
 * 去重且保序：GitHub 的旁 asset 通常正好叫「APK 名 + .sha256」，套上同一层镜像后与猜法算出来的
 * 地址逐字相同，这时候只该请求一次。
 */
internal fun buildSidecarCandidateUrls(
    sourceUrl: String,
    sidecarUrl: String?,
    mirrors: List<String> = GITHUB_MIRROR_PREFIXES
): List<String> {
    val source = sourceUrl.trim()
    val guess = if (source.isEmpty()) null else source + SHA256_URL_SUFFIX
    val explicit = sidecarUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: return listOfNotNull(guess)
    val prefix = mirrors.firstOrNull { source.startsWith(it, ignoreCase = true) }
    val alreadyMirrored = mirrors.any { explicit.startsWith(it, ignoreCase = true) }
    val aligned = if (prefix != null && !alreadyMirrored) prefix + explicit else explicit
    return listOfNotNull(aligned, guess).distinct()
}

