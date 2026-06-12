package com.yumark.app.presentation.filelist

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.yumark.app.R
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.SearchResult
import com.yumark.app.domain.model.SortOption
import com.yumark.app.domain.usecase.importing.ImportCandidate
import com.yumark.app.presentation.navigation.Screen
import com.yumark.app.presentation.sidebar.SidebarFileTree
import com.yumark.app.presentation.sidebar.WorkspaceFileTree

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FileListScreen(
    navController: NavController,
    viewModel: FileListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val expandedFolders by viewModel.expandedFolders.collectAsState()

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
    var showImportMenu by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val workspace by viewModel.workspace.collectAsState()
    val workspaceError by viewModel.workspaceError.collectAsState()
    val isWorkspaceLoading by viewModel.isWorkspaceLoading.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 操作失败提示（删除/重命名/创建失败等），不影响列表
    val actionError by viewModel.actionError.collectAsState()
    LaunchedEffect(actionError) {
        actionError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionError()
        }
    }

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
        val fallbackName = stringResource(R.string.default_dir_picked_fallback)
        val name = remember(uri, fallbackName) {
            DocumentFile.fromTreeUri(context, uri)?.name
                ?: uri.lastPathSegment ?: fallbackName
        }
        AlertDialog(
            onDismissRequest = { pendingWorkspaceDir = null },
            title = { Text(stringResource(R.string.open_folder_confirm_title)) },
            text = { Text(stringResource(R.string.open_folder_confirm_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    viewModel.openWorkspace(uri.toString())
                    pendingWorkspaceDir = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingWorkspaceDir = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // 导入成功提示
    val importMessage by viewModel.importMessage.collectAsState()
    LaunchedEffect(importMessage) {
        importMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearImportMessage()
        }
    }

    // 导入文件：系统多选选择器，仅勾选项被导入（手动选择，非自动导入）
    val importFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // 持久读取授权对每个文件单独生效（复制读取需要）
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            viewModel.importFiles(uris)
        }
    }

    // 导入文件夹：先选文件夹（树授权），扫描后弹勾选对话框，仅勾选项被复制
    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.scanImportFolder(uri.toString())
        }
    }

    // 文件夹导入勾选对话框（默认全不选，手动勾）
    val importCandidates by viewModel.importCandidates.collectAsState()
    importCandidates?.let { candidates ->
        ImportSelectionDialog(
            candidates = candidates,
            onConfirm = { viewModel.confirmImportFolder(it) },
            onDismiss = { viewModel.cancelImportFolder() }
        )
    }

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
                            .padding(16.dp),
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
                                            importFilesLauncher.launch(arrayOf("text/*", "text/markdown", "application/octet-stream"))
                                        },
                                        leadingIcon = { Icon(Icons.Default.Description, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.import_folder)) },
                                        onClick = {
                                            showImportMenu = false
                                            importFolderLauncher.launch(null)
                                        },
                                        leadingIcon = { Icon(Icons.Default.FolderOpen, null) }
                                    )
                                }
                            }
                            IconButton(onClick = { showFolderDialog = true }) {
                                Icon(Icons.Default.CreateNewFolder, stringResource(R.string.create_folder))
                            }
                        }
                    }

                    HorizontalDivider()

                    // 工作区错误提示条（如恢复失败/打开失败）
                    workspaceError?.let { err ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    viewModel.clearWorkspaceError()
                                    folderPickerLauncher.launch(null)
                                }) {
                                    Text(stringResource(R.string.workspace_reselect))
                                }
                            }
                        }
                    }

                    if (isWorkspaceLoading) {
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
                            onCreateDocument = { folderId ->
                                viewModel.onFolderSelected(folderId)
                                showCreateDialog = true
                            },
                            onCreateSubfolder = { parentId ->
                                showSubfolderDialog = parentId
                            },
                            onRenameFolder = { folderId ->
                                // 找到文件夹名称
                                s.folders.find { it.id == folderId }?.let { folder ->
                                    folderToRename = folderId to folder.name
                                }
                            },
                            onDeleteFolder = { folderId ->
                                folderToDelete = folderId
                            }
                        )
                    }
                } else {
                    // ===== 外部工作区模式 =====
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
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

                    if (ws.truncated) {
                        Text(
                            stringResource(R.string.workspace_truncated),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    workspaceError?.let { err ->
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    if (isWorkspaceLoading) {
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
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                isSearchActive = false
                                searchQuery = ""
                                viewModel.onSearchQueryChanged("")
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close))
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
                            // 搜索/排序作用于内部文档库，工作区模式下隐藏避免语义混乱
                            if (workspace == null) {
                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(Icons.Default.Search, stringResource(R.string.search))
                                }
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort))
                                }
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
                    FloatingActionButton(onClick = { showCreateDialog = true }) {
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
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "正在浏览「${ws.name}」",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.workspace_main_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text(stringResource(R.string.workspace_open_sidebar))
                        }
                    }
                } else when (val s = uiState) {
                    is FileListUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    is FileListUiState.Success -> {
                        if (s.documents.isEmpty() && !s.isSearching) {
                            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.empty_documents), style = MaterialTheme.typography.headlineSmall)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(stringResource(R.string.empty_documents_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else if (s.isSearching && s.searchResults.isEmpty()) {
                            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.empty_search_results), style = MaterialTheme.typography.headlineSmall)
                            }
                        } else if (s.isSearching) {
                            // 搜索结果列表
                            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(s.documents, key = { it.id }) { doc ->
                                    Box(modifier = Modifier.animateItemPlacement()) {
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
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // 重命名文档对话框
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
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { documentToDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
fun SearchResultCard(
    result: SearchResult,
    onClick: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                result.document.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (result.matchCount > 0) "${result.matchCount} 处匹配" else "标题匹配",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            result.snippets.firstOrNull()?.let { snippet ->
                Spacer(modifier = Modifier.height(4.dp))
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${doc.wordCount} 字 • ${formatDate(doc.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row {
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (doc.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (doc.isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "更多")
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = { showMenu = false; onRename() },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
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

private fun formatDate(instant: kotlinx.datetime.Instant): String {
    val now = kotlinx.datetime.Clock.System.now()
    val diff = now - instant
    return when {
        diff.inWholeMinutes < 1 -> "刚刚"
        diff.inWholeMinutes < 60 -> "${diff.inWholeMinutes}分钟前"
        diff.inWholeHours < 24 -> "${diff.inWholeHours}小时前"
        diff.inWholeDays < 7 -> "${diff.inWholeDays}天前"
        else -> "${diff.inWholeDays / 7}周前"
    }
}

/**
 * 文件夹导入勾选对话框：列出扫描到的候选文件，默认全不选，用户手动勾选要导入的项。
 * 按相对文件夹路径分组，便于在嵌套结构中辨认。
 */
@Composable
private fun ImportSelectionDialog(
    candidates: List<ImportCandidate>,
    onConfirm: (List<ImportCandidate>) -> Unit,
    onDismiss: () -> Unit
) {
    // 选中状态用候选的 uri 作键（默认全不选）
    val checked = remember(candidates) { mutableStateMapOf<String, Boolean>() }
    val selectedCount = checked.count { it.value }
    val allSelected = selectedCount == candidates.size && candidates.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_select_folder_files, selectedCount)) },
        text = {
            Column {
                // 全选/全不选快捷开关
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val target = !allSelected
                            candidates.forEach { checked[it.uri] = target }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { target -> candidates.forEach { checked[it.uri] = target } }
                    )
                    Text(stringResource(R.string.import_select_all), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(candidates, key = { it.uri }) { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { checked[candidate.uri] = !(checked[candidate.uri] ?: false) }
                                .padding(vertical = 4.dp),
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
                onClick = { onConfirm(candidates.filter { checked[it.uri] == true }) },
                enabled = selectedCount > 0
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
