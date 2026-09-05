package com.yumark.app.core.update

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 候选下载地址的**顺序**决定了失败时用户要白等多久，所以三条规则各自钉一个用例：
 * 非 GitHub 地址不套镜像、已带镜像前缀不叠第二层、真正的 GitHub 直链镜像在前直连兜底。
 */
class ApkCandidateUrlsTest {

    private val release =
        "https://github.com/ban-code-art/YuMark/releases/download/v1.0/YuMark-v1.0.apk"

    @Test
    fun `GitHub 直链：镜像在前，直连兜底`() {
        val candidates = buildApkCandidateUrls(release)

        assertThat(candidates).hasSize(GITHUB_MIRROR_PREFIXES.size + 1)
        assertThat(candidates.first()).isEqualTo(GITHUB_MIRROR_PREFIXES.first() + release)
        // 国内直连 GitHub 的下载 CDN 常年超时，直连只配当最后一个
        assertThat(candidates.last()).isEqualTo(release)
    }

    @Test
    fun `非 GitHub 地址只有直连`() {
        // 旧实现无条件套四层镜像前缀，自建服务器会先收四个必然失败的请求，白等四轮超时
        val own = "https://files.example.com/apk/YuMark-v1.0.apk"
        assertThat(buildApkCandidateUrls(own)).containsExactly(own)
    }

    @Test
    fun `raw githubusercontent 也算 GitHub 资源`() {
        val raw = "https://raw.githubusercontent.com/ban-code-art/YuMark/main/app.apk"
        assertThat(buildApkCandidateUrls(raw)).hasSize(GITHUB_MIRROR_PREFIXES.size + 1)
    }

    @Test
    fun `host 判定不区分大小写`() {
        val upper = "https://GitHub.COM/ban-code-art/YuMark/releases/download/v1/a.apk"
        assertThat(buildApkCandidateUrls(upper)).hasSize(GITHUB_MIRROR_PREFIXES.size + 1)
    }

    @Test
    fun `已经带镜像前缀时不再叠第二层代理`() {
        // https://ghfast.top/https://gh-proxy.com/https://github.com/... 没有服务器认得
        val mirrored = GITHUB_MIRROR_PREFIXES[1] + release
        assertThat(buildApkCandidateUrls(mirrored)).containsExactly(mirrored)
    }

    @Test
    fun `镜像列表出现重复项时去重且保序`() {
        val dup = listOf("https://m1.example/", "https://m1.example/", "https://m2.example/")
        // 重复项不该变成「同一个源下载两遍」，剩下的顺序也不许被 distinct 打乱
        assertThat(buildApkCandidateUrls(release, dup)).containsExactly(
            "https://m1.example/$release",
            "https://m2.example/$release",
            release
        ).inOrder()
    }

    @Test
    fun `空地址给空列表而不是一串没头的镜像前缀`() {
        // 元数据里 browser_download_url 缺失时，套上前缀会得到 https://ghfast.top/ 这种"能连通但下到垃圾"的地址
        assertThat(buildApkCandidateUrls("")).isEmpty()
        assertThat(buildApkCandidateUrls("   \n ")).isEmpty()
    }

    @Test
    fun `地址两端空白先剪掉再拼前缀`() {
        // 手填/复制来的 URL 常带换行；拼进前缀后会得到 https://ghfast.top/https://github.com/...%0A
        assertThat(buildApkCandidateUrls("  $release  ").last()).isEqualTo(release)
    }

    @Test
    fun `镜像前缀都以斜杠结尾且是 https`() {
        // 前缀是直接字符串拼接的：漏一个 `/` 就拼成 https://ghfast.tophttps://github.com/...
        GITHUB_MIRROR_PREFIXES.forEach {
            assertThat(it).endsWith("/")
            assertThat(it).startsWith("https://")
        }
        assertThat(GITHUB_MIRROR_PREFIXES).containsNoDuplicates()
    }
}
