package com.yumark.app.data.remote

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 版本比较是「每次启动都弹一次不存在的更新」这类投诉的唯一来源，所以三个纯函数逐个钉。
 *
 * 最要紧的一条：debug 变体的 versionName 是 `0.9.1-debug`，旧实现 `split(".")` 后
 * `"1-debug".toIntOrNull() ?: 0` 把第三段算成 0，于是「已装 0.9.1-debug」永远小于「线上 0.9.1」。
 */
class VersionCompareTest {

    @Test
    fun `预发布后缀不参与比较，0_9_1-debug 与 0_9_1 等值`() {
        assertThat(parseVersionParts("0.9.1-debug")).containsExactly(0, 9, 1).inOrder()
        assertThat(isNewerVersion("0.9.1", "0.9.1-debug")).isFalse()
        assertThat(isNewerVersion("0.9.1-debug", "0.9.1")).isFalse()
    }

    @Test
    fun `容忍 v 前缀与常见后缀`() {
        assertThat(parseVersionParts("v1.2.3")).containsExactly(1, 2, 3).inOrder()
        assertThat(parseVersionParts("V1.2")).containsExactly(1, 2).inOrder()
        // 纯字母段整段丢弃：rc1 的数字不在段首，匹配不到
        assertThat(parseVersionParts("1.0.0-rc1")).containsExactly(1, 0, 0).inOrder()
        assertThat(parseVersionParts("1.2.3+build")).containsExactly(1, 2, 3).inOrder()
        assertThat(parseVersionParts(" 1.2.3 ")).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `一个数字都解析不出来时给空列表`() {
        assertThat(parseVersionParts("")).isEmpty()
        assertThat(parseVersionParts("nightly")).isEmpty()
    }

    @Test
    fun `缺的段按 0 补，1_2 与 1_2_0 等值`() {
        assertThat(isNewerVersion("1.2", "1.2.0")).isFalse()
        assertThat(isNewerVersion("1.2.0", "1.2")).isFalse()
        assertThat(isNewerVersion("1.2.1", "1.2")).isTrue()
    }

    @Test
    fun `逐段按数值比较而不是按字符串`() {
        // 字符串比较会判定 "1.10.0" < "1.9.0"，于是 1.10 发布后所有 1.9 用户都收不到更新
        assertThat(isNewerVersion("1.10.0", "1.9.0")).isTrue()
        assertThat(isNewerVersion("1.0.0", "0.9.9")).isTrue()
        assertThat(isNewerVersion("0.9.9", "1.0.0")).isFalse()
        assertThat(isNewerVersion("1.0.0", "1.0.0")).isFalse()
    }

    @Test
    fun `畸形的线上 tag 宁可漏报也不弹窗`() {
        // 发布方手滑把 tag 打成 "latest"：漏一次更新只是晚点升级，天天弹窗是每天都在骚扰
        assertThat(isNewerVersion("latest", "1.0.0")).isFalse()
        assertThat(isNewerVersion("", "1.0.0")).isFalse()
    }

    @Test
    fun `读不出本机版本时按「有更新」处理`() {
        // 本机版本解析不出来（理论上不该发生）时，提示升级比静默留在旧版本安全
        assertThat(isNewerVersion("1.0.0", "unknown")).isTrue()
    }

    @Test
    fun `parseVersionCode 只取前三段并保持单调`() {
        assertThat(parseVersionCode("1.2.3")).isEqualTo(10203)
        assertThat(parseVersionCode("1.2")).isEqualTo(10200)
        assertThat(parseVersionCode("0.9.1-debug")).isEqualTo(901)
        // 每段按两位十进制装：99 是上限，1.99.99 必须仍小于 2.0.0
        assertThat(parseVersionCode("1.99.99")).isLessThan(parseVersionCode("2.0.0"))
        // 解析不出来时给 0，而不是抛异常打断整次检查
        assertThat(parseVersionCode("nightly")).isEqualTo(0)
    }
}
