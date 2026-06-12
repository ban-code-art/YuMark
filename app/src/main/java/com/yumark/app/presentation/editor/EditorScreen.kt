package com.yumark.app.presentation.editor

import android.content.Intent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.yumark.app.R
import kotlinx.coroutines.launch

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

    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val outlineDrawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val themeId by viewModel.themeId.collectAsState()
    // 以实际生效的主题亮度判断深浅（兼容设置里手动选择的深色模式）
    val isDarkMode = MaterialTheme.colorScheme.background.luminance() < 0.5f
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
            }
            // 透明背景：深色模式进预览不闪白
            setBackgroundColor(android.graphics.Color.TRANSPARENT)

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
            }, "Android")

            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.d("WebView", consoleMessage.message())
                    return true
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

    // 保存失败 Snackbar（编辑内容保留在内存，不打断编辑）
    LaunchedEffect(saveError) {
        saveError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSaveError()
        }
    }

    // 导出成功 → 系统分享
    val exportedFile by viewModel.exportedFile.collectAsState()
    LaunchedEffect(exportedFile) {
        exportedFile?.let { file ->
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null))
            viewModel.clearExportedFile()
        }
    }

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
                            title = { Text(document?.name ?: stringResource(R.string.editor)) },
                            navigationIcon = {
                                IconButton(onClick = {
                                    // 等待保存完成再返回：避免协程随 ViewModel 销毁被取消导致丢数据
                                    scope.launch {
                                        viewModel.saveAndWait()
                                        navController.navigateUp()
                                    }
                                }) {
                                    Icon(Icons.Default.ArrowBack, stringResource(R.string.close))
                                }
                            },
                            actions = {
                                // 大纲（仅预览模式）
                                if (isPreviewMode) {
                                    IconButton(onClick = { scope.launch { outlineDrawerState.open() } }) {
                                        Icon(Icons.Default.FormatListBulleted, stringResource(R.string.outline))
                                    }
                                }

                                // 编辑/预览切换
                                IconButton(onClick = { viewModel.togglePreviewMode() }) {
                                    Icon(
                                        if (isPreviewMode) Icons.Default.Edit else Icons.Default.Visibility,
                                        if (isPreviewMode) stringResource(R.string.edit_mode) else stringResource(R.string.preview_mode)
                                    )
                                }

                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp).padding(end = 8.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    IconButton(onClick = { viewModel.saveDocument() }) {
                                        Icon(Icons.Default.Save, stringResource(R.string.save))
                                    }
                                }

                                // 导出（外部工作区文档暂不支持）
                                if (!viewModel.isExternal) {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Default.MoreVert, stringResource(R.string.menu_file))
                                    }

                                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.export)) },
                                            onClick = { viewModel.exportAsHtml(); showMenu = false },
                                            leadingIcon = { Icon(Icons.Default.Download, null) }
                                        )
                                    }
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

                                LaunchedEffect(document?.id) {
                                    document?.content?.let { content ->
                                        if (editValue.text.isEmpty() && content.isNotEmpty()) {
                                            editValue = TextFieldValue(content)
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

                                if (isPreviewMode) {
                                    AndroidView(
                                        factory = {
                                            // 复用单实例：attach 前先脱离旧 parent
                                            (previewWebView.parent as? ViewGroup)?.removeView(previewWebView)
                                            previewWebView
                                        },
                                        update = { },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    // 编辑模式：Typora 式无边框书写区
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        MarkdownToolbar(
                                            onInsertSyntax = { syntax ->
                                                // 在光标处插入；包裹型语法把光标移到中间
                                                val pos = editValue.selection.start
                                                    .coerceIn(0, editValue.text.length)
                                                val newText = editValue.text.substring(0, pos) +
                                                    syntax + editValue.text.substring(pos)
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
                                        )

                                        BasicTextField(
                                            value = editValue,
                                            onValueChange = { newValue ->
                                                editValue = newValue
                                                viewModel.onContentChanged(newValue.text)
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth()
                                                .verticalScroll(rememberScrollState())
                                                .padding(horizontal = 20.dp, vertical = 16.dp),
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onBackground,
                                                lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.6f
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
                                                horizontalArrangement = Arrangement.End
                                            ) {
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
