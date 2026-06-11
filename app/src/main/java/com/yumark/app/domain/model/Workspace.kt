package com.yumark.app.domain.model

/**
 * 外部文件夹工作区（SAF 实时关联，不入 Room）
 */
data class Workspace(
    val name: String,
    val treeUri: String,
    val root: WorkspaceNode,
    val truncated: Boolean = false
)

data class WorkspaceNode(
    val name: String,
    val uri: String,
    val folders: List<WorkspaceNode>,
    val docs: List<WorkspaceDoc>
)

data class WorkspaceDoc(
    val name: String,       // 不含扩展名，用于显示
    val fileName: String,   // 含扩展名
    val uri: String,
    val lastModified: Long
)

/**
 * 预览模式大纲条目（来自 WebView 渲染后的真实标题元素）
 */
data class OutlineItem(
    val level: Int,      // 1-6 对应 h1-h6
    val text: String,
    val anchorId: String // DOM 元素 id，如 yumark-h-0
)
