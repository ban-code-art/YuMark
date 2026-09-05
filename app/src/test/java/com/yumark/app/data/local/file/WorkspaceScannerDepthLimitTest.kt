package com.yumark.app.data.local.file

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.WorkspaceNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [WorkspaceScanner] 的深度上限判定，用**真实目录**跑。
 *
 * 与同目录下 WorkspaceScannerTest 的 FakeEntry 分工不同：那边验的是收集规则，这边验的是
 * 「到底有没有少收东西」。这个判断依赖 children() 的真实返回，用真目录才能保证
 * 判定看到的内容和收集看到的是同一份。
 *
 * depthLimitHit 的含义是「更深的内容没读到」。到达上限的目录本身可能空着，也可能只装着
 * 隐藏项或不支持的扩展名——那时置位等于告诉用户「有内容没显示」，而他点进去只会看到空目录。
 */
class WorkspaceScannerDepthLimitTest {

    @TempDir
    lateinit var tempRoot: File

    private val workspaceRoot: File get() = File(tempRoot, "ws")

    /** 造出 ws/lvl1/…/lvl10 并返回最后那一层：它正好落在深度上限上，children() 不参与收集。 */
    private fun deepestDir(): File {
        var cur = workspaceRoot
        repeat(WorkspaceScanner.MAX_DEPTH) { i -> cur = File(cur, "lvl${i + 1}") }
        check(cur.mkdirs() || cur.isDirectory) { "建不出深目录：${cur.path}" }
        return cur
    }

    private fun scanWorkspace() = WorkspaceScanner.scan(FileEntry(workspaceRoot))

    /** 递归收集整棵树的文件名，用来确认某篇文档确实进了树。 */
    private fun allFileNames(node: WorkspaceNode): List<String> =
        node.docs.map { it.fileName } + node.folders.flatMap { allFileNames(it) }

    /** 真实目录喂给扫描器的 ScanEntry：DocumentFile 在 JVM 单测里跑不了，File 可以。 */
    private class FileEntry(private val file: File) : ScanEntry {
        override val name: String? get() = file.name
        override val isDirectory: Boolean get() = file.isDirectory
        override val uri: String get() = file.toURI().toString()
        override val lastModified: Long get() = file.lastModified()
        override fun children(): List<ScanEntry> = file.listFiles()?.map(::FileEntry) ?: emptyList()
    }

    @Test
    fun `深度上限处目录为空时不报深度截断`() {
        deepestDir()
        val result = scanWorkspace()

        // 一层不落地扫完了，只是最深那层本来就没东西：这不是截断
        assertThat(result.depthLimitHit).isFalse()
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `深度上限处有文档时报深度截断`() {
        File(deepestDir(), "deep.md").writeText("# 深处的笔记")

        val result = scanWorkspace()

        assertThat(result.depthLimitHit).isTrue()
        // 文档一篇都没收够 2000，不能顺手把文件数上限也点亮
        assertThat(result.fileLimitHit).isFalse()
    }

    @Test
    fun `深度上限处有子目录时报深度截断`() {
        // 空子目录也算：它本来会成为树上的一个节点，用户在界面上就是少看到一个文件夹
        check(File(deepestDir(), "deeper").mkdir())

        assertThat(scanWorkspace().depthLimitHit).isTrue()
    }

    @Test
    fun `深度上限处只有隐藏项和不支持的扩展名时不报深度截断`() {
        val deepest = deepestDir()
        File(deepest, ".hidden.md").writeText("x")
        File(deepest, "扫描件.pdf").writeText("x")
        check(File(deepest, ".git").mkdir())

        // 这三样即便下钻也一律不收，少收为零；报「层级过深」会让用户去找根本不会显示的内容
        assertThat(scanWorkspace().depthLimitHit).isFalse()
    }

    @Test
    fun `判定深度截断不影响上层已收集的内容`() {
        File(deepestDir(), "deep.md").writeText("x")
        File(workspaceRoot, "top.md").writeText("x")

        val result = scanWorkspace()

        // 上限那一层多读一次 children() 只为了判定，不能把树本身改坏
        assertThat(result.root.docs.map { it.fileName }).containsExactly("top.md")
        assertThat(result.root.folders.map { it.name }).containsExactly("lvl1")
        // 上限之外的 deep.md 依旧收不到——判定只改提示，不改收集范围
        assertThat(allFileNames(result.root)).containsExactly("top.md")
    }

    @Test
    fun `正好落在上限之内的文档仍会被收集`() {
        // lvl9 在深度 9，仍然会被读。这条把「上限落在哪一层」钉住：深度算术若改成
        // depth > MAX_DEPTH 或从 1 起算，这里立刻会红。
        var cur = workspaceRoot
        repeat(WorkspaceScanner.MAX_DEPTH - 1) { i -> cur = File(cur, "lvl${i + 1}") }
        check(cur.mkdirs())
        File(cur, "edge.md").writeText("x")

        val result = scanWorkspace()

        assertThat(result.depthLimitHit).isFalse()
        assertThat(allFileNames(result.root)).contains("edge.md")
    }
}
