# 文件夹工作区 + 大纲导航 + 默认预览 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 YuMark 中实现 SAF 外部文件夹实时关联工作区（左侧栏浏览 md 文件树）、预览模式右侧大纲目录定位、打开文档默认预览模式。

**Architecture:** 新增独立的 `WorkspaceRepository` 层用 `DocumentFile` 扫描外部文件夹（不写入 Room），编辑器按导航参数双源加载（Room docId / SAF docUri），大纲由 WebView 渲染后 JS 收集真实 DOM 标题回传 Kotlin。

**Tech Stack:** Kotlin + Jetpack Compose + Hilt + DataStore + SAF (DocumentFile) + WebView (marked.js)

**注意：本项目不是 git 仓库，所有"提交"步骤替换为编译/测试验证。** 设计文档见 `docs/superpowers/specs/2026-06-11-folder-workspace-outline-design.md`。

**构建命令约定**（在项目根目录 `D:\CCguiPlay\Typora\YuMark` 运行，bash）：
- 编译检查：`./gradlew :app:compileDebugKotlin -q`
- 单测：`./gradlew :app:testDebugUnitTest --tests "<pattern>"`
- 完整构建：`./gradlew :app:assembleDebug -q`

---

### Task 1: 依赖与域模型

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/yumark/app/domain/model/Workspace.kt`

- [ ] **Step 1.1: libs.versions.toml 加 documentfile**

`[versions]` 区块（`commonmark = "0.21.0"` 之后）加：

```toml
documentfile = "1.0.1"
```

`[libraries]` 区块（`androidx-activity-compose` 之后）加：

```toml
androidx-documentfile = { group = "androidx.documentfile", name = "documentfile", version.ref = "documentfile" }
```

- [ ] **Step 1.2: app/build.gradle.kts 引依赖**

在 `implementation(libs.androidx.activity.compose)` 之后加一行：

```kotlin
implementation(libs.androidx.documentfile)
```

- [ ] **Step 1.3: 配置 JUnit5 平台（修复存量问题：JUnit5 测试从未被运行）**

`app/build.gradle.kts` 的 `android { }` 块内（`buildFeatures` 之前）加：

```kotlin
testOptions {
    unitTests.all { it.useJUnitPlatform() }
}
```

- [ ] **Step 1.4: 创建域模型 Workspace.kt**

```kotlin
package com.yumark.app.domain.model

/**
 * 外部文件夹工作区（SAF 实时关联，不入 Room）
 */
data class Workspace(
    val name: String,
    val treeUri: String,
    val root: WorkspaceNode,
    val truncated: Boolean = false
)

data class WorkspaceNode(
    val name: String,
    val uri: String,
    val folders: List<WorkspaceNode>,
    val docs: List<WorkspaceDoc>
)

data class WorkspaceDoc(
    val name: String,       // 不含扩展名，用于显示
    val fileName: String,   // 含扩展名
    val uri: String,
    val lastModified: Long
)

/**
 * 预览模式大纲条目（来自 WebView 渲染后的真实标题元素）
 */
data class OutlineItem(
    val level: Int,      // 1-6 对应 h1-h6
    val text: String,
    val anchorId: String // DOM 元素 id，如 yumark-h-0
)
```

- [ ] **Step 1.5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL（无输出即成功）

---

### Task 2: WorkspaceScanner（TDD）

**Files:**
- Create: `app/src/main/java/com/yumark/app/data/local/file/WorkspaceScanner.kt`
- Test: `app/src/test/java/com/yumark/app/data/local/file/WorkspaceScannerTest.kt`

把"DocumentFile 树 → WorkspaceNode 树"的纯逻辑（过滤扩展名、跳隐藏、限深、限量、排序）独立成可单测的对象，`DocumentFile` 通过 `ScanEntry` 接口隔离。

- [ ] **Step 2.1: 先写失败测试 WorkspaceScannerTest.kt**

```kotlin
package com.yumark.app.data.local.file

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WorkspaceScannerTest {

    private class FakeEntry(
        override val name: String?,
        override val isDirectory: Boolean,
        private val childEntries: List<FakeEntry> = emptyList(),
        override val lastModified: Long = 0L
    ) : ScanEntry {
        override val uri: String = "fake://${name}"
        override fun children(): List<ScanEntry> = childEntries
    }

    private fun dir(name: String, vararg children: FakeEntry) =
        FakeEntry(name, true, children.toList())

    private fun file(name: String) = FakeEntry(name, false)

    @Test
    fun `只收集 md markdown txt 文件`() {
        val root = dir("root", file("a.md"), file("b.markdown"), file("c.txt"), file("d.pdf"), file("e.jpg"))
        val result = WorkspaceScanner.scan(root)
        assertThat(result.root.docs.map { it.fileName })
            .containsExactly("a.md", "b.markdown", "c.txt")
    }

    @Test
    fun `跳过隐藏文件和隐藏文件夹`() {
        val root = dir("root", file(".hidden.md"), dir(".git", file("x.md")), file("ok.md"))
        val result = WorkspaceScanner.scan(root)
        assertThat(result.root.docs.map { it.fileName }).containsExactly("ok.md")
        assertThat(result.root.folders).isEmpty()
    }

    @Test
    fun `递归收集子文件夹并按名称排序`() {
        val root = dir("root", dir("b", file("2.md")), dir("a", file("1.md")))
        val result = WorkspaceScanner.scan(root)
        assertThat(result.root.folders.map { it.name }).containsExactly("a", "b").inOrder()
        assertThat(result.root.folders[0].docs.first().fileName).isEqualTo("1.md")
    }

    @Test
    fun `超过深度上限停止下钻并标记截断`() {
        var node = dir("leaf", file("deep.md"))
        repeat(WorkspaceScanner.MAX_DEPTH + 1) { i -> node = dir("d$i", node) }
        val result = WorkspaceScanner.scan(node)
        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `超过文件数上限停止收集并标记截断`() {
        val files = (0 until WorkspaceScanner.MAX_FILES + 10).map { file("f$it.md") }
        val root = FakeEntry("root", true, files)
        val result = WorkspaceScanner.scan(root)
        assertThat(result.root.docs).hasSize(WorkspaceScanner.MAX_FILES)
        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `文档显示名去掉扩展名`() {
        val root = dir("root", file("我的笔记.md"))
        val result = WorkspaceScanner.scan(root)
        assertThat(result.root.docs.first().name).isEqualTo("我的笔记")
    }

    @Test
    fun `name 为 null 的条目被跳过`() {
        val root = dir("root", FakeEntry(null, false), file("ok.md"))
        val result = WorkspaceScanner.scan(root)
        assertThat(result.root.docs.map { it.fileName }).containsExactly("ok.md")
    }
}
```

- [ ] **Step 2.2: 运行测试确认编译失败（ScanEntry/WorkspaceScanner 不存在）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.yumark.app.data.local.file.WorkspaceScannerTest"`
Expected: FAIL — `Unresolved reference: ScanEntry`

- [ ] **Step 2.3: 实现 WorkspaceScanner.kt**

```kotlin
package com.yumark.app.data.local.file

import com.yumark.app.domain.model.WorkspaceDoc
import com.yumark.app.domain.model.WorkspaceNode

/**
 * 文件树条目抽象，隔离 DocumentFile 以便单元测试
 */
interface ScanEntry {
    val name: String?
    val isDirectory: Boolean
    val uri: String
    val lastModified: Long
    fun children(): List<ScanEntry>
}

/**
 * 把外部文件夹扫描为 WorkspaceNode 树。
 * 规则：只收 md/markdown/txt；跳过 . 开头的隐藏项；限深 MAX_DEPTH；限量 MAX_FILES。
 */
object WorkspaceScanner {
    const val MAX_DEPTH = 10
    const val MAX_FILES = 2000
    private val SUPPORTED_EXTENSIONS = setOf("md", "markdown", "txt")

    data class ScanResult(val root: WorkspaceNode, val truncated: Boolean)

    fun scan(rootEntry: ScanEntry): ScanResult {
        val state = ScanState()
        val root = scanNode(rootEntry, depth = 0, state = state)
        return ScanResult(root, state.truncated)
    }

    private class ScanState {
        var fileCount = 0
        var truncated = false
    }

    private fun scanNode(entry: ScanEntry, depth: Int, state: ScanState): WorkspaceNode {
        val folders = mutableListOf<WorkspaceNode>()
        val docs = mutableListOf<WorkspaceDoc>()

        if (depth >= MAX_DEPTH) {
            state.truncated = true
        } else {
            for (child in entry.children()) {
                val childName = child.name ?: continue
                if (childName.startsWith(".")) continue

                if (child.isDirectory) {
                    folders += scanNode(child, depth + 1, state)
                } else {
                    if (state.fileCount >= MAX_FILES) {
                        state.truncated = true
                        break
                    }
                    val ext = childName.substringAfterLast('.', "").lowercase()
                    if (ext in SUPPORTED_EXTENSIONS) {
                        state.fileCount++
                        docs += WorkspaceDoc(
                            name = childName.substringBeforeLast('.'),
                            fileName = childName,
                            uri = child.uri,
                            lastModified = child.lastModified
                        )
                    }
                }
            }
        }

        return WorkspaceNode(
            name = entry.name ?: "?",
            uri = entry.uri,
            folders = folders.sortedBy { it.name.lowercase() },
            docs = docs.sortedBy { it.name.lowercase() }
        )
    }
}
```

- [ ] **Step 2.4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.yumark.app.data.local.file.WorkspaceScannerTest"`
Expected: 7 个测试全部 PASS

---

### Task 3: WorkspaceDataStore + WorkspaceRepository + DI

**Files:**
- Create: `app/src/main/java/com/yumark/app/data/local/prefs/WorkspaceDataStore.kt`
- Create: `app/src/main/java/com/yumark/app/domain/repository/WorkspaceRepository.kt`
- Create: `app/src/main/java/com/yumark/app/data/repository/WorkspaceRepositoryImpl.kt`
- Modify: `app/src/main/java/com/yumark/app/di/RepositoryModule.kt`

- [ ] **Step 3.1: WorkspaceDataStore.kt**

```kotlin
package com.yumark.app.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.workspaceDataStore: DataStore<Preferences> by preferencesDataStore(name = "workspace")

/**
 * 持久化当前工作区的 SAF 树 URI，应用重启后恢复
 */
@Singleton
class WorkspaceDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val TREE_URI = stringPreferencesKey("workspace_tree_uri")
    }

    val treeUriFlow: Flow<String?> = context.workspaceDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.TREE_URI] }

    suspend fun saveTreeUri(uri: String) {
        context.workspaceDataStore.edit { it[Keys.TREE_URI] = uri }
    }

    suspend fun clearTreeUri() {
        context.workspaceDataStore.edit { it.remove(Keys.TREE_URI) }
    }
}
```

- [ ] **Step 3.2: WorkspaceRepository.kt（domain 接口）**

```kotlin
package com.yumark.app.domain.repository

import com.yumark.app.domain.model.Workspace
import kotlinx.coroutines.flow.StateFlow

interface WorkspaceRepository {
    /** 当前工作区，null 表示未打开 */
    val workspace: StateFlow<Workspace?>

    /** 打开外部文件夹工作区并扫描（treeUri 为 SAF 树 URI 字符串） */
    suspend fun openWorkspace(treeUri: String): Result<Workspace>

    /** 关闭工作区（保留系统授权，便于下次快速重开） */
    suspend fun closeWorkspace()

    /** 重新扫描当前工作区文件树 */
    suspend fun rescan(): Result<Workspace>

    /** 应用启动时从持久化恢复工作区；授权失效则静默清除 */
    suspend fun restoreOnLaunch()

    suspend fun readDocument(docUri: String): Result<String>
    suspend fun writeDocument(docUri: String, content: String): Result<Unit>

    /** 外部文档显示名（去扩展名） */
    fun documentName(docUri: String): String
}
```

- [ ] **Step 3.3: WorkspaceRepositoryImpl.kt**

```kotlin
package com.yumark.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.yumark.app.data.local.file.ScanEntry
import com.yumark.app.data.local.file.WorkspaceScanner
import com.yumark.app.data.local.prefs.WorkspaceDataStore
import com.yumark.app.domain.model.Workspace
import com.yumark.app.domain.repository.WorkspaceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspaceDataStore: WorkspaceDataStore
) : WorkspaceRepository {

    private val _workspace = MutableStateFlow<Workspace?>(null)
    override val workspace: StateFlow<Workspace?> = _workspace.asStateFlow()

    override suspend fun openWorkspace(treeUri: String): Result<Workspace> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                    ?: error("无法访问所选文件夹")
                if (!rootDoc.canRead()) error("没有该文件夹的读取权限")
                val result = WorkspaceScanner.scan(DocumentFileEntry(rootDoc))
                val ws = Workspace(
                    name = rootDoc.name ?: "外部文件夹",
                    treeUri = treeUri,
                    root = result.root,
                    truncated = result.truncated
                )
                workspaceDataStore.saveTreeUri(treeUri)
                _workspace.value = ws
                ws
            }
        }

    override suspend fun closeWorkspace() {
        workspaceDataStore.clearTreeUri()
        _workspace.value = null
    }

    override suspend fun rescan(): Result<Workspace> {
        val current = _workspace.value
            ?: return Result.failure(IllegalStateException("没有打开的工作区"))
        return openWorkspace(current.treeUri)
    }

    override suspend fun restoreOnLaunch() {
        if (_workspace.value != null) return
        val saved = workspaceDataStore.treeUriFlow.first() ?: return
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == saved && it.isReadPermission
        }
        if (!hasPermission) {
            workspaceDataStore.clearTreeUri()
            return
        }
        openWorkspace(saved).onFailure { workspaceDataStore.clearTreeUri() }
    }

    override suspend fun readDocument(docUri: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(docUri))
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("无法打开文件")
            }
        }

    override suspend fun writeDocument(docUri: String, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                // "wt" 截断模式：新内容比旧内容短时不残留旧字节
                context.contentResolver.openOutputStream(Uri.parse(docUri), "wt")
                    ?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                    ?: error("无法写入文件（可能为只读）")
            }
        }

    override fun documentName(docUri: String): String {
        val raw = DocumentFile.fromSingleUri(context, Uri.parse(docUri))?.name ?: "未命名"
        return raw.substringBeforeLast('.', raw)
    }
}

/** DocumentFile 到 ScanEntry 的适配 */
private class DocumentFileEntry(private val file: DocumentFile) : ScanEntry {
    override val name: String? get() = file.name
    override val isDirectory: Boolean get() = file.isDirectory
    override val uri: String get() = file.uri.toString()
    override val lastModified: Long get() = file.lastModified()
    override fun children(): List<ScanEntry> = file.listFiles().map { DocumentFileEntry(it) }
}
```

- [ ] **Step 3.4: RepositoryModule.kt 加绑定**

import 区加：

```kotlin
import com.yumark.app.data.repository.WorkspaceRepositoryImpl
import com.yumark.app.domain.repository.WorkspaceRepository
```

类体内加：

```kotlin
@Binds @Singleton
abstract fun bindWorkspaceRepository(impl: WorkspaceRepositoryImpl): WorkspaceRepository
```

- [ ] **Step 3.5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

---

### Task 4: 设置项 defaultPreviewMode

**Files:**
- Modify: `app/src/main/java/com/yumark/app/domain/model/Models.kt`（UserSettings）
- Modify: `app/src/main/java/com/yumark/app/data/local/prefs/SettingsDataStore.kt`
- Modify: `app/src/main/java/com/yumark/app/presentation/settings/SettingsScreen.kt`

- [ ] **Step 4.1: UserSettings 加字段**

`Models.kt` 中 `UserSettings` 改为（在 `maxImageWidth` 后加一个字段）：

```kotlin
data class UserSettings(
    val lightThemeId: String = "default-light",
    val darkThemeId: String = "default-dark",
    val fontSize: Int = 16,
    val autoSaveEnabled: Boolean = true,
    val autoSaveInterval: Int = 30,
    val autoCompressImages: Boolean = true,
    val imageCompressionQuality: CompressionQuality = CompressionQuality.MEDIUM,
    val maxImageWidth: Int = 1920,
    val defaultPreviewMode: Boolean = true
)
```

- [ ] **Step 4.2: SettingsDataStore 读写新 key**

`Keys` object 加：

```kotlin
val DEFAULT_PREVIEW_MODE = booleanPreferencesKey("default_preview_mode")
```

`settingsFlow` 的 `UserSettings(...)` 构造里加：

```kotlin
defaultPreviewMode = prefs[Keys.DEFAULT_PREVIEW_MODE] ?: true
```

`updateSettings` 里加：

```kotlin
prefs[Keys.DEFAULT_PREVIEW_MODE] = settings.defaultPreviewMode
```

- [ ] **Step 4.3: SettingsScreen 加开关**

`SettingsViewModel` 加方法：

```kotlin
fun updateDefaultPreviewMode(on: Boolean) {
    viewModelScope.launch {
        repo.updateSettings(settings.value.copy(defaultPreviewMode = on))
    }
}
```

`SettingsScreen` 的 Column 中，"自动保存" ListItem 之后、`Divider()` + "自动压缩图片" 之前插入：

```kotlin
Divider()

ListItem(
    headlineContent = { Text("打开文档默认进入预览") },
    supportingContent = { Text(if (settings.defaultPreviewMode) "开启（空文档仍进入编辑）" else "关闭") },
    trailingContent = {
        Switch(
            checked = settings.defaultPreviewMode,
            onCheckedChange = { viewModel.updateDefaultPreviewMode(it) }
        )
    }
)
```

- [ ] **Step 4.4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

---

### Task 5: 路由扩展（docId / docUri 双参数）

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/navigation/Screen.kt`
- Modify: `app/src/main/java/com/yumark/app/presentation/navigation/YuMarkNavGraph.kt`
- Modify: `app/src/main/java/com/yumark/app/presentation/filelist/FileListScreen.kt`（两处 `"editor/$id"` 字面量）

保留导航参数名 `documentId` 不变（`EditorViewModel` 与既有测试依赖该 key），新增可选参数 `docUri`。

- [ ] **Step 5.1: Screen.kt**

```kotlin
package com.yumark.app.presentation.navigation

import java.net.URLEncoder

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object FileList : Screen("files")
    data object Editor : Screen("editor?documentId={documentId}&docUri={docUri}") {
        fun createRoute(documentId: String) = "editor?documentId=$documentId"
        fun createExternalRoute(docUri: String) =
            "editor?docUri=${URLEncoder.encode(docUri, "UTF-8")}"
    }
    data object Settings : Screen("settings")
}
```

- [ ] **Step 5.2: YuMarkNavGraph.kt 的 Editor composable 改为可选参数**

```kotlin
composable(
    route = Screen.Editor.route,
    arguments = listOf(
        navArgument("documentId") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        },
        navArgument("docUri") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        }
    )
) {
    EditorScreen(navController = navController)
}
```

- [ ] **Step 5.3: FileListScreen.kt 替换两处字面量路由**

第 77 行附近（抽屉内 `onDocumentClick`）：

```kotlin
navController.navigate(Screen.Editor.createRoute(it))
```

第 187 行附近（`DocumentCard` 的 `onClick`）：

```kotlin
onClick = { navController.navigate(Screen.Editor.createRoute(doc.id)) },
```

import 区加：

```kotlin
import com.yumark.app.presentation.navigation.Screen
```

- [ ] **Step 5.4: EditorViewModel documentId 改为可空（临时兼容，Task 6 完整改造）**

`EditorViewModel.kt` 第 32-33 行改为：

```kotlin
private val documentId: String? = savedStateHandle["documentId"]
private val docUri: String? = savedStateHandle["docUri"]
```

init 块的首次加载改为（先保证编译通过，双源逻辑 Task 6 实现）：

```kotlin
init {
    require(documentId != null || docUri != null) { "documentId or docUri required" }

    // 首次加载文档
    viewModelScope.launch {
        if (documentId != null) {
            val result = loadDocumentUseCase(documentId)
            result.onSuccess { doc ->
                _document.value = doc
                _uiState.value = EditorUiState.Success(doc)
                isDocumentDirty = false
            }.onFailure { e ->
                _uiState.value = EditorUiState.Error(e.message ?: "Document not found")
            }
        }
    }
    // ……（监听设置变化的协程保持不变）
}
```

- [ ] **Step 5.5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

---

### Task 6: EditorViewModel 双源加载 + 默认预览 + 大纲状态 + 保存错误流

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/EditorViewModel.kt`

- [ ] **Step 6.1: 注入 WorkspaceRepository，加大纲/保存错误状态**

构造函数改为：

```kotlin
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val loadDocumentUseCase: LoadDocumentUseCase,
    private val saveDocumentUseCase: SaveDocumentUseCase,
    private val loadSettingsUseCase: LoadSettingsUseCase,
    private val workspaceRepository: WorkspaceRepository,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
```

import 区加：

```kotlin
import com.yumark.app.domain.model.OutlineItem
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.repository.WorkspaceRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
```

状态区（`_cursorPosition` 之后）加：

```kotlin
private val _outline = MutableStateFlow<List<OutlineItem>>(emptyList())
val outline: StateFlow<List<OutlineItem>> = _outline.asStateFlow()

private val _saveError = MutableStateFlow<String?>(null)
val saveError: StateFlow<String?> = _saveError.asStateFlow()
```

- [ ] **Step 6.2: init 双源加载 + 默认预览**

init 的首次加载协程整体替换为：

```kotlin
init {
    require(documentId != null || docUri != null) { "documentId or docUri required" }

    // 首次加载文档（内部 Room 文档 或 外部 SAF 文档）
    viewModelScope.launch {
        val settings = loadSettingsUseCase()
        if (docUri != null) {
            workspaceRepository.readDocument(docUri).onSuccess { content ->
                val doc = Document.create(
                    id = "external",
                    name = workspaceRepository.documentName(docUri)
                ).copy(content = content)
                _document.value = doc
                _uiState.value = EditorUiState.Success(doc)
                isDocumentDirty = false
                applyDefaultPreview(settings, content)
            }.onFailure { e ->
                _uiState.value = EditorUiState.Error(e.message ?: "无法读取文件")
            }
        } else {
            loadDocumentUseCase(documentId!!).onSuccess { doc ->
                _document.value = doc
                _uiState.value = EditorUiState.Success(doc)
                isDocumentDirty = false
                applyDefaultPreview(settings, doc.content)
            }.onFailure { e ->
                _uiState.value = EditorUiState.Error(e.message ?: "Document not found")
            }
        }
    }

    // 监听设置变化（自动保存）—— 保持原有协程不变
    viewModelScope.launch {
        loadSettingsUseCase.observe().collect { settings ->
            if (settings.autoSaveEnabled) startAutoSave(settings.autoSaveInterval)
            else stopAutoSave()
        }
    }
}

/** 默认预览：设置开启且文档非空才进预览（空文档直接编辑，避免空白预览） */
private fun applyDefaultPreview(settings: UserSettings, content: String) {
    if (settings.defaultPreviewMode && content.isNotBlank()) {
        _isPreviewMode.value = true
    }
}
```

注意 `Document` 的 import 已存在（`com.yumark.app.domain.model.Document`）。

- [ ] **Step 6.3: saveDocument 双源分支 + 失败走 saveError 而非整页 Error**

`saveDocument()` 整体替换为：

```kotlin
fun saveDocument() {
    viewModelScope.launch {
        stateMutex.withLock {
            _document.value?.let { doc ->
                _isSaving.value = true
                val result = if (docUri != null) {
                    workspaceRepository.writeDocument(docUri, doc.content)
                } else {
                    saveDocumentUseCase(doc).map { }
                }
                result.onSuccess {
                    isDocumentDirty = false
                }.onFailure { e ->
                    // 保存失败不改变整页状态，编辑内容保留在内存
                    _saveError.value = e.message ?: "保存失败"
                }
                _isSaving.value = false
            }
        }
    }
}

fun clearSaveError() {
    _saveError.value = null
}
```

（`saveDocumentUseCase(doc)` 返回 `Result<Unit>`，`.map { }` 仅为类型统一保险，可直接省略——两分支均为 `Result<Unit>` 时直接用 if 表达式。）

- [ ] **Step 6.4: 大纲回调**

类内（`onCursorPositionChanged` 之后）加：

```kotlin
@Serializable
private data class OutlineItemDto(val level: Int, val text: String, val id: String)

private val outlineJson = Json { ignoreUnknownKeys = true }

/** WebView JS 渲染完成后回传的大纲（JSON 数组） */
fun onOutlineReceived(json: String) {
    runCatching {
        outlineJson.decodeFromString<List<OutlineItemDto>>(json)
    }.onSuccess { items ->
        _outline.value = items.map { OutlineItem(it.level, it.text, it.id) }
    }
}
```

注意：`@Serializable` 的嵌套 data class 放在伴生位置会触发 kapt 问题时，移到文件顶层 `private data class`。

- [ ] **Step 6.5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6.6: 跑全量既有单测确认无回归**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（既有 3 个测试类 + WorkspaceScannerTest 全部通过；若既有测试因 JUnit5 平台首次真正运行而暴露存量失败，记录失败原因——仅修复由本次改动引入的，存量失败报告给用户）

---

### Task 7: renderer.html 大纲收集 JS

**Files:**
- Modify: `app/src/main/assets/templates/renderer.html`

- [ ] **Step 7.1: renderMarkdown 末尾收集标题大纲**

在 `renderer.html` 中 Mermaid 渲染块（`mermaid.run({...})`）之后、`}`（`if (contentEl)` 闭合）之前插入：

```js
// 大纲收集：给标题分配锚点 id 并回传 Android
try {
    var headings = contentEl.querySelectorAll('h1,h2,h3,h4,h5,h6');
    var outline = [];
    for (var hi = 0; hi < headings.length; hi++) {
        var h = headings[hi];
        var hid = 'yumark-h-' + hi;
        h.id = hid;
        outline.push({
            level: parseInt(h.tagName.substring(1), 10),
            text: (h.textContent || '').trim(),
            id: hid
        });
    }
    if (window.Android && Android.onOutline) {
        Android.onOutline(JSON.stringify(outline));
    }
} catch(e) {
    log('Outline error: ' + e.message);
}
```

- [ ] **Step 7.2: 全局滚动函数**

在 `window.renderMarkdown = function(...){...};` 定义之后、`console.log('renderMarkdown function defined');` 之前加：

```js
window.scrollToHeading = function(id) {
    var el = document.getElementById(id);
    if (el) {
        el.scrollIntoView({behavior: 'smooth', block: 'start'});
    }
};
```

- [ ] **Step 7.3: 编译验证（资源打包）**

Run: `./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL

---

### Task 8: EditorScreen 右侧大纲抽屉 + JS 桥 + 保存错误 Snackbar

**Files:**
- Create: `app/src/main/java/com/yumark/app/presentation/editor/OutlinePanel.kt`
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 8.1: strings.xml 加字符串**

`</resources>` 前加：

```xml
<string name="outline">大纲</string>
<string name="outline_empty">没有检测到标题</string>
```

- [ ] **Step 8.2: OutlinePanel.kt**

```kotlin
package com.yumark.app.presentation.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.domain.model.OutlineItem

@Composable
fun OutlinePanel(
    outline: List<OutlineItem>,
    onItemClick: (OutlineItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxHeight()) {
        Text(
            text = stringResource(R.string.outline),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )
        Divider()
        if (outline.isEmpty()) {
            Text(
                text = stringResource(R.string.outline_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(outline) { item ->
                    Text(
                        text = item.text,
                        style = if (item.level <= 1) {
                            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onItemClick(item) }
                            .padding(
                                start = (16 + (item.level - 1) * 16).dp,
                                end = 16.dp,
                                top = 10.dp,
                                bottom = 10.dp
                            )
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 8.3: EditorScreen 整体接入右侧抽屉**

`EditorScreen.kt` 改动点（逐项）：

(a) import 区加：

```kotlin
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
```

(b) 函数体开头状态区（`var showMenu` 之后）加：

```kotlin
val outline by viewModel.outline.collectAsState()
val saveError by viewModel.saveError.collectAsState()
val outlineDrawerState = rememberDrawerState(DrawerValue.Closed)
val scope = rememberCoroutineScope()
val snackbarHostState = remember { SnackbarHostState() }
var previewWebView: WebView? by remember { mutableStateOf(null) }

// 保存失败 Snackbar
LaunchedEffect(saveError) {
    saveError?.let {
        snackbarHostState.showSnackbar(it)
        viewModel.clearSaveError()
    }
}
```

(c) 原 `Scaffold(...)` 整体用 RTL 抽屉包裹（右侧滑出）。结构：

```kotlin
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
                            previewWebView?.evaluateJavascript(
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
                topBar = { /* 原 TopAppBar，见 (d) */ }
            ) { padding ->
                /* 原内容不变 */
            }
        }
    }
}
```

(d) TopAppBar 的 actions 中，"编辑/预览切换" IconButton 之前加大纲按钮：

```kotlin
if (isPreviewMode) {
    IconButton(onClick = { scope.launch { outlineDrawerState.open() } }) {
        Icon(Icons.Default.FormatListBulleted, stringResource(R.string.outline))
    }
}
```

(e) 预览分支中原局部变量 `var webView: WebView? by remember { mutableStateOf(null) }` 删除，改用 (b) 中提升的 `previewWebView`；分支内所有 `webView` 引用改为 `previewWebView`（`derivedStateOf`、factory 末尾赋值 `previewWebView = this`、`LaunchedEffect` 内）。

(f) factory 中现有 `addJavascriptInterface(object { ... }, "Android")` 增加大纲回调方法：

```kotlin
addJavascriptInterface(object {
    @android.webkit.JavascriptInterface
    fun log(message: String) {
        android.util.Log.d("WebView", message)
    }

    @android.webkit.JavascriptInterface
    fun onOutline(json: String) {
        viewModel.onOutlineReceived(json)
    }
}, "Android")
```

注意：`onOutlineReceived` 内部只更新 StateFlow，线程安全（JS 桥回调在 WebView 线程）。

(g) 移除原 Scaffold 参数里没有的 snackbarHost 冲突——原 Scaffold 没有 snackbarHost，直接加即可。

- [ ] **Step 8.4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

---

### Task 9: 左侧栏双态（打开文件夹 + 工作区文件树）

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/filelist/FileListViewModel.kt`
- Create: `app/src/main/java/com/yumark/app/presentation/sidebar/WorkspaceFileTree.kt`
- Modify: `app/src/main/java/com/yumark/app/presentation/filelist/FileListScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 9.1: strings.xml 加字符串**

`</resources>` 前加：

```xml
<string name="open_folder">打开文件夹</string>
<string name="close_workspace">关闭文件夹</string>
<string name="refresh">刷新</string>
<string name="workspace_truncated">文件过多，仅显示前 2000 个文档</string>
<string name="workspace_reselect">重新选择</string>
```

- [ ] **Step 9.2: FileListViewModel 注入工作区**

构造函数加参数：

```kotlin
private val workspaceRepository: WorkspaceRepository,
```

import 加：

```kotlin
import com.yumark.app.domain.repository.WorkspaceRepository
```

状态区（`expandedFolders` 之后）加：

```kotlin
val workspace: StateFlow<Workspace?> = workspaceRepository.workspace

private val _workspaceError = MutableStateFlow<String?>(null)
val workspaceError: StateFlow<String?> = _workspaceError.asStateFlow()

private val _isWorkspaceLoading = MutableStateFlow(false)
val isWorkspaceLoading: StateFlow<Boolean> = _isWorkspaceLoading.asStateFlow()
```

（`Workspace` 由已有的 `import com.yumark.app.domain.model.*` 覆盖。）

init 块末尾加：

```kotlin
// 启动时恢复上次的工作区（授权失效会静默清除）
viewModelScope.launch { workspaceRepository.restoreOnLaunch() }
```

类内加方法：

```kotlin
fun openWorkspace(treeUri: String) {
    viewModelScope.launch {
        _isWorkspaceLoading.value = true
        workspaceRepository.openWorkspace(treeUri)
            .onFailure { _workspaceError.value = it.message ?: "打开文件夹失败" }
        _isWorkspaceLoading.value = false
    }
}

fun closeWorkspace() {
    viewModelScope.launch { workspaceRepository.closeWorkspace() }
}

fun rescanWorkspace() {
    viewModelScope.launch {
        _isWorkspaceLoading.value = true
        workspaceRepository.rescan()
            .onFailure { _workspaceError.value = it.message ?: "刷新失败" }
        _isWorkspaceLoading.value = false
    }
}

fun clearWorkspaceError() {
    _workspaceError.value = null
}

/** 工作区文件夹展开/收起（与内部文件夹共用 _expandedFolders，键为 uri） */
fun onWorkspaceFolderToggle(uri: String) {
    _expandedFolders.value = if (uri in _expandedFolders.value) {
        _expandedFolders.value - uri
    } else {
        _expandedFolders.value + uri
    }
}
```

- [ ] **Step 9.3: WorkspaceFileTree.kt**

```kotlin
package com.yumark.app.presentation.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yumark.app.domain.model.WorkspaceDoc
import com.yumark.app.domain.model.WorkspaceNode

/**
 * 外部工作区文件树（结构只读：不提供新建/重命名/删除）
 */
@Composable
fun WorkspaceFileTree(
    root: WorkspaceNode,
    expandedFolders: Set<String>,
    onDocumentClick: (WorkspaceDoc) -> Unit,
    onFolderToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(root.docs, key = { it.uri }) { doc ->
            WorkspaceDocRow(doc = doc, level = 0, onClick = { onDocumentClick(doc) })
        }
        items(root.folders, key = { it.uri }) { folder ->
            WorkspaceFolderItem(
                node = folder,
                level = 0,
                expandedFolders = expandedFolders,
                onDocumentClick = onDocumentClick,
                onFolderToggle = onFolderToggle
            )
        }
    }
}

@Composable
private fun WorkspaceFolderItem(
    node: WorkspaceNode,
    level: Int,
    expandedFolders: Set<String>,
    onDocumentClick: (WorkspaceDoc) -> Unit,
    onFolderToggle: (String) -> Unit
) {
    val isExpanded = expandedFolders.contains(node.uri)

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onFolderToggle(node.uri) }
                .padding(start = (level * 16).dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isExpanded) {
            node.docs.forEach { doc ->
                WorkspaceDocRow(doc = doc, level = level + 1, onClick = { onDocumentClick(doc) })
            }
            node.folders.forEach { child ->
                WorkspaceFolderItem(
                    node = child,
                    level = level + 1,
                    expandedFolders = expandedFolders,
                    onDocumentClick = onDocumentClick,
                    onFolderToggle = onFolderToggle
                )
            }
        }
    }
}

@Composable
private fun WorkspaceDocRow(
    doc: WorkspaceDoc,
    level: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (level * 16 + 24).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = doc.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
```

注意：`WorkspaceFolderItem` 递归部分渲染在普通 Column 中（与现有 `SidebarFileTree` 同模式），LazyColumn 仅作顶层容器。

- [ ] **Step 9.4: FileListScreen 抽屉双态 + 文件夹选择器**

(a) import 区加：

```kotlin
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.yumark.app.presentation.navigation.Screen
import com.yumark.app.presentation.sidebar.WorkspaceFileTree
```

(b) 函数体状态区（`drawerState` 之后）加：

```kotlin
val workspace by viewModel.workspace.collectAsState()
val workspaceError by viewModel.workspaceError.collectAsState()
val isWorkspaceLoading by viewModel.isWorkspaceLoading.collectAsState()
val context = LocalContext.current

val folderPickerLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocumentTree()
) { uri ->
    if (uri != null) {
        // 持久化读写授权，重启后仍有效
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        viewModel.openWorkspace(uri.toString())
    }
}
```

(c) `ModalDrawerSheet { ... }` 内容整体替换为双态：

```kotlin
ModalDrawerSheet {
    val ws = workspace
    if (ws == null) {
        // ===== 内部文档库模式（现状 + 打开文件夹入口）=====
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
                IconButton(onClick = { folderPickerLauncher.launch(null) }) {
                    Icon(Icons.Default.FolderOpen, stringResource(R.string.open_folder))
                }
                IconButton(onClick = { showFolderDialog = true }) {
                    Icon(Icons.Default.CreateNewFolder, stringResource(R.string.create_folder))
                }
            }
        }

        Divider()

        // 工作区错误提示条（如上次恢复失败）
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

        // 侧边栏文件树（原有代码保持不变）
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
        // ===== 工作区模式 =====
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

        Divider()

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
```

- [ ] **Step 9.5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9.6: 既有 FileListViewModelTest 适配**

`FileListViewModelTest.kt` 构造 `FileListViewModel` 处需补 `WorkspaceRepository` mock：

```kotlin
private val workspaceRepository: WorkspaceRepository = mockk(relaxed = true) {
    every { workspace } returns MutableStateFlow(null)
}
```

并在构造调用处传入（参数位置见 Step 9.2 的构造顺序）。

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS

---

### Task 10: 完整构建 + 手动验收

- [ ] **Step 10.1: 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部 PASS

- [ ] **Step 10.2: 完整构建**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，产物 `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 10.3: 手动验收清单（需真机/模拟器，报告给用户自测）**

1. 左侧抽屉 → 打开文件夹 → 选含 md 的文件夹 → 侧栏显示文件树（子文件夹可展开）
2. 点击 md 文档 → 直接进入预览模式（非编辑）
3. 预览中点顶栏大纲按钮 / 从右边缘滑入 → 右侧大纲，点击标题平滑滚动定位
4. 切到编辑模式改内容 → 保存 → 用其他文件管理器确认原文件已更新
5. 在外部新增一个 md 文件 → 抽屉刷新按钮 → 新文件出现
6. 杀进程重开应用 → 工作区自动恢复
7. 设置 → 关闭"默认预览" → 打开文档进编辑模式
8. 新建空文档 → 即使默认预览开启也进编辑模式

## Self-Review 结果

- **Spec 覆盖**：导入语义/单工作区/双态侧栏（Task 3、9）、大纲仅预览（Task 7、8）、默认预览+空文档例外+设置开关（Task 4、6）、错误处理（恢复失败提示条 Task 9.4、保存失败 Snackbar Task 6.3/8.3、截断提示 Task 9.4）、测试策略（Task 2 TDD、Task 9.6 回归）——全覆盖
- **占位符**：无 TBD/TODO；所有代码块完整
- **类型一致性**：`OutlineItem.anchorId`（Task 1）— Task 8 `item.anchorId` ✓；`WorkspaceRepository` 方法签名 Task 3 与 Task 6/9 调用一致 ✓；导航参数 key 保持 `documentId` 与既有 `savedStateHandle["documentId"]` 兼容 ✓
