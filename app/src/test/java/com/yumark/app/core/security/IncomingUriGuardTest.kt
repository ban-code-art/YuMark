package com.yumark.app.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [IncomingUriGuard] 单测。
 *
 * 重点覆盖「前缀边界」与「规范化失败」两类容易写错的分支——这两处写错的后果不是功能不对，
 * 而是 confused deputy 防线直接失效。
 */
class IncomingUriGuardTest {

    private val roots = listOf(
        "/data/user/0/com.yumark.app",
        "/storage/emulated/0/Android/data/com.yumark.app/files"
    )

    @Test
    fun `content 与 file 在白名单内`() {
        assertThat(IncomingUriGuard.isAllowedScheme("content")).isTrue()
        assertThat(IncomingUriGuard.isAllowedScheme("file")).isTrue()
    }

    @Test
    fun `其它 scheme 一律拒绝`() {
        assertThat(IncomingUriGuard.isAllowedScheme("intent")).isFalse()
        assertThat(IncomingUriGuard.isAllowedScheme("javascript")).isFalse()
        assertThat(IncomingUriGuard.isAllowedScheme("data")).isFalse()
        assertThat(IncomingUriGuard.isAllowedScheme("http")).isFalse()
        assertThat(IncomingUriGuard.isAllowedScheme("")).isFalse()
        assertThat(IncomingUriGuard.isAllowedScheme(null)).isFalse()
    }

    @Test
    fun `scheme 白名单区分大小写_调用方须先转小写`() {
        // 记录既有行为：URI scheme 本身大小写不敏感，归一化由调用方负责
        assertThat(IncomingUriGuard.isAllowedScheme("FILE")).isFalse()
    }

    @Test
    fun `私有目录内的路径被识别`() {
        assertThat(IncomingUriGuard.isInsidePrivateRoots("/data/user/0/com.yumark.app/databases/yumark.db", roots))
            .isTrue()
        assertThat(IncomingUriGuard.isInsidePrivateRoots("/data/user/0/com.yumark.app/files/documents/a.md", roots))
            .isTrue()
        assertThat(
            IncomingUriGuard.isInsidePrivateRoots(
                "/storage/emulated/0/Android/data/com.yumark.app/files/updates/YuMark-v1.0.apk",
                roots
            )
        ).isTrue()
    }

    @Test
    fun `等于私有根本身也算私有`() {
        assertThat(IncomingUriGuard.isInsidePrivateRoots("/data/user/0/com.yumark.app", roots)).isTrue()
    }

    @Test
    fun `私有根尾部斜杠不影响判定`() {
        val withSlash = listOf("/data/user/0/com.yumark.app/")
        assertThat(IncomingUriGuard.isInsidePrivateRoots("/data/user/0/com.yumark.app/f.md", withSlash)).isTrue()
        assertThat(IncomingUriGuard.isInsidePrivateRoots("/data/user/0/com.yumark.app", withSlash)).isTrue()
    }

    @Test
    fun `同前缀的兄弟包名不被误判为私有`() {
        // 边界核心：字符串前缀相同但不是同一棵目录树
        assertThat(IncomingUriGuard.isInsidePrivateRoots("/data/user/0/com.yumark.appx/note.md", roots)).isFalse()
        assertThat(IncomingUriGuard.isInsidePrivateRoots("/data/user/0/com.yumark.app.debug/note.md", roots))
            .isFalse()
    }

    @Test
    fun `外部公共目录的路径放行`() {
        assertThat(IncomingUriGuard.isInsidePrivateRoots("/storage/emulated/0/Download/note.md", roots)).isFalse()
        assertThat(IncomingUriGuard.isInsidePrivateRoots("/storage/emulated/0/Documents/a.md", roots)).isFalse()
        // 其它应用的外部私有目录不属于本应用私有根，放行（能否真读到由系统权限决定）
        assertThat(
            IncomingUriGuard.isInsidePrivateRoots("/storage/emulated/0/Android/data/com.other.app/files/x.md", roots)
        ).isFalse()
    }

    @Test
    fun `空路径按私有处理_拿不到路径就不放行`() {
        assertThat(IncomingUriGuard.isInsidePrivateRoots("", roots)).isTrue()
    }

    @Test
    fun `空私有根清单下任何非空路径都不算私有`() {
        assertThat(IncomingUriGuard.isInsidePrivateRoots("/data/user/0/com.yumark.app/f.md", emptyList())).isFalse()
    }

    @Test
    fun `空字符串私有根被忽略_不会把一切判成私有`() {
        assertThat(IncomingUriGuard.isInsidePrivateRoots("/storage/emulated/0/Download/a.md", listOf("", "/")))
            .isFalse()
    }

    @Test
    fun `canonicalOrNull 消解相对段`() {
        val canonical = IncomingUriGuard.canonicalOrNull("/a/b/../c/./d.md")
        // 结果的分隔符随平台（Windows JVM 上是反斜杠），只断言相对段已被消解
        assertThat(canonical).isNotNull()
        assertThat(canonical!!).doesNotContain("..")
        assertThat(canonical.replace('\\', '/')).endsWith("/a/c/d.md")
    }

    @Test
    fun `canonicalOrNull 对空输入返回 null`() {
        assertThat(IncomingUriGuard.canonicalOrNull(null)).isNull()
        assertThat(IncomingUriGuard.canonicalOrNull("")).isNull()
    }

    @Test
    fun `未规范化的穿越路径会绕过前缀检查_故必须先 canonical`() {
        // 同一个目标文件的两种写法：带 `..` 的原串前缀不匹配任何私有根，会被误判为安全；
        // 规范化后落回私有根内才能被识别。这就是调用方必须先过 canonicalOrNull 的原因。
        val traversal = "/storage/emulated/0/Download/../../../../data/user/0/com.yumark.app/databases/yumark.db"
        val canonical = "/data/user/0/com.yumark.app/databases/yumark.db"

        assertThat(IncomingUriGuard.isInsidePrivateRoots(traversal, roots)).isFalse()
        assertThat(IncomingUriGuard.isInsidePrivateRoots(canonical, roots)).isTrue()
    }
}
