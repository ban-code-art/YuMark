package com.yumark.app.core.export

import java.io.File

/**
 * 导出目录的保留策略——只依赖 `java.io`，可在 JVM 单元测试里用临时目录全覆盖。
 *
 * **为什么需要它**：导出落在应用私有目录（filesDir/exports），再通过 FileProvider 把 URI
 * 分享给外部应用。原实现是每次导出前把整个目录清空，这有一个真实的坏后果——分享出去的
 * URI 指向的文件可能还没被对方读完：邮件客户端要等用户点发送才去读附件，网盘客户端在后台
 * 排队上传，系统分享面板本身也只是把 URI 传过去而不复制内容。上一份导出被删掉，对方拿到的
 * 就是「文件不存在」，而且错误发生在别人的进程里，用户只会觉得是本应用导出坏了。
 *
 * 所以改成**带宽限期的有界保留**：
 * - [graceMillis] 之内的文件一律不动，把「刚分享出去、对方还没读」这段窗口保住；
 * - 超过宽限期的按时间从旧到新删；
 * - [maxFiles] / [maxTotalBytes] 是安全阀，防止用户在宽限期内连续导出几十份大 PDF
 *   把私有目录撑爆——超限时即使还在宽限期内也删，但永远至少留最新一份。
 *
 * 判定时间用 `lastModified()` 而不是文件名：导出文件名是「文档名.扩展名」，不含时间戳，
 * 名字排序和时间顺序没有关系。系统时钟被回拨时 `now - lastModified` 会变成负数，落在
 * 「还在宽限期内」一侧——偏向保留而不是偏向删除，这个方向是安全的。
 */
class ExportRetention(
    private val dir: File,
    /** 多新的文件算「可能正被外部应用读取」，一律不删 */
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    /** 最多保留几份导出 */
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    /** 全部导出合计字节上限 */
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
) {

    /**
     * 清理一次，返回实际删掉的份数。
     *
     * 任何失败都不抛异常：清理只是打扫卫生，不能连带让本次导出失败。
     */
    fun prune(now: Long = System.currentTimeMillis()): Int = runCatching {
        var files = list()
        var deleted = 0

        // 第一轮：过了宽限期的，从旧到新删
        for (file in files) {
            if (now - file.lastModified() < graceMillis) continue
            if (!file.delete()) continue
            deleted++
        }

        // 第二轮：安全阀。重新列一次目录而不是复用上面的列表——中间可能有别的导出写进来
        files = list()
        while (files.size > maxFiles) {
            // delete 失败立刻停手：条件不变会转成死循环
            if (!files.first().delete()) return@runCatching deleted
            deleted++
            files = files.drop(1)
        }
        var total = files.sumOf { it.length() }
        while (files.size > 1 && total > maxTotalBytes) {
            val oldest = files.first()
            val size = oldest.length()
            if (!oldest.delete()) return@runCatching deleted
            deleted++
            total -= size
            files = files.drop(1)
        }
        deleted
    }.getOrDefault(0)

    /**
     * 目录下的导出文件，按时间升序（旧的在前）。
     *
     * 只取 `isFile`：顺带跳过子目录，以及 `listFiles()` 因权限返回 null 的情况。
     * 时间相同时按名字兜底，保证顺序是确定的——否则测试会随文件系统时间戳精度飘。
     */
    fun list(): List<File> = dir.listFiles()
        ?.filter { it.isFile }
        ?.sortedWith(compareBy({ it.lastModified() }, { it.name }))
        ?: emptyList()

    companion object {
        /**
         * 宽限期 15 分钟。
         *
         * 下界由「分享出去还没被读」的最长合理时间决定：用户点分享 → 在邮件客户端里写正文
         * → 点发送，几分钟是常态。上界由私有目录容量决定，配合下面两个安全阀，15 分钟不会
         * 让目录失控。两边代价不对称——留久一点只是多占一会儿空间，删早一点是分享直接失败。
         */
        const val DEFAULT_GRACE_MILLIS = 15 * 60 * 1000L

        /** 最多 20 份：够覆盖「连着导出好几篇、逐个分享」的正常用法 */
        const val DEFAULT_MAX_FILES = 20

        /** 合计 128 MiB：长图与 PDF 是这里最大的两种产物，单份通常几 MB */
        const val DEFAULT_MAX_TOTAL_BYTES = 128L * 1024 * 1024
    }
}
