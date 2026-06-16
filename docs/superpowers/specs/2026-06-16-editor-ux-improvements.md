---
title: 编辑器用户体验改进设计
date: 2026-06-16
status: draft
---

# 编辑器用户体验改进设计

## 概述

本设计涵盖五个编辑器体验改进：

1. **AI代码块横向滚动优化** - 解决代码块滑动不灵敏、卡顿问题
2. **滚动位置保持** - 从设置返回后保持编辑器和预览的滚动位置
3. **侧边栏自动定位** - 打开侧边栏时自动滚动并高亮当前文档
4. **AI文档上下文工具调用** - AI可以主动读取项目文档内容
5. **文档预览缩放** - 支持双指缩放，范围100%-300%

## 技术方案选择

采用**渐进式改进方案**（方案A）：
- 每个问题独立实现，降低风险
- 可分批交付，快速响应用户反馈
- 使用成熟技术模式，无需引入新依赖

## 问题1：AI代码块横向滚动优化

### 现状
- WebView内的代码块使用Prism.js渲染，有横向滚动
- 用户反馈：横向滑动响应不灵敏且卡顿
- 原因：WebView父容器的纵向滚动拦截了代码块的横向滑动手势

### 解决方案

**JavaScript触摸事件拦截 + Android触摸协作**

在`renderer.html`中注入触摸事件监听：

```javascript
function enableCodeBlockScroll() {
    const codeBlocks = document.querySelectorAll('pre code');
    codeBlocks.forEach(block => {
        const pre = block.parentElement;
        let startX = 0, startY = 0;
        
        pre.addEventListener('touchstart', (e) => {
            startX = e.touches[0].clientX;
            startY = e.touches[0].clientY;
        }, { passive: true });
        
        pre.addEventListener('touchmove', (e) => {
            const deltaX = Math.abs(e.touches[0].clientX - startX);
            const deltaY = Math.abs(e.touches[0].clientY - startY);
            
            // 横向滑动距离大于纵向，且超过阈值（10px）
            if (deltaX > deltaY && deltaX > 10) {
                // 通知Android禁用父容器滚动
                if (typeof AndroidTouch !== 'undefined') {
                    AndroidTouch.requestDisallowInterceptTouchEvent(true);
                }
            }
        }, { passive: true });
        
        pre.addEventListener('touchend', () => {
            // 恢复父容器滚动
            if (typeof AndroidTouch !== 'undefined') {
                AndroidTouch.requestDisallowInterceptTouchEvent(false);
            }
        }, { passive: true });
    });
}

// 在renderMarkdown完成后调用
window.renderMarkdown = function(markdownText) {
    // ... 现有渲染逻辑 ...
    enableCodeBlockScroll();
};
```


在`EditorScreen.kt`中添加JS接口：

```kotlin
// 在previewWebView初始化时添加
previewWebView.addJavascriptInterface(object {
    @JavascriptInterface
    fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        // 在主线程通知父容器
        post {
            (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(disallow)
        }
    }
}, "AndroidTouch")
```

**适用范围：**
- 编辑器预览模式的WebView
- AI助手对话中的WebView（AiQuickDialog、AiChatSheet）

### 实现细节
- 横向滑动阈值：10px（避免误触发）
- 使用`{ passive: true }`优化滚动性能
- 每次`renderMarkdown`后重新绑定事件（因为DOM重建）
- AI消息WebView也需要相同处理

---

## 问题2：滚动位置保持

### 现状
- 编辑器有编辑模式（BasicTextField + ScrollState）和预览模式（WebView）
- 跳转到设置页面后返回，两侧滚动位置都丢失回到顶部
- 原因：EditorViewModel中没有保存滚动状态

### 解决方案

**在EditorViewModel中添加滚动状态管理**

```kotlin
// EditorViewModel.kt 新增数据类和状态
data class EditorScrollState(
    val editScrollPosition: Int = 0,      // 编辑器滚动位置（像素）
    val previewScrollRatio: Float = 0f    // 预览滚动比例（0-1）
)

private val _scrollState = MutableStateFlow(EditorScrollState())
val scrollState: StateFlow<EditorScrollState> = _scrollState.asStateFlow()

fun saveEditScrollPosition(position: Int) {
    _scrollState.update { it.copy(editScrollPosition = position) }
}

fun savePreviewScrollRatio(ratio: Float) {
    _scrollState.update { it.copy(previewScrollRatio = ratio) }
}
```

**在EditorScreen中保存和恢复滚动位置**

```kotlin
// 1. 持续保存编辑器滚动位置
LaunchedEffect(Unit) {
    snapshotFlow { scrollState.value }
        .debounce(200) // 防抖，避免频繁更新
        .collect { position ->
            viewModel.saveEditScrollPosition(position)
        }
}

// 2. 导航离开时保存WebView滚动位置
DisposableEffect(Unit) {
    onDispose {
        // 在主线程执行JS调用
        previewWebView.post {
            previewWebView.evaluateJavascript("window.getScrollRatio()") { result ->
                val ratio = result?.trim('"')?.toFloatOrNull() ?: 0f
                viewModel.savePreviewScrollRatio(ratio)
            }
        }
    }
}


// 3. 从设置返回后恢复滚动位置
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

### 实现细节
- 编辑器：保存`ScrollState.value`（像素值）
- 预览：保存滚动比例（0-1），避免分辨率差异
- 使用`debounce(200ms)`防止频繁更新状态
- 恢复时延迟等待视图渲染（编辑器150ms，预览250ms）
- 状态保存在内存中（不持久化到数据库）

---

## 问题3：侧边栏自动定位当前文档

### 现状
- 侧边栏使用`LazyColumn`渲染文件树
- `SidebarFileTree`接收`currentDocumentId`用于高亮
- 打开侧边栏时不会自动滚动到当前文档，需要手动查找

### 解决方案

**改造SidebarFileTree支持自动滚动**

```kotlin
// SidebarFileTree.kt 修改
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
    val listState = rememberLazyListState()
    
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
    
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(tree) { node ->
            FolderTreeItem(/* ... */)
        }
    }
}

// 辅助函数：扁平化遍历树找到文档索引
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


**在EditorScreen中触发自动滚动**

```kotlin
// EditorScreen.kt 修改调用
SidebarFileTree(
    tree = folderTree,
    currentDocumentId = currentDocumentId,
    expandedFolders = fileTreeExpanded,
    onDocumentClick = { id -> openFromSidebar(Screen.Editor.createRoute(id)) },
    onFolderExpand = { key -> fileTreeExpanded = fileTreeExpanded + key },
    onFolderCollapse = { key -> fileTreeExpanded = fileTreeExpanded - key },
    actions = null,
    scrollToCurrentDocument = fileDrawerState.isOpen, // 新增：侧栏打开时触发滚动
    modifier = Modifier.fillMaxSize()
)
```

**增强高亮样式（深色背景标记）**

```kotlin
// SidebarFileTree.kt 修改DocumentRow
@Composable
fun DocumentRow(
    document: Document,
    level: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer // 深色标记
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(start = (level * 16).dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
        Spacer(modifier = Modifier.width(8.dp))
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
        // ... 菜单按钮 ...
    }
}
```

### 实现细节
- 每次侧边栏打开（`fileDrawerState.isOpen`）时触发滚动
- 扁平化遍历树结构，只计算展开的节点
- 使用`animateScrollToItem`平滑滚动
- 高亮使用Material 3的`primaryContainer`背景色
- 文字和图标使用`onPrimaryContainer`颜色确保对比度

---

## 问题4：AI文档上下文工具调用

### 现状
- 当前AI适配器只支持简单对话消息（`List<ChatMessage>`）
- AI无法主动读取项目文档内容
- 用户提问时需要手动复制粘贴相关文档

### 解决方案

采用**工具调用（Function Calling）**模式，让AI能主动请求查看文档。

### 步骤1：扩展数据模型

```kotlin
// domain/model/AiModels.kt 新增
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

/**
 * 对话消息（扩展支持工具调用）
 */
data class ChatMessage(
    val role: String,                    // "user", "assistant", "tool"
    val content: String?,
    val toolCalls: List<ToolCall>? = null,  // assistant发起的工具调用
    val toolCallId: String? = null          // tool角色响应关联的调用ID
)

/**
 * 流式事件（新增ToolCallDelta）
 */
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


### 步骤2：定义文档操作工具

```kotlin
// domain/usecase/ai/DocumentContextTools.kt 新建
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
    
    fun getAllTools(): List<AiTool> = listOf(
        READ_DOCUMENT,
        LIST_DOCUMENTS,
        SEARCH_IN_PROJECT
    )
}
```

### 步骤3：实现工具执行器

```kotlin
// domain/usecase/ai/ExecuteDocumentToolUseCase.kt 新建
@Singleton
class ExecuteDocumentToolUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val fileManager: FileManager
) {
    suspend operator fun invoke(toolCall: ToolCall): Result<String> = runCatching {
        val args = Json.decodeFromString<Map<String, JsonElement>>(toolCall.arguments)
        
        when (toolCall.name) {
            "read_document" -> {
                val docId = args["document_id"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("缺少参数: document_id")
                
                val doc = documentRepository.getDocumentById(docId)
                    ?: throw IllegalArgumentException("文档不存在: $docId")
                
                val content = fileManager.readFile(doc.id)
                
                """
                【文档名称】${doc.name}
                【文档路径】${doc.folderPath ?: "根目录"}
                【文档内容】
                $content
                """.trimIndent()
            }
            
            "list_documents" -> {
                val folderId = args["folder_id"]?.jsonPrimitive?.content
                
                val docs = if (folderId != null) {
                    documentRepository.getDocumentsByFolder(folderId)
                } else {
                    documentRepository.getAllDocuments()
                }
                
                if (docs.isEmpty()) {
                    "项目中暂无文档。"
                } else {
                    "项目文档列表（共${docs.size}个）：\n" + docs.joinToString("\n") { doc ->
                        "- 【${doc.name}】ID: ${doc.id}, 路径: ${doc.folderPath ?: "根目录"}"
                    }
                }
            }
            
            "search_in_project" -> {
                val query = args["query"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("缺少参数: query")
                val maxResults = args["max_results"]?.jsonPrimitive?.intOrNull ?: 5
                
                val allDocs = documentRepository.getAllDocuments()
                val results = mutableListOf<Pair<Document, List<Pair<Int, String>>>>()
                
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
                
                if (results.isEmpty()) {
                    "未找到包含"$query"的文档。"
                } else {
                    "搜索结果（关键词："$query"）：\n\n" + results.joinToString("\n\n") { (doc, matches) ->
                        "【${doc.name}】\n" + matches.joinToString("\n") { (index, line) ->
                            "  第${index + 1}行: ${line.trim()}"
                        }
                    }
                }
            }
            
            else -> throw IllegalArgumentException("未知工具: ${toolCall.name}")
        }
    }
}
```


### 步骤4：修改AI适配器接口

```kotlin
// data/ai/AiApiAdapter.kt 修改
interface AiApiAdapter {
    /** 测试连接 */
    suspend fun testConnection(model: String): ModelTestResult

    /** 拉取可用模型列表 */
    suspend fun fetchAvailableModels(): List<ModelInfo>

    /** 流式对话，支持工具调用 */
    fun sendChatStream(
        messages: List<ChatMessage>,
        config: AiRequestConfig,
        tools: List<AiTool> = emptyList()  // 新增：可用工具列表
    ): Flow<StreamEvent>

    /** 释放资源 */
    fun close()
}
```

### 步骤5：各适配器实现工具调用

**OpenAI适配器**（原生支持Function Calling）：

```kotlin
// data/ai/adapters/OpenAiAdapter.kt 修改
override fun sendChatStream(
    messages: List<ChatMessage>,
    config: AiRequestConfig,
    tools: List<AiTool>
): Flow<StreamEvent> = flow {
    val requestBody = buildJsonObject {
        put("model", config.model)
        put("stream", config.streaming)
        put("temperature", config.temperature)
        config.maxTokens?.let { put("max_tokens", it) }
        
        // 转换消息格式
        put("messages", buildJsonArray {
            messages.forEach { msg ->
                add(buildJsonObject {
                    put("role", msg.role)
                    msg.content?.let { put("content", it) }
                    msg.toolCalls?.let { calls ->
                        put("tool_calls", buildJsonArray {
                            calls.forEach { call ->
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    })
                                })
                            }
                        })
                    }
                    msg.toolCallId?.let { 
                        put("tool_call_id", it)
                        put("name", "tool_response") // OpenAI要求tool角色带name
                    }
                })
            }
        })
        
        // 添加工具定义
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", JsonObject(tool.parameters.mapValues { 
                                Json.parseToJsonElement(Json.encodeToString(it.value))
                            }))
                        })
                    })
                }
            })
        }
    }
    
    // ... 流式解析逻辑，新增ToolCallDelta事件 ...
}
```

**Claude适配器**（支持Tools API）：

```kotlin
// data/ai/adapters/ClaudeAdapter.kt 修改
override fun sendChatStream(
    messages: List<ChatMessage>,
    config: AiRequestConfig,
    tools: List<AiTool>
): Flow<StreamEvent> = flow {
    // Claude格式：system消息单独提取
    val systemMessage = messages.firstOrNull { it.role == "system" }?.content
    val conversationMessages = messages.filter { it.role != "system" }
    
    val requestBody = buildJsonObject {
        put("model", config.model)
        put("max_tokens", config.maxTokens ?: 4096)
        put("temperature", config.temperature)
        put("stream", config.streaming)
        systemMessage?.let { put("system", it) }
        
        // 转换消息格式
        put("messages", buildJsonArray {
            conversationMessages.forEach { msg ->
                add(buildJsonObject {
                    put("role", msg.role)
                    msg.content?.let { 
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", it)
                            })
                        })
                    }
                    msg.toolCalls?.let { calls ->
                        put("content", buildJsonArray {
                            calls.forEach { call ->
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", call.id)
                                    put("name", call.name)
                                    put("input", Json.parseToJsonElement(call.arguments))
                                })
                            }
                        })
                    }
                    msg.toolCallId?.let {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", it)
                                put("content", msg.content ?: "")
                            })
                        })
                    }
                })
            }
        })
        
        // Claude工具格式
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", JsonObject(tool.parameters.mapValues {
                            Json.parseToJsonElement(Json.encodeToString(it.value))
                        }))
                    })
                }
            })
        }
    }
    
    // ... 流式解析 ...
}
```

**Gemini适配器**（使用Function Declarations）：

```kotlin
// data/ai/adapters/GeminiAdapter.kt 修改
// Gemini的Function Calling格式略有不同，需要转换为functionDeclarations
// 实现类似，省略详细代码
```


### 步骤6：集成到AI对话流程

```kotlin
// presentation/ai/chat/AiChatViewModel.kt 修改
private suspend fun sendMessageInternal(userMessage: String?) {
    try {
        // 用户消息追加到历史
        if (userMessage != null) {
            val newMessage = Message(
                id = generateId(),
                conversationId = conversationId,
                role = "user",
                content = userMessage,
                timestamp = System.currentTimeMillis()
            )
            conversationRepository.insertMessage(newMessage)
            _messages.update { it + newMessage }
        }
        
        // 发送流式请求（带工具定义）
        val adapter = aiAdapterFactory.getAdapter(currentProvider)
        adapter.sendChatStream(
            messages = _messages.value.map { it.toChatMessage() },
            config = currentConfig,
            tools = DocumentContextTools.getAllTools() // 新增：传递工具列表
        ).collect { event ->
            when (event) {
                is StreamEvent.Content -> {
                    // 累积AI响应内容
                    currentAiMessageContent.append(event.delta)
                    _streamingContent.emit(currentAiMessageContent.toString())
                }
                
                is StreamEvent.ToolCallDelta -> {
                    // 累积工具调用参数
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
                
                is StreamEvent.Done -> {
                    // 保存AI消息
                    val aiMessage = Message(
                        id = generateId(),
                        conversationId = conversationId,
                        role = "assistant",
                        content = currentAiMessageContent.toString().ifBlank { null },
                        toolCalls = currentToolCalls.values.toList().ifEmpty { null },
                        timestamp = System.currentTimeMillis()
                    )
                    conversationRepository.insertMessage(aiMessage)
                    _messages.update { it + aiMessage }
                    
                    // 如果有工具调用，执行并继续对话
                    if (currentToolCalls.isNotEmpty()) {
                        handleToolCalls(currentToolCalls.values.toList())
                    }
                    
                    // 重置状态
                    currentAiMessageContent.clear()
                    currentToolCalls.clear()
                    _streamingContent.emit(null)
                }
                
                is StreamEvent.Error -> {
                    _error.emit(event.message)
                }
            }
        }
    } catch (e: Exception) {
        _error.emit("发送失败: ${e.message}")
    }
}

private suspend fun handleToolCalls(toolCalls: List<ToolCall>) {
    // 执行所有工具调用
    val toolResults = toolCalls.map { call ->
        val result = executeDocumentToolUseCase(call)
        Message(
            id = generateId(),
            conversationId = conversationId,
            role = "tool",
            content = result.getOrElse { "执行失败: ${it.message}" },
            toolCallId = call.id,
            timestamp = System.currentTimeMillis()
        )
    }
    
    // 保存工具结果
    toolResults.forEach { conversationRepository.insertMessage(it) }
    _messages.update { it + toolResults }
    
    // 继续发送给AI（带工具结果）
    sendMessageInternal(userMessage = null)
}
```

### 实现细节
- AI可以在一次响应中调用多个工具
- 工具执行后自动继续对话，无需用户再次发送
- 工具调用和结果都持久化到数据库
- 支持多轮工具调用（AI可以根据工具结果再次调用其他工具）
- 错误处理：工具执行失败时返回错误信息，不中断对话

---

## 问题5：文档预览缩放功能

### 现状
- 当前WebView未启用缩放功能
- 用户无法放大查看细节

### 解决方案

**启用WebView原生缩放支持，并限制范围**

```kotlin
// EditorScreen.kt previewWebView初始化时
previewWebView.settings.apply {
    // 启用缩放控制
    setSupportZoom(true)
    builtInZoomControls = true
    displayZoomControls = false  // 隐藏默认的+/-按钮（使用双指手势）
    
    // 启用viewport支持
    useWideViewPort = true
    loadWithOverviewMode = true
}

// 注入meta标签限制缩放范围
previewWebView.webViewClient = object : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
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
}
```


**添加双击恢复原始大小功能**

在`renderer.html`中添加双击监听：

```javascript
// 双击恢复原始缩放
window.addEventListener('DOMContentLoaded', function() {
    var isZoomed = false;
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

```kotlin
// EditorScreen.kt 添加JS接口
previewWebView.addJavascriptInterface(object {
    @JavascriptInterface
    fun resetZoom() {
        // 在主线程恢复缩放
        post {
            // 方法1：重置viewport缩放
            evaluateJavascript("""
                document.querySelector('meta[name="viewport"]').content = 
                    'width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=3.0, user-scalable=yes';
            """, null)
            
            // 方法2：重置WebView缩放级别
            setInitialScale(100)
            
            // 滚动到顶部（可选）
            scrollTo(0, 0)
        }
    }
}, "Android")
```

### 实现细节
- 使用WebView原生缩放，不需要自定义手势检测
- 通过viewport meta标签限制缩放范围：1.0-3.0（100%-300%）
- 双击检测：300ms内两次触摸
- 双击排除链接、按钮等可交互元素，避免干扰正常点击
- 缩放状态不持久化，每次打开文档恢复100%
- 注意与问题1的代码块滑动功能协调：代码块横向滑动优先级高于缩放手势

---

## 实现优先级

建议分三个阶段实施：

### Phase 1：快速改进（1-2天）
- ✅ **问题2**：滚动位置保持（实现简单，体验提升明显）
- ✅ **问题3**：侧边栏自动定位（代码量少，用户反馈好）

### Phase 2：交互优化（2-3天）
- ✅ **问题1**：代码块横向滚动优化（需要测试各种设备）
- ✅ **问题5**：文档预览缩放（需要调试viewport和手势冲突）

### Phase 3：AI增强（3-5天）
- ✅ **问题4**：AI文档上下文工具调用（涉及多个模块，需要适配三个AI提供商）

---

## 测试要点

### 问题1：代码块滑动
- [ ] 代码块横向滑动流畅，无卡顿
- [ ] 不影响页面纵向滚动
- [ ] 在AI助手对话中的代码块也能正常滑动
- [ ] 多种设备尺寸和DPI测试

### 问题2：滚动位置
- [ ] 编辑模式跳转设置后返回，滚动位置保持
- [ ] 预览模式跳转设置后返回，滚动位置保持
- [ ] 分屏模式切换到设置再返回，两侧都保持
- [ ] 切换文档后滚动位置正确重置

### 问题3：侧边栏定位
- [ ] 打开侧边栏自动滚动到当前文档
- [ ] 当前文档高亮显示（深色背景）
- [ ] 文档在折叠的文件夹内，自动展开并定位
- [ ] 文档在列表顶部/底部时滚动行为正确

### 问题4：AI工具调用
- [ ] AI能成功调用list_documents列出文档
- [ ] AI能成功调用read_document读取指定文档
- [ ] AI能成功调用search_in_project搜索关键词
- [ ] 工具调用失败时AI能优雅处理
- [ ] 多轮工具调用（AI连续调用多个工具）正常工作
- [ ] 三个AI提供商（OpenAI/Claude/Gemini）都能正常工作

### 问题5：预览缩放
- [ ] 双指缩放流畅，范围100%-300%
- [ ] 无法缩小到100%以下
- [ ] 双击非交互元素恢复100%
- [ ] 双击链接仍然能正常跳转（不触发恢复）
- [ ] 缩放不影响代码块横向滚动

---

## 潜在风险

1. **问题1和问题5的手势冲突**
   - 代码块横向滚动与缩放手势可能冲突
   - 缓解：代码块区域禁用缩放，或调整触摸事件优先级

2. **问题4的token消耗**
   - 读取多个大文档会消耗大量token
   - 缓解：限制read_document返回的内容长度（如前5000字符）

3. **问题4的API兼容性**
   - Gemini的Function Calling格式与OpenAI/Claude不同
   - 缓解：在适配器层做好格式转换，充分测试

4. **WebView性能**
   - 频繁JS调用可能影响性能
   - 缓解：使用防抖（debounce），减少不必要的JS执行

---

## 后续优化方向

1. **AI上下文增强**
   - 支持读取外部工作区文档（目前只支持内部库）
   - 支持图片OCR识别（读取文档中的图片内容）
   - 支持语义搜索（而非简单关键词匹配）

2. **缩放功能增强**
   - 记住用户的缩放偏好（持久化）
   - 支持字体大小独立调整（不影响图片大小）

3. **侧边栏优化**
   - 支持搜索文档（过滤文件树）
   - 显示最近打开的文档（快速切换）

---

## 文件清单

### 新建文件
- `domain/usecase/ai/DocumentContextTools.kt` - 工具定义
- `domain/usecase/ai/ExecuteDocumentToolUseCase.kt` - 工具执行器

### 修改文件
- `app/src/main/assets/templates/renderer.html` - JS增强（问题1、5）
- `presentation/editor/EditorScreen.kt` - WebView配置和UI逻辑（问题1、2、3、5）
- `presentation/editor/EditorViewModel.kt` - 滚动状态管理（问题2）
- `presentation/sidebar/SidebarFileTree.kt` - 自动滚动和高亮（问题3）
- `data/ai/AiApiAdapter.kt` - 接口扩展（问题4）
- `data/ai/adapters/OpenAiAdapter.kt` - 工具调用实现（问题4）
- `data/ai/adapters/ClaudeAdapter.kt` - 工具调用实现（问题4）
- `data/ai/adapters/GeminiAdapter.kt` - 工具调用实现（问题4）
- `domain/model/AiModels.kt` - 数据模型扩展（问题4）
- `presentation/ai/chat/AiChatViewModel.kt` - 工具调用集成（问题4）
- `presentation/ai/agent/AgentChatViewModel.kt` - 工具调用集成（问题4）

---

## 总结

本设计采用渐进式改进方案，将五个用户体验问题分解为独立的技术方案，降低实现风险。

**核心亮点**：
- 代码块滑动通过触摸事件协作解决
- 滚动位置保持使用比例而非绝对值，适配多分辨率
- 侧边栏定位通过树遍历算法精确计算索引
- AI工具调用采用Function Calling标准，支持多轮交互
- 预览缩放使用原生WebView能力，性能优秀

**预期收益**：
- 用户体验显著提升，减少操作摩擦
- AI能力增强，支持项目级上下文理解
- 代码质量保持，遵循现有架构模式

