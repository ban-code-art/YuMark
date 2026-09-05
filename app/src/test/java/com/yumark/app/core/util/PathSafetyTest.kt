package com.yumark.app.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * 路径包含校验的回归锁。
 *
 * 这层校验是「文件不许写到私有目录外」的最后一道防线，而它此前的实现是
 * `canonicalFile.path.startsWith(canonicalDir.path)`——字符串前缀比较，
 * 同级目录只要名字以允许目录名开头就能骗过它。这种错写起来毫无违和感、
 * 读起来也像对的，唯一能钉住它的就是下面 `同级目录名以允许目录名开头时必须拒绝`
 * 这条用例：它在旧实现下会失败，在段比较实现下才通过。
 */
class PathSafetyTest {

    @TempDir
    lateinit var tempRoot: File

    /** 被允许写入的目录，对应真实代码里的 `files/documents`、`files/exports`。 */
    private val allowedDir: File by lazy { File(tempRoot, "documents").apply { mkdirs() } }

    // ---- 应当放行 ----

    @Test
    fun `目录内的文件放行`() {
        PathSafety.requireInside(File(allowedDir, "a.md"), allowedDir)
    }

    @Test
    fun `嵌套子目录内的文件放行`() {
        PathSafety.requireInside(File(allowedDir, "sub/deep/a.md"), allowedDir)
    }

    @Test
    fun `目录自身放行`() {
        // startsWith 在两个路径相等时为 true。真实调用点不会传目录，
        // 但这条固定住语义是「含边界」，免得后来有人改成严格真子路径。
        PathSafety.requireInside(allowedDir, allowedDir)
    }

    @Test
    fun `文件不存在也能判定`() {
        // canonicalFile 不要求路径真实存在——保存新文档时目标文件本来就还没有。
        val notYet = File(allowedDir, "never-created.md")
        assumeTrue(!notYet.exists())

        PathSafety.requireInside(notYet, allowedDir)
    }

    @Test
    fun `路径里的多余分隔与点号被规范化后放行`() {
        PathSafety.requireInside(File(allowedDir, "./sub/../a.md"), allowedDir)
    }

    // ---- 应当拒绝 ----

    @Test
    fun `同级目录名以允许目录名开头时必须拒绝`() {
        // 本类存在的理由。字符串前缀比较下
        // "…/documents-backup/x.md".startsWith("…/documents") == true，旧实现放行；
        // 段比较里 documents-backup 与 documents 是两个不同的段，拒绝。
        val sibling = File(tempRoot, "documents-backup").apply { mkdirs() }
        val escaped = File(sibling, "x.md")

        assertThat(escaped.canonicalPath).startsWith(allowedDir.canonicalPath)

        assertThrows<SecurityException> { PathSafety.requireInside(escaped, allowedDir) }
    }

    @Test
    fun `仅多一个字符的同级目录同样拒绝`() {
        val sibling = File(tempRoot, "documents2").apply { mkdirs() }

        assertThrows<SecurityException> {
            PathSafety.requireInside(File(sibling, "x.md"), allowedDir)
        }
    }

    @Test
    fun `用点点跳出目录时拒绝`() {
        assertThrows<SecurityException> {
            PathSafety.requireInside(File(allowedDir, "../outside.md"), allowedDir)
        }
    }

    @Test
    fun `多层点点跳到目录树之外时拒绝`() {
        assertThrows<SecurityException> {
            PathSafety.requireInside(File(allowedDir, "../../../etc/passwd"), allowedDir)
        }
    }

    @Test
    fun `完全无关的目录拒绝`() {
        val elsewhere = File(tempRoot, "images").apply { mkdirs() }

        assertThrows<SecurityException> {
            PathSafety.requireInside(File(elsewhere, "x.png"), allowedDir)
        }
    }

    @Test
    fun `父目录拒绝`() {
        assertThrows<SecurityException> { PathSafety.requireInside(tempRoot, allowedDir) }
    }

    @Test
    fun `指向目录外的符号链接拒绝`() {
        // 单纯的段比较挡不住这个：link 的路径段确实以 allowedDir 开头。
        // 能拒绝掉是因为先做了 canonical 化——这条用例钉住的就是「canonical 那一步不能省」。
        val outside = File(tempRoot, "outside").apply { mkdirs() }
        val link = File(allowedDir, "link")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        // Windows 上创建符号链接需要 SeCreateSymbolicLinkPrivilege（开发者模式或管理员）。
        assumeTrue(created, "当前环境不允许创建符号链接，跳过")
        // 而且即便建得出来，Windows 的 File.getCanonicalPath 也不解析符号链接
        // （已实测：…\documents\link\x.md 原样返回，只有 Path.toRealPath 会解析到 …\outside）。
        // 生产环境是 Android/Linux，那里走 realpath(3) 会解析——所以断言只在具备该能力的
        // 平台上跑（CI 的 Linux runner 就是），拿本机 Windows 的平台差异去染红测试没有意义。
        assumeTrue(canonicalResolvesSymlinks(), "当前平台的 canonicalPath 不解析符号链接，跳过")

        assertThrows<SecurityException> {
            PathSafety.requireInside(File(link, "x.md"), allowedDir)
        }
    }

    /**
     * 探测本平台 [File.getCanonicalPath] 是否解析符号链接。
     *
     * 探测而不是判断 `os.name`：需要的是「这个行为在不在」，而不是「是不是 Windows」——
     * 换了 JDK 实现或文件系统，OS 名字不变而行为可能变。
     */
    private fun canonicalResolvesSymlinks(): Boolean {
        val probeTarget = File(tempRoot, "probe-target").apply { mkdirs() }
        val probeLink = File(tempRoot, "probe-link")
        if (runCatching { Files.createSymbolicLink(probeLink.toPath(), probeTarget.toPath()) }.isFailure) {
            return false
        }
        return probeLink.canonicalPath == probeTarget.canonicalPath
    }

    // ---- 报错内容 ----

    @Test
    fun `拒绝时报错带上 label 与两侧规范路径`() {
        val outside = File(tempRoot, "images").apply { mkdirs() }

        val e = assertThrows<SecurityException> {
            PathSafety.requireInside(File(outside, "x.png"), allowedDir, label = "Output path")
        }

        // 绝对路径只进崩溃日志，不上界面（SecurityException 没实现 UserFacingMessage，
        // ErrorHandler 会换成「路径不合法或权限不足」）。label 用来区分是哪一层拦下的。
        assertThat(e).hasMessageThat().startsWith("Output path ")
        assertThat(e).hasMessageThat().contains(allowedDir.canonicalPath)
        assertThat(e).hasMessageThat().contains("is outside allowed directory")
    }

    @Test
    fun `label 默认值为 File path`() {
        val e = assertThrows<SecurityException> {
            PathSafety.requireInside(File(tempRoot, "x.md"), allowedDir)
        }

        assertThat(e).hasMessageThat().startsWith("File path ")
    }
}
