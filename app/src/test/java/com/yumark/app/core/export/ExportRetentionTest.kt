package com.yumark.app.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 这里的重点是「宁可多留一会儿，也不能删掉正被外部应用读取的那一份」。
 *
 * 导出文件的 URI 已经通过 FileProvider 交给了别的进程（邮件、网盘、系统分享面板），
 * 删早了，报错发生在对方进程里，本应用的日志上什么也看不到，用户只会觉得导出坏了。
 * 所以宽限期的优先级高于份数/字节上限，只有安全阀（防止私有目录被撑爆）才允许越过它。
 */
class ExportRetentionTest {

    @TempDir
    lateinit var tempRoot: File

    private fun dir() = File(tempRoot, "exports").apply { mkdirs() }

    private fun retention(
        dir: File,
        graceMillis: Long = GRACE,
        maxFiles: Int = 20,
        maxTotalBytes: Long = 128L * 1024 * 1024
    ) = ExportRetention(dir, graceMillis, maxFiles, maxTotalBytes)

    /**
     * 造一份导出：[ageMillis] 是相对 [NOW] 的年龄，[size] 是字节数。
     *
     * setLastModified 的返回值必须断言：它在个别文件系统上会静默失败，那时所有
     * 「按年龄删」的断言都会以极难理解的方式挂掉，排查方向完全被带偏。
     */
    private fun export(dir: File, name: String, ageMillis: Long, size: Int = 16): File {
        val file = File(dir, name)
        file.writeBytes(ByteArray(size))
        assertThat(file.setLastModified(NOW - ageMillis)).isTrue()
        return file
    }

    private fun names(dir: File) = dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    // ---- 宽限期 ----

    @Test
    fun `宽限期内的导出一份都不删`() {
        val dir = dir()
        export(dir, "刚导出.pdf", ageMillis = 0)
        export(dir, "一分钟前.html", ageMillis = 60_000)

        val deleted = retention(dir).prune(NOW)

        assertThat(deleted).isEqualTo(0)
        assertThat(names(dir)).containsExactly("刚导出.pdf", "一分钟前.html")
    }

    @Test
    fun `超过宽限期的导出全部删掉`() {
        val dir = dir()
        export(dir, "旧.pdf", ageMillis = GRACE + 1)
        export(dir, "更旧.html", ageMillis = GRACE * 10)
        export(dir, "新.png", ageMillis = 1000)

        val deleted = retention(dir).prune(NOW)

        assertThat(deleted).isEqualTo(2)
        assertThat(names(dir)).containsExactly("新.png")
    }

    @Test
    fun `刚好卡在宽限期边界上按删除处理`() {
        val dir = dir()
        // prune 的判定是 now - lastModified < grace 才保留，等于 grace 落在删除一侧。
        // 这条固定住边界语义，避免以后改条件时把它悄悄挪成 <=。
        export(dir, "边界.pdf", ageMillis = GRACE)

        assertThat(retention(dir).prune(NOW)).isEqualTo(1)
        assertThat(names(dir)).isEmpty()
    }

    @Test
    fun `时钟回拨到文件时间之前不删任何东西`() {
        val dir = dir()
        // 文件时间在 now 之后（用户把系统时间往前调过），now - lastModified 为负，
        // 落在「还在宽限期内」一侧。偏向保留是安全方向：删错的代价远大于多留一会儿。
        export(dir, "来自未来.pdf", ageMillis = -GRACE * 10)

        assertThat(retention(dir).prune(NOW)).isEqualTo(0)
        assertThat(names(dir)).containsExactly("来自未来.pdf")
    }

    // ---- 安全阀：份数与总字节 ----

    @Test
    fun `份数超上限时越过宽限期删最旧的`() {
        val dir = dir()
        // 全部都在宽限期内，只有安全阀能删它们
        repeat(5) { export(dir, "第$it.pdf", ageMillis = (5 - it) * 1000L) }

        val deleted = retention(dir, maxFiles = 2).prune(NOW)

        assertThat(deleted).isEqualTo(3)
        // 留下的是最新的两份：age 越小越新，第 3 / 第 4
        assertThat(names(dir)).containsExactly("第3.pdf", "第4.pdf")
    }

    @Test
    fun `总字节超上限时从最旧删起`() {
        val dir = dir()
        export(dir, "旧.pdf", ageMillis = 3000, size = 400)
        export(dir, "中.pdf", ageMillis = 2000, size = 400)
        export(dir, "新.pdf", ageMillis = 1000, size = 400)

        val deleted = retention(dir, maxTotalBytes = 900).prune(NOW)

        assertThat(deleted).isEqualTo(1)
        assertThat(names(dir)).containsExactly("中.pdf", "新.pdf")
    }

    @Test
    fun `单份就超总字节上限时仍保留最新一份`() {
        val dir = dir()
        // 否则一份超大长图会把自己也删掉，用户点分享时文件已经不在了
        export(dir, "超大长图.png", ageMillis = 1000, size = 500)

        assertThat(retention(dir, maxTotalBytes = 100).prune(NOW)).isEqualTo(0)
        assertThat(names(dir)).containsExactly("超大长图.png")
    }

    // ---- 边缘情况 ----

    @Test
    fun `目录不存在时返回零且不抛异常`() {
        val missing = File(tempRoot, "从未创建")

        assertThat(retention(missing).prune(NOW)).isEqualTo(0)
        assertThat(retention(missing).list()).isEmpty()
    }

    @Test
    fun `子目录既不删也不计入份数`() {
        val dir = dir()
        val nested = File(dir, "子目录").apply { mkdirs() }
        export(nested, "藏在里面.pdf", ageMillis = GRACE * 10)
        export(dir, "该删的.pdf", ageMillis = GRACE * 10)

        // maxFiles = 0 也不该动子目录：list() 只认 isFile
        val deleted = retention(dir, maxFiles = 0).prune(NOW)

        assertThat(deleted).isEqualTo(1)
        assertThat(names(dir)).containsExactly("子目录")
        assertThat(File(nested, "藏在里面.pdf").exists()).isTrue()
    }

    @Test
    fun `list 按时间升序`() {
        val dir = dir()
        export(dir, "a.pdf", ageMillis = 1000)
        export(dir, "b.pdf", ageMillis = 2000)
        export(dir, "c.pdf", ageMillis = 3000)

        // 名字升序是 a b c，时间升序是 c(最旧) b a——刻意排成相反，
        // 否则这条用名字排序也能过，等于没有验证「按时间排」。
        assertThat(retention(dir).list().map { it.name })
            .containsExactly("c.pdf", "b.pdf", "a.pdf").inOrder()
    }

    companion object {
        /** 固定的「现在」：所有断言都相对它算，不受真实时钟影响。 */
        private const val NOW = 1_700_000_000_000L

        /** 测试用宽限期，取小值让边界断言写起来直观；生产默认值见 ExportRetention。 */
        private const val GRACE = 10 * 60 * 1000L
    }
}
