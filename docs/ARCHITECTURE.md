# YuMark 技术文档

## 架构概览

YuMark 采用 Clean Architecture + MVVM 模式，分为三层：

### 1. Presentation Layer (表现层)
**技术栈**: Jetpack Compose + ViewModel + Hilt

**职责**:
- UI 渲染
- 用户交互处理
- 状态管理

**文件结构**:
```
presentation/
├── theme/          # Material 3 主题
├── navigation/     # 导航路由
├── editor/         # 编辑器 UI
├── filelist/       # 文件列表 UI
└── settings/       # 设置页面
```

### 2. Domain Layer (领域层)
**技术栈**: Pure Kotlin (无 Android 依赖)

**职责**:
- 业务逻辑
- 数据模型
- Repository 接口定义

**核心概念**:
- **Model**: 领域实体 (Document, Folder, Image)
- **UseCase**: 单一职责的业务操作
- **Repository**: 数据访问抽象接口

### 3. Data Layer (数据层)
**技术栈**: Room + DataStore + File I/O

**职责**:
- 数据持久化
- Repository 接口实现
- 数据源管理

**组件**:
- **Room Database**: SQLite 封装，存储文档元数据
- **FileManager**: 管理 Markdown 文件内容
- **DataStore**: 存储用户设置
- **Mapper**: Entity ↔ Domain Model 转换

---

## 核心模块详解

### WebView 渲染引擎

**文件**: `core/webview/MarkdownRenderer.kt`

**工作流程**:
1. 加载 HTML 模板 (`renderer.html`)
2. 注入 JavaScript 库 (Marked, KaTeX, Mermaid, Prism)
3. 通过 JsBridge 在 Kotlin ↔ JS 间通信
4. Markdown → HTML 转换在 WebView 中完成

**关键 API**:
```kotlin
fun renderMarkdown(markdown: String, themeId: String)
fun setOnRenderCompleteListener(listener: () -> Unit)
fun setOnLinkClickListener(listener: (String) -> Unit)
```

### 数据库设计

**表结构**:

```sql
-- documents 表
CREATE TABLE documents (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    folder_id TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    is_favorite INTEGER NOT NULL,
    word_count INTEGER NOT NULL,
    character_count INTEGER NOT NULL,
    FOREIGN KEY(folder_id) REFERENCES folders(id) ON DELETE SET NULL
);

-- folders 表
CREATE TABLE folders (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    parent_id TEXT,
    created_at INTEGER NOT NULL,
    order INTEGER NOT NULL,
    FOREIGN KEY(parent_id) REFERENCES folders(id) ON DELETE CASCADE
);

-- images 表
CREATE TABLE images (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL,
    file_name TEXT NOT NULL,
    file_path TEXT NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    file_size INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
);
```

### 依赖注入

**Hilt 模块**:

1. **DatabaseModule**: 提供 Room Database 和 DAO
2. **RepositoryModule**: 绑定 Repository 接口到实现

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun bindDocumentRepository(
        impl: DocumentRepositoryImpl
    ): DocumentRepository
}
```

---

## 关键功能实现

### 自动保存

**位置**: `EditorViewModel`

```kotlin
private fun startAutoSave(intervalSec: Int) {
    autoSaveJob = viewModelScope.launch {
        while (isActive) {
            delay(intervalSec * 1000L)
            saveDocument()
        }
    }
}
```

### 图片压缩

**位置**: `ImageRepositoryImpl.saveImage()`

**算法**:
1. 检查图片宽度是否超过 `maxImageWidth`
2. 计算缩放比例
3. 使用 `Bitmap.createScaledBitmap()` 缩放
4. 根据 `compressionQuality` 压缩 JPEG/PNG

### 文件夹树构建

**位置**: `FolderRepositoryImpl.buildFolderTree()`

**递归算法**:
```kotlin
private suspend fun buildFolderTree(parentId: String?): FolderTree {
    val folder = parentId?.let { folderDao.getById(it) }
    val children = folderDao.getByParent(parentId)
        .map { buildFolderTree(it.id) }  // 递归
    val documentCount = documentDao.getByFolder(parentId).size
    return FolderTree(folder, children, documentCount)
}
```

---

## 测试策略

### 单元测试

**覆盖范围**:
- UseCase 业务逻辑
- Repository 数据访问
- ViewModel 状态管理

**工具**:
- JUnit 5
- MockK (Kotlin mocking)
- Truth (assertions)
- Turbine (Flow testing)

### 示例

```kotlin
@Test
fun `saveDocument updates word count`() = runTest {
    val doc = Document.create("id", "Test")
        .copy(content = "Hello World")
    
    val result = saveDocumentUseCase(doc)
    
    assertThat(result.isSuccess).isTrue()
    coVerify { repo.saveDocument(match { it.wordCount == 2 }) }
}
```

---

## 性能优化

### 已实现

1. **LazyColumn**: 文件列表虚拟滚动
2. **Flow + StateFlow**: 响应式数据流
3. **图片压缩**: 减少存储空间
4. **索引**: Room 数据库字段索引

### 待优化

1. **WebView 预加载**: 提前初始化 WebView
2. **增量渲染**: 只重新渲染变更部分
3. **分页加载**: 文件列表分页
4. **后台任务**: 使用 WorkManager 清理孤立图片

---

## 扩展点

### 添加新的导出格式

1. 在 `ExportFormat` enum 添加新格式
2. 实现 `Exporter` 接口
3. 在 `ExportDocumentUseCase` 添加分支

### 自定义主题

1. 在 `assets/themes/` 添加 CSS 文件
2. 更新 `EditorTheme` 模型
3. 在 `SettingsScreen` 添加主题选择器

### 插件系统 (规划中)

- 定义 `Plugin` 接口
- 实现插件加载器
- 支持自定义 Markdown 语法
- 允许第三方扩展编辑器功能
