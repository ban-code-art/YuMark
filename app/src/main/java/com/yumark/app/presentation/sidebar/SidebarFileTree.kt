package com.yumark.app.presentation.sidebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.FolderTreeNode
import com.yumark.app.presentation.common.displayName
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppMotion
import com.yumark.app.presentation.theme.AppSpacing
import com.yumark.app.presentation.theme.extendedColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 侧栏文件树的管理动作集合。
 * 传 null 给 [SidebarFileTree] 表示纯浏览模式（如编辑器内的切换文档侧栏），
 * 文件夹/文档行都不显示管理菜单，也不启用拖动。
 */
data class SidebarActions(
    val onCreateDocument: (String?) -> Unit,
    val onCreateSubfolder: (String?) -> Unit,
    val onRenameFolder: (String) -> Unit,
    val onDeleteFolder: (String) -> Unit,
    val onRenameDocument: (Document) -> Unit,
    val onDeleteDocument: (Document) -> Unit,
    val onMoveDocument: (Document) -> Unit,
    val onMoveFolder: (String) -> Unit,
    /** 拖拽落点后直接移动（不弹对话框）。targetFolderId 为 null 表示根目录。 */
    val onMoveDocumentTo: (docId: String, targetFolderId: String?) -> Unit,
    val onMoveFolderTo: (folderId: String, targetParentId: String?) -> Unit
)

private const val ROOT_DROP_KEY = "__ROOT__"

// 本屏的布局常量。与 AppSpacing / AppIconSize 的分工：那两个是跨屏复用的角色令牌
// （间距该多大、图标该多大），这里是只有文件树才有的具体物件——一层缩进多宽、拖影
// 离手指多远、选中竖线多高。塞进令牌表会让令牌变成杂物抽屉，继续写裸字面量则没人
// 知道两处 20.dp 是同一个东西还是碰巧撞上。

/** 每一层缩进的宽度，文件夹行按 level 累加。 */
private val RowIndentStep = 16.dp

/** 文档行比同层文件夹行多缩进的一段，让文档图标对齐文件夹名而不是文件夹图标。 */
private val DocumentIndentExtra = 24.dp

/** 拖动时距容器上下边缘多近开始自动滚动。 */
private val AutoScrollEdge = 72.dp

/** 自动滚动每帧的位移。 */
private val AutoScrollStep = 10.dp

/** 拖影相对容器左边缘的固定横向偏移。 */
private val DragGhostOffsetX = 24.dp

/** 拖影相对手指的纵向偏移，让拖影不被手指盖住。 */
private val DragGhostFingerOffsetY = 18.dp

/** 拖影的投影高度。 */
private val DragGhostElevation = 6.dp

/** 拖影里文件名的最大宽度，超出省略。 */
private val DragGhostLabelMaxWidth = 200.dp

/** 选中文档行左侧那条竖线的宽。 */
private val SelectionIndicatorWidth = 3.dp

/** 选中文档行左侧那条竖线的高。 */
private val SelectionIndicatorHeight = 20.dp

/**
 * UI 测试用的稳定标签。
 *
 * 集中成一个 object 而不是散着写字面量：标签一旦被测试引用就是契约，
 * 散落的魔法字符串改名时必然漏改。
 *
 * 树里会重复出现的行共用同一个标签（[NODE] / [DOCUMENT] / [EXPAND_TOGGLE]），
 * 测试侧用 onAllNodesWithTag 取集合再按文本或索引筛：文件夹/文档 id 都是运行时
 * 生成的 UUID，测试里写不出字面量。
 */
private object SidebarTags {
    /** 文件树根容器（LazyColumn） */
    const val ROOT = "sidebar_tree"
    /** 拖动中悬浮在顶部的「移到根目录」落区 */
    const val ROOT_DROP = "sidebar_tree_root_drop"
    /** 文件夹行（重复节点，共用同一标签） */
    const val NODE = "sidebar_tree_node"
    /** 文件夹行的展开/收起箭头 */
    const val EXPAND_TOGGLE = "sidebar_tree_expand"
    /** 文件夹行的更多菜单按钮 */
    const val NODE_MENU = "sidebar_tree_node_menu"
    /** 文档行（重复节点，共用同一标签） */
    const val DOCUMENT = "sidebar_tree_document"
    /** 文档行的更多菜单按钮 */
    const val DOCUMENT_MENU = "sidebar_tree_document_menu"
}

/**
 * 拖动控制器：长按某行进入拖动，落到文件夹行/根落区上松手即 reparent。
 * 落点（文件夹行 + 根落区）通过 [registerTarget] 上报根坐标范围；[recompute] 命中检测。
 */
class DragController(
    private val moveDocumentTo: (String, String?) -> Unit,
    private val moveFolderTo: (String, String?) -> Unit
) {
    var draggedId by mutableStateOf<String?>(null); private set
    var draggedIsFolder by mutableStateOf(false); private set
    var draggedLabel by mutableStateOf(""); private set
    var pointerY by mutableFloatStateOf(0f); private set

    var dropTargetFolderId by mutableStateOf<String?>(null); private set
    var dropIsRoot by mutableStateOf(false); private set
    var dropValid by mutableStateOf(false); private set

    val isDragging: Boolean get() = draggedId != null

    private var currentParent: String? = null
    private var invalid: Set<String> = emptySet()

    private data class Target(val range: ClosedFloatingPointRange<Float>, val folderId: String?, val isRoot: Boolean)
    private val targets = mutableMapOf<String, Target>()

    fun registerTarget(key: String, top: Float, bottom: Float, folderId: String?, isRoot: Boolean) {
        if (bottom > top) targets[key] = Target(top..bottom, folderId, isRoot)
    }

    fun unregisterTarget(key: String) { targets.remove(key) }

    fun start(id: String, isFolder: Boolean, label: String, parent: String?, invalidSet: Set<String>, startRootY: Float) {
        draggedId = id
        draggedIsFolder = isFolder
        draggedLabel = label
        currentParent = parent
        invalid = invalidSet
        pointerY = startRootY
        recompute()
    }

    fun drag(deltaY: Float) {
        pointerY += deltaY
        recompute()
    }

    fun recompute() {
        if (draggedId == null) return
        val y = pointerY
        val hit = targets.values.firstOrNull { y in it.range }
        if (hit == null) {
            dropValid = false; dropTargetFolderId = null; dropIsRoot = false
            return
        }
        val target = hit.folderId
        dropTargetFolderId = target
        dropIsRoot = hit.isRoot
        dropValid = target !in invalid && target != currentParent
    }

    fun commit() {
        val id = draggedId
        if (id != null && dropValid) {
            if (draggedIsFolder) moveFolderTo(id, dropTargetFolderId)
            else moveDocumentTo(id, dropTargetFolderId)
        }
        clear()
    }

    fun cancel() = clear()

    private fun clear() {
        draggedId = null
        draggedIsFolder = false
        draggedLabel = ""
        dropTargetFolderId = null
        dropIsRoot = false
        dropValid = false
        currentParent = null
        invalid = emptySet()
    }
}

/** 收集某文件夹子树内的所有 folder id（含自身）——拖动文件夹时作为非法落点，防止移进自己的子树。 */
private fun collectSubtreeFolderIds(tree: List<FolderTreeNode>, rootFolderId: String): Set<String> {
    fun find(nodes: List<FolderTreeNode>): FolderTreeNode? {
        for (n in nodes) {
            if (n.folder?.id == rootFolderId) return n
            find(n.children)?.let { return it }
        }
        return null
    }
    val result = mutableSetOf<String>()
    fun collect(n: FolderTreeNode) {
        n.folder?.let { result.add(it.id) }
        n.children.forEach { collect(it) }
    }
    find(tree)?.let { collect(it) }
    return result
}

@Composable
fun SidebarFileTree(
    tree: List<FolderTreeNode>,
    currentDocumentId: String?,
    expandedFolders: Set<String>,
    onDocumentClick: (String) -> Unit,
    onFolderExpand: (String) -> Unit,
    onFolderCollapse: (String) -> Unit,
    actions: SidebarActions?,
    scrollToCurrentDocument: Boolean = false,
    /**
     * 批量确保若干文件夹处于展开状态（用于自动展开深处当前文档的祖先链）。
     * 必须是「并集」语义（已展开的保持展开），不能是 toggle。
     * 默认空实现：调用方未提供时不展开祖先，仅尝试滚动（兼容旧行为）。
     */
    onEnsureFoldersExpanded: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // 拖动控制器（仅管理模式启用）
    val drag = remember(actions) {
        actions?.let { DragController(it.onMoveDocumentTo, it.onMoveFolderTo) }
    }
    val subtreeIdsOf: (String) -> Set<String> = remember(tree) { { id -> collectSubtreeFolderIds(tree, id) } }

    var containerTop by remember { mutableFloatStateOf(0f) }
    var containerBottom by remember { mutableFloatStateOf(0f) }

    // 自动定位到当前文档：先展开其祖先文件夹链，使其可见，再滚动过去。
    // key 带 expandedFolders：展开祖先后会重组，effect 再次执行进入滚动分支。
    LaunchedEffect(scrollToCurrentDocument, currentDocumentId, expandedFolders) {
        if (!scrollToCurrentDocument || currentDocumentId == null) return@LaunchedEffect
        val ancestors = ancestorFolderIdsOf(tree, currentDocumentId)
        val missing = ancestors.filter { it !in expandedFolders }
        if (missing.isNotEmpty()) {
            // 先展开缺失的祖先，本次返回；expandedFolders 变化后 effect 重启，再滚动。
            onEnsureFoldersExpanded(missing)
            return@LaunchedEffect
        }
        delay(100) // 等待LazyColumn完成布局
        val index = findTopLevelIndexOfDocument(tree, currentDocumentId, expandedFolders)
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    // 拖动时靠近上下边缘自动滚动
    LaunchedEffect(drag?.isDragging) {
        if (drag == null || !drag.isDragging) return@LaunchedEffect
        val edge = with(density) { AutoScrollEdge.toPx() }
        val step = with(density) { AutoScrollStep.toPx() }
        while (drag.isDragging) {
            val y = drag.pointerY
            val dy = when {
                y < containerTop + edge -> -step
                y > containerBottom - edge -> step
                else -> 0f
            }
            if (dy != 0f) {
                listState.scrollBy(dy)
                drag.recompute()
            }
            delay(16)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val r = it.boundsInRoot()
                containerTop = r.top
                containerBottom = r.bottom
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag(SidebarTags.ROOT),
            contentPadding = PaddingValues(vertical = AppSpacing.Default)
        ) {
            items(tree) { node ->
                FolderTreeItem(
                    node = node,
                    currentDocumentId = currentDocumentId,
                    isExpanded = node.folder?.let { expandedFolders.contains(it.id) } ?: true,
                    expandedFolders = expandedFolders,
                    onDocumentClick = onDocumentClick,
                    onFolderExpand = onFolderExpand,
                    onFolderCollapse = onFolderCollapse,
                    actions = actions,
                    drag = drag,
                    subtreeIdsOf = subtreeIdsOf
                )
            }
        }

        // 空态：tree 为空时这块面板原来整个是空白的，用户读不出是「还没有文档」还是
        // 「加载失败/加载中」。复用文件列表已有的 empty_documents，不新增字符串键；
        // 刻意不带 empty_documents_hint——那句指向「+ 按钮」，而编辑器侧栏里没有那个按钮。
        if (tree.isEmpty()) {
            Text(
                text = stringResource(R.string.empty_documents),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(AppSpacing.Screen)
            )
        }

        // 根落区：仅拖动中显示，悬浮在列表顶部，作为「移出到根目录」的落点。
        //
        // 用 AnimatedVisibility 而不是裸 if：拖起来那一瞬间这条落区凭空压在列表顶上，
        // 硬切看着像界面抖了一下。只做 fadeIn / fadeOut，**不**做 expandVertically：
        // 尺寸动画期间 onGloballyPositioned 会把逐帧变矮的 bounds 报上去，下一次拖动的
        // 起始帧就可能按这个缩水的范围判命中——正是下面那条注释里已经踩过一次的坑。
        if (drag != null) {
            AnimatedVisibility(
                visible = drag.isDragging,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(AppMotion.enter()),
                exit = fadeOut(AppMotion.exit())
            ) {
                // 拖动结束后注销根落区的命中范围，避免其陈旧 bounds 残留到下一次拖动
                // 起始帧误命中（文件夹行在自身 dispose 时已注销，根落区此前漏了）。
                // 放在 AnimatedVisibility 内层：淡出结束、内容真正离开组合时才注销，
                // 比原来晚 150ms，而这 150ms 内 bounds 不变，判定仍然正确。
                DisposableEffect(Unit) {
                    onDispose { drag.unregisterTarget(ROOT_DROP_KEY) }
                }
                val rootHighlighted = drag.dropValid && drag.dropIsRoot
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SidebarTags.ROOT_DROP)
                        .onGloballyPositioned {
                            val r = it.boundsInRoot()
                            drag.registerTarget(ROOT_DROP_KEY, r.top, r.bottom, folderId = null, isRoot = true)
                        },
                    color = if (rootHighlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(AppSpacing.Cozy),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Home,
                            null,
                            Modifier.size(AppIconSize.Small),
                            tint = extendedColors.primaryText
                        )
                        Spacer(Modifier.width(AppSpacing.Default))
                        Text(
                            stringResource(R.string.sidebar_drop_to_root),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // 拖影：跟随手指
        // 注意：pointerY 在 offset{} lambda(layout 阶段)中读取，
        // 否则每拖动帧都会触发整棵树的 recomposition。
        // 刻意不给它加淡入淡出：拖影本身就一直在动，再叠一层 alpha 动画会和下面写死的
        // alpha(0.95f) 相乘，出场那 200ms 变成半透明，反而像渲染坏了。
        if (drag != null && drag.isDragging) {
            Surface(
                modifier = Modifier
                    .offset {
                        val y = drag.pointerY - containerTop - with(density) { DragGhostFingerOffsetY.toPx() }
                        IntOffset(x = with(density) { DragGhostOffsetX.roundToPx() }, y = y.roundToInt())
                    }
                    .alpha(0.95f),
                shadowElevation = DragGhostElevation,
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AppSpacing.Cozy, vertical = AppSpacing.Default),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (drag.draggedIsFolder) Icons.Default.Folder else Icons.Default.Description,
                        null,
                        Modifier.size(AppIconSize.Small),
                        tint = extendedColors.primaryText
                    )
                    Spacer(Modifier.width(AppSpacing.Default))
                    Text(
                        drag.draggedLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = DragGhostLabelMaxWidth)
                    )
                }
            }
        }
    }
}

/**
 * 找到 [documentId] 所在的**顶层节点**在 LazyColumn 里的索引；找不到返回 -1。
 *
 * 必须是顶层索引，因为列表是 `items(tree)`——一个顶层节点连同它整棵展开的子树只占**一个**
 * LazyColumn item（见 [FolderTreeItem]：文件夹行、文档行、子文件夹全在同一个 Column 里）。
 * 旧实现返回的是「把所有展开的行摊平之后的第几行」，两个坐标系完全不同：树上稍微有点内容，
 * 摊平行号就远大于 tree.size，animateScrollToItem 会把它夹到最后一项——于是「定位到当前
 * 文档」的结果是滚到列表末尾，越是深处的文档越是滚得离谱。行号只有在
 * LazyColumn 改成逐行 items(摊平后的行) 之后才会成为正确答案。
 */
internal fun findTopLevelIndexOfDocument(
    tree: List<FolderTreeNode>,
    documentId: String,
    expandedFolders: Set<String>
): Int {
    // 只按展开状态判断可见性，与 FolderTreeItem 的 `isExpanded || node.folder == null` 一致：
    // 折叠的顶层文件夹里的文档在屏上根本不存在，滚过去也没有行可看。调用方在此之前已经
    // 展开了祖先链（见上面的 LaunchedEffect），所以正常路径上这个过滤不会拦掉目标。
    fun containsVisible(node: FolderTreeNode): Boolean {
        if (node.folder != null && node.folder.id !in expandedFolders) return false
        if (node.documents.any { it.id == documentId }) return true
        return node.children.any { containsVisible(it) }
    }
    return tree.indexOfFirst { containsVisible(it) }
}

/**
 * 找到文档所在位置的全部祖先文件夹 id（从根到直接父文件夹，有序）。
 * 用于打开侧栏时自动展开这些文件夹，使深处的当前文档可见并可滚动定位。
 */
internal fun ancestorFolderIdsOf(tree: List<FolderTreeNode>, documentId: String): List<String> {
    fun find(nodes: List<FolderTreeNode>, path: List<String>): List<String>? {
        for (node in nodes) {
            val pathHere = node.folder?.let { path + it.id } ?: path
            if (node.documents.any { it.id == documentId }) return pathHere
            find(node.children, pathHere)?.let { return it }
        }
        return null
    }
    return find(tree, emptyList()) ?: emptyList()
}

@Composable
fun FolderTreeItem(
    node: FolderTreeNode,
    currentDocumentId: String?,
    isExpanded: Boolean,
    expandedFolders: Set<String>,
    onDocumentClick: (String) -> Unit,
    onFolderExpand: (String) -> Unit,
    onFolderCollapse: (String) -> Unit,
    actions: SidebarActions?,
    drag: DragController? = null,
    subtreeIdsOf: (String) -> Set<String> = { emptySet() }
) {
    Column {
        // 文件夹行
        node.folder?.let { folder ->
            FolderRow(
                folder = folder,
                level = node.level,
                isExpanded = isExpanded,
                hasChildren = node.hasChildren,
                onToggleExpand = {
                    if (isExpanded) onFolderCollapse(folder.id)
                    else onFolderExpand(folder.id)
                },
                actions = actions,
                drag = drag,
                subtreeIdsOf = subtreeIdsOf
            )
        }

        // 展开时显示内容。
        //
        // 用 AnimatedVisibility 而不是裸 if：展开一个文件夹时子树整块凭空出现、底下所有行
        // 瞬间被顶下去，用户读不出是自己刚才那一下造成的。展开 200ms / 收起 150ms
        // （出场比入场快，见 AppMotion）。
        //
        // 内层必须再套一个 Column：AnimatedVisibility 的 content 接收者是
        // AnimatedVisibilityScope 而不是 ColumnScope，文档行和子文件夹直接摞进去会重叠。
        //
        // 收起动画那 150ms 里子树仍在组合中，里面的文件夹行也仍是注册着的拖放落点——但
        // 它们的 bounds 由 onGloballyPositioned 逐帧上报、跟着一起变矮，所以判定不会用到
        // 陈旧范围；动画结束后各行自身的 DisposableEffect 注销。
        AnimatedVisibility(
            visible = isExpanded || node.folder == null,
            enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
            exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 文档列表
                node.documents.forEach { doc ->
                    DocumentRow(
                        document = doc,
                        level = node.level + 1,
                        isSelected = doc.id == currentDocumentId,
                        onClick = { onDocumentClick(doc.id) },
                        onRename = actions?.let { { it.onRenameDocument(doc) } },
                        onDelete = actions?.let { { it.onDeleteDocument(doc) } },
                        onMove = actions?.let { { it.onMoveDocument(doc) } },
                        drag = drag
                    )
                }

                // 递归显示子文件夹
                node.children.forEach { child ->
                    FolderTreeItem(
                        node = child,
                        currentDocumentId = currentDocumentId,
                        isExpanded = child.folder?.let { expandedFolders.contains(it.id) } ?: true,
                        expandedFolders = expandedFolders,
                        onDocumentClick = onDocumentClick,
                        onFolderExpand = onFolderExpand,
                        onFolderCollapse = onFolderCollapse,
                        actions = actions,
                        drag = drag,
                        subtreeIdsOf = subtreeIdsOf
                    )
                }
            }
        }
    }
}

@Composable
fun FolderRow(
    folder: Folder,
    level: Int,
    isExpanded: Boolean,
    hasChildren: Boolean,
    onToggleExpand: () -> Unit,
    actions: SidebarActions?,
    drag: DragController? = null,
    subtreeIdsOf: (String) -> Set<String> = { emptySet() }
) {
    var showMenu by remember { mutableStateOf(false) }
    var rowTop by remember { mutableFloatStateOf(0f) }

    // 导入库的显示名。在这里取而不是各用点各取：下面拖动的 onDragStart 在 pointerInput 的
    // suspend 作用域里，不是 @Composable，读不了 stringResource。
    val folderLabel = folder.displayName()

    val isDraggedSelf = drag?.draggedId == folder.id
    val isDropTarget = drag?.dropValid == true && !drag.dropIsRoot && drag.dropTargetFolderId == folder.id

    // 拖动落点上报 + 离开时注销，避免折叠/换树后留下陈旧命中区
    if (drag != null) {
        DisposableEffect(folder.id) {
            onDispose { drag.unregisterTarget(folder.id) }
        }
    }

    // 落点高亮做成渐变而不是硬切：拖动中手指扫过一串文件夹，颜色瞬跳会让人分不清到底
    // 停在哪一行。用 exit 那一档（150ms）而不是 200ms——悬停反馈慢了就变成拖尾。
    val dropHighlight by animateColorAsState(
        targetValue = if (isDropTarget) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else Color.Transparent,
        animationSpec = AppMotion.exit(),
        label = "folderDropHighlight"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行高下限 48dp。右侧「更多」菜单按钮原来是 32dp，而整行本身也是可点的
            // （点行 = 展开/收起）。M3 的 minimumInteractiveComponentSize 只是布局层
            // 兜底——它把预留框撑到 48dp 并居中子节点，并不放大按钮的指针命中区，而整行
            // 的 clickable 是精确命中，优先级更高。结果是按钮左右那一圈其实点不到菜单，
            // 反而把文件夹折叠了。把按钮撑到 48dp 并让行高跟上，是同时解决「点不准」和
            // 「行太挤」的唯一做法（M3 单行列表项本就是 48–56dp）。
            .heightIn(min = AppSpacing.MinTouchTarget)
            .testTag(SidebarTags.NODE)
            .background(dropHighlight)
            .clickable(onClick = onToggleExpand)
            .then(
                if (drag != null) Modifier
                    .onGloballyPositioned {
                        val r = it.boundsInRoot()
                        rowTop = r.top
                        drag.registerTarget(folder.id, r.top, r.bottom, folderId = folder.id, isRoot = false)
                    }
                    .pointerInput(folder.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { off ->
                                drag.start(
                                    id = folder.id,
                                    isFolder = true,
                                    label = folderLabel,
                                    parent = folder.parentId,
                                    invalidSet = subtreeIdsOf(folder.id),
                                    startRootY = rowTop + off.y
                                )
                            },
                            onDrag = { change, amount -> change.consume(); drag.drag(amount.y) },
                            onDragEnd = { drag.commit() },
                            onDragCancel = { drag.cancel() }
                        )
                    }
                else Modifier
            )
            .alpha(if (isDraggedSelf) 0.4f else 1f)
            // 不再写 top/bottom padding：行高由上面的 48dp 下限决定，再叠 padding 就是 56dp。
            .padding(start = RowIndentStep * level, end = AppSpacing.Tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 展开/收起图标
        if (hasChildren) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                // 箭头表示的是「点这一行会发生什么」，整行可点即为切换展开。
                // contentDescription 必须跟着状态变，否则读屏用户听到的永远是同一句。
                contentDescription = stringResource(
                    if (isExpanded) R.string.cd_collapse_folder else R.string.cd_expand_folder
                ),
                modifier = Modifier
                    .size(AppIconSize.Medium)
                    .testTag(SidebarTags.EXPAND_TOGGLE),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 占位撑出箭头的槽位，让没有子项的文件夹名与有子项的对齐，所以用的是图标尺寸而非间距。
            Spacer(modifier = Modifier.width(AppIconSize.Medium))
        }

        Spacer(modifier = Modifier.width(AppSpacing.Tight))

        // 文件夹图标
        Icon(
            imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(AppIconSize.Medium),
            tint = extendedColors.primaryText
        )

        Spacer(modifier = Modifier.width(AppSpacing.Default))

        // 文件夹名称
        Text(
            text = folderLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 更多菜单（纯浏览模式不显示）
        if (actions != null) {
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier
                        .size(AppSpacing.MinTouchTarget)
                        .testTag(SidebarTags.NODE_MENU)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        // 原来读的是 menu_file（「文件」），与按钮实际行为无关；
                        // 复用编辑器里同一个「更多选项」键
                        contentDescription = stringResource(R.string.cd_more_options),
                        modifier = Modifier.size(AppIconSize.Small)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_document)) },
                        onClick = {
                            actions.onCreateDocument(folder.id)
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Add, null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_subfolder)) },
                        onClick = {
                            actions.onCreateSubfolder(folder.id)
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.CreateNewFolder, null)
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_to)) },
                        onClick = {
                            actions.onMoveFolder(folder.id)
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        onClick = {
                            actions.onRenameFolder(folder.id)
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_folder)) },
                        onClick = {
                            actions.onDeleteFolder(folder.id)
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DocumentRow(
    document: Document,
    level: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    drag: DragController? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    var rowTop by remember { mutableFloatStateOf(0f) }

    val isDraggedSelf = drag?.draggedId == document.id

    // 选中底色走渐变：切文档时整行颜色硬跳，和左侧竖线一起瞬间出现会显得很突兀。
    val selectionTint by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = AppMotion.enter(),
        label = "documentSelectionTint"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 与 FolderRow 同一个理由：右侧菜单按钮原来 28dp，整行又是可点的，按钮周围
            // 那一圈会被整行的精确命中抢走。详见 FolderRow 处的长注释。
            .heightIn(min = AppSpacing.MinTouchTarget)
            .testTag(SidebarTags.DOCUMENT)
            .background(selectionTint)
            .clickable(onClick = onClick)
            .then(
                if (drag != null) Modifier
                    .onGloballyPositioned { rowTop = it.boundsInRoot().top }
                    .pointerInput(document.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { off ->
                                drag.start(
                                    id = document.id,
                                    isFolder = false,
                                    label = document.name,
                                    parent = document.folderId,
                                    invalidSet = emptySet(),
                                    startRootY = rowTop + off.y
                                )
                            },
                            onDrag = { change, amount -> change.consume(); drag.drag(amount.y) },
                            onDragEnd = { drag.commit() },
                            onDragCancel = { drag.cancel() }
                        )
                    }
                else Modifier
            )
            .alpha(if (isDraggedSelf) 0.4f else 1f)
            // 同 FolderRow：不写 top/bottom padding，行高交给 48dp 下限。
            // 也不再按 isSelected 追加 start padding——见下面选中指示器的注释。
            .padding(
                start = RowIndentStep * level + DocumentIndentExtra,
                end = AppSpacing.Default
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 选中指示器。旧写法有两个毛病，一起改掉：
        // 一是它根本画不出来——3dp 宽的 Box 里又写了 padding(end = 4.dp)，padding 是从这 3dp
        // 里往内扣，扣完内容区宽度为 0，里面那个 fillMaxSize 的 Surface 一个像素都没有。
        // 二是它只在选中时才占位，加上外层那句 `if (isSelected) padding(start = 4.dp)`，
        // 选中/取消选中会让整行文字左右跳约 11dp，看着像点错了行。
        // 现在槽位恒定占位、直接由 Box 自己上色，只有颜色随选中变化。
        Box(
            modifier = Modifier
                .width(SelectionIndicatorWidth)
                .height(SelectionIndicatorHeight)
                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
        )
        Spacer(modifier = Modifier.width(AppSpacing.Tight))

        // 文档图标
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(AppIconSize.Small),
            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(AppSpacing.Default))

        // 文档名称
        Text(
            text = document.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 收藏图标
        if (document.isFavorite) {
            Spacer(modifier = Modifier.width(AppSpacing.Tight))
            Icon(
                imageVector = Icons.Default.Favorite,
                // 这是行末的状态标记（不是可点的收藏按钮），读屏必须念出来，
                // 否则「已收藏」这个信息对读屏用户完全丢失
                contentDescription = stringResource(R.string.cd_favorited),
                // 原来是 14dp——不成档的孤例。并到 Inline(16dp) 是有意的 +2dp，
                // 这颗心在行末本来就偏小。
                modifier = Modifier.size(AppIconSize.Inline),
                tint = extendedColors.primaryText
            )
        }

        // 更多菜单（移动/重命名/删除；纯浏览模式不显示）
        if (onRename != null || onDelete != null || onMove != null) {
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier
                        .size(AppSpacing.MinTouchTarget)
                        .testTag(SidebarTags.DOCUMENT_MENU)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        // 原来读的是 menu_file（「文件」），与按钮行为无关
                        contentDescription = stringResource(R.string.cd_more_options),
                        modifier = Modifier.size(AppIconSize.Inline)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (onMove != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.move_to)) },
                            onClick = { showMenu = false; onMove() },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) }
                        )
                    }
                    if (onRename != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = { showMenu = false; onRename() },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                    }
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = { showMenu = false; onDelete() },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
