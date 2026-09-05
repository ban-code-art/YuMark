package com.yumark.app.core.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 「用新内容替换一个文本文件」的落盘原语：tmp → fsync → rename，且**任何失败路径都不会让目标
 * 文件消失**。
 *
 * 单独成一个不依赖 Context 的对象，是为了能用 `@TempDir` 在 JVM 单测里真刀真枪地跑一遍
 * rename 失败、断电窗口、备份复位这些分支——挂在 `FileManager` 上就只能靠仪器化测试，
 * 而这段逻辑正是「用户正文凭空变空白」这一类事故的唯一出口。
 *
 * 三条不变量：
 * 1. 目标文件要么是替换前的完整内容，要么是替换后的完整内容，绝不是半截；
 * 2. 替换失败时目标文件仍在（旧内容），不会被删；
 * 3. 进程在中途被杀，内容至少还留在 [backupOf] 里，[readOrRecover] 能把它捞回来。
 */
object AtomicFileReplace {

    /** 备份后缀。挂在完整文件名之后，`a.md` 的备份是 `a.md.bak`。 */
    const val BACKUP_SUFFIX = ".bak"

    fun backupOf(target: File): File = File(target.parentFile, target.name + BACKUP_SUFFIX)

    /**
     * 把 [content] 写进 [target]，中转文件用 [tempFile]。
     *
     * [tempFile] 由调用方给，且**每次调用都必须是不同的名字**：同一篇文档如果两次写入撞在同一个
     * 中转文件上，`FileOutputStream` 会让两份内容交错，先完成的一方 rename 走之后另一方
     * 就找不到自己的 tmp 了。名字唯一 + 调用方按文档加锁，两层都不能省。
     *
     * @throws IOException 替换未完成；此时 [target] 保持调用前的状态。
     */
    fun replace(target: File, content: String, tempFile: File) {
        try {
            writeAndSync(tempFile, content)
            if (tempFile.renameTo(target)) return

            // 走到这里说明目标已存在且平台不允许直接覆盖 rename。
            // 关键：先把现有文件**挪**成备份，而不是 delete。delete 之后第二次 rename 再失败，
            // 用户正文就彻底没了——这正是这段代码从前的写法造成的事故。
            val backup = backupOf(target)
            backup.delete()
            val hadOriginal = target.exists()
            if (hadOriginal && !target.renameTo(backup)) {
                throw IOException("Failed to back up ${target.name} before replace")
            }
            if (!tempFile.renameTo(target)) {
                // 宁可回到改动前，也不能两头都没有。
                if (hadOriginal) backup.renameTo(target)
                throw IOException("Failed to replace ${target.name}")
            }
            backup.delete()
        } finally {
            // 成功路径上 tmp 已被 rename 掉，delete 返回 false，无害；
            // 失败路径上这一句保证不留残骸。
            tempFile.delete()
        }
    }

    /**
     * 读 [target]；文件不存在时先看有没有备份可救，返回 null 表示两者都没有。
     *
     * 为什么需要这一步：[replace] 的备份窗口里（旧文件已挪成 .bak、新内容还没 rename 上去）
     * 进程被杀，目标文件就是不存在的。把这种情况当成「空文档」会让用户看到一篇空白正文，
     * 而正文其实完好地躺在 .bak 里。捞回来的同时顺手复位，下次就走正常路径。
     *
     * 复位用 renameTo 而非复制：调用方持有该文档的锁，但复位失败（权限、被占用）也不该让
     * 这次读取失败——内容已经读到手了，返回它比抛异常有用。
     */
    fun readOrRecover(target: File): String? {
        if (target.isFile) return target.readText()
        val backup = backupOf(target)
        if (!backup.isFile) return null
        val recovered = backup.readText()
        runCatching { if (!target.exists()) backup.renameTo(target) }
        return recovered
    }

    /**
     * 写入 + fsync：把内容真正压到盘上，而不是只交给内核页缓存。
     *
     * 为什么必须有：tmp + rename 只保证「目标文件不会是半截的」，不保证断电后目标文件里
     * **有内容**。rename 是元数据操作，可能先于数据落盘；时序不巧就得到一个长度为 0 的
     * .md——整篇文档内容归零，比留下半截文件更糟。fsync 把这个窗口关掉。
     *
     * 三步顺序不能换：write 只填 Java 缓冲；flush 把缓冲交给内核；sync 才让内核把脏页写进
     * 存储介质。少了 flush，sync 看不到还在 Java 缓冲里的字节。
     *
     * fsync 的代价是几毫秒到几十毫秒的 I/O 等待，所以整段跑在 Dispatchers.IO 上而不是在主线程
     * 上同步等待。能接受是因为调用点只有内部文档保存这一条（外部文档走 ContentResolver，
     * 拿不到 fd），而保存频率已被防抖收敛到「停手 1.8s 后一次」，不是每敲一个字 sync 一次。
     */
    private fun writeAndSync(target: File, content: String) {
        FileOutputStream(target).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            // 只吞 sync 阶段的失败（理由见上）；write / flush / close 的异常照旧向上抛，
            // 那些才是「内容没写进去」的真信号。
            runCatching { out.fd.sync() }
        }
    }
}
