# 设计文档：文件夹工作区 + 大纲导航 + 默认预览模式

- 日期：2026-06-11
- 项目：YuMark（Android Typora 风格 Markdown 编辑器）
- 状态：已获用户批准

## 背景与目标

用户需求：

1. 可以选择手机内的文件夹"导入"，在左侧栏查看其中的 md 文档
2. 长文档可以拉出右侧栏查看详细目录（大纲），点击定位到对应位置
3. 打开 md 文档默认进入预览模式而非编辑模式

已确认的关键决策：

| 决策点 | 结论 |
|---|---|
| 导入语义 | **实时关联**：文件留在原位，SAF 持久授权直接读写原文件，不复制 |
| 工作区模型 | **单工作区**：同时只打开一个外部文件夹，侧栏整体切换 |
| 大纲范围 | **仅预览模式**可用 |
| 架构方案 | **方案 A**：独立工作区层（内存扫描），不写入 Room |

## 现状摘要

- Compose + Hilt + Room 清洁架构；内部文档存 `filesDir/documents/<uuid>.md`，元数据在 Room
- 左侧抽屉已有内部文件夹树（`SidebarFileTree.kt`）
- 编辑器（`EditorScreen.kt`）有编辑/预览切换，默认编辑（`EditorViewModel.kt:44`）
- 预览用 WebView + `assets/templates/renderer.html`（marked.js + KaTeX + Prism + Mermaid），入口函数 `window.renderMarkdown`
- 路由：`editor/{documentId}`（`Screen.kt`）
- 设置用 DataStore（`SettingsDataStore.kt`）

## 1. 工作区层（外部文件夹实时关联）

### 入口与授权

- 左侧抽屉顶部新增"打开文件夹"按钮
- 调用 `ActivityResultContracts.OpenDocumentTree` 让用户选文件夹
- 成功后 `takePersistableUriPermission`（读 + 写），URI 存入 `WorkspaceDataStore`

### 新组件

| 组件 | 位置 | 职责 |
|---|---|---|
| `WorkspaceRepository`（接口） | `domain/repository/` | 工作区生命周期与文件读写的抽象 |
| `WorkspaceRepositoryImpl` | `data/repository/` | 用 `DocumentFile` 实现扫描/读/写 |
| `WorkspaceDataStore` | `data/local/prefs/` | 持久化 `workspace_tree_uri`，重启恢复 |
| `WorkspaceNode` / `WorkspaceDoc` | `domain/model/` | 文件夹节点 / 文档（name、uri、lastModified） |

接口形态：

```kotlin
interface WorkspaceRepository {
    val workspace: StateFlow<Workspace?>          // null = 未打开工作区
    suspend fun openWorkspace(treeUri: Uri): Result<Workspace>
    suspend fun closeWorkspace()
    suspend fun rescan(): Result<WorkspaceNode>   // 重新扫描文件树
    suspend fun readDocument(docUri: Uri): Result<String>
    suspend fun writeDocument(docUri: Uri, content: String): Result<Unit>
    suspend fun restoreOnLaunch()                 // 启动时从 DataStore 恢复并校验授权
}
```

### 扫描规则

- 只收 `.md` / `.markdown` / `.txt` 文件
- 跳过 `.` 开头的隐藏文件/文件夹
- 递归深度上限 10 层；文件总数上限 2000，超出截断并在侧栏提示
- 扫描运行在 `Dispatchers.IO`，结果为不可变树

### 实时性策略

- 打开左侧抽屉时自动触发 `rescan()`
- 侧栏标题栏提供手动刷新按钮
- 不使用 ContentObserver 监听 SAF 树（各厂商 ROM 行为不一致，不可靠）

### 依赖新增

- `androidx.documentfile:documentfile`（加入 version catalog）

## 2. 左侧栏（双态）

抽屉内容根据 `workspace` 状态二选一：

- **无工作区**（现状保持）：内部文件树 + 顶部"打开文件夹"按钮
- **有工作区**：
  - 标题栏：文件夹名 + 刷新按钮 + "关闭工作区"按钮（关闭后回到内部文件树，不删除任何文件，授权保留以便快速重开）
  - 文件树：渲染 `WorkspaceNode`，复用现有 `FolderRow`/`DocumentRow` 的视觉样式（新建 `WorkspaceFileTree` 组件，不强行改造现有 `SidebarFileTree` 的 CRUD 回调签名）
  - 工作区文件树只读结构：本期不支持在侧栏里新建/重命名/删除外部文件（YAGNI，编辑内容已可写回）

### 路由变更

`Screen.Editor` 改为可选参数形式：

```
editor?docId={docId}&docUri={docUri}
```

- 内部文档传 `docId`，外部文档传 URL-encode 后的 `docUri`，二选一
- `Screen.Editor.createRoute(documentId)` 保留兼容，新增 `createExternalRoute(docUri)`

## 3. 编辑器双源加载

`EditorViewModel` 按导航参数选择数据通路：

| 操作 | 内部文档（docId） | 外部文档（docUri） |
|---|---|---|
| 加载 | `LoadDocumentUseCase`（现状） | `WorkspaceRepository.readDocument` |
| 保存 | `SaveDocumentUseCase`（现状） | `WorkspaceRepository.writeDocument` 直接写回原文件 |
| 文档名 | Room 元数据 | `DocumentFile.name`（去扩展名） |

- 自动保存、脏标记、手动保存逻辑对两种来源一致
- 外部文档不支持收藏/字数统计入库（这些是 Room 特性），顶栏只显示名称

## 4. 右侧大纲栏（仅预览模式）

### 大纲提取（JS 端）

`renderer.html` 的 `renderMarkdown` 在 `contentEl.innerHTML = html` 之后：

1. 遍历 `#content` 内的 `h1–h6`，分配 `id="yumark-h-<序号>"`
2. 收集 `[{level: 1-6, text: 标题文本, id: 锚点id}]`
3. `Android.onOutline(JSON.stringify(list))` 回传（扩展现有 JS 接口对象）

### UI（Compose 端）

- `EditorViewModel` 新增 `outline: StateFlow<List<OutlineItem>>`，WebView 的 JavascriptInterface 收到回调后更新
- 预览模式且大纲非空时，顶栏显示"大纲"按钮；右侧抽屉通过 `CompositionLocalProvider(LocalLayoutDirection provides Rtl)` 包裹 `ModalNavigationDrawer` 实现（内容区再恢复 Ltr），支持从右边缘手势拉出
- 大纲列表按 `level` 缩进显示；点击条目：
  ```js
  document.getElementById('<id>').scrollIntoView({behavior:'smooth'})
  ```
  并关闭抽屉。锚点来自渲染后的真实 DOM，定位精确
- 切换回编辑模式或文档变更时大纲随重新渲染自动更新

新组件：`presentation/editor/OutlinePanel.kt`

## 5. 默认预览模式

- `UserSettings` 新增 `defaultPreviewMode: Boolean`（默认 `true`），`SettingsDataStore` 加对应 key
- `EditorViewModel` 初始化：文档加载成功后，若 `defaultPreviewMode && content.isNotBlank()` 则 `_isPreviewMode.value = true`
- **空文档例外**：内容为空白时强制编辑模式（避免新建文档看到空白预览）
- `SettingsScreen` 加开关："打开文档时默认进入预览模式"

## 6. 错误处理

| 场景 | 处理 |
|---|---|
| 启动恢复时授权已失效/文件夹被删 | 清除工作区状态，侧栏显示提示条 + "重新选择文件夹" |
| 扫描中途失败 | 保留上次成功的树，Snackbar 报错 |
| 读取外部文档失败 | EditorUiState.Error + 重试按钮（沿用现有错误态） |
| 写回失败（只读文件/云盘虚拟文档） | Snackbar "保存失败"，编辑内容保留在内存不丢失 |
| 文件超大（>2MB 单文件） | 打开前提示，仍允许打开 |

## 7. 测试策略

- `WorkspaceRepositoryImpl` 的扫描/过滤/限深逻辑：把 `DocumentFile` 遍历抽象为内部可替换的节点接口，用假树做 JUnit 单测（沿用 mockk + truth）
- `EditorViewModel` 双源加载/默认预览逻辑：现有 ViewModel 测试模式（mockk + turbine）
- 大纲 JS 提取与滚动定位：真机/模拟器手动验证（WebView 行为不适合单测）
- 回归点：内部文档的打开/保存/自动保存不受影响

## 非目标（本期不做）

- 外部文档进全局搜索/最近列表
- 侧栏内对外部文件的新建/重命名/删除
- 多工作区并列
- 外部文件夹中图片资源的相对路径解析（预览中相对路径图片可能不显示，后续版本处理）

## 涉及文件清单

新增：
- `domain/repository/WorkspaceRepository.kt`
- `domain/model/Workspace.kt`（Workspace/WorkspaceNode/WorkspaceDoc/OutlineItem）
- `data/repository/WorkspaceRepositoryImpl.kt`
- `data/local/prefs/WorkspaceDataStore.kt`
- `presentation/sidebar/WorkspaceFileTree.kt`
- `presentation/editor/OutlinePanel.kt`

修改：
- `presentation/navigation/Screen.kt`、`YuMarkNavGraph.kt`（路由可选参数）
- `presentation/filelist/FileListScreen.kt`、`FileListViewModel.kt`（抽屉双态 + 打开文件夹）
- `presentation/editor/EditorScreen.kt`、`EditorViewModel.kt`（双源加载、大纲、默认预览）
- `presentation/settings/SettingsScreen.kt`、`SettingsDataStore.kt`、`domain/model/Models.kt`（新设置项）
- `assets/templates/renderer.html`（大纲收集 JS）
- `di/RepositoryModule.kt`（绑定 WorkspaceRepository）
- `gradle/libs.versions.toml`、`app/build.gradle.kts`（documentfile 依赖）
