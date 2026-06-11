# 性能与视觉优化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除编辑器预览的秒级卡顿（WebView 单实例 + 就绪握手 + mermaid 延迟加载）、输入直通、文件树缓存，并完成编辑区 Typora 化等 4 项视觉优化。

**Architecture:** WebView 提升为 `remember` 单实例随编辑器整个生命周期存活，渲染由 JS 桥 `onReady` 握手驱动（删除全部 postDelayed）；输入路径去协程去锁；文件夹树按 docs/folders 哈希缓存。

**Tech Stack:** Jetpack Compose + WebView + marked.js/KaTeX/Mermaid

**注意：项目非 git 仓库，提交步骤替换为编译/测试验证。** 设计文档：`docs/superpowers/specs/2026-06-11-performance-visual-design.md`

---

### Task 1: 死代码清理

**Files:**
- Delete: `app/src/main/java/com/yumark/app/core/webview/MarkdownRenderer.kt`
- Delete: `app/src/main/java/com/yumark/app/core/webview/JsBridge.kt`
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/com/yumark/app/data/local/db/dao/Daos.kt`

- [ ] **Step 1.1: EditorViewModel 移除渲染器残留**

删除以下内容：
- import `com.yumark.app.core.webview.MarkdownRenderer`
- 字段 `private var markdownRenderer: MarkdownRenderer? = null` 及注释
- 方法 `initializeRenderer(webView)`、`getRenderer()`
- `onCleared()` 中的 `markdownRenderer?.cleanup()`、`markdownRenderer = null` 两行（保留 `stopAutoSave()` 与 `super.onCleared()`）

- [ ] **Step 1.2: 删除文件与 DAO 死方法**

Run: `rm app/src/main/java/com/yumark/app/core/webview/MarkdownRenderer.kt app/src/main/java/com/yumark/app/core/webview/JsBridge.kt`

`Daos.kt` 删除（修复搜索后已无引用）：

```kotlin
    @Query("SELECT * FROM documents WHERE name LIKE '%' || :query || '%' ORDER BY updated_at DESC")
    suspend fun search(query: String): List<DocumentEntity>
```

- [ ] **Step 1.3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL（若有其他文件引用 JsBridge 会在此暴露，按报错移除引用）

---

### Task 2: 输入直通

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/EditorViewModel.kt`

- [ ] **Step 2.1: onContentChanged / onCursorPositionChanged 去协程去锁**

整体替换两个方法（`MutableStateFlow.update` 原子；Mutex 仅保留给 saveDocument 的读-改-写）：

```kotlin
    fun onContentChanged(newContent: String) {
        // 输入热路径：直通更新，不开协程不抢锁（StateFlow.update 本身原子）
        _document.update { it?.copy(content = newContent) }
        isDocumentDirty = true
    }

    fun onCursorPositionChanged(position: Int) {
        _cursorPosition.value = position
    }
```

- [ ] **Step 2.2: 编译 + 全量测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 17 个测试全部 PASS

---

### Task 3: renderer.html — onReady 握手 + mermaid defer

**Files:**
- Modify: `app/src/main/assets/templates/renderer.html`

- [ ] **Step 3.1: mermaid 改 defer 并加载完成后补渲染**

头部脚本行：

```html
<script src="file:///android_asset/raw/mermaidjs.js"></script>
```

改为：

```html
<script defer src="file:///android_asset/raw/mermaidjs.js" onload="window.__mermaidLoaded && window.__mermaidLoaded()"></script>
```

主 `<script>` 块内（`window.renderMarkdown = ...` 定义之前）加：

```js
// mermaid 延迟加载完成后，补渲染已存在的图表
window.__mermaidLoaded = function() {
    try {
        var contentEl = document.getElementById('content');
        if (contentEl && typeof mermaid !== 'undefined') {
            mermaid.run({nodes: contentEl.querySelectorAll('.language-mermaid')});
        }
    } catch(e) { console.log('mermaid late run: ' + e.message); }
};
```

- [ ] **Step 3.2: body 末尾加就绪握手**

`<div id="content">Loading...</div>` 之后、`</body>` 之前加：

```html
<script>
// 渲染管线就绪（marked/katex/prism 已同步加载；mermaid 延迟，渲染函数内有 typeof 守卫）
if (window.Android && Android.onReady) { Android.onReady(); }
</script>
```

---

### Task 4: EditorScreen 重构（WebView 单实例 + 就绪渲染 + Typora 编辑区）

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt`（整体重写）

- [ ] **Step 4.1: 整体替换 EditorScreen.kt**

```kotlin
package com.yumark.app.presentation.editor

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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
                                    viewModel.saveDocument()
                                    navController.navigateUp()
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

                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.menu_file))
                                }

                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.toolbar_image)) },
                                        onClick = { viewModel.onInsertImageClick(); showMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Image, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.export)) },
                                        onClick = { showMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Download, null) }
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
                                // 本地编辑状态，避免依赖异步的 document 状态
                                var editContent by remember { mutableStateOf(document?.content ?: "") }

                                LaunchedEffect(document?.id) {
                                    document?.content?.let { content ->
                                        if (editContent.isEmpty() && content.isNotEmpty()) {
                                            editContent = content
                                        }
                                    }
                                }

                                // 就绪即渲染；内容变化时重渲染（守卫避免重复渲染同一内容）
                                var lastRendered by remember { mutableStateOf<String?>(null) }
                                LaunchedEffect(isPreviewMode, rendererReady.value, editContent) {
                                    if (isPreviewMode && rendererReady.value &&
                                        editContent.isNotEmpty() && editContent != lastRendered
                                    ) {
                                        val encoded = android.util.Base64.encodeToString(
                                            editContent.toByteArray(Charsets.UTF_8),
                                            android.util.Base64.NO_WRAP
                                        )
                                        previewWebView.evaluateJavascript(renderJs(encoded), null)
                                        lastRendered = editContent
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
                                                viewModel.insertSyntax(syntax)
                                            },
                                            onInsertImage = { viewModel.onInsertImageClick() }
                                        )

                                        BasicTextField(
                                            value = editContent,
                                            onValueChange = { newValue ->
                                                editContent = newValue
                                                viewModel.onContentChanged(newValue)
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
                                                    if (editContent.isEmpty()) {
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
                                                    "${editContent.length} 字符",
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
                                    Button(onClick = { viewModel.saveDocument() }) {
                                        Text("Retry")
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
```

- [ ] **Step 4.2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

---

### Task 5: FileListViewModel 文件夹树缓存

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/filelist/FileListViewModel.kt`

- [ ] **Step 5.1: 树构建按 docs/folders 哈希缓存**

类内加字段（`_isWorkspaceLoading` 之后）：

```kotlin
    // 文件夹树缓存：仅 docs/folders 实际变化时重建（搜索/排序不再触发 DB 查询）
    private var cachedTreeKey: Pair<Int, Int>? = null
    private var cachedTree: List<FolderTreeNode>? = null
```

init 的 collect 块中，替换：

```kotlin
                val tree = getFolderTreeUseCase().getOrNull()
```

为：

```kotlin
                val treeKey = data.docs.hashCode() to data.folders.hashCode()
                if (treeKey != cachedTreeKey) {
                    cachedTree = getFolderTreeUseCase().getOrNull()
                    cachedTreeKey = treeKey
                }
                val tree = cachedTree
```

（`FolderTreeNode` 已由 `domain.model.*` 通配 import 覆盖。）

- [ ] **Step 5.2: 测试回归**

Run: `./gradlew :app:testDebugUnitTest --tests "com.yumark.app.presentation.filelist.FileListViewModelTest"`
Expected: 2 个测试 PASS

---

### Task 6: 视觉——卡片扁平化 + 列表动画 + 导航过渡

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/filelist/FileListScreen.kt`
- Modify: `app/src/main/java/com/yumark/app/presentation/navigation/YuMarkNavGraph.kt`

- [ ] **Step 6.1: 卡片扁平化**

`DocumentCard` 与 `SearchResultCard` 中的 `Card(` 改为 `OutlinedCard(`（各一处，参数不变）。

- [ ] **Step 6.2: 文档列表位移动画**

`FileListScreen` 函数注解改为：

```kotlin
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
```

文档列表 items 块改为（包一层带动画的 Box）：

```kotlin
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
```

- [ ] **Step 6.3: 导航过渡动画**

`YuMarkNavGraph.kt` import 区加：

```kotlin
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
```

`NavHost(...)` 调用加过渡参数：

```kotlin
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 24 }
        },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = {
            fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { it / 24 }
        }
    ) {
```

- [ ] **Step 6.4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

---

### Task 7: 完整构建与验收

- [ ] **Step 7.1: 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 17 个测试全部 PASS

- [ ] **Step 7.2: 完整构建**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7.3: 手动验收清单**

1. 打开文档（默认预览）：首次渲染明显快于之前（无固定 1 秒延迟）
2. 编辑 ↔ 预览来回切换：第二次起瞬时完成，无白屏/加载
3. 快速连续打字：输入流畅跟手
4. 编辑区：无边框通栏书写、底部状态条显示字符数
5. 搜索输入时不再有列表卡顿（树不重查）
6. 文件列表卡片为描边扁平风格；删除/排序时项目平滑移动
7. 进出编辑器/设置有淡入滑动过渡
8. 深色模式进预览不闪白

## Self-Review 结果

- **Spec 覆盖**：单实例+握手+defer（Task 3/4）、输入直通（Task 2）、树缓存（Task 5）、死代码（Task 1）、视觉 4 项（Task 4 编辑区 + Task 6）——10 项全覆盖
- **占位符**：无
- **类型一致性**：`rendererReady: MutableState<Boolean>`、`renderJs/darkStyleJs` 仅 Task 4 内定义使用；`animateItemPlacement` 需 ExperimentalFoundationApi 已注明 ✓
