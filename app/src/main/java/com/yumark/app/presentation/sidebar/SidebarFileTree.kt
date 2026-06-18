package com.yumark.app.presentation.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.FolderTreeNode
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
    var pointerY by mutableStateOf(0f); private set

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

    var containerTop by remember { mutableStateOf(0f) }
    var containerBottom by remember { mutableStateOf(0f) }

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
        val index = findDocumentIndex(tree, currentDocumentId, expandedFolders)
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    // 拖动时靠近上下边缘自动滚动
    LaunchedEffect(drag?.isDragging) {
        if (drag == null || !drag.isDragging) return@LaunchedEffect
        val edge = with(density) { 72.dp.toPx() }
        val step = with(density) { 10.dp.toPx() }
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
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

        // 根落区：仅拖动中显示，悬浮在列表顶部，作为「移出到根目录」的落点
        if (drag != null && drag.isDragging) {
            // 拖动结束后注销根落区的命中范围，避免其陈旧 bounds 残留到下一次拖动
            // 起始帧误命中（文件夹行在自身 dispose 时已注销，根落区此前漏了）。
            DisposableEffect(Unit) {
                onDispose { drag.unregisterTarget(ROOT_DROP_KEY) }
            }
            val rootHighlighted = drag.dropValid && drag.dropIsRoot
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        val r = it.boundsInRoot()
                        drag.registerTarget(ROOT_DROP_KEY, r.top, r.bottom, folderId = null, isRoot = true)
                    },
                color = if (rootHighlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Home, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("根目录（松手移到此处）", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // 拖影：跟随手指
        // 注意：pointerY 在 offset{} lambda(layout 阶段)中读取，
        // 否则每拖动帧都会触发整棵树的 recomposition。
        if (drag != null && drag.isDragging) {
            Surface(
                modifier = Modifier
                    .offset {
                        val y = drag.pointerY - containerTop - with(density) { 18.dp.toPx() }
                        IntOffset(x = with(density) { 24.dp.roundToPx() }, y = y.roundToInt())
                    }
                    .alpha(0.95f),
                shadowElevation = 6.dp,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (drag.draggedIsFolder) Icons.Default.Folder else Icons.Default.Description,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        drag.draggedLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp)
                    )
                }
            }
        }
    }
}

/**
 * 扁平化遍历树结构，找到指定文档在LazyColumn中的索引
 */
private fun findDocumentIndex(
    tree: List<FolderTreeNode>,
    documentId: String,
    expandedFolders: Set<String>
): Int {
    var index = 0

    fun traverse(nodes: List<FolderTreeNode>): Boolean {
        for (node in nodes) {
            // 文件夹行占一个索引
            if (node.folder != null) index++

            // 只遍历展开的节点
            val isExpanded = node.folder == null || expandedFolders.contains(node.folder.id)
            if (isExpanded) {
                // 检查文档列表
                for (doc in node.documents) {
                    if (doc.id == documentId) {
                        return true // 找到目标文档
                    }
                    index++
                }

                // 递归子文件夹
                if (traverse(node.children)) {
                    return true
                }
            }
        }
        return false
    }

    return if (traverse(tree)) index else -1
}

/**
 * 找到文档所在位置的全部祖先文件夹 id（从根到直接父文件夹，有序）。
 * 用于打开侧栏时自动展开这些文件夹，使深处的当前文档可见并可滚动定位。
 */
private fun ancestorFolderIdsOf(tree: List<FolderTreeNode>, documentId: String): List<String> {
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

        // 展开时显示内容
        if (isExpanded || node.folder == null) {
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
    var rowTop by remember { mutableStateOf(0f) }

    val isDraggedSelf = drag?.draggedId == folder.id
    val isDropTarget = drag?.dropValid == true && !drag.dropIsRoot && drag.dropTargetFolderId == folder.id

    // 拖动落点上报 + 离开时注销，避免折叠/换树后留下陈旧命中区
    if (drag != null) {
        DisposableEffect(folder.id) {
            onDispose { drag.unregisterTarget(folder.id) }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isDropTarget) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
            )
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
                                    label = folder.name,
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
            .padding(start = (level * 16).dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 展开/收起图标
        if (hasChildren) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(modifier = Modifier.width(20.dp))
        }

        Spacer(modifier = Modifier.width(4.dp))

        // 文件夹图标
        Icon(
            imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 文件夹名称
        Text(
            text = folder.name,
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
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.menu_file),
                        modifier = Modifier.size(18.dp)
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
    var rowTop by remember { mutableStateOf(0f) }

    val isDraggedSelf = drag?.draggedId == document.id

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                }
            )
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
            .padding(
                start = (level * 16 + 24).dp,
                end = 8.dp,
                top = 6.dp,
                bottom = 6.dp
            )
            .then(
                if (isSelected) Modifier.padding(start = 4.dp)
                else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 选中指示器
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .padding(end = 4.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary
                ) {}
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        // 文档图标
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(8.dp))

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
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // 更多菜单（移动/重命名/删除；纯浏览模式不显示）
        if (onRename != null || onDelete != null || onMove != null) {
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.menu_file),
                        modifier = Modifier.size(16.dp)
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
