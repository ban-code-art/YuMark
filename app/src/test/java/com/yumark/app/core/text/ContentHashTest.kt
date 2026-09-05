package com.yumark.app.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ContentHashTest {

    @Test
    fun `matches the standard SHA-256 vectors`() {
        // 固定住算法本身：换实现（比如误用 MD5 或把编码改成平台默认）会在这里断
        assertThat(ContentHash.of(""))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertThat(ContentHash.of("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `is 64 lowercase hex chars`() {
        val hash = ContentHash.of("# 标题\n正文")
        assertThat(hash).hasLength(64)
        assertThat(hash).matches("[0-9a-f]{64}")
    }

    @Test
    fun `same content hashes the same and different content differs`() {
        val a = "# 标题\n第一段。"
        assertThat(ContentHash.of(a)).isEqualTo(ContentHash.of(a))
        // 只差一个字符也必须不同——Agent 的基线校验就靠它认出「文档被改过」
        assertThat(ContentHash.of(a)).isNotEqualTo(ContentHash.of("# 标题\n第一段"))
        // 空白差异同样算改动：diff 闸门按行比对，行尾空格会改变合成结果
        assertThat(ContentHash.of("a\nb")).isNotEqualTo(ContentHash.of("a\r\nb"))
    }

    @Test
    fun `hashes CJK as UTF-8 rather than the platform charset`() {
        // 手算：UTF-8 下「中」是 E4 B8 AD。若实现漏了 Charsets.UTF_8，在
        // file.encoding 非 UTF-8 的机器上同一份正文会算出两个指纹，
        // 于是 Agent 的每条编辑提议都会被误判成「文档已被改动」。
        val expected = ContentHash.of(String(byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte()), Charsets.UTF_8))
        assertThat(ContentHash.of("中")).isEqualTo(expected)
    }
}
