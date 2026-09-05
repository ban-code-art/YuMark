package com.yumark.app.data.remote

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.GitHubAsset
import org.junit.jupiter.api.Test

/**
 * 摘要**从哪来**这两步的钉子：从 asset 列表里挑旁文件、把 `digest` 字段解析成十六进制。
 *
 * 这两处一错，摘要校验就悄悄退回「没有期望值」的空转状态（下载链路刻意不因缺摘要判失败），
 * 平时完全看不出来——只有真被投毒时才会发现根本没在校验。挑错文件更糟：拿别的产物的校验和去比，
 * 每次升级都报「摘要不一致」，用户永远装不上。
 */
class Sha256AssetTest {

    private val hex = "a1b2c3d4".repeat(8)   // 64 位合法十六进制
    private val apk = "YuMark-v1.0.apk"

    // ---- findSha256SidecarUrl ----

    @Test
    fun `同名 apk-sha256 旁文件被挑中，返回它的下载地址`() {
        val assets = listOf(asset(apk), asset("$apk.sha256"))
        assertThat(findSha256SidecarUrl(assets, apk))
            .isEqualTo("https://example.com/$apk.sha256")
    }

    @Test
    fun `换扩展名的写法也认`() {
        val assets = listOf(asset(apk), asset("YuMark-v1.0.sha256"))
        assertThat(findSha256SidecarUrl(assets, apk))
            .isEqualTo("https://example.com/YuMark-v1.0.sha256")
    }

    @Test
    fun `两种命名同时存在时优先 apk-sha256`() {
        // 「原名 + .sha256」是 sha256sum 的直接产物，最可能是这个 APK 的校验和
        val assets = listOf(asset("YuMark-v1.0.sha256"), asset("$apk.sha256"))
        assertThat(findSha256SidecarUrl(assets, apk))
            .isEqualTo("https://example.com/$apk.sha256")
    }

    @Test
    fun `不匹配的 sha256 文件绝不误选`() {
        // 同一个 release 里挂着源码包/mapping 的校验和：拿错一个，摘要必然不一致，
        // 升级会被自己拦下来，比「没有摘要」更糟
        val assets = listOf(
            asset(apk),
            asset("source.tar.gz.sha256"),
            asset("mapping.txt.sha256"),
            asset("YuMark-v0.9.apk.sha256")
        )
        assertThat(findSha256SidecarUrl(assets, apk)).isNull()
    }

    @Test
    fun `名字比较不区分大小写`() {
        val assets = listOf(asset("YUMARK-V1.0.APK.SHA256"))
        assertThat(findSha256SidecarUrl(assets, apk))
            .isEqualTo("https://example.com/YUMARK-V1.0.APK.SHA256")
    }

    @Test
    fun `下载地址为空的 asset 跳过，不返回空地址`() {
        // 空地址拼进请求只会白等一轮超时；此时还有换扩展名那份可用
        val assets = listOf(
            asset("$apk.sha256", url = "  "),
            asset("YuMark-v1.0.sha256")
        )
        assertThat(findSha256SidecarUrl(assets, apk))
            .isEqualTo("https://example.com/YuMark-v1.0.sha256")
    }

    @Test
    fun `没有旁文件或列表为空时返回 null 而不是抛异常`() {
        assertThat(findSha256SidecarUrl(listOf(asset(apk)), apk)).isNull()
        assertThat(findSha256SidecarUrl(emptyList(), apk)).isNull()
        assertThat(findSha256SidecarUrl(listOf(asset("$apk.sha256")), "   ")).isNull()
    }

    // ---- parseAssetDigest ----

    @Test
    fun `sha256 前缀被剥掉，结果统一成小写十六进制`() {
        assertThat(parseAssetDigest("sha256:$hex")).isEqualTo(hex)
        assertThat(parseAssetDigest("SHA256:${hex.uppercase()}")).isEqualTo(hex)
        assertThat(parseAssetDigest("sha-256:$hex")).isEqualTo(hex)
        assertThat(parseAssetDigest("  sha256: $hex  ")).isEqualTo(hex)
    }

    @Test
    fun `没有前缀的裸十六进制照收`() {
        assertThat(parseAssetDigest(hex)).isEqualTo(hex)
        assertThat(parseAssetDigest(hex.uppercase())).isEqualTo(hex)
    }

    @Test
    fun `不是 SHA-256 的算法一律返回 null`() {
        // 把 SHA-512 当 SHA-256 去比，结果是每次升级都报「摘要不一致」，用户永远装不上；
        // 宁可没有摘要（只校验包名/签名），也不要一个错的
        assertThat(parseAssetDigest("sha512:${"a".repeat(128)}")).isNull()
        assertThat(parseAssetDigest("md5:${"a".repeat(32)}")).isNull()
        assertThat(parseAssetDigest("sha1:${"a".repeat(40)}")).isNull()
    }

    @Test
    fun `字段缺失或格式不合法时返回 null`() {
        assertThat(parseAssetDigest(null)).isNull()
        assertThat(parseAssetDigest("")).isNull()
        assertThat(parseAssetDigest("   ")).isNull()
        assertThat(parseAssetDigest("sha256:")).isNull()
        assertThat(parseAssetDigest("sha256:${"a".repeat(63)}")).isNull()   // 截断
        assertThat(parseAssetDigest("sha256:${"a".repeat(65)}")).isNull()   // 多一位
        assertThat(parseAssetDigest("sha256:${"z".repeat(64)}")).isNull()   // 非十六进制
        assertThat(parseAssetDigest("sha256:$hex $hex")).isNull()           // 带尾巴
    }

    private fun asset(
        name: String,
        url: String = "https://example.com/$name",
        digest: String? = null
    ) = GitHubAsset(name = name, size = 1L, browser_download_url = url, digest = digest)
}
