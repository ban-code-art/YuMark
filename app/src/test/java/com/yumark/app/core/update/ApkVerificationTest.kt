package com.yumark.app.core.update

import com.google.common.truth.Truth.assertThat
import com.yumark.app.core.util.UiMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * 钉住安装包校验的比较逻辑。
 *
 * 这条链上最不能写错的一环就是「期望值和实际值怎么算不一致」——错成恒等于相等，就等于没校验，
 * 而它平时永远静默（真被投毒时才第一次执行到）。`PackageManager` 在 JVM 单测里跑不了，
 * 所以 [ApkVerification] 只接纯数据，这里把每种结论都跑一遍。
 */
class ApkVerificationTest {

    @TempDir
    lateinit var dir: File

    private val hexA = "a".repeat(64)
    private val hexB = "b".repeat(64)

    @Test
    fun `摘要比较忽略大小写与空白`() {
        assertThat(ApkVerification.digestMatches(" ${hexA.uppercase()} ", hexA)).isTrue()
        assertThat(ApkVerification.digestMatches(hexA, hexB)).isFalse()
    }

    @Test
    fun `期望摘要不是合法 64 位十六进制时判不一致而不是放行`() {
        // 发布方把校验和写错/写成 MD5 时，绝不能因为「格式不对」就当校验通过
        assertThat(ApkVerification.digestMatches("", hexA)).isFalse()
        assertThat(ApkVerification.digestMatches("not-a-hash", hexA)).isFalse()
        assertThat(ApkVerification.digestMatches("a".repeat(63), hexA)).isFalse()
        assertThat(ApkVerification.digestMatches("z".repeat(64), hexA)).isFalse()
    }

    @Test
    fun `旁文件三种常见写法都能取到摘要`() {
        assertThat(ApkVerification.parseSha256Sidecar(hexA.uppercase())).isEqualTo(hexA)
        assertThat(ApkVerification.parseSha256Sidecar("$hexA  YuMark-v1.0.apk\n")).isEqualTo(hexA)
        assertThat(ApkVerification.parseSha256Sidecar("SHA256(YuMark.apk)= $hexA")).isEqualTo(hexA)
    }

    @Test
    fun `旁文件没有摘要时返回 null`() {
        assertThat(ApkVerification.parseSha256Sidecar("<html>404 Not Found</html>")).isNull()
        assertThat(ApkVerification.parseSha256Sidecar("a".repeat(63))).isNull()
    }

    @Test
    fun `证书按交集判定，密钥轮换不算不一致`() {
        // 已安装侧只见过旧证书，新包的 signingCertificateHistory 里新旧都在
        assertThat(ApkVerification.signersMatch(listOf("old"), listOf("old", "new"))).isTrue()
        assertThat(ApkVerification.signersMatch(listOf("old", "new"), listOf("new"))).isTrue()
        assertThat(ApkVerification.signersMatch(listOf("old"), listOf("evil"))).isFalse()
    }

    @Test
    fun `两侧任一读不到证书都算校验失败`() {
        // 读不到证书意味着「没校验成」，这时候放行等于把校验写成装饰
        assertThat(ApkVerification.signersMatch(emptyList(), listOf("x"))).isFalse()
        assertThat(ApkVerification.signersMatch(listOf("x"), emptyList())).isFalse()
        assertThat(ApkVerification.signersMatch(emptyList(), emptyList())).isFalse()
    }

    @Test
    fun `全部一致才是 Ok`() {
        assertThat(verdictOf(actualPackage = PKG, expected = hexA, actual = hexA))
            .isSameInstanceAs(ApkVerdict.Ok)
        // 数据源没给校验和：不因此放行，包名与签名照查
        assertThat(verdictOf(actualPackage = PKG, expected = null, actual = ""))
            .isSameInstanceAs(ApkVerdict.Ok)
    }

    @Test
    fun `结论顺序是摘要 包名 签名`() {
        // 摘要不一致时先报摘要：文件本身就不对，包名再对也没意义
        val bothWrong = verdictOf(actualPackage = "com.other.app", expected = hexA, actual = hexB)
        assertThat(bothWrong).isInstanceOf(ApkVerdict.DigestMismatch::class.java)

        val wrongPkg = verdictOf(actualPackage = "com.other.app", expected = null, actual = "")
        assertThat(wrongPkg).isInstanceOf(ApkVerdict.PackageMismatch::class.java)
        // 报错要说清是哪个包，用户才知道自己下到了什么。界面那句是「资源 id + 实参」，
        // 实际包名在实参里——JVM 上没有资源表，取不到成句的结果，只能核实参。
        assertThat((wrongPkg.message as UiMessage.Res).args).contains("com.other.app")
        // logDetail 走 logcat，两侧的值都得在：只说一句「包名不一致」，排查时定位不到差在哪
        assertThat(wrongPkg.logDetail).contains("com.other.app")
        assertThat(wrongPkg.logDetail).contains(PKG)

        val wrongSigner = ApkVerification.verify(
            expectedPackage = PKG, actualPackage = PKG,
            installedSigners = listOf("mine"), downloadedSigners = listOf("theirs"),
            expectedSha256 = null, actualSha256 = ""
        )
        assertThat(wrongSigner).isSameInstanceAs(ApkVerdict.SignerMismatch)
    }

    @Test
    fun `解析不出包名时报「不是 APK」而不是包名不符`() {
        assertThat(verdictOf(actualPackage = null, expected = null, actual = ""))
            .isSameInstanceAs(ApkVerdict.Unreadable)
        assertThat(verdictOf(actualPackage = "  ", expected = null, actual = ""))
            .isSameInstanceAs(ApkVerdict.Unreadable)
    }

    @Test
    fun `sha256Hex 与一次性摘要等值，且跨缓冲区边界正确`() {
        // 升级包动辄几十 MB，实现必须流式；这里用比 64KB 缓冲更大的文件压一次读循环
        val file = File(dir, "big.bin")
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        file.writeBytes(bytes)

        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        assertThat(ApkVerification.sha256Hex(file)).isEqualTo(expected)
    }

    @Test
    fun `sha256Hex 对已知向量给出标准值`() {
        val file = File(dir, "abc.txt")
        file.writeBytes("abc".toByteArray())
        // NIST 的 SHA-256("abc") 标准向量：钉住十六进制字节序与大小写
        assertThat(ApkVerification.sha256Hex(file))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `证书指纹是 DER 字节的 SHA-256，小写无冒号`() {
        val der = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x0A, 0xFF.toByte(), 0x00)
        val fp = ApkVerification.fingerprintOf(der)
        assertThat(fp).isEqualTo(MessageDigest.getInstance("SHA-256").digest(der).toHex())
        assertThat(fp).hasLength(64)
        assertThat(fp).doesNotContain(":")
        assertThat(fp).isEqualTo(fp.lowercase())
    }

    // ---- 辅助 ----

    /** 只有「实际包名 + 摘要」在变的那几条用例，其余参数固定成「一切正常」。 */
    private fun verdictOf(actualPackage: String?, expected: String?, actual: String): ApkVerdict =
        ApkVerification.verify(
            expectedPackage = PKG,
            actualPackage = actualPackage,
            installedSigners = listOf("fp-mine"),
            downloadedSigners = listOf("fp-mine"),
            expectedSha256 = expected,
            actualSha256 = actual
        )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val PKG = "com.yumark.app"
    }
}
