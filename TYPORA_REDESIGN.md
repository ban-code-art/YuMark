# YuMark - Typora 风格重设计文档

## 📋 问题总结

基于用户反馈，当前实现与 Typora PC 端存在以下差距：

### 1. 界面语言问题
- ❌ 部分按钮和界面文字仍为英文
- ✅ 需要：完全中文化界面

### 2. 文件夹功能不完整
- ❌ 不能删除文件夹
- ❌ 不支持子文件夹（嵌套）
- ✅ 需要：完整的文件夹树形结构

### 3. 侧边栏缺失
- ❌ 左侧没有类似 Typora 的文件树
- ❌ 文件夹展开/收起功能缺失
- ✅ 需要：Typora 风格的侧边栏文件树

### 4. 文件导入功能缺失
- ❌ 无法导入外部 Markdown 文件
- ✅ 需要：文件导入功能

### 5. 工具栏图标不清晰
- ❌ 仅图标，无文字提示
- ✅ 需要：工具提示（Tooltip）或中文标签

### 6. 顶部操作栏不完整
- ❌ 缺少设置按钮
- ❌ 功能按键太少
- ✅ 需要：完整的顶部操作栏

---

## 🎯 Typora 核心特征分析

### Typora PC 端界面布局

```
┌─────────────────────────────────────────────────────────────┐
│  [☰] YuMark    [文件] [编辑] [段落] [格式] [视图] [主题] [帮助] │  ← 顶部菜单栏
├──────────┬──────────────────────────────────────────────────┤
│          │  # 标题                                           │
│ 📁 文档  │                                                    │
│  └─📄 A  │  正文内容...                                       │
│  └─📄 B  │                                                    │
│          │                                                    │
│ 📁 项目  │  支持实时预览                                      │
│  ├─📁子  │                                                    │
│  │ └─📄C │                                                    │
│  └─📄 D  │                                                    │
│          │                                                    │
│ [+ 新建] │                                                    │
└──────────┴──────────────────────────────────────────────────┘
 ↑                              ↑
侧边栏                       编辑/预览区域
```

### Typora 核心特性

1. **侧边栏文件树**
   - 展开/收起文件夹
   - 嵌套子文件夹
   - 文件拖拽排序
   - 右键菜单

2. **顶部菜单栏**
   - 文件（新建、打开、保存、导入、导出）
   - 编辑（撤销、重做、查找、替换）
   - 段落（标题、列表、引用）
   - 格式（粗体、斜体、代码）
   - 视图（侧边栏、大纲、源代码模式）
   - 主题（切换主题）

3. **即时预览模式**
   - 所见即所得（WYSIWYG）
   - 无需切换编辑/预览

4. **浮动工具栏**
   - 选中文字时出现
   - 快速格式化

---

## 📐 重设计方案

### 阶段 1：界面本地化（P0）

#### 1.1 完全中文化
```kotlin
// strings.xml 增强
<string name="app_name">YuMark</string>
<string name="file_list">文件列表</string>
<string name="editor">编辑器</string>
<string name="settings">设置</string>
<string name="create_document">新建文档</string>
<string name="create_folder">新建文件夹</string>
<string name="rename">重命名</string>
<string name="delete">删除</string>
<string name="delete_confirm">确认删除</string>
<string name="import_file">导入文件</string>
<string name="export">导出</string>
<string name="search">搜索</string>
<string name="sort">排序</string>

// 工具栏按钮
<string name="toolbar_heading">标题</string>
<string name="toolbar_bold">粗体</string>
<string name="toolbar_italic">斜体</string>
<string name="toolbar_link">链接</string>
<string name="toolbar_image">图片</string>
<string name="toolbar_code">代码</string>
<string name="toolbar_list">列表</string>
<string name="toolbar_quote">引用</string>
<string name="toolbar_table">表格</string>
```

#### 1.2 工具栏添加文字标签
```kotlin
@Composable
fun MarkdownToolbarWithLabels() {
    Row {
        ToolbarButton(
            icon = Icons.Default.Title,
            label = "标题",
            onClick = { }
        )
        ToolbarButton(
            icon = Icons.Default.FormatBold,
            label = "粗体",
            onClick = { }
        )
        // ...
    }
}

@Composable
fun ToolbarButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
    }
}
```

---

### 阶段 2：侧边栏文件树（P0）

#### 2.1 数据模型增强
```kotlin
// 支持嵌套文件夹
data class FolderTreeNode(
    val folder: Folder?,  // null 表示根节点
    val documents: List<Document>,
    val children: List<FolderTreeNode>,
    val isExpanded: Boolean = false,
    val level: Int = 0
)

// FolderRepository 新增方法
interface FolderRepository {
    suspend fun deleteFolder(id: String, deleteContents: Boolean): Result<Unit>
    suspend fun moveFolder(folderId: String, newParentId: String?): Result<Unit>
    suspend fun getFolderTree(): Result<List<FolderTreeNode>>
}
```

#### 2.2 侧边栏 UI
```kotlin
@Composable
fun SidebarFileTree(
    tree: List<FolderTreeNode>,
    onDocumentClick: (String) -> Unit,
    onFolderClick: (String) -> Unit,
    onFolderExpand: (String) -> Unit,
    onFolderCollapse: (String) -> Unit,
    onCreateDocument: (String?) -> Unit,
    onCreateFolder: (String?) -> Unit,
    onDeleteFolder: (String) -> Unit
) {
    LazyColumn {
        items(tree) { node ->
            FolderTreeItem(
                node = node,
                onDocumentClick = onDocumentClick,
                onFolderClick = onFolderClick,
                onExpand = onFolderExpand,
                onCollapse = onFolderCollapse,
                onDelete = onDeleteFolder
            )
        }
    }
}

@Composable
fun FolderTreeItem(
    node: FolderTreeNode,
    onDocumentClick: (String) -> Unit,
    onFolderClick: (String) -> Unit,
    onExpand: (String) -> Unit,
    onCollapse: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Column {
        // 文件夹行
        node.folder?.let { folder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onFolderClick(folder.id) }
                    .padding(start = (node.level * 16).dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 展开/收起图标
                IconButton(
                    onClick = {
                        if (node.isExpanded) onCollapse(folder.id)
                        else onExpand(folder.id)
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (node.isExpanded) Icons.Default.ExpandMore 
                        else Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // 文件夹图标
                Icon(
                    if (node.isExpanded) Icons.Default.FolderOpen 
                    else Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 文件夹名称
                Text(
                    folder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )

                // 右键菜单
                var showMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("新建文档") },
                        onClick = { /* TODO */ }
                    )
                    DropdownMenuItem(
                        text = { Text("新建子文件夹") },
                        onClick = { /* TODO */ }
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { /* TODO */ }
                    )
                    DropdownMenuItem(
                        text = { Text("删除文件夹") },
                        onClick = { onDelete(folder.id) }
                    )
                }
            }
        }

        // 展开时显示文档列表
        if (node.isExpanded || node.folder == null) {
            node.documents.forEach { doc ->
                DocumentTreeItem(
                    document = doc,
                    level = node.level + 1,
                    onClick = { onDocumentClick(doc.id) }
                )
            }

            // 递归显示子文件夹
            node.children.forEach { child ->
                FolderTreeItem(
                    node = child,
                    onDocumentClick = onDocumentClick,
                    onFolderClick = onFolderClick,
                    onExpand = onExpand,
                    onCollapse = onCollapse,
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
fun DocumentTreeItem(
    document: Document,
    level: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (level * 16 + 32).dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            document.name,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
```

---

### 阶段 3：顶部操作栏增强（P0）

#### 3.1 完整顶部菜单
```kotlin
@Composable
fun TopMenuBar(
    onNewDocument: () -> Unit,
    onImportFile: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onToggleSidebar: () -> Unit,
    onTogglePreview: () -> Unit
) {
    TopAppBar(
        title = { Text("YuMark") },
        navigationIcon = {
            IconButton(onClick = onToggleSidebar) {
                Icon(Icons.Default.Menu, "侧边栏")
            }
        },
        actions = {
            // 文件菜单
            var showFileMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showFileMenu = true }) {
                    Icon(Icons.Default.InsertDriveFile, "文件")
                }
                DropdownMenu(
                    expanded = showFileMenu,
                    onDismissRequest = { showFileMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("新建文档") },
                        onClick = { onNewDocument(); showFileMenu = false },
                        leadingIcon = { Icon(Icons.Default.Add, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("导入文件") },
                        onClick = { onImportFile(); showFileMenu = false },
                        leadingIcon = { Icon(Icons.Default.Upload, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("导出") },
                        onClick = { onExport(); showFileMenu = false },
                        leadingIcon = { Icon(Icons.Default.Download, null) }
                    )
                }
            }

            // 视图切换
            IconButton(onClick = onTogglePreview) {
                Icon(Icons.Default.Visibility, "预览模式")
            }

            // 搜索
            IconButton(onClick = { /* TODO */ }) {
                Icon(Icons.Default.Search, "搜索")
            }

            // 设置
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, "设置")
            }
        }
    )
}
```

---

### 阶段 4：文件导入功能（P1）

#### 4.1 UseCase
```kotlin
class ImportDocumentUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val fileManager: FileManager
) {
    suspend operator fun invoke(uri: Uri, folderId: String?): Result<Document> = runCatching {
        // 读取文件内容
        val content = fileManager.readFromUri(uri)
        
        // 提取文件名
        val fileName = fileManager.getFileName(uri) ?: "未命名文档"
        
        // 创建文档
        val document = Document.create(
            name = fileName.removeSuffix(".md"),
            folderId = folderId
        ).copy(content = content)
        
        documentRepository.saveDocument(document).getOrThrow()
        document
    }
}
```

#### 4.2 UI 集成
```kotlin
// FileListScreen 中添加
val importLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
) { uri ->
    uri?.let { viewModel.importDocument(it) }
}

// 顶部菜单中调用
onImportFile = { importLauncher.launch("text/markdown") }
```

---

### 阶段 5：Typora 风格编辑器（P1）

#### 5.1 即时预览模式
```kotlin
// 不再是编辑/预览切换，而是所见即所得
@Composable
fun TyporaStyleEditor(
    content: String,
    onContentChange: (String) -> Unit
) {
    var currentLine by remember { mutableStateOf("") }
    var cursorPosition by remember { mutableStateOf(0) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // 当前编辑行显示原始 Markdown
        if (currentLine.isNotEmpty()) {
            OutlinedTextField(
                value = currentLine,
                onValueChange = { /* 更新当前行 */ },
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // 其他行显示渲染后的 HTML
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    // 渲染非当前行的内容
                }
            }
        )
    }
}
```

---

## 📋 实施优先级

### P0（必须实现）
1. ✅ 界面完全中文化
2. ✅ 侧边栏文件树（展开/收起）
3. ✅ 文件夹删除功能
4. ✅ 子文件夹支持（嵌套）
5. ✅ 顶部操作栏增强（设置按钮）
6. ✅ 工具栏文字标签

### P1（重要功能）
7. ✅ 文件导入功能
8. ✅ 文件夹右键菜单
9. ✅ 文档拖拽排序
10. ✅ 浮动格式工具栏

### P2（增强功能）
11. ✅ Typora 风格即时预览
12. ✅ 大纲视图
13. ✅ 源代码模式
14. ✅ 主题切换 UI

---

## 🎯 最终目标

创建一个**真正贴近 Typora 体验**的 Android Markdown 编辑器：

✅ 侧边栏文件树（完整嵌套）  
✅ 所见即所得编辑  
✅ 完整的顶部菜单栏  
✅ 文件导入/导出  
✅ 全中文界面  
✅ 流畅的用户体验  

---

**下一步**：开始实施 P0 优先级功能
