package com.yumark.app.core.crash

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 轮转与「安静失败」是这里的重点：崩溃日志写入发生在进程正在死的时候，
 * 既不能抛异常，也不能让日志无上界长大，更不能把最新一条也轮转掉。
 */
class CrashLogStoreTest {

    @TempDir
    lateinit var tempRoot: File

    private fun dir() = File(tempRoot, "crash")

    private fun store(
        dir: File = dir(),
        maxFiles: Int = 20,
        maxTotalBytes: Long = 512L * 1024
    ) = CrashLogStore(dir, maxFiles, maxTotalBytes)

    /** 第 index 条记录的文件名：时间戳递增，保证「名字升序 == 时间升序」。 */
    private fun nameOf(index: Int, fatal: Boolean = true) =
        CrashLogFormatter.fileName(BASE_MILLIS + index * 1000L, fatal)

    // ---- 基本读写 ----

    @Test
    fun `写入后能原样读回`() {
        val store = store()

        val file = store.write(nameOf(0), "崩溃正文")

        assertThat(file).isNotNull()
        assertThat(file!!.name).isEqualTo(nameOf(0))
        assertThat(file.readText()).isEqualTo("崩溃正文")
    }

    @Test
    fun `目录不存在时自动建出来`() {
        val nested = File(tempRoot, "a/b/crash")

        assertThat(store(dir = nested).write(nameOf(0), "x")).isNotNull()
        assertThat(nested.isDirectory).isTrue()
    }

    @Test
    fun `空目录的读取接口都给安全默认值`() {
        val store = store() // 目录还没建

        assertThat(store.list()).isEmpty()
        assertThat(store.count()).isEqualTo(0)
        assertThat(store.latest()).isNull()
        assertThat(store.exportText()).isEmpty()
        assertThat(store.clear()).isEqualTo(0)
    }

    @Test
    fun `list 升序_latest 取最新`() {
        val store = store()
        store.write(nameOf(0), "第一条")
        store.write(nameOf(1), "第二条")
        store.write(nameOf(2), "第三条")

        assertThat(store.count()).isEqualTo(3)
        assertThat(store.list().map { it.name }).isInOrder()
        assertThat(store.latest()!!.readText()).isEqualTo("第三条")
    }

    @Test
    fun `撞名时加序号而不覆盖`() {
        val store = store()
        val name = nameOf(0)

        val first = store.write(name, "先来的")
        val second = store.write(name, "同一毫秒又崩一次")

        assertThat(second!!.name).isNotEqualTo(first!!.name)
        assertThat(store.count()).isEqualTo(2)
        assertThat(first.readText()).isEqualTo("先来的")
        assertThat(second.readText()).isEqualTo("同一毫秒又崩一次")
    }

    // ---- 轮转 ----

    @Test
    fun `超过条数上限时删掉最旧的`() {
        val store = store(maxFiles = 3)
        repeat(5) { store.write(nameOf(it), "记录$it") }

        assertThat(store.count()).isEqualTo(3)
        assertThat(store.list().map { it.readText() })
            .containsExactly("记录2", "记录3", "记录4").inOrder()
    }

    @Test
    fun `条数上限为 1 时始终只留最新一条`() {
        val store = store(maxFiles = 1)
        store.write(nameOf(0), "旧")
        store.write(nameOf(1), "新")

        assertThat(store.count()).isEqualTo(1)
        assertThat(store.latest()!!.readText()).isEqualTo("新")
    }

    @Test
    fun `超过总字节上限时删旧留新`() {
        val store = store(maxTotalBytes = 250)
        repeat(4) { store.write(nameOf(it), "x".repeat(100)) }

        assertThat(store.count()).isEqualTo(2)
        assertThat(store.latest()!!.name).isEqualTo(nameOf(3))
        assertThat(store.list().sumOf { it.length() }).isAtMost(250)
    }

    @Test
    fun `单条超上限时不会把自己删掉`() {
        // 否则用户点开崩溃日志看到的是空的——最需要那条记录的时候恰好没有
        val store = store(maxTotalBytes = 10)

        store.write(nameOf(0), "y".repeat(500))
        assertThat(store.count()).isEqualTo(1)

        store.write(nameOf(1), "z".repeat(500))
        assertThat(store.count()).isEqualTo(1)
        assertThat(store.latest()!!.readText()).startsWith("z")
    }

    // ---- 导出与清理 ----

    @Test
    fun `exportText 最新的排在最前`() {
        val store = store()
        store.write(nameOf(0), "最旧")
        store.write(nameOf(1), "中间")
        store.write(nameOf(2), "最新")

        val out = store.exportText()

        assertThat(out.indexOf("最新")).isLessThan(out.indexOf("中间"))
        assertThat(out.indexOf("中间")).isLessThan(out.indexOf("最旧"))
        assertThat(out).contains("----")
    }

    @Test
    fun `exportText 超上界时说明少了几条`() {
        val store = store()
        repeat(3) { store.write(nameOf(it), "记录$it-" + "p".repeat(50)) }

        val out = store.exportText(maxBytes = 60)

        assertThat(out).contains("记录2")
        assertThat(out).doesNotContain("记录0")
        assertThat(out).contains("另有 2 条")
    }

    @Test
    fun `clear 返回删除条数并清空`() {
        val store = store()
        repeat(3) { store.write(nameOf(it), "记录$it") }

        assertThat(store.clear()).isEqualTo(3)
        assertThat(store.count()).isEqualTo(0)
        assertThat(store.latest()).isNull()
    }

    // ---- 边界 ----

    @Test
    fun `目录里的无关文件既不被读也不被删`() {
        val store = store()
        store.write(nameOf(0), "崩溃")
        val foreign = File(dir(), "readme.txt").apply { writeText("别动我") }
        val wrongSuffix = File(dir(), "crash-20240101-000000000-fatal.txt")
            .apply { writeText("我也不是日志") }

        assertThat(store.count()).isEqualTo(1)
        assertThat(store.exportText()).doesNotContain("别动我")
        assertThat(store.clear()).isEqualTo(1)
        assertThat(foreign.exists()).isTrue()
        assertThat(wrongSuffix.exists()).isTrue()
    }

    @Test
    fun `目录位置被普通文件占住时返回 null 而不抛`() {
        // 崩溃处理里再抛异常会顶掉原始堆栈，所以这条路径必须安静失败
        val occupied = File(tempRoot, "crash").apply { writeText("我是个文件") }
        val store = store(dir = occupied)

        assertThat(store.write(nameOf(0), "崩溃")).isNull()
        assertThat(store.count()).isEqualTo(0)
        assertThat(store.exportText()).isEmpty()
    }

    private companion object {
        const val BASE_MILLIS = 1_700_000_000_000L
    }
}
