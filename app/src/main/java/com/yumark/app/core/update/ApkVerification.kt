package com.yumark.app.core.update

import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import java.io.File
import java.security.MessageDigest

/**
 * 安装包校验的**纯逻辑**：摘要、包名、签名证书指纹怎么比，比出来算什么结论。
 *
 * 单独成文件只有一个理由——可测。`PackageManager` 在 JVM 单测里跑不了（本项目没有 Robolectric），
 * 而「期望值和实际值怎么算不一致」恰恰是这条链上最不能写错的一环：错成恒等于相等，就等于没校验。
 * 所以这里只接纯数据，读 PackageManager、删文件、报状态一律留给 [ApkDownloader]。
 */
internal object ApkVerification {

    /**
     * 流式算文件 SHA-256（小写十六进制）。
     *
     * 必须流式：升级包动辄几十 MB，`readBytes()` 一次性进堆是实打实的 OOM 风险。
     */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    /** 证书 DER 字节 → SHA-256 指纹（小写十六进制、无冒号），与 `keytool -list` 的 SHA256 同值。 */
    fun fingerprintOf(certDer: ByteArray): String =
        toHex(MessageDigest.getInstance("SHA-256").digest(certDer))

    /**
     * 从校验和文件正文里取 SHA-256。
     *
     * 容忍三种常见写法：裸十六进制、`sha256sum` 的 `<hex>  <文件名>`、OpenSSL 的
     * `SHA256(x.apk)= <hex>`。取第一段 64 位十六进制就够，不必按格式分支。
     */
    fun parseSha256Sidecar(text: String): String? = HEX_64.find(text)?.value?.lowercase()

    /** 摘要比较：大小写与前后空白无关；期望值不是合法的 64 位十六进制就算不一致（而不是放行）。 */
    fun digestMatches(expected: String, actual: String): Boolean {
        val e = expected.trim().lowercase()
        return e.matches(HEX_64_WHOLE) && e == actual.trim().lowercase()
    }

    /**
     * 签名证书是否可信：两侧都非空，且**有交集**。
     *
     * 不用集合相等：密钥轮换后新包的 `signingCertificateHistory` 里新旧证书都在，而已安装的那侧
     * 可能只见过旧证书，相等判定会把一次正当升级判成「签名不一致」。交集非空已经够了——
     * APK 的签名血缘由平台自己验（v3 lineage 必须由上一把钥匙签名），伪造一段包含我们证书的
     * 历史过不了 PackageManager 的解析。
     *
     * 两侧空集都判失败：读不到证书意味着**没校验成**，这时候放行等于把校验写成了装饰。
     */
    fun signersMatch(installed: List<String>, downloaded: List<String>): Boolean =
        installed.isNotEmpty() && downloaded.isNotEmpty() && downloaded.any { it in installed }

    /**
     * 汇总结论。顺序是刻意的：先摘要（文件本身对不对），再包名（是不是这个应用），
     * 最后签名（是不是同一个作者）——报错时用户看到的是最靠前的那个真实原因。
     *
     * [expectedSha256] 为 null 表示数据源没提供校验和：**不**因此放行整套校验，包名与签名照查
     * （防替换主要靠那两条），由调用方留一条日志说明本次没有摘要可比。
     */
    fun verify(
        expectedPackage: String,
        actualPackage: String?,
        installedSigners: List<String>,
        downloadedSigners: List<String>,
        expectedSha256: String?,
        actualSha256: String
    ): ApkVerdict = when {
        expectedSha256 != null && !digestMatches(expectedSha256, actualSha256) ->
            ApkVerdict.DigestMismatch(expectedSha256.trim().lowercase(), actualSha256.trim().lowercase())

        actualPackage.isNullOrBlank() -> ApkVerdict.Unreadable
        actualPackage != expectedPackage -> ApkVerdict.PackageMismatch(expectedPackage, actualPackage)
        !signersMatch(installedSigners, downloadedSigners) -> ApkVerdict.SignerMismatch
        else -> ApkVerdict.Ok
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
    private val HEX_64 = Regex("\\b[0-9a-fA-F]{64}\\b")
    private val HEX_64_WHOLE = Regex("[0-9a-f]{64}")
}

/**
 * 校验结论。每种失败都带上「哪儿不一致」，好让日志能定位、界面能说清。
 *
 * 两个文案成员分给两个受众，刻意不合并成一个：
 * - [message] 给用户，是 [UiMessage]（资源 id + 实参），随语区翻译，由界面层解析；
 * - [logDetail] 给 logcat，带上两侧的实际值。它**不**进界面：摘要/指纹这种长十六进制串
 *   对用户毫无意义，而排查时缺了它就只知道「不一致」却不知道差在哪。
 *
 * 本文件仍然在 JVM 单测里直接跑：[UiMessage] 只是数据类，`R.string.*` 是普通 int 常量，
 * 两者都不碰 Android 运行时。
 */
internal sealed interface ApkVerdict {

    /** 给界面的一句话；[Ok] 没有可说的。 */
    val message: UiMessage

    /** 给 logcat 的技术细节，带实际值；不进界面。 */
    val logDetail: String

    object Ok : ApkVerdict {
        override val message: UiMessage = UiMessage.Raw("")
        override val logDetail: String = "ok"
    }

    data class DigestMismatch(val expected: String, val actual: String) : ApkVerdict {
        override val message: UiMessage = UiMessage.Res(R.string.update_error_digest_mismatch)
        override val logDetail: String = "digest mismatch: expected=$expected actual=$actual"
    }

    data class PackageMismatch(val expected: String, val actual: String) : ApkVerdict {
        override val message: UiMessage =
            UiMessage.of(R.string.update_error_package_mismatch, actual)
        override val logDetail: String = "package mismatch: expected=$expected actual=$actual"
    }

    object SignerMismatch : ApkVerdict {
        override val message: UiMessage = UiMessage.Res(R.string.update_error_signer_mismatch)
        override val logDetail: String = "signer mismatch"
    }

    object Unreadable : ApkVerdict {
        override val message: UiMessage = UiMessage.Res(R.string.update_error_unreadable)
        override val logDetail: String = "unreadable: PackageManager could not parse the file"
    }
}
