package com.yumark.app.domain.model

/**
 * 外部文件夹工作区（SAF 实时关联，不入 Room）
 */
data class Workspace(
    val name: String,
    val treeUri: String,
    val root: WorkspaceNode,
    /** 文档数收满 WorkspaceScanner.MAX_FILES，后面的文档没收进来 */
    val fileLimitHit: Boolean = false,
    /** 目录深度到了 WorkspaceScanner.MAX_DEPTH，更深的文件夹整棵没读 */
    val depthLimitHit: Boolean = false
) {
    /**
     * 两个上限必须分开保留，不能在这一层合成一个 boolean：界面要说的话完全不同。
     * 撞文件数上限该说「文档太多，只显示前 2000 个」；撞深度上限时文档总数可能只有十几个，
     * 同一句话就是在骗用户——他会反复找那 2000 个文档在哪。
     * 这个派生属性只给「有没有缺东西」这类不关心原因的判断用。
     */
    val truncated: Boolean get() = fileLimitHit || depthLimitHit
}

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
