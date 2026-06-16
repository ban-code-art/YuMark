package com.yumark.app.presentation.editor

import android.content.Intent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.yumark.app.R
import com.yumark.app.domain.model.ExportFormat
import com.yumark.app.presentation.ai.AiAssistantHost
import com.yumark.app.presentation.navigation.Screen
import com.yumark.app.presentation.sidebar.SidebarActions
import com.yumark.app.presentation.sidebar.SidebarFileTree
import com.yumark.app.presentation.sidebar.WorkspaceFileTree

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    navController: NavController,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val document by viewModel.document.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val isPreviewMode by viewModel.isPreviewMode.collectAsState()
    val outline by viewModel.outline.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val applyError by viewModel.applyError.collectAsState()
    val aiEnabled by viewModel.aiEnabled.collectAsState()
    var showAiSheet by remember { mutableStateOf(false) }

    // 文本选择快捷 AI/Agent 功能
    var showQuickAiDialog by remember { mutableStateOf(false) }
    var selectedText by remember { mutableStateOf("") }
    // 编辑模式选区的半开区间 (start, end)，供 Agent 按精确区间替换；预览模式为 null
    var selectedRange by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val outlineDrawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val editorFontSize by viewModel.editorFontSize.collectAsState()

    // 左侧文件树抽屉：顶栏按钮打开，点文档直接切换（替代旧的返回箭头）
    val fileDrawerState = rememberDrawerState(DrawerValue.Closed)
    var fileTreeExpanded by remember { mutableStateOf(setOf<String>()) }
    val folderTree by viewModel.folderTree.collectAsState()
    val editorWorkspace by viewModel.workspace.collectAsState()

    // 返回键处理优先级（从高到低）：
    // 1. 抽屉打开时先收抽屉
    BackHandler(enabled = fileDrawerState.isOpen) {
        scope.launch { fileDrawerState.close() }
    }

    // 2. 预览模式或编辑模式下返回键直接退出并保存（防抖避免连退两页）
    var isExiting by remember { mutableStateOf(false) }
    BackHandler(enabled = !fileDrawerState.isOpen) {
        if (!isExiting) {
            isExiting = true
            scope.launch {
                viewModel.saveAndWait()
                navController.navigateUp()
            }
        }
    }

    // 切换到侧栏点选的文档：先保存当前文档，再用新编辑器替换当前页（返回栈不增长）
    val openFromSidebar: (String) -> Unit = { route ->
        scope.launch {
            fileDrawerState.close()
            viewModel.saveAndWait()
            navController.navigate(route) {
                popUpTo(Screen.Editor.route) { inclusive = true }
            }
        }
    }

    val themeId by viewModel.themeId.collectAsState()
    // 以实际生效的主题亮度判断深浅（兼容设置里手动选择的深色模式）
    val isDarkMode = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // AI 助手 BottomSheet
    if (showAiSheet) {
        AiAssistantHost(
            currentDocumentId = viewModel.currentDocumentId,
            currentDocumentName = document?.name,
            currentDocumentContent = document?.content,
            onNavigateToDocument = { docId ->
                scope.launch {
                    viewModel.saveAndWait()
                    navController.navigate(Screen.Editor.createRoute(docId)) {
                        popUpTo(Screen.Editor.route) { inclusive = true }
                    }
                }
            },
            onDocumentUpdated = {
                // AI 编辑完成后刷新当前文档
                viewModel.reloadDocumentFromRepository()
            },
            onDismiss = { showAiSheet = false }
        )
    }

    // 文本选择快捷 AI/Agent 对话框
    if (showQuickAiDialog && selectedText.isNotEmpty()) {
        AiQuickDialog(
            selectedText = selectedText,
            onDismiss = { showQuickAiDialog = false },
            onApplyEdit = { oldText, newText ->
                // Agent 模式下应用修改：编辑模式用精确选区，预览模式按原文匹配
                viewModel.replaceSelectedText(oldText, newText, selectedRange)
            },
            allowEditSelectedText = isPreviewMode  // 预览模式下允许编辑选中文本
        )
    }

    val previewDarkColors = remember(themeId, isDarkMode) {
        if (!isDarkMode) null else when (themeId) {
            "claude" -> "#262624" to "#F0EEE6"
            else -> "#1E1E1E" to "#DADADA"
        }
    }

    // 渲染器就绪标记：JS 桥在页面脚本加载完后回调置位
    val rendererReady = remember { mutableStateOf(false) }

    // WebView 单实例：整个编辑器生命周期只创建/加载一次，编辑/预览切换零成本
    val previewWebView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true

                // 启用缩放功能
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false  // 隐藏默认的+/-按钮
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            // 不透明背景：透明 WebView 会禁用分块光栅化/合成优化，长文档滚动时持续重绘导致卡顿。
            // 用与渲染页一致的纯色背景，既保持硬件加速滚动流畅，又避免深色模式进预览闪白。
            setBackgroundColor(previewBgColor(isDarkMode, themeId))

            // 预览模式下外层 ModalNavigationDrawer 的水平拖拽手势会与 WebView 竞争触摸事件，
            // 导致垂直滚动需经 Compose 手势竞技场判定方向后才响应，表现为滚动卡顿/划不动。
            // 触摸落在 WebView 上时请求父级不拦截，让 WebView 独占滚动（大纲仍可由顶栏按钮打开）。
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        v.performClick()
                    }
                    android.view.MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }

            // 添加 JavaScript 接口用于文本选择监听
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onTextSelected(text: String) {
                    scope.launch {
                        if (text.isBlank()) {
                            // 清空选中文本
                            selectedText = ""
                            selectedRange = null
                        } else if (aiEnabled && text.trim().length > 2) {
                            // 更新选中文本（预览选区来自渲染文本，无可靠源码区间，置 null 走原文匹配）
                            selectedText = text
                            selectedRange = null
                        }
                    }
                }
            }, "AndroidSelection")

            addJavascriptInterface(object {
                @JavascriptInterface
                fun log(message: String) {
                    android.util.Log.d("WebView", message)
                }

                @JavascriptInterface
                fun onOutline(json: String) {
                    viewModel.onOutlineReceived(json)
                }

                @JavascriptInterface
                fun onReady() {
                    // JS 桥回调在 WebView 线程，post 回主线程改 Compose 状态
                    post { rendererReady.value = true }
                }

                @JavascriptInterface
                fun resetZoom() {
                    // 在主线程恢复缩放
                    post {
                        // 重置viewport缩放
                        evaluateJavascript("""
                            (function() {
                                var meta = document.querySelector('meta[name="viewport"]');
                                if (!meta) {
                                    meta = document.createElement('meta');
                                    meta.name = 'viewport';
                                    document.head.appendChild(meta);
                                }
                                meta.content = 'width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=3.0, user-scalable=yes';
                            })();
                        """, null)

                        // 重置WebView缩放级别
                        setInitialScale(100)
                    }
                }
            }, "Android")

            addJavascriptInterface(object {
                @JavascriptInterface
                fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
                    // 在主线程通知父容器
                    post {
                        (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(disallow)
                    }
                }
            }, "AndroidTouch")

            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.d("WebView", consoleMessage.message())
                    return true
                }
            }

            // 处理链接点击：外部链接用系统浏览器打开，锚点链接在 WebView 内跳转
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    android.util.Log.d("WebView", "shouldOverrideUrlLoading: $url")

                    // 外部链接（http/https）用系统浏览器打开
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("WebView", "Failed to open URL: $url", e)
                        }
                        return true
                    }

                    // 锚点链接（以 # 开头）允许 WebView 内部处理
                    if (url.contains("#")) {
                        return false
                    }

                    // 其他所有链接都拦截，防止加载到错误页面
                    return true
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, url: String): Boolean {
                    android.util.Log.d("WebView", "shouldOverrideUrlLoading (deprecated): $url")

                    // 兼容旧版 Android
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("WebView", "Failed to open URL: $url", e)
                        }
                        return true
                    }

                    // 锚点链接允许 WebView 内部处理
                    if (url.contains("#")) {
                        return false
                    }

                    // 其他所有链接都拦截
                    return true
                }

                override fun onReceivedError(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    android.util.Log.e("WebView", "onReceivedError: ${request?.url}, error: ${error?.description}")
                    // 不调用 super，避免显示错误页面
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(
                    view: android.webkit.WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    android.util.Log.e("WebView", "onReceivedError (deprecated): $failingUrl, error: $description")
                    // 不调用 super，避免显示错误页面
                }

                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 设置viewport：最小100%，最大300%
                    view?.evaluateJavascript("""
                        (function() {
                            var meta = document.querySelector('meta[name="viewport"]');
                            if (!meta) {
                                meta = document.createElement('meta');
                                meta.name = 'viewport';
                                document.head.appendChild(meta);
                            }
                            meta.content = 'width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=3.0, user-scalable=yes';
                        })();
                    """, null)
                    // 注入文本选择监听脚本
                    view?.evaluateJavascript(textSelectionListenerJs(), null)
                }
            }

            val template = context.assets.open("templates/renderer.html")
                .bufferedReader().use { it.readText() }
            loadDataWithBaseURL("file:///android_asset/", template, "text/html", "UTF-8", null)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewWebView.removeJavascriptInterface("Android")
            previewWebView.destroy()
        }
    }

    // 深色模式覆盖样式：就绪即注入，无延迟
    LaunchedEffect(rendererReady.value, previewDarkColors) {
        if (rendererReady.value && previewDarkColors != null) {
            val (bg, fg) = previewDarkColors
            previewWebView.evaluateJavascript(darkStyleJs(bg, fg), null)
        }
    }

    // 相对路径图片解析基址（导入库/外部工作区文档）：就绪即注入，渲染前后到达均生效
    val imageResolver by viewModel.imageResolver.collectAsState()
    LaunchedEffect(rendererReady.value, imageResolver) {
        if (rendererReady.value && imageResolver != null) {
            val json = kotlinx.serialization.json.Json.encodeToString(
                ImageResolverConfig.serializer(), imageResolver!!
            )
            previewWebView.evaluateJavascript("window.setImageResolver($json)", null)
        }
    }

    // WebView 单实例创建时只取了初始背景色，主题/深浅切换后同步更新原生背景，保持与渲染页一致
    LaunchedEffect(isDarkMode, themeId) {
        previewWebView.setBackgroundColor(previewBgColor(isDarkMode, themeId))
    }

    // 预览字号跟随设置
    LaunchedEffect(rendererReady.value, editorFontSize) {
        if (rendererReady.value) {
            previewWebView.evaluateJavascript(
                "document.body.style.fontSize='${editorFontSize}px';", null
            )
        }
    }

    // 退出预览时收起大纲抽屉
    LaunchedEffect(isPreviewMode) {
        if (!isPreviewMode && outlineDrawerState.isOpen) {
            outlineDrawerState.close()
        }
    }

    // 保存失败 Snackbar（编辑内容保留在内存，不打断编辑）
    LaunchedEffect(saveError) {
        saveError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSaveError()
        }
    }

    // Agent 应用修改失败 Snackbar（无法在原文中定位选中文本）
    LaunchedEffect(applyError) {
        applyError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearApplyError()
        }
    }

    // 导出成功 → 系统分享（mime 按导出文件实际格式）
    val exportedFile by viewModel.exportedFile.collectAsState()
    LaunchedEffect(exportedFile) {
        exportedFile?.let { file ->
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = when (file.extension.lowercase()) {
                    "md", "markdown" -> "text/markdown"
                    "html" -> "text/html"
                    else -> "text/plain"
                }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null))
            viewModel.clearExportedFile()
        }
    }

    // 侧边栏的对话框状态
    var showCreateDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showSubfolderDialog by remember { mutableStateOf<String?>(null) }
    var folderToRename by remember { mutableStateOf<Pair<String, String>?>(null) }
    var folderToDelete by remember { mutableStateOf<String?>(null) }
    var documentToRename by remember { mutableStateOf<com.yumark.app.domain.model.Document?>(null) }
    var documentToDelete by remember { mutableStateOf<com.yumark.app.domain.model.Document?>(null) }
    var showImportMenu by remember { mutableStateOf(false) }

    // 外层左侧文件树抽屉（LTR）；内层沿用 RTL 包裹的右侧大纲抽屉
    ModalNavigationDrawer(
        drawerState = fileDrawerState,
        // 仅打开时允许手势（滑动关闭），关闭时不抢编辑区/WebView 的水平手势
        gesturesEnabled = fileDrawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            ) {
                EditorSidebarContent(
                    isExternal = viewModel.isExternal,
                    workspace = editorWorkspace,
                    folderTree = folderTree,
                    currentDocumentId = viewModel.currentDocumentId,
                    currentDocUri = viewModel.currentDocUri,
                    expandedFolders = fileTreeExpanded,
                    onToggleFolder = { key ->
                        fileTreeExpanded = if (key in fileTreeExpanded) fileTreeExpanded - key
                        else fileTreeExpanded + key
                    },
                    onOpenInternal = { id -> openFromSidebar(Screen.Editor.createRoute(id)) },
                    onOpenExternal = { uri -> openFromSidebar(Screen.Editor.createExternalRoute(uri)) },
                    onCloseDrawer = { scope.launch { fileDrawerState.close() } },
                    fileDrawerState = fileDrawerState,
                    showImportMenu = showImportMenu,
                    onShowImportMenu = { showImportMenu = true },
                    onDismissImportMenu = { showImportMenu = false },
                    onShowFolderDialog = { showFolderDialog = true },
                    onShowCreateDialog = { folderId ->
                        viewModel.selectFolderForNewDoc(folderId)
                        showCreateDialog = true
                    },
                    onShowSubfolderDialog = { showSubfolderDialog = it },
                    onRenameFolder = { folderId, name -> folderToRename = folderId to name },
                    onDeleteFolder = { folderToDelete = it },
                    onRenameDocument = { documentToRename = it },
                    onDeleteDocument = { documentToDelete = it }
                )
            }
        }
    ) {
        // 用 RTL 包裹实现右侧大纲抽屉（内容区恢复 LTR）
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = outlineDrawerState,
            gesturesEnabled = isPreviewMode,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ModalDrawerSheet(
                        drawerShape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                    ) {
                        OutlinePanel(
                            outline = outline,
                            onItemClick = { item ->
                                previewWebView.evaluateJavascript(
                                    "window.scrollToHeading('${item.anchorId}')", null
                                )
                                scope.launch { outlineDrawerState.close() }
                            }
                        )
                    }
                }
            }
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    document?.name ?: stringResource(R.string.editor),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = {
                                // 打开文件树侧栏（退出编辑器走系统返回手势/键）
                                IconButton(onClick = { scope.launch { fileDrawerState.open() } }) {
                                    Icon(Icons.Default.Menu, stringResource(R.string.toggle_sidebar))
                                }
                            },
                            actions = {
                                // 大纲（仅预览模式）
                                if (isPreviewMode) {
                                    IconButton(onClick = { scope.launch { outlineDrawerState.open() } }) {
                                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, stringResource(R.string.outline))
                                    }
                                }

                                // AI 助手（仅启用时显示）
                                if (aiEnabled) {
                                    IconButton(onClick = { showAiSheet = true }) {
                                        Icon(Icons.Default.AutoAwesome, "AI 助手")
                                    }
                                }

                                // 编辑/预览切换（核心功能，始终可见）
                                IconButton(onClick = { viewModel.togglePreviewMode() }) {
                                    Icon(
                                        if (isPreviewMode) Icons.Default.Edit else Icons.Default.Visibility,
                                        if (isPreviewMode) stringResource(R.string.edit_mode) else stringResource(R.string.preview_mode)
                                    )
                                }

                                // 更多菜单（收纳保存、导出、设置）
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, "更多选项")
                                }

                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    // 保存
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.save)) },
                                        onClick = { viewModel.saveDocument(); showMenu = false },
                                        leadingIcon = {
                                            if (isSaving) {
                                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                            } else {
                                                Icon(Icons.Default.Save, null)
                                            }
                                        },
                                        enabled = !isSaving
                                    )

                                    // 导出（仅内部文档）
                                    if (!viewModel.isExternal) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.export_markdown)) },
                                            onClick = { viewModel.exportAs(ExportFormat.MARKDOWN); showMenu = false },
                                            leadingIcon = { Icon(Icons.Default.Download, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.export_html)) },
                                            onClick = { viewModel.exportAs(ExportFormat.HTML); showMenu = false },
                                            leadingIcon = { Icon(Icons.Default.Download, null) }
                                        )
                                    }

                                    // 设置
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.settings)) },
                                        onClick = { navController.navigate(Screen.Settings.route); showMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Settings, null) }
                                    )
                                }
                            }
                        )
                    }
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        when (val state = uiState) {
                            is EditorUiState.Loading -> {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            }
                            is EditorUiState.Success -> {
                                // 本地编辑状态（含光标/选区），避免依赖异步的 document 状态
                                var editValue by remember { mutableStateOf(TextFieldValue(document?.content ?: "")) }
                                // 记录上次从文档加载的内容，用于检测 AI 编辑后的热更新
                                var lastLoadedContent by remember { mutableStateOf(document?.content ?: "") }

                                // 保存滚动状态，在预览和编辑模式间切换时保持位置
                                val scrollState = rememberScrollState()

                                // 持续保存编辑器滚动位置（防抖避免频繁更新）
                                LaunchedEffect(Unit) {
                                    snapshotFlow { scrollState.value }
                                        .debounce(200)
                                        .collect { position ->
                                            viewModel.saveEditScrollPosition(position)
                                        }
                                }

                                // 导航离开时保存WebView滚动位置
                                DisposableEffect(Unit) {
                                    onDispose {
                                        // 验证 WebView 仍然有效再尝试保存滚动位置
                                        if (previewWebView.parent != null) {
                                            previewWebView.post {
                                                previewWebView.evaluateJavascript("window.getScrollRatio()") { result ->
                                                    val ratio = result?.trim('"')?.toFloatOrNull() ?: 0f
                                                    viewModel.savePreviewScrollRatio(ratio)
                                                }
                                            }
                                        }
                                    }
                                }

                                // 从设置返回后恢复滚动位置
                                val savedScrollState by viewModel.scrollState.collectAsState()
                                LaunchedEffect(document?.id, isPreviewMode) {
                                    // 等待视图渲染完成
                                    delay(if (isPreviewMode) 250 else 150)

                                    if (isPreviewMode && savedScrollState.previewScrollRatio > 0f) {
                                        // 恢复预览滚动
                                        previewWebView.evaluateJavascript(
                                            "window.scrollToRatio(${savedScrollState.previewScrollRatio})",
                                            null
                                        )
                                    } else if (!isPreviewMode && savedScrollState.editScrollPosition > 0) {
                                        // 恢复编辑器滚动
                                        scrollState.scrollTo(savedScrollState.editScrollPosition)
                                    }
                                }

                                // 监听文档内容变化（AI 编辑后的热更新）
                                LaunchedEffect(document?.id, document?.content) {
                                    document?.content?.let { content ->
                                        // 如果文档内容变化了（AI 编辑），并且用户没有本地未保存的修改
                                        if (content != lastLoadedContent && content != editValue.text) {
                                            editValue = TextFieldValue(content)
                                            lastLoadedContent = content
                                        }
                                    }
                                }

                                // 就绪即渲染；内容变化时重渲染（守卫避免重复渲染同一内容）
                                var lastRendered by remember { mutableStateOf<String?>(null) }
                                LaunchedEffect(isPreviewMode, rendererReady.value, editValue.text) {
                                    if (isPreviewMode && rendererReady.value &&
                                        editValue.text.isNotEmpty() && editValue.text != lastRendered
                                    ) {
                                        val encoded = android.util.Base64.encodeToString(
                                            editValue.text.toByteArray(Charsets.UTF_8),
                                            android.util.Base64.NO_WRAP
                                        )
                                        previewWebView.evaluateJavascript(renderJs(encoded), null)
                                        lastRendered = editValue.text
                                    }
                                }

                                // 切换模式时同步滚动位置：统一用 JS 内的 CSS px 比例
                                // (renderer 的 getScrollRatio/scrollToRatio)，避免 contentHeight(CSS px)
                                // 与 WebView 物理 px 混算——后者在高密度屏会算成 0 而跳到顶部。
                                LaunchedEffect(isPreviewMode) {
                                    if (isPreviewMode) {
                                        // 切到预览：等内容渲染就绪后，按编辑器滚动比例定位 WebView
                                        kotlinx.coroutines.delay(180)
                                        var attempts = 0
                                        while (previewWebView.contentHeight == 0 && attempts < 20) {
                                            kotlinx.coroutines.delay(40)
                                            attempts++
                                        }
                                        val ratio = if (scrollState.maxValue > 0) {
                                            scrollState.value.toFloat() / scrollState.maxValue.toFloat()
                                        } else 0f
                                        previewWebView.evaluateJavascript(
                                            "window.scrollToRatio($ratio)", null
                                        )
                                    } else {
                                        // 切到编辑：异步读回 WebView 当前比例（此时 WebView 仍存活），再定位编辑器
                                        previewWebView.evaluateJavascript("window.getScrollRatio()") { result ->
                                            // 验证 scrollState 仍有效（避免 WebView 销毁后回调执行导致崩溃）
                                            if (scrollState.maxValue >= 0) {
                                                val ratio = result?.trim('"')?.toFloatOrNull() ?: 0f
                                                scope.launch {
                                                    kotlinx.coroutines.delay(160)
                                                    if (scrollState.maxValue > 0) {
                                                        val target = (scrollState.maxValue * ratio).toInt()
                                                            .coerceIn(0, scrollState.maxValue)
                                                        scrollState.scrollTo(target)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (isPreviewMode) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        AndroidView(
                                            factory = {
                                                // 复用单实例：attach 前先脱离旧 parent
                                                (previewWebView.parent as? ViewGroup)?.removeView(previewWebView)
                                                previewWebView
                                            },
                                            update = { },
                                            modifier = Modifier.weight(1f).fillMaxWidth()
                                        )

                                        // 预览模式下的 AI 按钮（仅当有选中文本且 AI 启用时显示）
                                        if (aiEnabled && selectedText.isNotBlank()) {
                                            Surface(
                                                tonalElevation = 2.dp,
                                                shadowElevation = 4.dp
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    horizontalArrangement = Arrangement.Center,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // AI 助手按钮 - 统一入口
                                                    SuggestionChip(
                                                        onClick = {
                                                            showQuickAiDialog = true
                                                        },
                                                        label = { Text("AI 助手") },
                                                        icon = { Text("✨") }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    // 编辑模式：Typora 式无边框书写区
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        MarkdownToolbar(
                                            onInsertSyntax = { syntax ->
                                                val text = editValue.text
                                                val pos = editValue.selection.start
                                                    .coerceIn(0, text.length)
                                                // 行级语法（标题/列表/引用）插到当前行行首，包裹型插在光标处
                                                val isLineSyntax = syntax in setOf("# ", "- ", "1. ", "> ")
                                                if (isLineSyntax) {
                                                    val lineStart = if (pos == 0) 0
                                                    else text.lastIndexOf('\n', pos - 1) + 1
                                                    val newText = text.substring(0, lineStart) +
                                                        syntax + text.substring(lineStart)
                                                    editValue = TextFieldValue(
                                                        newText,
                                                        selection = TextRange(pos + syntax.length)
                                                    )
                                                    viewModel.onContentChanged(newText)
                                                } else {
                                                    val newText = text.substring(0, pos) +
                                                        syntax + text.substring(pos)
                                                    val cursorOffset = when (syntax) {
                                                        "****" -> 2
                                                        "**" -> 1
                                                        "``" -> 1
                                                        "[](url)" -> 1
                                                        else -> syntax.length
                                                    }
                                                    editValue = TextFieldValue(
                                                        newText,
                                                        selection = TextRange(pos + cursorOffset)
                                                    )
                                                    viewModel.onContentChanged(newText)
                                                }
                                            }
                                        )

                                        BasicTextField(
                                            value = editValue,
                                            onValueChange = { newValue ->
                                                editValue = newValue
                                                viewModel.onContentChanged(newValue.text)

                                                // 检测文本选择（记录精确区间供 Agent 替换；折叠时清空避免 chip 带过期文本）
                                                if (aiEnabled) {
                                                    val sel = newValue.selection
                                                    if (!sel.collapsed) {
                                                        val s = minOf(sel.start, sel.end).coerceIn(0, newValue.text.length)
                                                        val e = maxOf(sel.start, sel.end).coerceIn(0, newValue.text.length)
                                                        val selected = newValue.text.substring(s, e)
                                                        if (selected.isNotBlank() && selected.trim().length > 2) {
                                                            selectedText = selected
                                                            selectedRange = s to e
                                                        }
                                                    } else if (selectedText.isNotEmpty()) {
                                                        selectedText = ""
                                                        selectedRange = null
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth()
                                                .verticalScroll(scrollState)
                                                .padding(horizontal = 20.dp, vertical = 16.dp),
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onBackground,
                                                fontSize = editorFontSize.sp,
                                                lineHeight = (editorFontSize * 1.6f).sp
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            decorationBox = { innerTextField ->
                                                Box {
                                                    if (editValue.text.isEmpty()) {
                                                        Text(
                                                            "开始书写 Markdown…",
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                            }
                                        )

                                        // 底部状态条
                                        Surface(tonalElevation = 1.dp) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // 文本选择操作按钮（仅当有选中文本且 AI 启用时显示）
                                                if (aiEnabled && selectedText.isNotBlank()) {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        // AI 助手 - 统一入口
                                                        SuggestionChip(
                                                            onClick = {
                                                                showQuickAiDialog = true
                                                            },
                                                            label = { Text("AI 助手", style = MaterialTheme.typography.labelSmall) },
                                                            icon = { Text("✨") }
                                                        )
                                                    }
                                                } else {
                                                    Spacer(modifier = Modifier.width(1.dp))
                                                }

                                                Text(
                                                    "${editValue.text.length} 字符",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            is EditorUiState.Error -> {
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(state.message, color = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(onClick = { viewModel.loadDocument() }) {
                                        Text("重试")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

    // ===== 侧边栏对话框 =====
    // 创建文档
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
                TextButton(
                    onClick = {
                        viewModel.createDocument(name)
                        showCreateDialog = false
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 创建文件夹
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
                TextButton(
                    onClick = {
                        viewModel.createFolder(name)
                        showFolderDialog = false
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 创建子文件夹
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
            dismissButton = {
                TextButton(onClick = { showSubfolderDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 重命名文件夹
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
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 删除文件夹
    folderToDelete?.let { folderId ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.delete_folder)) },
            text = { Text(stringResource(R.string.delete_folder_with_contents)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFolder(folderId)
                        folderToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 重命名文档
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
            dismissButton = {
                TextButton(onClick = { documentToRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 删除文档
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
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { documentToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 编辑器左侧抽屉内容：内部文档显示库文件树，外部文档显示工作区文件树。
 * 点击当前文档仅收起抽屉；点击其他文档经 onOpenInternal/onOpenExternal 切换。
 */
@Composable
private fun EditorSidebarContent(
    isExternal: Boolean,
    workspace: com.yumark.app.domain.model.Workspace?,
    folderTree: List<com.yumark.app.domain.model.FolderTreeNode>?,
    currentDocumentId: String?,
    currentDocUri: String?,
    expandedFolders: Set<String>,
    onToggleFolder: (String) -> Unit,
    onOpenInternal: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
    onCloseDrawer: () -> Unit,
    fileDrawerState: DrawerState,
    showImportMenu: Boolean,
    onShowImportMenu: () -> Unit,
    onDismissImportMenu: () -> Unit,
    onShowFolderDialog: () -> Unit,
    onShowCreateDialog: (String) -> Unit,
    onShowSubfolderDialog: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onRenameDocument: (com.yumark.app.domain.model.Document) -> Unit,
    onDeleteDocument: (com.yumark.app.domain.model.Document) -> Unit
) {
    if (isExternal) {
        val ws = workspace
        if (ws == null) {
            Text(
                stringResource(R.string.workspace_main_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            return
        }
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        HorizontalDivider()
        WorkspaceFileTree(
            root = ws.root,
            expandedFolders = expandedFolders,
            onDocumentClick = { doc ->
                if (doc.uri == currentDocUri) onCloseDrawer() else onOpenExternal(doc.uri)
            },
            onFolderToggle = onToggleFolder
        )
    } else {
        // 内部文档库模式 - 顶部标题 + 操作按钮
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
                Box {
                    IconButton(onClick = onShowImportMenu) {
                        Icon(Icons.Default.FileDownload, stringResource(R.string.import_to_library))
                    }
                    // 导入菜单下拉框
                    DropdownMenu(
                        expanded = showImportMenu,
                        onDismissRequest = onDismissImportMenu
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_file)) },
                            onClick = {
                                onDismissImportMenu()
                                // TODO: 启动导入文件流程
                            },
                            leadingIcon = { Icon(Icons.Default.Description, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_folder)) },
                            onClick = {
                                onDismissImportMenu()
                                // TODO: 启动导入文件夹流程
                            },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) }
                        )
                    }
                }
                IconButton(onClick = onShowFolderDialog) {
                    Icon(Icons.Default.CreateNewFolder, stringResource(R.string.create_folder))
                }
            }
        }
        HorizontalDivider()

        val tree = folderTree
        if (tree == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            SidebarFileTree(
                tree = tree,
                currentDocumentId = currentDocumentId,
                expandedFolders = expandedFolders,
                onDocumentClick = { id ->
                    if (id == currentDocumentId) onCloseDrawer() else onOpenInternal(id)
                },
                onFolderExpand = onToggleFolder,
                onFolderCollapse = onToggleFolder,
                scrollToCurrentDocument = fileDrawerState.isOpen,
                actions = SidebarActions(
                    onCreateDocument = { folderId -> onShowCreateDialog(folderId ?: "") },
                    onCreateSubfolder = { folderId -> folderId?.let { onShowSubfolderDialog(it) } },
                    onRenameFolder = { folderId ->
                        // 需要从 folderTree 中找到文件夹名称
                        val folder = findFolderInTree(tree, folderId)
                        folder?.folder?.let { onRenameFolder(folderId, it.name) }
                    },
                    onDeleteFolder = onDeleteFolder,
                    onRenameDocument = onRenameDocument,
                    onDeleteDocument = onDeleteDocument
                )
            )
        }
    }
}

/** 在文件树中递归查找文件夹 */
private fun findFolderInTree(
    tree: List<com.yumark.app.domain.model.FolderTreeNode>,
    folderId: String
): com.yumark.app.domain.model.FolderTreeNode? {
    for (node in tree) {
        if (node.folder?.id == folderId) return node
        val found = findFolderInTree(node.children, folderId)
        if (found != null) return found
    }
    return null
}

/**
 * 预览 WebView 的原生背景色。必须与 renderer.html 实际生效的 body 背景一致：
 * 浅色模式 body 恒为 #fff；深色模式由 darkStyleJs 注入主题背景色。
 * 用不透明纯色（而非透明）是为了保留 WebView 的硬件加速合成路径，长文档滚动才不卡。
 */
private fun previewBgColor(isDarkMode: Boolean, themeId: String): Int {
    val hex = if (!isDarkMode) "#FFFFFF" else when (themeId) {
        "claude" -> "#262624"
        else -> "#1E1E1E"
    }
    return android.graphics.Color.parseColor(hex)
}

/** 渲染 Markdown（Base64 通道，避免字符转义问题） */
private fun renderJs(encodedContent: String): String = """
    (function() {
        try {
            var decodedContent = decodeURIComponent(escape(atob('$encodedContent')));
            if (window.renderMarkdown) {
                window.renderMarkdown(decodedContent);
            } else {
                console.error('renderMarkdown not found');
            }
        } catch(e) {
            console.error('Render error:', e);
        }
    })();
""".trimIndent()

/** 深色模式覆盖样式（含代码块/表格/引用/链接） */
private fun darkStyleJs(bg: String, fg: String): String = """
    (function() {
        var s = document.getElementById('yumark-dark');
        if (!s) { s = document.createElement('style'); s.id = 'yumark-dark'; document.head.appendChild(s); }
        s.textContent = 'body{background:$bg;color:$fg;}' +
            'code,pre{background:#2E2E2C;color:#E0DED6;}' +
            'th{background:#343432;}' + 'th,td{border-color:#4A4A46;}' +
            'blockquote{border-color:#5A5A56;color:#A8A59B;}' +
            'a{color:#7FB2E5;}';
    })();
""".trimIndent()

/**
 * 文本选择监听脚本：在预览模式 WebView 中监听文本选择，
 * 选中文本后通过 AndroidSelection 接口回传给 Kotlin
 */
private fun textSelectionListenerJs(): String = """
    (function() {
        // 避免重复注入
        if (window.__yumarkSelectionListener) return;
        window.__yumarkSelectionListener = true;

        var lastSelection = '';

        function notifySelection() {
            var selection = window.getSelection();
            var text = selection ? selection.toString().trim() : '';

            // 通知选中文本或清空
            if (window.AndroidSelection && window.AndroidSelection.onTextSelected) {
                if (text && text.length > 2) {
                    if (text !== lastSelection) {
                        lastSelection = text;
                        window.AndroidSelection.onTextSelected(text);
                    }
                } else {
                    // 文本清空时也通知
                    if (lastSelection !== '') {
                        lastSelection = '';
                        window.AndroidSelection.onTextSelected('');
                    }
                }
            }
        }

        // 监听选择变化
        document.addEventListener('selectionchange', function() {
            clearTimeout(window.__selectionTimer);
            window.__selectionTimer = setTimeout(notifySelection, 200);
        });

        // 触摸结束时检查
        document.addEventListener('touchend', function() {
            setTimeout(notifySelection, 100);
        });

        // 点击空白处取消选择
        document.addEventListener('click', function(e) {
            setTimeout(notifySelection, 100);
        });
    })();
""".trimIndent()

