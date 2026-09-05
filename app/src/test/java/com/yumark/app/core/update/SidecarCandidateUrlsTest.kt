package com.yumark.app.core.update

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 旁文件地址的候选顺序。两件事值得钉：
 * 1. **元数据给的地址优先于「拼后缀」猜法**——发布流程完全可以把校验和挂成别的名字，
 *    猜法在那种命名下必然 404，摘要校验就又退回空转；
 * 2. **刚刚下载成功的源是镜像时，元数据地址要套上同一层镜像**。需要镜像才下得动 APK 的网络，
 *    拿一个 GitHub 直连地址去取旁文件就是白等一轮超时；而套完之后它常常与猜法逐字相同，
 *    这时候只该请求一次。
 */
class SidecarCandidateUrlsTest {

    private val release =
        "https://github.com/ban-code-art/YuMark/releases/download/v1.0/YuMark-v1.0.apk"
    private val sidecar = "$release.sha256"
    private val mirror = GITHUB_MIRROR_PREFIXES.first()

    @Test
    fun `没有元数据地址时只剩拼后缀的猜法`() {
        assertThat(buildSidecarCandidateUrls(release, null)).containsExactly(sidecar)
        assertThat(buildSidecarCandidateUrls(release, "  ")).containsExactly(sidecar)
    }

    @Test
    fun `直连源：元数据地址排第一，猜法兜底`() {
        val explicit = "https://cdn.example.com/checksums/YuMark-v1.0.sha256"
        assertThat(buildSidecarCandidateUrls(release, explicit))
            .containsExactly(explicit, sidecar).inOrder()
    }

    @Test
    fun `镜像源：给元数据的直连地址套上同一层镜像`() {
        // 下载走了镜像，说明直连 GitHub 走不通；这时拿直连地址取旁文件必然白等一轮超时
        val explicit = "https://github.com/ban-code-art/YuMark/releases/download/v1.0/sums.sha256"
        val result = buildSidecarCandidateUrls(mirror + release, explicit)
        assertThat(result.first()).isEqualTo(mirror + explicit)
        assertThat(result).hasSize(2)
        assertThat(result.last()).isEqualTo(mirror + release + ".sha256")
    }

    @Test
    fun `套上镜像后与猜法相同时只请求一次`() {
        // GitHub 的旁 asset 通常正好叫「APK 名 + .sha256」，两条路算出来的地址逐字相同
        assertThat(buildSidecarCandidateUrls(mirror + release, sidecar))
            .containsExactly(mirror + sidecar)
    }

    @Test
    fun `元数据地址已带镜像前缀时不叠第二层代理`() {
        // https://ghfast.top/https://gh-proxy.com/https://github.com/... 没有服务器认得
        val other = GITHUB_MIRROR_PREFIXES[1]
        val explicit = other + sidecar
        val result = buildSidecarCandidateUrls(mirror + release, explicit)
        assertThat(result.first()).isEqualTo(explicit)
        assertThat(result.first()).doesNotContain(mirror + other)
    }

    @Test
    fun `镜像前缀匹配不区分大小写`() {
        val upperMirror = mirror.uppercase()
        val result = buildSidecarCandidateUrls(upperMirror + release, sidecar)
        // 源用的是大写前缀，套回去时用列表里的规范写法即可，关键是别退化成直连地址
        assertThat(result.first()).isEqualTo(mirror + sidecar)
    }

    @Test
    fun `两端空白先剪掉`() {
        assertThat(buildSidecarCandidateUrls("  $release  ", "  $sidecar  "))
            .containsExactly(sidecar)
    }

    @Test
    fun `源地址为空时只剩元数据地址，两者都空则给空列表`() {
        // 猜法的底子是「刚刚下载成功的那个地址」，没有它就没有可猜的东西：
        // 拼出来的 ".sha256" 是个能连通但下到垃圾的地址
        assertThat(buildSidecarCandidateUrls("", sidecar)).containsExactly(sidecar)
        assertThat(buildSidecarCandidateUrls("   ", null)).isEmpty()
        assertThat(buildSidecarCandidateUrls("", "  ")).isEmpty()
    }

    @Test
    fun `自定义镜像列表照样生效`() {
        val mirrors = listOf("https://m1.example/", "https://m2.example/")
        val result = buildSidecarCandidateUrls("https://m2.example/$release", sidecar, mirrors)
        assertThat(result).containsExactly("https://m2.example/$sidecar")
    }
}
