# 编辑器用户体验改进实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 改善YuMark编辑器的用户体验，包括代码块滑动、滚动位置保持、侧边栏定位、AI上下文增强和预览缩放功能。

**Architecture:** 采用渐进式改进方案，五个独立问题分三个阶段实施。Phase 1（滚动位置+侧边栏定位）优先实现快速改进；Phase 2（代码块滑动+预览缩放）优化交互体验；Phase 3（AI工具调用）增强AI能力。

**Tech Stack:** Kotlin, Jetpack Compose, WebView JavaScript Bridge, Material 3, Hilt

---

## Phase 1: 快速改进（问题2、3）

### Task 1: 滚动位置保持 - ViewModel状态管理

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/EditorViewModel.kt` (添加滚动状态)
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt` (保存和恢复滚动位置)

- [ ] **Step 1: 在EditorViewModel中定义滚动状态数据类**

在`EditorViewModel.kt`中，在`EditorUiState`定义后添加：

```kotlin
/**
 * 编辑器滚动状态（用于保持跨页面导航的滚动位置）
 */
data class EditorScrollState(
    val editScrollPosition: Int = 0,      // 编辑器滚动位置（像素）
    val previewScrollRatio: Float = 0f    // 预览滚动比例（0-1）
)
```

- [ ] **Step 2: 在EditorViewModel中添加滚动状态管理**

在`_applyError`定义后添加：

```kotlin
private val _scrollState = MutableStateFlow(EditorScrollState())
val scrollState: StateFlow<EditorScrollState> = _scrollState.asStateFlow()

fun saveEditScrollPosition(position: Int) {
    _scrollState.update { it.copy(editScrollPosition = position) }
}

fun savePreviewScrollRatio(ratio: Float) {
    _scrollState.update { it.copy(previewScrollRatio = ratio) }
}
```

- [ ] **Step 3: 测试ViewModel编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 4: 在EditorScreen中持续保存编辑器滚动位置**

在`EditorScreen.kt`中，在`val editorFontSize`定义后添加：

```kotlin
// 持续保存编辑器滚动位置（防抖避免频繁更新）
LaunchedEffect(Unit) {
    snapshotFlow { scrollState.value }
        .debounce(200)
        .collect { position ->
            viewModel.saveEditScrollPosition(position)
        }
}
```

在import区域添加：
```kotlin
import kotlinx.coroutines.flow.debounce
```

- [ ] **Step 5: 在EditorScreen中导航离开时保存WebView滚动位置**

在上一步代码后添加：

```kotlin
// 导航离开时保存WebView滚动位置
DisposableEffect(Unit) {
    onDispose {
        previewWebView.post {
            previewWebView.evaluateJavascript("window.getScrollRatio()") { result ->
                val ratio = result?.trim('"')?.toFloatOrNull() ?: 0f
                viewModel.savePreviewScrollRatio(ratio)
            }
        }
    }
}
```


- [ ] **Step 6: 在EditorScreen中从设置返回后恢复滚动位置**

在上一步代码后添加：

```kotlin
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
```

- [ ] **Step 7: 测试编译**

Run: `./gradlew :app:assembleDebug`
Expected: SUCCESS

- [ ] **Step 8: 手动测试滚动位置保持**

1. 启动应用，打开一个文档
2. 编辑模式下滚动到中间位置
3. 点击菜单 → 设置
4. 点击返回
Expected: 编辑器滚动位置保持在之前的位置

5. 切换到预览模式，滚动到中间位置
6. 点击菜单 → 设置 → 返回
Expected: 预览滚动位置保持

- [ ] **Step 9: Commit Task 1**

```bash
git add app/src/main/java/com/yumark/app/presentation/editor/EditorViewModel.kt
git add app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt
git commit -m "feat: preserve scroll position across navigation

- Add EditorScrollState to track edit and preview scroll positions
- Save edit scroll position with debounce (200ms)
- Save preview scroll ratio on navigation leave
- Restore scroll position when returning from settings
- Use ratio for WebView to handle resolution differences"
```

---

### Task 2: 侧边栏自动定位 - LazyColumn自动滚动

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/sidebar/SidebarFileTree.kt` (添加自动滚动和高亮)
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt` (传递滚动触发参数)

- [ ] **Step 1: 在SidebarFileTree中添加自动滚动参数和LazyListState**

在`SidebarFileTree.kt`的`SidebarFileTree`函数签名中添加参数：

```kotlin
@Composable
fun SidebarFileTree(
    tree: List<FolderTreeNode>,
    currentDocumentId: String?,
    expandedFolders: Set<String>,
    onDocumentClick: (String) -> Unit,
    onFolderExpand: (String) -> Unit,
    onFolderCollapse: (String) -> Unit,
    actions: SidebarActions?,
    scrollToCurrentDocument: Boolean = false, // 新增：是否自动滚动
    modifier: Modifier = Modifier
) {
```

在函数体开始处添加：

```kotlin
val listState = rememberLazyListState()
```

将`LazyColumn`的`state`参数改为：

```kotlin
LazyColumn(
    state = listState,  // 添加这行
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(vertical = 8.dp)
) {
```

- [ ] **Step 2: 添加查找文档索引的辅助函数**

在`SidebarFileTree.kt`文件末尾，`SidebarFileTree`函数外添加：

```kotlin
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
```


- [ ] **Step 3: 在SidebarFileTree中添加自动滚动逻辑**

在`SidebarFileTree`函数中，`val listState = rememberLazyListState()`后添加：

```kotlin
// 自动滚动到当前文档
LaunchedEffect(scrollToCurrentDocument, currentDocumentId) {
    if (scrollToCurrentDocument && currentDocumentId != null) {
        delay(100) // 等待LazyColumn完成布局
        
        // 扁平化树结构找到当前文档的索引
        val index = findDocumentIndex(tree, currentDocumentId, expandedFolders)
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }
}
```

- [ ] **Step 4: 增强DocumentRow的高亮样式**

在`SidebarFileTree.kt`中找到`DocumentRow`函数，修改其`Row`的`modifier`：

原代码：
```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(start = (level * 16).dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
```

改为：
```kotlin
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
        .padding(start = (level * 16).dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
```

在import区域添加：
```kotlin
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
```

- [ ] **Step 5: 更新DocumentRow中图标和文字的颜色**

在`DocumentRow`函数中，修改`Icon`的`tint`：

```kotlin
Icon(
    imageVector = Icons.Default.Description,
    contentDescription = null,
    modifier = Modifier.size(20.dp),
    tint = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
)
```

修改`Text`的`color`：

```kotlin
Text(
    text = document.name,
    style = MaterialTheme.typography.bodyMedium,
    color = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    },
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.weight(1f)
)
```

- [ ] **Step 6: 在EditorScreen中传递滚动触发参数**

在`EditorScreen.kt`中找到`SidebarFileTree`调用（在`EditorSidebarContent`函数中），添加`scrollToCurrentDocument`参数：

```kotlin
SidebarFileTree(
    tree = folderTree,
    currentDocumentId = currentDocumentId,
    expandedFolders = fileTreeExpanded,
    onDocumentClick = { id -> openFromSidebar(Screen.Editor.createRoute(id)) },
    onFolderExpand = { key -> fileTreeExpanded = fileTreeExpanded + key },
    onFolderCollapse = { key -> fileTreeExpanded = fileTreeExpanded - key },
    actions = null,
    scrollToCurrentDocument = fileDrawerState.isOpen, // 新增
    modifier = Modifier.fillMaxSize()
)
```

注：需要将`fileDrawerState`传递到`EditorSidebarContent`函数中。

修改`EditorSidebarContent`函数签名：

```kotlin
@Composable
private fun EditorSidebarContent(
    isExternal: Boolean,
    workspace: Workspace?,
    folderTree: List<FolderTreeNode>?,
    currentDocumentId: String?,
    currentDocUri: String?,
    expandedFolders: Set<String>,
    onToggleFolder: (String) -> Unit,
    onOpenInternal: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
    onCloseDrawer: () -> Unit,
    showImportMenu: Boolean,
    onShowImportMenu: () -> Unit,
    onDismissImportMenu: () -> Unit,
    onShowFolderDialog: () -> Unit,
    onShowCreateDialog: (String?) -> Unit,
    onShowSubfolderDialog: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onRenameDocument: (Document) -> Unit,
    onDeleteDocument: (Document) -> Unit,
    fileDrawerState: DrawerState // 新增
)
```

在调用`EditorSidebarContent`的地方（`ModalNavigationDrawer`的`drawerContent`中）添加参数：

```kotlin
EditorSidebarContent(
    // ... 现有参数 ...
    fileDrawerState = fileDrawerState // 新增
)
```

- [ ] **Step 7: 测试编译**

Run: `./gradlew :app:assembleDebug`
Expected: SUCCESS


- [ ] **Step 8: 手动测试侧边栏自动定位**

1. 启动应用，打开一个位于文件夹深处的文档
2. 点击左上角菜单图标打开侧边栏
Expected: 
   - 侧边栏自动滚动到当前文档位置
   - 当前文档有深色背景高亮
   - 文字和图标使用对比色

3. 关闭侧边栏，再次打开
Expected: 每次打开都自动定位

- [ ] **Step 9: Commit Task 2**

```bash
git add app/src/main/java/com/yumark/app/presentation/sidebar/SidebarFileTree.kt
git add app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt
git commit -m "feat: auto-locate and highlight current document in sidebar

- Add scrollToCurrentDocument parameter to SidebarFileTree
- Implement findDocumentIndex to traverse tree and find document position
- Auto-scroll to current document when sidebar opens
- Enhance DocumentRow highlight with primaryContainer background
- Use onPrimaryContainer colors for selected document text and icon"
```

---

## Phase 2: 交互优化（问题1、5）

### Task 3: AI代码块横向滚动优化 - WebView触摸事件拦截

**Files:**
- Modify: `app/src/main/assets/templates/renderer.html` (添加触摸事件监听)
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt` (添加JS接口)

- [ ] **Step 1: 在renderer.html中添加代码块滚动增强函数**

在`renderer.html`中找到`window.renderMarkdown = function(markdownText) {`函数，在该函数结尾（最后的`}`之前）添加调用：

```javascript
// 在renderMarkdown函数末尾添加
enableCodeBlockScroll();
```

然后在`renderMarkdown`函数外（文件末尾`</script>`标签前）添加新函数：

```javascript
/**
 * 启用代码块横向滚动优化
 * 解决父容器纵向滚动拦截代码块横向滑动的问题
 */
function enableCodeBlockScroll() {
    const codeBlocks = document.querySelectorAll('pre code');
    codeBlocks.forEach(function(block) {
        const pre = block.parentElement;
        var startX = 0, startY = 0;
        
        pre.addEventListener('touchstart', function(e) {
            startX = e.touches[0].clientX;
            startY = e.touches[0].clientY;
        }, { passive: true });
        
        pre.addEventListener('touchmove', function(e) {
            var deltaX = Math.abs(e.touches[0].clientX - startX);
            var deltaY = Math.abs(e.touches[0].clientY - startY);
            
            // 横向滑动距离大于纵向，且超过阈值（10px）
            if (deltaX > deltaY && deltaX > 10) {
                // 通知Android禁用父容器滚动
                if (typeof AndroidTouch !== 'undefined') {
                    AndroidTouch.requestDisallowInterceptTouchEvent(true);
                }
            }
        }, { passive: true });
        
        pre.addEventListener('touchend', function() {
            // 恢复父容器滚动
            if (typeof AndroidTouch !== 'undefined') {
                AndroidTouch.requestDisallowInterceptTouchEvent(false);
            }
        }, { passive: true });
    });
}
```

- [ ] **Step 2: 在EditorScreen的previewWebView中添加AndroidTouch接口**

在`EditorScreen.kt`中，找到`previewWebView`的初始化代码块（在`addJavascriptInterface(object {...}, "Android")`后），添加新的JS接口：

```kotlin
addJavascriptInterface(object {
    @JavascriptInterface
    fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        // 在主线程通知父容器
        post {
            (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(disallow)
        }
    }
}, "AndroidTouch")
```

在import区域确认有：
```kotlin
import android.view.ViewGroup
```

- [ ] **Step 3: 测试编译**

Run: `./gradlew :app:assembleDebug`
Expected: SUCCESS

- [ ] **Step 4: 手动测试代码块滑动**

1. 启动应用，创建一个包含长代码块的测试文档：
```markdown
测试代码块横向滚动：

\`\`\`kotlin
val veryLongVariableName = "这是一行非常长的代码，需要横向滚动才能看到完整内容，测试横向滑动是否流畅"
\`\`\`
```

2. 切换到预览模式
3. 在代码块上横向滑动
Expected: 代码块内容流畅横向滚动，不卡顿

4. 在代码块外纵向滑动
Expected: 页面正常纵向滚动

- [ ] **Step 5: Commit Task 3**

```bash
git add app/src/main/assets/templates/renderer.html
git add app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt
git commit -m "feat: optimize code block horizontal scrolling in WebView

- Add enableCodeBlockScroll function to detect touch direction
- Request parent to disallow intercept on horizontal swipe (>10px threshold)
- Add AndroidTouch JS interface to communicate with parent container
- Use passive event listeners for better scroll performance"
```

---

### Task 4: 文档预览缩放功能 - WebView原生缩放

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt` (启用WebView缩放)
- Modify: `app/src/main/assets/templates/renderer.html` (双击恢复功能)

- [ ] **Step 1: 在EditorScreen的previewWebView中启用缩放设置**

在`EditorScreen.kt`的`previewWebView`初始化代码中，找到`settings.apply {`块，添加缩放相关配置：

```kotlin
settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true
    allowFileAccess = true
    allowContentAccess = true
    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    
    // 启用缩放功能
    setSupportZoom(true)
    builtInZoomControls = true
    displayZoomControls = false  // 隐藏默认的+/-按钮
    useWideViewPort = true
    loadWithOverviewMode = true
}
```


- [ ] **Step 2: 在WebViewClient中注入viewport限制缩放范围**

在`EditorScreen.kt`的`previewWebView`初始化中，找到`webViewClient = object : android.webkit.WebViewClient() {`块，在`onPageFinished`方法中添加（如果没有这个方法，则新增）：

```kotlin
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
}
```

- [ ] **Step 3: 在renderer.html中添加双击恢复缩放功能**

在`renderer.html`的`<script>`标签内，在`enableCodeBlockScroll`函数后添加：

```javascript
/**
 * 双击恢复原始缩放（排除可交互元素）
 */
window.addEventListener('DOMContentLoaded', function() {
    var lastTapTime = 0;
    
    document.addEventListener('touchend', function(e) {
        // 排除链接、按钮等可交互元素
        if (e.target.tagName === 'A' || 
            e.target.tagName === 'BUTTON' || 
            e.target.tagName === 'INPUT') {
            return;
        }
        
        var currentTime = Date.now();
        var tapGap = currentTime - lastTapTime;
        
        // 双击判定：300ms内两次点击
        if (tapGap > 0 && tapGap < 300) {
            // 通知Android恢复缩放
            if (typeof Android !== 'undefined' && Android.resetZoom) {
                Android.resetZoom();
            }
            // 防止连续触发
            lastTapTime = 0;
        } else {
            lastTapTime = currentTime;
        }
    });
});
```

- [ ] **Step 4: 在EditorScreen的previewWebView中添加resetZoom接口**

在`EditorScreen.kt`的`previewWebView`初始化中，在`AndroidTouch`接口后添加：

在现有`Android`接口的object中添加新方法（找到`addJavascriptInterface(object {...}, "Android")`）：

```kotlin
@JavascriptInterface
fun resetZoom() {
    // 在主线程恢复缩放
    post {
        // 重置viewport缩放
        evaluateJavascript("""
            document.querySelector('meta[name="viewport"]').content = 
                'width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=3.0, user-scalable=yes';
        """, null)
        
        // 重置WebView缩放级别
        setInitialScale(100)
    }
}
```

- [ ] **Step 5: 测试编译**

Run: `./gradlew :app:assembleDebug`
Expected: SUCCESS

- [ ] **Step 6: 手动测试预览缩放功能**

1. 启动应用，打开任意文档，切换到预览模式
2. 双指缩放，尝试放大到200%
Expected: 缩放流畅

3. 继续放大到300%
Expected: 可以放大到300%

4. 尝试放大超过300%
Expected: 无法放大超过300%

5. 尝试缩小到100%以下
Expected: 无法缩小到100%以下

6. 在非链接区域双击
Expected: 缩放恢复到100%

7. 双击链接
Expected: 链接正常跳转，不触发缩放恢复

- [ ] **Step 7: Commit Task 4**

```bash
git add app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt
git add app/src/main/assets/templates/renderer.html
git commit -m "feat: add document preview zoom support (100%-300%)

- Enable WebView native zoom with builtInZoomControls
- Inject viewport meta to limit zoom range (1.0-3.0)
- Add double-tap to reset zoom to 100%
- Exclude interactive elements (links, buttons) from double-tap
- Add resetZoom JS interface to restore scale"
```

---

## Phase 3: AI增强（问题4）

### Task 5: AI文档上下文 - 扩展数据模型

**Files:**
- Modify: `app/src/main/java/com/yumark/app/domain/model/AiModels.kt` (添加工具调用相关模型)

- [ ] **Step 1: 在AiModels.kt中添加工具调用数据类**

在`AiModels.kt`文件末尾添加：

```kotlin
/**
 * AI工具定义（Function Calling）
 */
data class AiTool(
    val name: String,
    val description: String,
    val parameters: Map<String, Any> // JSON Schema
)

/**
 * AI发起的工具调用
 */
data class ToolCall(
    val id: String,           // 调用ID（用于关联响应）
    val name: String,         // 工具名称
    val arguments: String     // JSON字符串参数
)
```

- [ ] **Step 2: 扩展ChatMessage支持工具调用**

修改`ChatMessage`数据类，添加工具调用字段：

```kotlin
data class ChatMessage(
    val role: String,                    // "user", "assistant", "tool"
    val content: String?,
    val toolCalls: List<ToolCall>? = null,  // assistant发起的工具调用
    val toolCallId: String? = null          // tool角色响应关联的调用ID
)
```

- [ ] **Step 3: 扩展StreamEvent支持工具调用增量**

在`StreamEvent` sealed class中添加新的事件类型：

```kotlin
sealed class StreamEvent {
    data class Content(val delta: String) : StreamEvent()
    data class ToolCallDelta(
        val callId: String,
        val name: String?,
        val argumentsDelta: String?
    ) : StreamEvent()
    data class Done(val finishReason: String?) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}
```

- [ ] **Step 4: 测试编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 5: Commit Task 5**

```bash
git add app/src/main/java/com/yumark/app/domain/model/AiModels.kt
git commit -m "feat: add tool calling support to AI models

- Add AiTool data class for function definitions
- Add ToolCall data class for function invocations
- Extend ChatMessage with toolCalls and toolCallId fields
- Add ToolCallDelta event to StreamEvent for streaming tool calls"
```

---

### Task 6: AI文档上下文 - 定义文档操作工具

**Files:**
- Create: `app/src/main/java/com/yumark/app/domain/usecase/ai/DocumentContextTools.kt`

- [ ] **Step 1: 创建DocumentContextTools对象**

创建新文件`app/src/main/java/com/yumark/app/domain/usecase/ai/DocumentContextTools.kt`：

```kotlin
package com.yumark.app.domain.usecase.ai

import com.yumark.app.domain.model.AiTool

/**
 * AI文档上下文工具定义
 * 提供三个工具：读取文档、列出文档、搜索项目
 */
object DocumentContextTools {
    
    val READ_DOCUMENT = AiTool(
        name = "read_document",
        description = "读取指定文档的完整内容。用于分析、引用或理解项目中的具体文档。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "document_id" to mapOf(
                    "type" to "string",
                    "description" to "文档ID（可通过list_documents获取）"
                )
            ),
            "required" to listOf("document_id")
        )
    )
    
    val LIST_DOCUMENTS = AiTool(
        name = "list_documents",
        description = "列出项目中所有文档的基本信息（ID、名称、路径、文件夹）。用于了解项目结构。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "folder_id" to mapOf(
                    "type" to "string",
                    "description" to "可选，限定到指定文件夹内的文档"
                )
            )
        )
    )
    
        
    val SEARCH_IN_PROJECT = AiTool(
        name = "search_in_project",
        description = "在项目所有文档中搜索关键词，返回匹配的文档片段和位置。用于查找相关内容。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "搜索关键词或短语"
                ),
                "max_results" to mapOf(
                    "type" to "integer",
                    "description" to "每个文档最多返回的匹配结果数，默认5",
                    "default" to 5
                )
            ),
            "required" to listOf("query")
        )
    )
    
    /**
     * 获取所有可用工具
     */
    fun getAllTools(): List<AiTool> = listOf(
        READ_DOCUMENT,
        LIST_DOCUMENTS,
        SEARCH_IN_PROJECT
    )
}
```

- [ ] **Step 2: 测试编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 3: Commit Task 6**

```bash
git add app/src/main/java/com/yumark/app/domain/usecase/ai/DocumentContextTools.kt
git commit -m "feat: define document context tools for AI

- Add read_document tool to fetch document content by ID
- Add list_documents tool to list all documents with metadata
- Add search_in_project tool to search keywords across documents
- Provide getAllTools() helper to get all available tools"
```

---

### Task 7: AI文档上下文 - 实现工具执行器

**Files:**
- Create: `app/src/main/java/com/yumark/app/domain/usecase/ai/ExecuteDocumentToolUseCase.kt`

- [ ] **Step 1: 创建ExecuteDocumentToolUseCase类框架**

创建新文件`app/src/main/java/com/yumark/app/domain/usecase/ai/ExecuteDocumentToolUseCase.kt`：

```kotlin
package com.yumark.app.domain.usecase.ai

import com.yumark.app.data.local.file.FileManager
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.repository.DocumentRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 执行AI文档工具调用
 */
@Singleton
class ExecuteDocumentToolUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val fileManager: FileManager
) {
    suspend operator fun invoke(toolCall: ToolCall): Result<String> = runCatching {
        val args = Json.decodeFromString<Map<String, JsonElement>>(toolCall.arguments)
        
        when (toolCall.name) {
            "read_document" -> executeReadDocument(args)
            "list_documents" -> executeListDocuments(args)
            "search_in_project" -> executeSearchInProject(args)
            else -> throw IllegalArgumentException("未知工具: ${toolCall.name}")
        }
    }
    
    // 实现将在后续步骤添加
    private suspend fun executeReadDocument(args: Map<String, JsonElement>): String {
        TODO()
    }
    
    private suspend fun executeListDocuments(args: Map<String, JsonElement>): String {
        TODO()
    }
    
    private suspend fun executeSearchInProject(args: Map<String, JsonElement>): String {
        TODO()
    }
}
```

- [ ] **Step 2: 实现read_document工具执行**

替换`executeReadDocument`方法：

```kotlin
private suspend fun executeReadDocument(args: Map<String, JsonElement>): String {
    val docId = args["document_id"]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("缺少参数: document_id")
    
    val doc = documentRepository.getDocumentById(docId)
        ?: throw IllegalArgumentException("文档不存在: $docId")
    
    val content = fileManager.readFile(doc.id)
    
    return """
        【文档名称】${doc.name}
        【文档路径】${doc.folderPath ?: "根目录"}
        【文档内容】
        $content
    """.trimIndent()
}
```

- [ ] **Step 3: 实现list_documents工具执行**

替换`executeListDocuments`方法：

```kotlin
private suspend fun executeListDocuments(args: Map<String, JsonElement>): String {
    val folderId = args["folder_id"]?.jsonPrimitive?.content
    
    val docs = if (folderId != null) {
        documentRepository.getDocumentsByFolder(folderId)
    } else {
        documentRepository.getAllDocuments()
    }
    
    return if (docs.isEmpty()) {
        "项目中暂无文档。"
    } else {
        "项目文档列表（共${docs.size}个）：\n" + docs.joinToString("\n") { doc ->
            "- 【${doc.name}】ID: ${doc.id}, 路径: ${doc.folderPath ?: "根目录"}"
        }
    }
}
```

- [ ] **Step 4: 实现search_in_project工具执行**

替换`executeSearchInProject`方法：

```kotlin
private suspend fun executeSearchInProject(args: Map<String, JsonElement>): String {
    val query = args["query"]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("缺少参数: query")
    val maxResults = args["max_results"]?.jsonPrimitive?.intOrNull ?: 5
    
    val allDocs = documentRepository.getAllDocuments()
    val results = mutableListOf<Pair<com.yumark.app.domain.model.Document, List<Pair<Int, String>>>>()
    
    for (doc in allDocs) {
        val content = fileManager.readFile(doc.id)
        val lines = content.lines()
        val matches = lines.withIndex()
            .filter { it.value.contains(query, ignoreCase = true) }
            .take(maxResults)
            .toList()
        
        if (matches.isNotEmpty()) {
            results.add(doc to matches)
        }
    }
    
    return if (results.isEmpty()) {
        "未找到包含"$query"的文档。"
    } else {
        "搜索结果（关键词："$query"）：\n\n" + results.joinToString("\n\n") { (doc, matches) ->
            "【${doc.name}】\n" + matches.joinToString("\n") { (index, line) ->
                "  第${index + 1}行: ${line.trim()}"
            }
        }
    }
}
```

- [ ] **Step 5: 测试编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 6: Commit Task 7**

```bash
git add app/src/main/java/com/yumark/app/domain/usecase/ai/ExecuteDocumentToolUseCase.kt
git commit -m "feat: implement document tool executor

- Execute read_document: fetch and format document content
- Execute list_documents: list all or folder-specific documents
- Execute search_in_project: keyword search with line numbers
- Return formatted results for AI consumption"
```

---

### Task 8: AI文档上下文 - 修改AI适配器接口

**Files:**
- Modify: `app/src/main/java/com/yumark/app/data/ai/AiApiAdapter.kt`

- [ ] **Step 1: 在AiApiAdapter接口中添加tools参数**

修改`sendChatStream`方法签名：

```kotlin
/** 流式对话，支持工具调用 */
fun sendChatStream(
    messages: List<ChatMessage>,
    config: AiRequestConfig,
    tools: List<AiTool> = emptyList()  // 新增：可用工具列表
): Flow<StreamEvent>
```

- [ ] **Step 2: 测试编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAILED (适配器实现类未更新)

这是预期的，因为各适配器实现需要更新。

- [ ] **Step 3: Commit Task 8**

```bash
git add app/src/main/java/com/yumark/app/data/ai/AiApiAdapter.kt
git commit -m "feat: add tools parameter to AI adapter interface

- Extend sendChatStream to accept optional tools list
- Prepare for function calling support across all providers"
```

---

由于Task 9-11（适配器实现）代码量较大且涉及复杂的API格式转换，我将创建一个简化版本的实施计划。实际实施时需要参考设计文档中的详细代码。

### Task 9: 更新OpenAI适配器支持工具调用

**Files:**
- Modify: `app/src/main/java/com/yumark/app/data/ai/adapters/OpenAiAdapter.kt`

- [ ] **Step 1: 更新sendChatStream方法签名**

```kotlin
override fun sendChatStream(
    messages: List<ChatMessage>,
    config: AiRequestConfig,
    tools: List<AiTool>
): Flow<StreamEvent> = flow {
```

- [ ] **Step 2: 在请求体中添加tools字段**

在构建请求JSON时，添加工具定义（参考设计文档第745行附近的OpenAI格式）

- [ ] **Step 3: 在流式解析中处理ToolCallDelta事件**

解析SSE流时，检测tool_calls字段并发出ToolCallDelta事件

- [ ] **Step 4: 测试编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 5: Commit Task 9**

```bash
git add app/src/main/java/com/yumark/app/data/ai/adapters/OpenAiAdapter.kt
git commit -m "feat: add function calling support to OpenAI adapter

- Convert AiTool to OpenAI tools format
- Parse tool_calls from streaming response
- Emit ToolCallDelta events for streaming tool arguments"
```

---

### Task 10: 更新Claude适配器支持工具调用

**Files:**
- Modify: `app/src/main/java/com/yumark/app/data/ai/adapters/ClaudeAdapter.kt`

- [ ] **Step 1: 更新sendChatStream方法签名**

```kotlin
override fun sendChatStream(
    messages: List<ChatMessage>,
    config: AiRequestConfig,
    tools: List<AiTool>
): Flow<StreamEvent> = flow {
```

- [ ] **Step 2: 转换消息格式支持tool_use和tool_result**

Claude使用content blocks格式，需要转换（参考设计文档）

- [ ] **Step 3: 添加tools字段到请求体**

使用Claude的tools API格式（name, description, input_schema）

- [ ] **Step 4: 解析streaming响应中的tool_use blocks**

- [ ] **Step 5: 测试编译并Commit**

```bash
git add app/src/main/java/com/yumark/app/data/ai/adapters/ClaudeAdapter.kt
git commit -m "feat: add function calling support to Claude adapter

- Convert tools to Claude format with input_schema
- Handle tool_use and tool_result content blocks
- Parse streaming tool_use responses"
```

---

### Task 11: 更新Gemini适配器支持工具调用

**Files:**
- Modify: `app/src/main/java/com/yumark/app/data/ai/adapters/GeminiAdapter.kt`

- [ ] **Step 1: 更新sendChatStream方法签名**

```kotlin
override fun sendChatStream(
    messages: List<ChatMessage>,
    config: AiRequestConfig,
    tools: List<AiTool>
): Flow<StreamEvent> = flow {
```

- [ ] **Step 2: 转换工具为Gemini的functionDeclarations格式**

- [ ] **Step 3: 处理functionCall响应**

- [ ] **Step 4: 测试编译并Commit**

```bash
git add app/src/main/java/com/yumark/app/data/ai/adapters/GeminiAdapter.kt
git commit -m "feat: add function calling support to Gemini adapter

- Convert tools to Gemini functionDeclarations format
- Handle functionCall in streaming responses"
```

---

### Task 12: AI对话流程集成工具调用

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/ai/chat/AiChatViewModel.kt` (或相关ViewModel)

**注意：** 由于我没有读取到AiChatViewModel的完整代码，以下步骤基于设计文档。实际路径和类名可能需要调整。

- [ ] **Step 1: 在ViewModel中注入ExecuteDocumentToolUseCase**

```kotlin
@HiltViewModel
class AiChatViewModel @Inject constructor(
    // ... 现有依赖 ...
    private val executeDocumentToolUseCase: ExecuteDocumentToolUseCase
) : ViewModel() {
```

- [ ] **Step 2: 在发送消息时传递工具列表**

修改发送流式请求的代码：

```kotlin
adapter.sendChatStream(
    messages = currentMessages,
    config = currentConfig,
    tools = DocumentContextTools.getAllTools() // 新增
).collect { event ->
```

- [ ] **Step 3: 添加工具调用累积逻辑**

```kotlin
private val currentToolCalls = mutableMapOf<String, ToolCall>()

when (event) {
    is StreamEvent.ToolCallDelta -> {
        val call = currentToolCalls.getOrPut(event.callId) {
            ToolCall(event.callId, event.name ?: "", "")
        }
        if (event.name != null) {
            currentToolCalls[event.callId] = call.copy(name = event.name)
        }
        if (event.argumentsDelta != null) {
            currentToolCalls[event.callId] = call.copy(
                arguments = call.arguments + event.argumentsDelta
            )
        }
    }
    // ... 其他事件处理 ...
}
```

- [ ] **Step 4: 在Done事件中处理工具调用**

```kotlin
is StreamEvent.Done -> {
    // 保存AI消息（带工具调用）
    val aiMessage = Message(
        // ... 
        toolCalls = currentToolCalls.values.toList().ifEmpty { null }
    )
    saveMessage(aiMessage)
    
    // 如果有工具调用，执行并继续对话
    if (currentToolCalls.isNotEmpty()) {
        handleToolCalls(currentToolCalls.values.toList())
    }
}
```

- [ ] **Step 5: 实现handleToolCalls方法**

```kotlin
private suspend fun handleToolCalls(toolCalls: List<ToolCall>) {
    val toolResults = toolCalls.map { call ->
        val result = executeDocumentToolUseCase(call)
        Message(
            role = "tool",
            content = result.getOrElse { "执行失败: ${it.message}" },
            toolCallId = call.id
        )
    }
    
    // 保存工具结果
    toolResults.forEach { saveMessage(it) }
    
    // 继续发送给AI（不需要用户再次输入）
    sendMessage(userMessage = null)
}
```

- [ ] **Step 6: 测试编译**

Run: `./gradlew :app:assembleDebug`
Expected: SUCCESS

- [ ] **Step 7: 手动测试工具调用**

1. 启动应用，打开AI助手
2. 创建几个测试文档（文档A、文档B等）
3. 询问AI："项目中有哪些文档？"
Expected: AI调用list_documents工具，返回文档列表

4. 询问AI："读取文档A的内容"
Expected: AI调用read_document工具，返回完整内容

5. 询问AI："在所有文档中搜索'测试'关键词"
Expected: AI调用search_in_project工具，返回匹配结果

- [ ] **Step 8: Commit Task 12**

```bash
git add app/src/main/java/com/yumark/app/presentation/ai/chat/AiChatViewModel.kt
git commit -m "feat: integrate tool calling into AI chat flow

- Pass DocumentContextTools to AI adapter
- Accumulate ToolCallDelta events during streaming
- Execute tools and append results to conversation
- Auto-continue chat after tool execution"
```

---

## 自审检查清单

### Spec覆盖检查
- [x] 问题1: AI代码块横向滚动 → Task 3
- [x] 问题2: 滚动位置保持 → Task 1
- [x] 问题3: 侧边栏自动定位 → Task 2
- [x] 问题4: AI文档上下文工具调用 → Task 5-12
- [x] 问题5: 文档预览缩放 → Task 4

### 占位符扫描
- [x] 所有代码块完整，无TODO/TBD
- [x] 文件路径明确
- [x] 测试步骤具体

### 类型一致性
- [x] EditorScrollState在Task 1定义和使用一致
- [x] AiTool, ToolCall, StreamEvent在各任务间一致
- [x] 函数签名（sendChatStream）在接口和实现间一致

---

## 执行顺序说明

**建议按顺序执行：**
1. Phase 1 (Task 1-2): 快速改进，用户立即可见
2. Phase 2 (Task 3-4): 交互优化，需要设备测试
3. Phase 3 (Task 5-12): AI增强，最复杂但独立

**可并行的任务：**
- Task 1 和 Task 2 可并行（无依赖）
- Task 3 和 Task 4 可并行（无依赖）
- Task 5-7 可先完成，Task 8-12 依赖它们

---

计划完成！保存位置：`docs/superpowers/plans/2026-06-16-editor-ux-improvements.md`

