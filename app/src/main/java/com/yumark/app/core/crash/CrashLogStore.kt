package com.yumark.app.core.crash

import java.io.File

/**
 * 崩溃日志的有界文件仓库——只依赖 `java.io`，可在 JVM 单元测试里用临时目录全覆盖。
 *
 * **一条记录一个文件**，而不是往单个日志尾部追加。理由：
 * - 「启动即崩」的死循环是崩溃日志最常见的产生方式，追加模式下单文件会无上界增长；
 *   一记录一文件让轮转退化成「删最旧的几个」，逻辑简单到不容易自己出 bug。
 * - 进程正在死，写一半被杀只污染这一个文件，之前的记录不受影响。
 *
 * 文件名由 [CrashLogFormatter.fileName] 给出，定宽时间戳保证「按名字排序 = 按时间排序」，
 * 因此轮转不依赖 `lastModified()`——那个值会被系统时钟回拨搅乱。
 */
class CrashLogStore(
    private val dir: File,
    /** 最多保留几条记录 */
    private val maxFiles: Int = 20,
    /** 全部记录合计字节上限 */
    private val maxTotalBytes: Long = 512L * 1024
) {

    private val lock = Any()

    /**
     * 写入一条记录。**任何失败都返回 null 而不抛异常**：调用方通常是正在死亡的进程，
     * 在崩溃处理里再抛一个异常会让系统拿到来自 handler 的二次异常，原始堆栈就丢了。
     */
    fun write(fileName: String, text: String): File? = synchronized(lock) {
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return@runCatching null
            val target = uniqueFile(fileName)
            target.writeText(text)
            rotate()
            target
        }.getOrNull()
    }

    /** 按时间升序（= 名字升序）。目录不存在或读取失败时返回空列表。 */
    fun list(): List<File> = dir.listFiles()
        ?.filter {
            it.isFile &&
                it.name.startsWith(CrashLogFormatter.FILE_PREFIX) &&
                it.name.endsWith(CrashLogFormatter.FILE_SUFFIX)
        }
        ?.sortedBy { it.name }
        ?: emptyList()

    fun count(): Int = list().size

    fun latest(): File? = list().lastOrNull()

    /**
     * 全部记录拼成一段文本，**最新的排在最前面**：导出日志的目的通常是看刚才那次崩溃。
     * [maxBytes] 给拼接结果一个上界，避免 20 条记录拼出一个对话框撑不住的字符串。
     */
    fun exportText(maxBytes: Int = 256 * 1024): String {
        val builder = StringBuilder()
        var skipped = 0
        for (file in list().asReversed()) {
            val content = runCatching { file.readText() }.getOrNull()
            // 读不出来（被清掉/权限异常）或加上它就超上界，都记一笔跳过数，不静默丢
            if (content == null || (builder.isNotEmpty() && builder.length + content.length > maxBytes)) {
                skipped++
                continue
            }
            if (builder.isNotEmpty()) builder.append('\n').append(SEPARATOR).append('\n')
            builder.append(content.trimEnd())
        }
        if (skipped > 0) {
            if (builder.isNotEmpty()) builder.append('\n').append(SEPARATOR).append('\n')
            builder.append("（另有 $skipped 条更早的记录未包含）")
        }
        return builder.toString()
    }

    /** 删除全部记录，返回实际删掉的条数。 */
    fun clear(): Int = synchronized(lock) { list().count { it.delete() } }

    /** 同一毫秒内连续两次崩溃会撞名（罕见但可能）；撞了就加序号，绝不覆盖已有记录。 */
    private fun uniqueFile(fileName: String): File {
        val direct = File(dir, fileName)
        if (!direct.exists()) return direct
        val base = fileName.removeSuffix(CrashLogFormatter.FILE_SUFFIX)
        for (i in 1..99) {
            val candidate = File(dir, "$base-$i${CrashLogFormatter.FILE_SUFFIX}")
            if (!candidate.exists()) return candidate
        }
        // 同一毫秒占满 100 个名额：这种情况下覆盖最后一个无所谓
        return direct
    }

    /**
     * 先按条数删，再按总字节删，**永远保留最新一条**——
     * 否则一条超大记录会把自己也删掉，用户点开崩溃日志看到的是空的。
     *
     * delete() 失败时立刻 break：否则条件不变会转成死循环。
     */
    private fun rotate() {
        var files = list()
        while (files.size > maxFiles) {
            if (!files.first().delete()) return
            files = files.drop(1)
        }
        var total = files.sumOf { it.length() }
        while (files.size > 1 && total > maxTotalBytes) {
            val oldest = files.first()
            val size = oldest.length()
            if (!oldest.delete()) return
            total -= size
            files = files.drop(1)
        }
    }

    companion object {
        private const val SEPARATOR = "----------------------------------------"
    }
}
