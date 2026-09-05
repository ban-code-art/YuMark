package com.yumark.app.data.local.file

import com.yumark.app.core.util.AtomicFileReplace
import com.yumark.app.core.util.PathSafety
import com.yumark.app.core.validation.FileNameValidator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * 远端删除的**正文救援副本**——`filesDir/sync_trash/` 下的有界文件仓库。
 *
 * ## 它堵的是哪个洞
 *
 * `SyncAction.DeleteLocal` 走 [com.yumark.app.data.repository.DocumentRepositoryImpl.deleteDocument]，
 * 而 `documents` 行的 CASCADE 会把同一篇的 `document_versions` 整段带走——「远端删了、本地库行
 * 也没了」之后，那篇正文在世界上任何地方都不再存在，而版本史存在的意义恰恰就是这一刻的兜底。
 * 详情见 `SyncPlanner.kt` 的 DeleteLocal 注释（「误删一篇，那份内容在任何地方都不再存在」）。
 *
 * 修法不是动 CASCADE（FK NOT NULL，改 SET NULL 要动表结构）：**删除前**把已落盘的正文原样
 * 落一份救援副本。本地内容此刻与基线一致（DeleteLocal 的前置条件），正是最后一份拷贝。
 *
 * ## 为什么一份正文就够、不搬整段版本史
 *
 * DocumentVersionEntity 的 FK 是 CASCADE **且 document_id NOT NULL**：版本行结构上就活不过
 * 文档行，救版本史等于要搬家再搬回来，搬家期间文档行已删、FK 必违反。而 DeleteLocal 只在
 * 「本地自基线以来一字未改」时触发（[com.yumark.app.data.sync.SyncPlanner]），此刻的正文
 * 就是那篇文档生命周期里的最后状态——与基线相等的内容。要恢复的是「这一篇」，不是「中间过程」。
 *
 * ## 为什么放私有目录、走 FileProvider + SAF 导出，而不是拉回库
 *
 * 救援是**保底**，不是工作流：拉回库等于把远端删除否决掉，而删除可能正是用户在另一台设备上
 * 的明确意图（在设备 A 删、同步到设备 B 时是 DeleteLocal 下行）。判断权留给用户：设置页
 * 「同步删除的正文备份」入口提供查看与导出，导入回来是既有能力（打开 .md / 恢复冲突副本那套）。
 *
 * ## 保留策略
 *
 * [MAX_TRASH_FILES] / [MAX_TRASH_TOTAL_BYTES] 有界保留（默认 100 份 / 64 MiB），超限从最旧
 * 删起，**永远保留最新一份**——写入失败可以接受（同步动作判失败、下轮重试，远端已没了重试
 * 无害），但「成功了却发现自己刚写的副本已被轮转掉」不行。时间判定不用 `lastModified()`
 * （时钟回拨会搅乱），文件名里的 [STAMP_FORMAT] 定宽时间戳保证「按名字排序 = 按时间排序」，
 * 与 [com.yumark.app.core.crash.CrashLogStore] 的轮转是同一套哲学。
 *
 * 只依赖 `java.io`（+[AtomicFileReplace] 与 [PathSafety] 也都只依赖 JDK），可用临时目录在
 * JVM 单测里全覆盖。
 */
class SyncTrashStore(
    private val dir: File,
    private val maxFiles: Int = MAX_TRASH_FILES,
    private val maxTotalBytes: Long = MAX_TRASH_TOTAL_BYTES
) {

    /**
     * 删除前落一份救援副本。
     *
     * 任何 IO 失败都抛 [java.io.IOException]（[AtomicFileReplace.replace] 的契约）：
     * 调用方（`SyncRepositoryImpl` 的 DeleteLocal 分支）以 `getOrThrow` 把整个动作判成失败，
     * 远端已没了、下一轮重判仍是 DeleteLocal，重试幂等。**救援失败而照删**才是要拦的事：
     * 那正是本类要堵的洞的原始形态。CancellationException 由 IO 调度路径自然传播，这里不吞。
     *
     * @param docId 用于路径段校验（文件名不含 id，但保留参数以钉住「内容确实属于这篇」的调用契约）
     * @param title 文档标题，用于文件名主体；[FileNameValidator.sanitize] 消毒
     * @param content 最后一笔已落盘的正文（此刻必与同步基线相等）
     */
    fun rescue(docId: String, title: String, content: String, now: Long = System.currentTimeMillis()): File {
        // 建目录失败落到后面 replace 的 IOException 上去，不在这里另造一个错误面
        if (!dir.isDirectory) dir.mkdirs()
        val name = fileNameOf(title, now)
        val target = File(dir, name)
        // 文件名由本类自己生成（定宽时间戳 + sanitize 后的标题），路径必然在目录内；仍过一遍
        // 唯一那份包含校验，理由同 FileManager 的各处调用点：安全校验有两份实现，
        // 实际强度就等于更弱的那一份，而这里的目标路径毕竟来自数据库里的标题。
        PathSafety.requireInside(target, dir, label = "Sync trash")
        AtomicFileReplace.replace(target, content, tmpFor(name))
        synchronized(lock) { rotate() }
        return target
    }

    /** 按时间升序（= 名字升序，时间戳定宽）。目录不存在或读取失败返回空列表。 */
    fun list(): List<File> = dir.listFiles()
        ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
        ?.sortedBy { it.name }
        ?: emptyList()

    fun count(): Int = list().size

    /** 全部副本拼成一段文本，最新的排最前面（导出的目的通常是救回最近删的那篇）。 */
    fun exportText(maxBytes: Int = EXPORT_MAX_BYTES): String {
        val builder = StringBuilder()
        var skipped = 0
        for (file in list().asReversed()) {
            val content = runCatching { file.readText() }.getOrNull()
            // 读不出来或超上界都记一笔跳过数，不静默丢
            if (content == null || (builder.isNotEmpty() && builder.length + content.length > maxBytes)) {
                skipped++
                continue
            }
            if (builder.isNotEmpty()) builder.append('\n').append(SEPARATOR).append('\n')
            builder.append(content.trimEnd())
        }
        if (skipped > 0) {
            if (builder.isNotEmpty()) builder.append('\n').append(SEPARATOR).append('\n')
            builder.append("（另有 $skipped 份更早的备份未包含）")
        }
        return builder.toString()
    }

    /** 删除全部副本，返回实际删掉的份数。 */
    fun clear(): Int = synchronized(lock) { list().count { it.delete() } }

    /**
     * 先按份数、再按总字节轮转，从最旧删起，**永远保留最新一份**。
     * delete() 失败立刻 return：条件不变继续转就是死循环。
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

    /** 救援落盘的中转文件，一次性名字（同一秒同标题的两次救援不会共用 tmp）。 */
    private fun tmpFor(name: String): File {
        val stem = name.removeSuffix(FILE_SUFFIX)
        return File(dir, "$stem.${java.util.UUID.randomUUID()}$TMP_SUFFIX")
    }

    companion object {
        /** 目录名。与 FileManager 的五个目录平级，由调用方（[FileManager.getSyncTrashDir]）创建。 */
        const val DIR_NAME = "sync_trash"
        const val FILE_PREFIX = "ym-trash-"
        const val FILE_SUFFIX = ".md"
        const val TMP_SUFFIX = ".md.tmp"
        const val STAMP_FORMAT = "yyyyMMdd-HHmmss"

        /** 最多 100 份：按远端删除频率估计，已远超「误删后发现」的合理窗口。 */
        const val MAX_TRASH_FILES = 100
        /** 合计 64 MiB：正常文档是 KB 级；超限说明用户在同步流上删大文档，轮转即可。 */
        const val MAX_TRASH_TOTAL_BYTES = 64L * 1024 * 1024

        /** 导出拼接上限 1 MiB：既是对话框/SAF 写入的上界，也逼着「导出」走文件而不是内存。 */
        const val EXPORT_MAX_BYTES = 1024 * 1024

        private val SEPARATOR = "----------------------------------------"
        private val lock = Any()

        /**
         * 副本文件名：`ym-trash-<定宽时间戳>-<消毒后的标题>.md`。
         *
         * 纯函数（[SimpleDateFormat]/[Date] 在 JDK 里，不碰 android.jar），可单测——
         * 名字一旦不自洽（时间戳定宽、标题合法），「按名字排序 = 按时间排序」就断了，
         * 轮转会从错误的优先级开始删。
         */
        fun fileNameOf(title: String, now: Long): String {
            val stamp = SimpleDateFormat(STAMP_FORMAT, Locale.ROOT).format(Date(now))
            val sanitized = FileNameValidator.sanitize(title)
            return "$FILE_PREFIX$stamp-$sanitized$FILE_SUFFIX"
        }
    }
}
