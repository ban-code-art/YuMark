package com.yumark.app.data.local.file

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * [SyncTrashStore] 的 JVM 全覆盖：救援落盘、命名自洽（按名字排序=按时间排序）、
 * 有界轮转、导出拼接、清除。全部走真实文件系统（@TempDir），不 mock。
 */
class SyncTrashStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun store(maxFiles: Int = 100, maxTotalBytes: Long = 64L * 1024 * 1024) =
        SyncTrashStore(File(tempDir, "sync_trash"), maxFiles, maxTotalBytes)

    @Test
    fun `rescue 写入正文并返回落在目录内的文件`() {
        val s = store()
        val file = s.rescue("d1", "笔记", "# 标题\n正文", now = 1_756_800_000_000L)

        assertThat(file.exists()).isTrue()
        assertThat(file.readText()).isEqualTo("# 标题\n正文")
        assertThat(file.parentFile).isEqualTo(File(tempDir, "sync_trash"))
        assertThat(s.count()).isEqualTo(1)
    }

    @Test
    fun `文件名以定宽时间戳开头，按名字排序就是按时间排序`() {
        // 轮转删「最旧的」依赖这个不变量；时间戳不定宽（如单数字月）时排序就断了
        val s = store()
        s.rescue("a", "A", "x", now = 1_756_800_000_000L)          // 2025-09-02 前后
        s.rescue("b", "B", "y", now = 1_756_800_000_000L + 5_000L)

        val names = s.list().map { it.name }
        assertThat(names).hasSize(2)
        assertThat(names).isInOrder(Comparator<String> { a, b -> a.compareTo(b) })
        // 更晚的 now 排在后面（按名字）
        assertThat(names[1]).startsWith(SyncTrashStore.FILE_PREFIX)
        assertThat(names[0].substringAfter(SyncTrashStore.FILE_PREFIX).take(15))
            .isLessThan(names[1].substringAfter(SyncTrashStore.FILE_PREFIX).take(15))
    }

    @Test
    fun `标题里的非法字符被消毒，空标题回退默认名`() {
        // 标题来自数据库，也终将拼进文件名：带 `/` 或 `..` 的标题不能穿透到路径
        val name = SyncTrashStore.fileNameOf("a/b:笔记?", now = 1_756_800_000_000L)
        assertThat(name).doesNotContain("/")
        assertThat(name).doesNotContain(":")
        assertThat(name).doesNotContain("?")
        assertThat(name).endsWith(".md")

        val blank = SyncTrashStore.fileNameOf("  ", now = 1_756_800_000_000L)
        assertThat(blank).contains("document")
    }

    @Test
    fun `超长标题按码点截断，不留半个代理对`() {
        val emoji = "😀".repeat(150)  // 300 个 Char
        val name = SyncTrashStore.fileNameOf(emoji, now = 1_756_800_000_000L)
        assertThat(name.length).isAtMost(
            SyncTrashStore.FILE_PREFIX.length + SyncTrashStore.STAMP_FORMAT.length + 1 +
                200 + SyncTrashStore.FILE_SUFFIX.length
        )
        // 截断处不在代理对中间：名中每个高位代理必须跟着低位代理
        val highIndices = name.indices.filter { name[it].isHighSurrogate() }
        highIndices.forEach { i ->
            assertThat(i + 1 < name.length).isTrue()
            assertThat(name[i + 1].isLowSurrogate()).isTrue()
        }
    }

    @Test
    fun `份数超限时从最旧删起，永远保留最新一份`() {
        val s = store(maxFiles = 3)
        for (i in 0 until 5) {
            s.rescue("d$i", "标题$i", "内容$i", now = 1_756_800_000_000L + i * 1_000L)
        }

        assertThat(s.count()).isEqualTo(3)
        // 留下的是最后三份（最新的在前）
        val text = s.exportText()
        assertThat(text).contains("内容4")
        assertThat(text).contains("内容3")
        assertThat(text).contains("内容2")
        assertThat(text).doesNotContain("内容0")
        assertThat(text).doesNotContain("内容1")
    }

    @Test
    fun `总字节超限时从最旧删起，单份超限时自己保命`() {
        // 每份 ~1KB，总限 2.5KB → 第 3 份写入后第 1 份被删
        val s = store(maxFiles = 100, maxTotalBytes = 2_500L)
        val body = "x".repeat(1_000)
        for (i in 0 until 3) {
            s.rescue("d$i", "t$i", body + "\n标记$i", now = 1_756_800_000_000L + i * 1_000L)
        }

        assertThat(s.count()).isAtLeast(1)
        val text = s.exportText()
        // 最新一份永远在
        assertThat(text).contains("标记2")
        assertThat(text).doesNotContain("标记0")
    }

    @Test
    fun `exportText 最新的排最前，超上界记跳过数不静默丢`() {
        val s = store()
        s.rescue("a", "A", "旧的内容", now = 1_756_800_000_000L)
        s.rescue("b", "B", "新的内容", now = 1_756_800_000_000L + 1_000L)

        val text = s.exportText()
        // 最新的在前（导出的目的通常是救回最近删的那篇）
        assertThat(text.indexOf("新的内容")).isLessThan(text.indexOf("旧的内容"))
        // 限到只容第一份时（第一份 4 字已入、第二份再入就越界），另一份被跳过且明确说明。
        // 判定单位是 Char 数（与 CrashLogStore 的导出拼接同款），maxBytes=5 即触发。
        val tight = s.exportText(maxBytes = 5)
        assertThat(tight).contains("新的内容")
        assertThat(tight).doesNotContain("旧的内容")
        assertThat(tight).contains("份更早的备份未包含")
    }

    @Test
    fun `clear 删除全部并清点，空目录上返回 0`() {
        val s = store()
        assertThat(s.clear()).isEqualTo(0)
        s.rescue("a", "A", "x", now = 1_756_800_000_000L)
        assertThat(s.clear()).isEqualTo(1)
        assertThat(s.count()).isEqualTo(0)
    }

    @Test
    fun `list 只认自己的文件，别的文件不进也不被轮转误删`() {
        val s = store(maxFiles = 1)
        val dir = File(tempDir, "sync_trash")
        dir.mkdirs()
        // 别人放进目录的文件（不合规名）
        val stranger = File(dir, "note.md")
        stranger.writeText("他人文件")

        s.rescue("a", "A", "x", now = 1_756_800_000_000L)
        s.rescue("b", "B", "y", now = 1_756_800_000_000L + 1_000L)

        assertThat(s.count()).isEqualTo(1)
        // 轮转只删自己的文件；他人文件仍在（不会被当成自己的删掉）
        assertThat(stranger.exists()).isTrue()
    }

    @Test
    fun `目标目录是文件而非目录时 rescue 抛 IOException 而不是静默`() {
        // mkdirs 失败（被同名文件占位）必须以失败告终——静默成功=救援没发生=照删
        val dir = File(tempDir, "sync_trash")
        dir.parentFile.mkdirs()
        dir.writeText("我是一个占位文件")
        val s = SyncTrashStore(dir)

        val error = runCatching { s.rescue("a", "A", "x") }.exceptionOrNull()
        // AtomicFileReplace.replace 的 tmp 写入/名字都会在「目录是文件」时炸出 IOException
        assertThat(error).isNotNull()
        assertThat(error).isInstanceOf(IOException::class.java)
    }
}
