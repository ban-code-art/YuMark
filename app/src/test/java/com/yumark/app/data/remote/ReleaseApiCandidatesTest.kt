package com.yumark.app.data.remote

import com.google.common.truth.Truth.assertThat
import com.yumark.app.core.update.GITHUB_MIRROR_PREFIXES
import com.yumark.app.core.update.buildApkCandidateUrls
import org.junit.jupiter.api.Test

/**
 * 元数据地址与下载地址的候选顺序**刻意相反**（见 [buildReleaseApiCandidates] 的 KDoc）。
 * 这个反向关系只写在注释里就会被下一次「统一一下」的重构抹平，所以在这里钉住。
 */
class ReleaseApiCandidatesTest {

    private val api = "https://api.github.com/repos/ban-code-art/YuMark/releases/latest"

    @Test
    fun `直连排第一，镜像只当兜底`() {
        val candidates = buildReleaseApiCandidates(api)

        assertThat(candidates).hasSize(GITHUB_MIRROR_PREFIXES.size + 1)
        assertThat(candidates.first()).isEqualTo(api)
        assertThat(candidates.last()).isEqualTo(GITHUB_MIRROR_PREFIXES.last() + api)
    }

    @Test
    fun `与下载链路方向相反`() {
        // 被墙的是 release 下载 CDN，不是几 KB 的 api.github.com；两条链路的首选必须不一样，
        // 否则每次开机自动检查都要先白等一轮公益代理
        val release = "https://github.com/ban-code-art/YuMark/releases/download/v1/a.apk"
        assertThat(buildReleaseApiCandidates(api).first()).isEqualTo(api)
        assertThat(buildApkCandidateUrls(release).first()).isNotEqualTo(release)
    }

    @Test
    fun `去重且保序`() {
        val dup = listOf("https://m1.example/", "https://m1.example/")
        assertThat(buildReleaseApiCandidates(api, dup))
            .containsExactly(api, "https://m1.example/$api").inOrder()
    }

    @Test
    fun `空地址给空列表`() {
        assertThat(buildReleaseApiCandidates("")).isEmpty()
        assertThat(buildReleaseApiCandidates("  \n ")).isEmpty()
    }

    @Test
    fun `地址两端空白先剪掉`() {
        assertThat(buildReleaseApiCandidates("  $api  ").first()).isEqualTo(api)
    }
}
