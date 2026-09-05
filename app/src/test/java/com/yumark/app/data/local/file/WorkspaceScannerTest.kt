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
    fun `超过深度上限只标记深度截断且不标记文件数截断`() {
        var node = dir("leaf", file("deep.md"))
        repeat(WorkspaceScanner.MAX_DEPTH + 1) { i -> node = dir("d$i", node) }
        val result = WorkspaceScanner.scan(node)
        assertThat(result.depthLimitHit).isTrue()
        // 这条断言是整个改动的理由：这棵树一个文档都没收到，却曾经会被界面说成
        // 「文件过多，仅显示前 2000 个文档」。两个标志混成一个 boolean 就分不出来了。
        assertThat(result.fileLimitHit).isFalse()
        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `超过文件数上限只标记文件数截断且不标记深度截断`() {
        val files = (0 until WorkspaceScanner.MAX_FILES + 10).map { file("f$it.md") }
        val root = FakeEntry("root", true, files)
        val result = WorkspaceScanner.scan(root)
        assertThat(result.root.docs).hasSize(WorkspaceScanner.MAX_FILES)
        assertThat(result.fileLimitHit).isTrue()
        assertThat(result.depthLimitHit).isFalse()
        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `两个上限同时撞上时两个标志都为真`() {
        // 深目录排在文件洪水**前面**：撞文件数上限的 break 只跳出当前目录的遍历，
        // 排在它后面的目录就再也不会被下钻。顺序反过来这个用例会假绿。
        var deep = dir("leaf", file("deep.md"))
        repeat(WorkspaceScanner.MAX_DEPTH + 1) { i -> deep = dir("d$i", deep) }
        val files = (0 until WorkspaceScanner.MAX_FILES + 10).map { file("f$it.md") }
        val result = WorkspaceScanner.scan(FakeEntry("root", true, listOf(deep) + files))
        assertThat(result.fileLimitHit).isTrue()
        assertThat(result.depthLimitHit).isTrue()
    }

    @Test
    fun `普通目录树两个截断标志都为假`() {
        val root = dir("root", dir("sub", file("a.md")), file("b.md"))
        val result = WorkspaceScanner.scan(root)
        assertThat(result.fileLimitHit).isFalse()
        assertThat(result.depthLimitHit).isFalse()
        assertThat(result.truncated).isFalse()
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

    @Test
    fun `收满上限后紧跟一个不支持的文件不算文件数截断`() {
        // 旧实现把上限判定放在扩展名过滤之前：计数正好到 MAX_FILES 时来一张 .jpg，
        // 它就把 fileLimitHit 顶起来并 break——可那张图片本来就不进树，一篇文档都没少收，
        // 用户会照着「仅显示前 2000 个文档」的提示去找根本不存在的漏项。
        val files = (0 until WorkspaceScanner.MAX_FILES).map { file("f$it.md") } + file("photo.jpg")
        val result = WorkspaceScanner.scan(FakeEntry("root", true, files))
        assertThat(result.root.docs).hasSize(WorkspaceScanner.MAX_FILES)
        assertThat(result.fileLimitHit).isFalse()
        assertThat(result.truncated).isFalse()
    }
}
