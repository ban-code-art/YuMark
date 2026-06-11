package com.yumark.app.data.local.file

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WorkspaceScannerTest {

    private class FakeEntry(
        override val name: String?,
        override val isDirectory: Boolean,
        private val childEntries: List<FakeEntry> = emptyList(),
        override val lastModified: Long = 0L
    ) : ScanEntry {
        override val uri: String = "fake://${name}"
        override fun children(): List<ScanEntry> = childEntries
    }

    private fun dir(name: String, vararg children: FakeEntry) =
        FakeEntry(name, true, children.toList())

    private fun file(name: String) = FakeEntry(name, false)

    @Test
    fun `只收集 md markdown txt 文件`() {
        val root = dir("root", file("a.md"), file("b.markdown"), file("c.txt"), file("d.pdf"), file("e.jpg"))
        val result = WorkspaceScanner.scan(root)
        assertThat(result.root.docs.map { it.fileName })
            .containsExactly("a.md", "b.markdown", "c.txt")
    }

    @Test
    fun `跳过隐藏文件和隐藏文件夹`() {
        val root = dir("root", file(".hidden.md"), dir(".git", file("x.md")), file("ok.md"))
        val result = WorkspaceScanner.scan(root)
        assertThat(result.root.docs.map { it.fileName }).containsExactly("ok.md")
        assertThat(result.root.folders).isEmpty()
    }

    @Test
    fun `递归收集子文件夹并按名称排序`() {
        val root = dir("root", dir("b", file("2.md")), dir("a", file("1.md")))
        val result = WorkspaceScanner.scan(root)
        assertThat(result.root.folders.map { it.name }).containsExactly("a", "b").inOrder()
        assertThat(result.root.folders[0].docs.first().fileName).isEqualTo("1.md")
    }

    @Test
    fun `超过深度上限停止下钻并标记截断`() {
        var node = dir("leaf", file("deep.md"))
        repeat(WorkspaceScanner.MAX_DEPTH + 1) { i -> node = dir("d$i", node) }
        val result = WorkspaceScanner.scan(node)
        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `超过文件数上限停止收集并标记截断`() {
        val files = (0 until WorkspaceScanner.MAX_FILES + 10).map { file("f$it.md") }
        val root = FakeEntry("root", true, files)
        val result = WorkspaceScanner.scan(root)
        assertThat(result.root.docs).hasSize(WorkspaceScanner.MAX_FILES)
        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `文档显示名去掉扩展名`() {
        val root = dir("root", file("我的笔记.md"))
        val result = WorkspaceScanner.scan(root)
        assertThat(result.root.docs.first().name).isEqualTo("我的笔记")
    }

    @Test
    fun `name 为 null 的条目被跳过`() {
        val root = dir("root", FakeEntry(null, false), file("ok.md"))
        val result = WorkspaceScanner.scan(root)
        assertThat(result.root.docs.map { it.fileName }).containsExactly("ok.md")
    }
}
