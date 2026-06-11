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

    data class ScanResult(val root: WorkspaceNode, val truncated: Boolean)

    fun scan(rootEntry: ScanEntry): ScanResult {
        val state = ScanState()
        val root = scanNode(rootEntry, depth = 0, state = state)
        return ScanResult(root, state.truncated)
    }

    private class ScanState {
        var fileCount = 0
        var truncated = false
    }

    private fun scanNode(entry: ScanEntry, depth: Int, state: ScanState): WorkspaceNode {
        val folders = mutableListOf<WorkspaceNode>()
        val docs = mutableListOf<WorkspaceDoc>()

        if (depth >= MAX_DEPTH) {
            state.truncated = true
        } else {
            for (child in entry.children()) {
                val childName = child.name ?: continue
                if (childName.startsWith(".")) continue

                if (child.isDirectory) {
                    folders += scanNode(child, depth + 1, state)
                } else {
                    if (state.fileCount >= MAX_FILES) {
                        state.truncated = true
                        break
                    }
                    val ext = childName.substringAfterLast('.', "").lowercase()
                    if (ext in SUPPORTED_EXTENSIONS) {
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
        }

        return WorkspaceNode(
            name = entry.name ?: "?",
            uri = entry.uri,
            folders = folders.sortedBy { it.name.lowercase() },
            docs = docs.sortedBy { it.name.lowercase() }
        )
    }
}
