package com.yumark.app.presentation.filelist

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.yumark.app.R
import com.yumark.app.core.util.SafLocations
import com.yumark.app.data.local.file.WorkspaceScanner
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.SearchResult
import com.yumark.app.domain.model.SortOption
import com.yumark.app.domain.repository.FolderRepository
import com.yumark.app.domain.usecase.importing.ImportCandidate
import com.yumark.app.presentation.ai.AiAssistantHost
import com.yumark.app.presentation.common.FolderConfirmDialog
import com.yumark.app.presentation.common.SnackbarEffect
import com.yumark.app.presentation.common.displayName
import com.yumark.app.presentation.common.formatElapsedTime
import com.yumark.app.presentation.common.resolveOrNull
import com.yumark.app.presentation.navigation.Screen
import com.yumark.app.presentation.sidebar.SidebarActions
import com.yumark.app.presentation.sidebar.SidebarFileTree
import com.yumark.app.presentation.sidebar.MoveToFolderDialog
import com.yumark.app.presentation.sidebar.selfAndDescendantFolderIds
import com.yumark.app.presentation.sidebar.WorkspaceFileTree
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppMotion
import com.yumark.app.presentation.theme.AppSpacing
import com.yumark.app.presentation.theme.extendedColors

/**
 * UI 测试用的稳定标签。
 *
 * 集中成一个 object 而不是散着写字面量：标签一旦被测试引用就是契约，
 * 散落的魔法字符串改名时必然漏改，而漏改在测试里只表现成「节点找不到」，很难查。
 *
 * 会重复出现的列表行共用同一个标签（[ITEM] / [SEARCH_ITEM]），测试侧用
 * onAllNodesWithTag 取集合再按文本筛。不做 id 后缀是因为文档 id 是运行时生成的
 * UUID，测试里写不出字面量；拼接出来的标签还会在每次重组时多分配一个 String。
 *
 * 标签一律挂在真正可点的那个节点上（卡片本体，不是外层做动画的 Box），
 * 否则 performClick() 点不到东西。
 */
private object FileListTags {
    /** 文档列表根容器（LazyColumn） */
    const val ROOT = "filelist_root"
    /** 单个文档行（重复节点，共用同一标签） */
    const val ITEM = "filelist_item"
    /** 搜索结果列表根容器 */
    const val SEARCH_RESULTS = "filelist_search_results"
    /** 单条搜索结果（重复节点，共用同一标签） */
    const val SEARCH_ITEM = "filelist_search_item"
    const val SEARCH_FIELD = "filelist_search_field"
    const val SORT_TOGGLE = "filelist_sort_toggle"
    const val CREATE_DOCUMENT = "filelist_create_document"
    const val CREATE_FOLDER = "filelist_create_folder"
    const val DELETE_CONFIRM = "filelist_delete_confirm"
    const val DELETE_CANCEL = "filelist_delete_cancel"
    const val DELETE_FOLDER_CONFIRM = "filelist_delete_folder_confirm"
    const val DELETE_FOLDER_CANCEL = "filelist_delete_folder_cancel"
    const val IMPORT_SELECT_ALL = "filelist_import_select_all"
    const val IMPORT_CONFIRM = "filelist_import_confirm"
    const val IMPORT_CANCEL = "filelist_import_cancel"
}

/**
 * 本屏特有的两个布局上界，刻意不塞进全局 [AppSpacing] / [AppIconSize] 标度：它们是对话框内
 * 滚动列表的最大高度——超过就让列表自身滚动，而不是把 AlertDialog 顶穿屏幕。这类「布局上界」
 * 与「间距标度」是两回事，按 AiConfigMetrics 的先例落在屏幕局部。
 */
private object FileListMetrics {
    /** 导入勾选列表最大高度 */
    val ImportListMaxHeight = 320.dp
    /** 导入位置选择树最大高度 */
    val ImportPickerMaxHeight = 360.dp
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FileListScreen(
    navController: NavController,
    viewModel: FileListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val expandedFolders by viewModel.expandedFolders.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showSubfolderDialog by remember { mutableStateOf<String?>(null) }
    var folderToRename by remember { mutableStateOf<Pair<String, String>?>(null) }
    var folderToDelete by remember { mutableStateOf<String?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var documentToRename by remember { mutableStateOf<Document?>(null) }
    var documentToDelete by remember { mutableStateOf<Document?>(null) }
    var documentToMove by remember { mutableStateOf<Document?>(null) }
    var folderToMove by remember { mutableStateOf<String?>(null) }
    var showImportMenu by remember { mutableStateOf(false) }
    var showAiSheet by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val workspace by viewModel.workspace.collectAsStateWithLifecycle()
    val workspaceError by viewModel.workspaceError.collectAsStateWithLifecycle()
    // 错误条的文案在这里一次性解析：下面两处显示点（抽屉里 / 工作区树上方）共用同一个字符串，
    // 各自 resolve 一次没有收益，还会让「同一条错误」的两处渲染分别持有各自的解析结果。
    val workspaceErrorText = workspaceError.resolveOrNull()
    val defaultDirRestoreFailed by viewModel.defaultDirRestoreFailed.collectAsStateWithLifecycle()
    val isWorkspaceLoading by viewModel.isWorkspaceLoading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 操作失败提示（删除/重命名/创建失败等），不影响列表。
    // 文案先在组合期解析：LaunchedEffect 的 block 不是 @Composable，里面调不了 stringResource。
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val actionErrorText = actionError.resolveOrNull()
    SnackbarEffect(actionErrorText, snackbarHostState) { viewModel.clearActionError() }

    // 删除成功后的「可撤销」提示：移入回收站不是终点，10 秒内点撤销原样拿回。
    // 撤销窗刻意用 Long（约 10s）——这是误删的主要挽回窗口，比普通提示长才有意义。
    val trashUndo by viewModel.trashUndo.collectAsStateWithLifecycle()
    val trashCount by viewModel.trashCount.collectAsStateWithLifecycle()
    val undoText = stringResource(R.string.trash_undo_action)
    val trashUndoMessage = trashUndo?.let { undo ->
        undo.name?.let { stringResource(R.string.trash_moved_snackbar, it) }
            ?: stringResource(R.string.trash_moved_generic)
    }
    SnackbarEffect(
        message = trashUndoMessage,
        actionLabel = undoText,
        hostState = snackbarHostState,
        duration = SnackbarDuration.Long,
        onAction = { viewModel.undoTrashDelete() },
        // 超时清理不交给 onConsumed：连续删除时旧 effect 的取消回调会吞掉新撤销项
        // （竞态细节见 FileListViewModel.scheduleUndoExpiry），清理由超时任务按 id 对账完成
        onConsumed = {}
    )

    // 打开抽屉时自动重扫工作区，保持文件树新鲜
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen && workspace != null) {
            viewModel.rescanWorkspace()
        }
    }

    // 方案 A：选完文件夹先回显名称确认，确认后才持久授权 + 打开，避免嵌套深时误选父目录
    var pendingWorkspaceDir by remember { mutableStateOf<android.net.Uri?>(null) }
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) pendingWorkspaceDir = uri }

    pendingWorkspaceDir?.let { uri ->
        FolderConfirmDialog(
            uri = uri,
            titleRes = R.string.open_folder_confirm_title,
            messageRes = R.string.open_folder_confirm_message,
            onConfirm = {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.openWorkspace(uri.toString())
                pendingWorkspaceDir = null
            },
            onDismiss = { pendingWorkspaceDir = null }
        )
    }

    // 文件列表（未进入文档）也提供 AI 助手入口；无文档时 Agent 仍可经工具操作任意文档
    if (showAiSheet) {
        AiAssistantHost(
            currentDocumentId = null,
            currentDocumentName = null,
            currentDocumentContent = null,
            onNavigateToDocument = { docId ->
                navController.navigate(Screen.Editor.createRoute(docId))
            },
            onDismiss = { showAiSheet = false }
        )
    }

    // 启动时自动检查更新
    val autoUpdateInfo by viewModel.autoUpdateInfo.collectAsStateWithLifecycle()
    var downloadingUpdate by remember { mutableStateOf<com.yumark.app.domain.model.UpdateInfo?>(null) }

    // 自动更新对话框
    autoUpdateInfo?.let { updateInfo ->
        com.yumark.app.presentation.settings.UpdateDialog(
            updateInfo = updateInfo,
            onDismiss = { viewModel.dismissAutoUpdate() },
            onUpdate = { update ->
                downloadingUpdate = update
                viewModel.dismissAutoUpdate()
            }
        )
    }

    // 下载对话框
    downloadingUpdate?.let { updateInfo ->
        com.yumark.app.presentation.settings.DownloadDialog(
            updateInfo = updateInfo,
            onDismiss = { downloadingUpdate = null },
            context = context
        )
    }

    // 「导入到库」整条流程（选文件/选文件夹 → 确认 → 勾选 → 复制 → 结果提示）都在这里面，
    // 包括它自己的对话框与 Snackbar；本页只保留两个入口。编辑器侧栏用的是同一个函数。
    val importFlow = rememberImportFlow(
        folders = (uiState as? FileListUiState.Success)?.folders ?: emptyList(),
        snackbarHostState = snackbarHostState
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                val ws = workspace
                if (ws == null) {
                    // ===== 内部文档库模式 =====
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.Screen),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.all_documents),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Row {
                            // 旧的「打开文件夹」入口已移除（外部文件夹改由 设置 → 默认目录 进入），
                            // 避免与「导入文件夹」并存造成两个入口的困惑
                            Box {
                                IconButton(onClick = { showImportMenu = true }) {
                                    Icon(Icons.Default.FileDownload, stringResource(R.string.import_to_library))
                                }
                                DropdownMenu(
                                    expanded = showImportMenu,
                                    onDismissRequest = { showImportMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.import_file)) },
                                        onClick = {
                                            showImportMenu = false
                                            importFlow.pickFiles()
                                        },
                                        leadingIcon = { Icon(Icons.Default.Description, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.import_folder)) },
                                        onClick = {
                                            showImportMenu = false
                                            importFlow.pickFolder()
                                        },
                                        leadingIcon = { Icon(Icons.Default.FolderOpen, null) }
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showFolderDialog = true },
                                modifier = Modifier.testTag(FileListTags.CREATE_FOLDER)
                            ) {
                                Icon(Icons.Default.CreateNewFolder, stringResource(R.string.create_folder))
                            }
                        }
                    }

                    HorizontalDivider()

                    // 工作区错误提示条（如恢复失败/打开失败）
                    workspaceErrorText?.let { err ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Default),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                // 默认目录失效要去设置页重设（这里临时选文件夹不会改默认目录）；
                                // 其他打开失败仍走「重新选择」临时工作区流程
                                if (defaultDirRestoreFailed) {
                                    TextButton(onClick = {
                                        viewModel.clearWorkspaceError()
                                        navController.navigate(Screen.Settings.route)
                                        scope.launch { drawerState.close() }
                                    }) {
                                        Text(stringResource(R.string.workspace_goto_settings))
                                    }
                                } else {
                                    TextButton(onClick = {
                                        viewModel.clearWorkspaceError()
                                        folderPickerLauncher.launch(SafLocations.storageRootHint())
                                    }) {
                                        Text(stringResource(R.string.workspace_reselect))
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = isWorkspaceLoading,
                        enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
                        exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    // 侧边栏文件树
                    val s = uiState
                    if (s is FileListUiState.Success && s.folderTree != null) {
                        SidebarFileTree(
                            tree = s.folderTree,
                            currentDocumentId = null,
                            expandedFolders = expandedFolders,
                            onDocumentClick = {
                                navController.navigate(Screen.Editor.createRoute(it))
                                scope.launch { drawerState.close() }
                            },
                            onFolderExpand = { viewModel.onFolderExpand(it) },
                            onFolderCollapse = { viewModel.onFolderCollapse(it) },
                            actions = SidebarActions(
                                onCreateDocument = { folderId ->
                                    viewModel.onFolderSelected(folderId)
                                    showCreateDialog = true
                                },
                                onCreateSubfolder = { parentId ->
                                    showSubfolderDialog = parentId
                                },
                                onRenameFolder = { folderId ->
                                    // 预填的是 Room 里真实存的 name，不是 displayName()：
                                    // 这个对话框编辑的就是那个值，预填本地化文案会让「不改直接保存」
                                    // 把导入库改名成一句英文，之后中文语区也只能看到英文。
                                    s.folders.find { it.id == folderId }?.let { folder ->
                                        folderToRename = folderId to folder.name
                                    }
                                },
                                onDeleteFolder = { folderId ->
                                    folderToDelete = folderId
                                },
                                onRenameDocument = { documentToRename = it },
                                onDeleteDocument = { documentToDelete = it },
                                onMoveDocument = { documentToMove = it },
                                onMoveFolder = { folderToMove = it },
                                onMoveDocumentTo = { id, target -> viewModel.moveDocument(id, target) },
                                onMoveFolderTo = { id, target -> viewModel.moveFolder(id, target) }
                            )
                        )
                    }
                } else {
                    // ===== 外部工作区模式 =====
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Cozy),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = extendedColors.primaryText
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Default))
                        Text(
                            ws.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = { viewModel.rescanWorkspace() }) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                        }
                        IconButton(onClick = { viewModel.closeWorkspace() }) {
                            Icon(Icons.Default.Close, stringResource(R.string.close_workspace))
                        }
                    }

                    HorizontalDivider()

                    // 上限由 WorkspaceScanner 定，文案里不能再抄一份数字：
                    // 改了常量而文案没跟着改，界面就会对用户说谎。
                    val fileLimitNotice = stringResource(
                        R.string.workspace_truncated,
                        WorkspaceScanner.MAX_FILES
                    )
                    val depthLimitNotice = stringResource(
                        R.string.workspace_truncated_depth,
                        WorkspaceScanner.MAX_DEPTH
                    )
                    // 两条分开说：撞深度上限时文档总数可能只有十几个，此时说「仅显示前 2000 个
                    // 文档」就是在骗用户，他会反复去找那 2000 个在哪。两个上限都撞上就都显示，
                    // 它们指的是两件不同的缺失（横向没收完 / 纵向没下钻）。
                    val limitNotices = listOfNotNull(
                        fileLimitNotice.takeIf { ws.fileLimitHit },
                        depthLimitNotice.takeIf { ws.depthLimitHit }
                    )
                    limitNotices.forEach { notice ->
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Tight)
                        )
                    }

                    workspaceErrorText?.let { err ->
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Tight)
                        )
                    }

                    AnimatedVisibility(
                        visible = isWorkspaceLoading,
                        enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
                        exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    WorkspaceFileTree(
                        root = ws.root,
                        expandedFolders = expandedFolders,
                        onDocumentClick = { doc ->
                            navController.navigate(Screen.Editor.createExternalRoute(doc.uri))
                            scope.launch { drawerState.close() }
                        },
                        onFolderToggle = { uri -> viewModel.onWorkspaceFolderToggle(uri) }
                    )
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (isSearchActive) {
                    // 搜索模式
                    TopAppBar(
                        title = {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it
                                    viewModel.onSearchQueryChanged(it)
                                },
                                placeholder = { Text(stringResource(R.string.hint_search)) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(FileListTags.SEARCH_FIELD)
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                isSearchActive = false
                                searchQuery = ""
                                viewModel.onSearchQueryChanged("")
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    stringResource(R.string.cd_exit_search)
                                )
                            }
                        }
                    )
                } else {
                    // 正常模式
                    TopAppBar(
                        title = { Text("YuMark") },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, stringResource(R.string.toggle_sidebar))
                            }
                        },
                        actions = {
                            // 搜索/排序/回收站作用于内部文档库，工作区模式下隐藏避免语义混乱
                            if (workspace == null) {
                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(Icons.Default.Search, stringResource(R.string.search))
                                }
                                IconButton(
                                    onClick = { showSortMenu = true },
                                    modifier = Modifier.testTag(FileListTags.SORT_TOGGLE)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort))
                                }
                                IconButton(onClick = { navController.navigate(Screen.Trash.route) }) {
                                    // 角标 = 回收站非空计数：把「删过的东西还在」直接画在入口上，
                                    // 不用点进去才知道有没有可恢复的内容
                                    BadgedBox(
                                        badge = {
                                            if (trashCount > 0) {
                                                Badge { Text(trashCount.toString()) }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, stringResource(R.string.trash_title))
                                    }
                                }
                            }
                            IconButton(onClick = { showAiSheet = true }) {
                                // 复用编辑器的 editor_ai_assistant：那个键的注释已经写明它既做
                                // contentDescription 也做可见文案，不再造一个同义键
                                Icon(Icons.Default.AutoAwesome, stringResource(R.string.editor_ai_assistant))
                            }
                            IconButton(onClick = { navController.navigate("settings") }) {
                                Icon(Icons.Default.Settings, stringResource(R.string.settings))
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                SortOption.entries.forEach { o ->
                                    DropdownMenuItem(
                                        text = { Text(o.localizedLabel()) },
                                        onClick = { viewModel.onSortOptionChanged(o); showSortMenu = false }
                                    )
                                }
                            }
                        }
                    )
                }
            },
            floatingActionButton = {
                // 工作区模式下 FAB 新建的是内部文档，隐藏避免误解
                if (workspace == null) {
                    FloatingActionButton(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.testTag(FileListTags.CREATE_DOCUMENT)
                    ) {
                        Icon(Icons.Default.Add, stringResource(R.string.create_document))
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                val ws = workspace
                if (ws != null) {
                    // 工作区模式：主界面提示从侧栏打开文档（内部文档库列表在此模式下隐藏）
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(AppSpacing.Section),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(AppIconSize.Hero),
                            tint = extendedColors.primaryText
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Screen))
                        Text(
                            stringResource(R.string.filelist_workspace_browsing, ws.name),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Tight))
                        Text(
                            stringResource(R.string.workspace_main_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Cozy))
                        TextButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text(stringResource(R.string.workspace_open_sidebar))
                        }
                    }
                } else when (val s = uiState) {
                    is FileListUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    is FileListUiState.Success -> {
                        if (s.documents.isEmpty() && !s.isSearching) {
                            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                // 空态的视觉锚点：纯文字的空屏读起来像「出了问题」，
                                // 一个降饱和的大图标先给出「这是正常的空状态」的信号
                                Icon(
                                    Icons.AutoMirrored.Filled.Notes,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(AppSpacing.Cozy))
                                Text(stringResource(R.string.empty_documents), style = MaterialTheme.typography.headlineSmall)
                                Spacer(modifier = Modifier.height(AppSpacing.Default))
                                Text(stringResource(R.string.empty_documents_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else if (s.isSearching && s.searchResults.isEmpty()) {
                            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                // 与「还没有文档」空态同一套视觉锚点手法（图标语义换成搜索）
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(AppSpacing.Cozy))
                                Text(stringResource(R.string.empty_search_results), style = MaterialTheme.typography.headlineSmall)
                            }
                        } else if (s.isSearching) {
                            // 搜索结果列表
                            LazyColumn(
                                modifier = Modifier.testTag(FileListTags.SEARCH_RESULTS),
                                contentPadding = PaddingValues(AppSpacing.Screen),
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.Default)
                            ) {
                                items(s.searchResults, key = { it.document.id }) { result ->
                                    SearchResultCard(
                                        result = result,
                                        onClick = {
                                            navController.navigate(Screen.Editor.createRoute(result.document.id))
                                        }
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.testTag(FileListTags.ROOT),
                                contentPadding = PaddingValues(AppSpacing.Screen),
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.Default)
                            ) {
                                items(s.documents, key = { it.id }) { doc ->
                                    // Compose Foundation 1.7 移除了实验性的 animateItemPlacement()，
                                    // 换成已转正的 animateItem()（同时覆盖出现/移动/消失三种动画）。
                                    Box(modifier = Modifier.animateItem()) {
                                        DocumentCard(
                                            doc = doc,
                                            onClick = { navController.navigate(Screen.Editor.createRoute(doc.id)) },
                                            onFavorite = { viewModel.toggleFavorite(doc.id) },
                                            onRename = { documentToRename = doc },
                                            onDelete = { documentToDelete = doc }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.create_document)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.document_name)) },
                    placeholder = { Text(stringResource(R.string.hint_document_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.createDocument(name); showCreateDialog = false }, enabled = name.isNotBlank()) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text(stringResource(R.string.create_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    placeholder = { Text(stringResource(R.string.hint_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.createFolder(name); showFolderDialog = false }, enabled = name.isNotBlank()) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = { TextButton(onClick = { showFolderDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // 创建子文件夹对话框
    showSubfolderDialog?.let { parentId ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSubfolderDialog = null },
            title = { Text(stringResource(R.string.create_subfolder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    placeholder = { Text(stringResource(R.string.hint_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createSubfolder(name, parentId)
                        showSubfolderDialog = null
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = { TextButton(onClick = { showSubfolderDialog = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // 重命名文件夹对话框
    folderToRename?.let { (folderId, oldName) ->
        var newName by remember { mutableStateOf(oldName) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameFolder(folderId, newName)
                        folderToRename = null
                    },
                    enabled = newName.isNotBlank() && newName != oldName
                ) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = { TextButton(onClick = { folderToRename = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // 删除文件夹确认对话框
    folderToDelete?.let { folderId ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.delete_folder)) },
            text = { Text(stringResource(R.string.delete_folder_with_contents)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFolder(folderId, deleteContents = true)
                        folderToDelete = null
                    },
                    modifier = Modifier.testTag(FileListTags.DELETE_FOLDER_CONFIRM),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { folderToDelete = null },
                    modifier = Modifier.testTag(FileListTags.DELETE_FOLDER_CANCEL)
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 重命名文档对话框
    documentToRename?.let { doc ->
        var newName by remember { mutableStateOf(doc.name) }
        AlertDialog(
            onDismissRequest = { documentToRename = null },
            title = { Text(stringResource(R.string.rename_document)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.document_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameDocument(doc.id, newName)
                        documentToRename = null
                    },
                    enabled = newName.isNotBlank() && newName != doc.name
                ) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = { TextButton(onClick = { documentToRename = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // 删除文档确认对话框
    // 移动文档到其他文件夹
    documentToMove?.let { doc ->
        val moveFolders = (uiState as? FileListUiState.Success)?.folders ?: emptyList()
        // 禁选文档当前所在位置(重选等同 no-op，仍会刷新 updated_at/重排)：
        // 在某文件夹内 → 禁该文件夹；在根目录 → 禁根目录行。
        MoveToFolderDialog(
            title = stringResource(R.string.editor_move_to_title, doc.name),
            folders = moveFolders,
            disabledFolderIds = setOfNotNull(doc.folderId),
            rootEnabled = doc.folderId != null,
            onDismiss = { documentToMove = null },
            onPick = { target ->
                viewModel.moveDocument(doc.id, target)
                documentToMove = null
            }
        )
    }

    // 移动文件夹到其他文件夹
    folderToMove?.let { folderId ->
        val moveFolders = (uiState as? FileListUiState.Success)?.folders ?: emptyList()
        // 收 String 的重载：接收者可空，?. 会把 @Composable 调用变成条件调用
        val importLibraryName = stringResource(R.string.import_library)
        val name = moveFolders.find { it.id == folderId }?.displayName(importLibraryName) ?: ""
        val folderParentId = moveFolders.find { it.id == folderId }?.parentId
        MoveToFolderDialog(
            title = stringResource(R.string.editor_move_to_title, name),
            folders = moveFolders,
            disabledFolderIds = selfAndDescendantFolderIds(moveFolders, folderId),
            rootEnabled = folderParentId != null,
            onDismiss = { folderToMove = null },
            onPick = { target ->
                viewModel.moveFolder(folderId, target)
                folderToMove = null
            }
        )
    }

    documentToDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { documentToDelete = null },
            title = { Text(stringResource(R.string.delete_document)) },
            text = { Text(stringResource(R.string.delete_document_confirm, doc.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDocument(doc.id)
                        documentToDelete = null
                    },
                    modifier = Modifier.testTag(FileListTags.DELETE_CONFIRM),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { documentToDelete = null },
                    modifier = Modifier.testTag(FileListTags.DELETE_CANCEL)
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun SearchResultCard(
    result: SearchResult,
    onClick: () -> Unit
) {
    OutlinedCard(
        // 标签挂在可点的卡片本体上（不是外层容器），测试里 performClick 才点得到
        modifier = Modifier
            .fillMaxWidth()
            .testTag(FileListTags.SEARCH_ITEM)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(AppSpacing.Screen)) {
            Text(
                result.document.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(AppSpacing.Tight))
            Text(
                // 命中次数走 plurals：英文 "1 match" / "2 matches" 分支不同；
                // 只有标题命中（正文 0 处）是另一条独立文案，不能当成 0 的复数形式。
                if (result.matchCount > 0) {
                    pluralStringResource(
                        R.plurals.filelist_search_match_count,
                        result.matchCount,
                        result.matchCount
                    )
                } else {
                    stringResource(R.string.filelist_search_title_match)
                },
                style = MaterialTheme.typography.bodySmall,
                color = extendedColors.primaryText
            )
            result.snippets.firstOrNull()?.let { snippet ->
                Spacer(modifier = Modifier.height(AppSpacing.Tight))
                Text(
                    snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun DocumentCard(
    doc: Document,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    OutlinedCard(
        // 标签挂在可点的卡片本体上（不是外层做 animateItem 的 Box），
        // 测试里 onAllNodesWithTag(ITEM)[i].performClick() 才点得到
        modifier = Modifier
            .fillMaxWidth()
            .testTag(FileListTags.ITEM)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Screen),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    doc.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(AppSpacing.Tight))
                // 字数与相对时间都必须走资源：英文语境下 "1 word" / "1 minute ago" 的单复数
                // 只能由 plurals 决定，Kotlin 字符串模板拼不出来。中间的「 • 」是标点分隔符，
                // 不进资源表。
                val words = pluralStringResource(R.plurals.word_count, doc.wordCount, doc.wordCount)
                val updated = formatElapsedTime(doc.updatedAt)
                Text(
                    "$words • $updated",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row {
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (doc.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        // 图标是收藏/取消收藏的**开关**，contentDescription 必须跟着状态变，
                        // 否则读屏用户听到的永远是同一句，无从知道按下去会发生什么。
                        contentDescription = stringResource(
                            if (doc.isFavorite) R.string.unfavorite else R.string.favorite
                        ),
                        tint = if (doc.isFavorite) extendedColors.primaryText
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.cd_more_options))
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = { showMenu = false; onRename() },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
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

// formatElapsedTime 已抽到 presentation/common（回收站页复用同一套口径），
// 这里只留 import；8 周以上退绝对日期是抽离时顺带补的分支，行为见 RelativeTime.kt。

/**
 * 文件夹导入勾选对话框：列出扫描到的候选文件，默认全不选，用户手动勾选要导入的项。
 * 按相对文件夹路径分组，便于在嵌套结构中辨认。顶部可查看/更改导入位置（默认导入库）。
 *
 * internal 而不是 private：编辑器侧栏也有「导入到库」入口，两处共用 [rememberImportFlow]，
 * 而这个对话框由它统一发射。留在本文件里不动是为了少搬 150 行——它只被同包内调用。
 */
@Composable
internal fun ImportSelectionDialog(
    candidates: List<ImportCandidate>,
    folders: List<Folder>,
    onConfirm: (List<ImportCandidate>, String?) -> Unit,
    onDismiss: () -> Unit
) {
    // 选中状态用候选的 uri 作键（默认全不选）
    val checked = remember(candidates) { mutableStateMapOf<String, Boolean>() }
    val selectedCount = checked.count { it.value }
    val allSelected = selectedCount == candidates.size && candidates.isNotEmpty()

    var targetFolderId by remember {
        mutableStateOf<String?>(FolderRepository.IMPORT_LIBRARY_FOLDER_ID)
    }
    var showTargetPicker by remember { mutableStateOf(false) }
    if (showTargetPicker) {
        ImportTargetPickerDialog(
            folders = folders,
            current = targetFolderId,
            onSelect = { targetFolderId = it; showTargetPicker = false },
            onDismiss = { showTargetPicker = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        ),
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .wrapContentHeight(),
        title = { Text(stringResource(R.string.import_select_folder_files, selectedCount)) },
        text = {
            Column {
                ImportTargetRow(
                    targetFolderId = targetFolderId,
                    folders = folders,
                    onChange = { showTargetPicker = true }
                )
                HorizontalDivider()
                // 全选/全不选快捷开关
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(FileListTags.IMPORT_SELECT_ALL)
                        .clickable {
                            val target = !allSelected
                            candidates.forEach { checked[it.uri] = target }
                        }
                        .padding(vertical = AppSpacing.Tight),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { target -> candidates.forEach { checked[it.uri] = target } }
                    )
                    Text(stringResource(R.string.import_select_all), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.heightIn(max = FileListMetrics.ImportListMaxHeight)) {
                    items(candidates, key = { it.uri }) { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { checked[candidate.uri] = !(checked[candidate.uri] ?: false) }
                                .padding(vertical = AppSpacing.Tight),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked[candidate.uri] ?: false,
                                onCheckedChange = { checked[candidate.uri] = it }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    candidate.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // 相对路径（去掉根名后的子文件夹链），帮助辨认同名文件
                                val sub = candidate.relativeFolderPath.drop(1).joinToString(" / ")
                                if (sub.isNotEmpty()) {
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(candidates.filter { checked[it.uri] == true }, targetFolderId) },
                modifier = Modifier.testTag(FileListTags.IMPORT_CONFIRM),
                enabled = selectedCount > 0
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(FileListTags.IMPORT_CANCEL)
            ) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** 导入文件确认对话框：回显文件数 + 导入位置（默认导入库，可更改）。internal 理由同 [ImportSelectionDialog]。 */
@Composable
internal fun ImportFilesDialog(
    fileCount: Int,
    folders: List<Folder>,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var targetFolderId by remember {
        mutableStateOf<String?>(FolderRepository.IMPORT_LIBRARY_FOLDER_ID)
    }
    var showTargetPicker by remember { mutableStateOf(false) }
    if (showTargetPicker) {
        ImportTargetPickerDialog(
            folders = folders,
            current = targetFolderId,
            onSelect = { targetFolderId = it; showTargetPicker = false },
            onDismiss = { showTargetPicker = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        ),
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .wrapContentHeight(),
        title = { Text(stringResource(R.string.import_file)) },
        text = {
            Column {
                Text(pluralStringResource(R.plurals.import_files_count, fileCount, fileCount))
                Spacer(modifier = Modifier.height(AppSpacing.Cozy))
                ImportTargetRow(
                    targetFolderId = targetFolderId,
                    folders = folders,
                    onChange = { showTargetPicker = true }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(targetFolderId) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** 导入位置行：展示当前导入路径 + 更改入口（导入文件/导入文件夹两个对话框共用） */
@Composable
private fun ImportTargetRow(
    targetFolderId: String?,
    folders: List<Folder>,
    onChange: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(AppIconSize.Medium),
            tint = extendedColors.primaryText
        )
        Spacer(modifier = Modifier.width(AppSpacing.Default))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.import_target),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                importTargetLabel(targetFolderId, folders),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onChange) { Text(stringResource(R.string.import_target_change)) }
    }
}

/**
 * 导入位置的完整路径文案：沿父链拼出「a / b / c」，根目录与未建的导入库有专名。
 *
 * 标 @Composable 只为了能读 stringResource（唯一调用点在 ImportTargetRow 的组合里）。
 * 根目录文案在函数开头无条件取好再判空：composable 调用放在提前 return 之前，
 * 组合的分组结构最稳定。
 */
@Composable
private fun importTargetLabel(targetFolderId: String?, folders: List<Folder>): String {
    val rootLabel = stringResource(R.string.move_to_root)
    val importLibraryName = stringResource(R.string.import_library)
    if (targetFolderId == null) return rootLabel
    val byId = folders.associateBy { it.id }
    // 找不到只有一种情况：导入库还没惰性创建出来，所以它不在 folders 里
    if (targetFolderId !in byId) return importLibraryName
    val names = ArrayDeque<String>()
    var cur: String? = targetFolderId
    var guard = 0
    while (cur != null && ++guard <= 64) {
        val folder = byId[cur] ?: break
        names.addFirst(folder.displayName(importLibraryName))
        cur = folder.parentId
    }
    return names.joinToString(" / ")
}

/** 导入位置选择器中的一行（平铺后的树节点） */
private data class ImportTargetOption(val folderId: String?, val label: String, val level: Int)

/**
 * 导入位置选择对话框：导入库（默认）置顶，其后是根目录与库内全部文件夹的缩进树。
 * 导入库可能尚未创建（惰性建），所以默认行不依赖 folders 列表。
 */
@Composable
private fun ImportTargetPickerDialog(
    folders: List<Folder>,
    current: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    // 文案先在组合期取好，再喂给 remember：remember 的 block 不是 @Composable，
    // 里面调不了 stringResource。两条文案同时进 key，切换系统语言后列表会重建。
    val rootLabel = stringResource(R.string.move_to_root)
    // 导入库这一行的名字取本地化文案，不取 IMPORT_LIBRARY_FOLDER_NAME：那个常量是写进 Room 的
    // 中文字面量，英文语区的用户会在这里看到「导入库（默认）」。这一行不依赖 folders 列表
    // （导入库是惰性创建的，可能还不存在），所以走不了 Folder.displayName()。
    val importLibraryLabel = stringResource(
        R.string.filelist_import_target_default,
        stringResource(R.string.import_library)
    )
    val options = remember(folders, rootLabel, importLibraryLabel) {
        val result = mutableListOf<ImportTargetOption>()
        fun addChildren(parentId: String?, level: Int) {
            // 导入库自己被这条 filter 排除，它的行在下面单独拼；所以这里的 folder.name
            // 不可能是那个中文常量，不需要过 displayName()。
            folders.filter { it.parentId == parentId && it.id != FolderRepository.IMPORT_LIBRARY_FOLDER_ID }
                .sortedBy { it.order }
                .forEach { folder ->
                    result += ImportTargetOption(folder.id, folder.name, level)
                    addChildren(folder.id, level + 1)
                }
        }
        result += ImportTargetOption(
            FolderRepository.IMPORT_LIBRARY_FOLDER_ID,
            importLibraryLabel,
            0
        )
        addChildren(FolderRepository.IMPORT_LIBRARY_FOLDER_ID, 1)
        result += ImportTargetOption(null, rootLabel, 0)
        addChildren(null, 1)
        result
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        ),
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .wrapContentHeight(),
        title = { Text(stringResource(R.string.import_target_pick)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = FileListMetrics.ImportPickerMaxHeight)) {
                items(options.size) { index ->
                    val option = options[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option.folderId) }
                            .padding(start = AppSpacing.Screen * option.level),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option.folderId == current,
                            onClick = { onSelect(option.folderId) }
                        )
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(AppIconSize.Small),
                            tint = extendedColors.primaryText
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Default))
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
