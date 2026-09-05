package com.yumark.app.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import org.junit.jupiter.api.assertThrows

/**
 * 钉住 [AtomicFileReplace] 的三条不变量。
 *
 * 这段逻辑是「用户正文凭空变空白」这类事故的唯一出口，所以每条不变量都用真实文件系统跑，
 * 而不是 mock：mock 掉 renameTo 就等于把要验证的那件事假设成真。
 */
class AtomicFileReplaceTest {

    @TempDir
    lateinit var dir: File

    private fun target() = File(dir, "doc.md")

    private fun tmp(token: String = "t1") = File(dir, "doc.$token.md.tmp")

    @Test
    fun `写入新文件后内容逐字节相同`() {
        val target = target()
        // CJK + 换行 + 制表符：UTF-8 编码与行尾都别在这一层被改写。
        val content = "第一行\n\t缩进的第二行\n"

        AtomicFileReplace.replace(target, content, tmp())

        assertThat(target.readText()).isEqualTo(content)
        assertThat(target.readBytes().size).isEqualTo(content.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `覆盖已有文件且不留中转文件`() {
        val target = target()
        target.writeText("旧正文")
        val tmp = tmp()

        AtomicFileReplace.replace(target, "新正文", tmp)

        assertThat(target.readText()).isEqualTo("新正文")
        assertThat(tmp.exists()).isFalse()
        // 备份是替换过程的中间产物，成功后不该留下——留着会让下一篇复用同 id 的文档被
        // readOrRecover 捞出上一篇的正文。
        assertThat(AtomicFileReplace.backupOf(target).exists()).isFalse()
    }

    @Test
    fun `连续多次替换各用一个中转文件时目录里只剩目标文件`() {
        val target = target()

        AtomicFileReplace.replace(target, "v1", tmp("a"))
        AtomicFileReplace.replace(target, "v2", tmp("b"))
        AtomicFileReplace.replace(target, "v3", tmp("c"))

        assertThat(target.readText()).isEqualTo("v3")
        assertThat(dir.list()!!.toList()).containsExactly("doc.md")
    }

    @Test
    fun `写入失败时目标文件保持替换前的内容`() {
        val target = target()
        target.writeText("必须活下来的正文")
        // 中转文件放进一个不存在的子目录，FileOutputStream 会抛 FileNotFoundException。
        // 这是不变量 2 唯一可移植的触发方式：真实的 rename 失败依赖平台行为，造不出来。
        val unreachableTmp = File(File(dir, "not-created"), "doc.x.md.tmp")

        assertThrows<IOException> {
            AtomicFileReplace.replace(target, "不该落盘的内容", unreachableTmp)
        }

        assertThat(target.readText()).isEqualTo("必须活下来的正文")
    }

    @Test
    fun `备份名挂在完整文件名之后`() {
        // 不是把扩展名换成 bak：`doc.bak` 会和用户自己命名为 doc.bak 的文档撞车，
        // 而 `doc.md.bak` 不是合法文档名（含点），撞不上。
        assertThat(AtomicFileReplace.backupOf(File(dir, "doc.md")).name).isEqualTo("doc.md.bak")
    }

    @Test
    fun `文件与备份都不存在时读取返回 null`() {
        // null 与 "" 必须分开：新建文档在首次保存前就没有文件，调用方靠 null 判断
        // 「本来就没有」，而不是把它当成一篇被清空的文档。
        assertThat(AtomicFileReplace.readOrRecover(target())).isNull()
    }

    @Test
    fun `目标文件缺失时从备份捞回正文并顺手复位`() {
        val target = target()
        // 手工造出替换窗口里被杀的现场：旧文件已挪成 .bak，新内容还没 rename 上去。
        AtomicFileReplace.backupOf(target).writeText("窗口里的正文")

        val recovered = AtomicFileReplace.readOrRecover(target)

        assertThat(recovered).isEqualTo("窗口里的正文")
        // 复位之后下一次读走正常路径，不再依赖备份。
        assertThat(target.isFile).isTrue()
        assertThat(target.readText()).isEqualTo("窗口里的正文")
    }

    @Test
    fun `目标文件存在时忽略备份`() {
        val target = target()
        target.writeText("当前正文")
        AtomicFileReplace.backupOf(target).writeText("过期备份")

        assertThat(AtomicFileReplace.readOrRecover(target)).isEqualTo("当前正文")
    }

    @Test
    fun `目标是空文件时按空文件读取而不是回退到备份`() {
        val target = target()
        target.writeText("")
        AtomicFileReplace.backupOf(target).writeText("不该被捞出来的旧正文")

        // 用户真把文档清空了也是一次合法保存。这里若按「空就当没有」回退到备份，
        // 清空操作会在下次打开时被静默撤销。
        assertThat(AtomicFileReplace.readOrRecover(target)).isEmpty()
    }
}
