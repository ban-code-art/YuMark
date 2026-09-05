package com.yumark.app.data.local.file

import com.yumark.app.domain.model.WorkspaceDoc
import com.yumark.app.domain.model.WorkspaceNode

/**
 * 文件树条目抽象，隔离 DocumentFile 以便单元测试
 */
interface ScanEntry {
    val name: String?
    val isDirectory: Boolean
    val uri: String
    val lastModified: Long
    fun children(): List<ScanEntry>
}

/**
 * 把外部文件夹扫描为 WorkspaceNode 树。
 * 规则：只收 md/markdown/txt；跳过 . 开头的隐藏项；限深 MAX_DEPTH；限量 MAX_FILES。
 */
object WorkspaceScanner {
    const val MAX_DEPTH = 10
    const val MAX_FILES = 2000
    private val SUPPORTED_EXTENSIONS = setOf("md", "markdown", "txt")

    /**
     * 扫描结果。
     *
     * 两个上限分开报，因为界面要说的话完全不同：撞了 [MAX_FILES] 该说「文档太多，只显示前
     * 2000 个」；撞了 [MAX_DEPTH] 时文档总数可能只有十几个，同一句话就是在骗用户——他会
     * 反复找那 2000 个文档在哪。合成一个 boolean 之后这个区别就再也取不回来了。
     */
    data class ScanResult(
        val root: WorkspaceNode,
        /** 收满 [MAX_FILES] 后停止收集 */
        val fileLimitHit: Boolean,
        /** 到 [MAX_DEPTH] 层后不再下钻，更深的目录整棵没读 */
        val depthLimitHit: Boolean
    ) {
        /** 任一上限撞上即为截断。保留这个派生属性，调用方只关心「有没有缺东西」时不必写 or。 */
        val truncated: Boolean get() = fileLimitHit || depthLimitHit
    }

    fun scan(rootEntry: ScanEntry): ScanResult {
        val state = ScanState()
        val root = scanNode(rootEntry, depth = 0, state = state)
        return ScanResult(root, state.fileLimitHit, state.depthLimitHit)
    }

    private class ScanState {
        var fileCount = 0
        var fileLimitHit = false
        var depthLimitHit = false
    }

    private fun scanNode(entry: ScanEntry, depth: Int, state: ScanState): WorkspaceNode {
        val folders = mutableListOf<WorkspaceNode>()
        val docs = mutableListOf<WorkspaceDoc>()

        if (depth >= MAX_DEPTH) {
            // 只在确实少收了东西时才置位。到达上限的目录本身可能是空的，也可能只装着隐藏项或
            // 不支持的扩展名——那种情况下一样什么都没少收，报「层级过深」是凭空吓用户一跳，
            // 而他打开那一层只会看到空目录，永远对不上提示里说的「更深的内容没读」。
            if (entry.children().any { isCollectible(it) }) state.depthLimitHit = true
        } else {
            for (child in entry.children()) {
                val childName = child.name ?: continue
                if (childName.startsWith(".")) continue

                if (child.isDirectory) {
                    folders += scanNode(child, depth + 1, state)
                } else {
                    val ext = childName.substringAfterLast('.', "").lowercase()
                    // 不支持的扩展名直接跳过，**在上限判定之前**。反过来写（旧实现）的后果是：
                    // 一个目录里正好有 2000 个 .md 和一张 .jpg，计数走到 2000 之后那张图片
                    // 撞上上限 → 报「文件过多，仅显示前 2000 个文档」并 break，可实际上
                    // 一篇文档都没少收——用户会去找那些根本不存在的「被省略的文档」。
                    // 图片、PDF 压根不进树，不该有资格触发这个提示。
                    if (ext !in SUPPORTED_EXTENSIONS) continue
                    if (state.fileCount >= MAX_FILES) {
                        state.fileLimitHit = true
                        break
                    }
                    state.fileCount++
                    docs += WorkspaceDoc(
                        name = childName.substringBeforeLast('.'),
                        fileName = childName,
                        uri = child.uri,
                        lastModified = child.lastModified
                    )
                }
            }
        }

        return WorkspaceNode(
            name = entry.name ?: "?",
            uri = entry.uri,
            folders = folders.sortedBy { it.name.lowercase() },
            docs = docs.sortedBy { it.name.lowercase() }
        )
    }

    /**
     * 这个条目在没有深度限制的情况下会不会进树。
     *
     * 判定条件必须与 [scanNode] 主循环的过滤逐条对齐：名字为空或以 `.` 开头的一律跳过；
     * 目录一定会成为一个节点（哪怕它下面空无一物）；文件只收 [SUPPORTED_EXTENSIONS]。
     * 两边将来只改一处，「层级过深」的提示就会和实际收集结果对不上——这正是它存在的理由，
     * 也是它只被深度上限那一支使用、不参与实际收集的原因（收集侧还要计数与建节点）。
     */
    private fun isCollectible(entry: ScanEntry): Boolean {
        val name = entry.name ?: return false
        if (name.startsWith(".")) return false
        if (entry.isDirectory) return true
        return name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
    }
}
