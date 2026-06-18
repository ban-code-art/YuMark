# 云盘文件夹同步设计

- 日期：2026-06-18
- 项目：YuMark Android Markdown 编辑器
- 状态：设计稿，待评审
- 方案选择：混合方案。第一版使用 Android SAF 云盘文件夹；架构预留未来云盘 API Provider。

## 1. 背景

YuMark 当前已经有两套和外部文件相关的能力：

1. 外部文件夹工作区：通过 Android SAF `OpenDocumentTree()` 获取目录授权，直接扫描和读写用户选择的原始文件，不复制进 Room。
2. 导入库：通过导入文件或文件夹，把外部文件复制进 App 内部文档库，后续由 Room 和 `filesDir/documents` 管理。

云盘同步不能复用导入库语义。导入库会复制文件，复制后与原文件脱钩，不会自然同步到云盘。云同步应基于外部文件夹工作区：文件留在云盘 App 暴露的目录中，YuMark 直接读写原文件，云盘 App 负责上传、下载和跨设备同步。

## 2. 目标

第一版目标：

- 用户可以选择一个云盘 App 暴露出来的文件夹作为“云同步工作区”。
- YuMark 显示该文件夹内的 Markdown 文件树。
- 用户打开、编辑、保存文件时，直接读写云盘文件夹中的原文件。
- 云盘 App 负责真正的云端同步，YuMark 不实现网络上传下载。
- YuMark 在保存前做远端变更检测，避免静默覆盖其他设备的修改。
- 用户可以手动刷新文件树，查看云盘目录最新状态。
- 权限失效、云盘离线、文件不可写等情况有明确提示。
- 代码结构预留 `CloudSyncProvider` 边界，未来可接 Google Drive / OneDrive 等 API 同步。

## 3. 非目标

第一版不做：

- 不直接接入 Google Drive、OneDrive、坚果云等私有 API。
- 不实现登录、OAuth、token 刷新或云端增量同步队列。
- 不做后台常驻同步服务。
- 不做多设备实时协作。
- 不自动合并冲突。
- 不把云盘文件复制进导入库。
- 不支持多个云同步工作区同时打开。
- 不扫描用户未授权的云盘目录。

## 4. 关键约束

Android SAF 对云盘 Provider 的能力不稳定：

- 有些云盘 Provider 不支持可靠的 `lastModified`。
- 有些 Provider 需要联网或本地缓存就绪才能读取文件。
- 有些 Provider 写入时会延迟上传。
- 有些 Provider 对目录枚举、文件大小、修改时间返回空值或旧值。
- App 无法直接知道云端是否已经上传完成。

因此第一版同步状态不能承诺“云端已完成同步”。YuMark 只能承诺：

- 本地写入 SAF Provider 成功。
- 文件树已按当前 Provider 可见状态刷新。
- 保存前做了尽力而为的冲突检测。

## 5. 推荐用户模型

新增术语：

- 外部工作区：普通 SAF 文件夹，直接读写原文件。
- 云同步工作区：外部工作区的一种，通常来自云盘 Provider，增加同步提示、刷新、冲突检测和状态展示。
- 导入库：复制到 App 内部的文档集合，不参与云同步。

用户看到的入口：

- 文件列表页：打开云盘文件夹。
- 设置页：默认云同步文件夹。
- 侧栏：当前云同步工作区名称、刷新按钮、状态提示。
- 编辑器：保存状态、远端变更冲突提示。

## 6. 总体架构

第一版架构以 SAF 为主线：

```text
UI
  FileListScreen / SettingsScreen / EditorScreen / WorkspaceFileTree

Presentation
  FileListViewModel
  EditorViewModel

Domain
  WorkspaceRepository
  CloudWorkspaceRepository facade or extended WorkspaceRepository methods
  WorkspaceMetadata models
  ExternalChangeDetector

Data
  WorkspaceRepositoryImpl
  WorkspaceDataStore
  SAF ContentResolver / DocumentsContract

Provider
  Android DocumentsProvider
  Google Drive / OneDrive / other cloud app SAF provider
```

未来 API Provider 预留：

```text
CloudSyncProvider
  SafCloudSyncProvider       // 第一版唯一实现
  GoogleDriveSyncProvider   // 未来
  OneDriveSyncProvider      // 未来
```

第一版可以不创建完整 API Provider 实现，但数据模型和命名需要避免把“云同步”写死成 SAF 唯一路径。

## 7. 数据模型

### 7.1 Workspace 类型

扩展 `Workspace`：

```kotlin
enum class WorkspaceKind {
    LOCAL_FOLDER,
    CLOUD_FOLDER
}

data class Workspace(
    val name: String,
    val treeUri: String,
    val root: WorkspaceNode,
    val truncated: Boolean = false,
    val kind: WorkspaceKind = WorkspaceKind.LOCAL_FOLDER,
    val providerAuthority: String? = null,
    val syncStatus: WorkspaceSyncStatus = WorkspaceSyncStatus.Unknown
)
```

`WorkspaceKind.CLOUD_FOLDER` 的判断不应只靠包名白名单。第一版采用用户入口决定：

- 用户点“打开文件夹”进入的是普通外部工作区。
- 用户点“打开云盘文件夹”进入的是云同步工作区。

同时保存 `providerAuthority` 作为诊断和后续 Provider 适配依据。

### 7.2 Workspace 持久化

`WorkspaceDataStore` 新增：

```text
cloud_workspace_tree_uri
cloud_workspace_display_name
cloud_workspace_provider_authority
default_cloud_workspace_enabled
```

与现有 key 的关系：

- `workspace_tree_uri`：当前会话外部工作区。
- `default_dir_uri`：默认普通目录。
- `cloud_workspace_tree_uri`：默认云同步目录。

启动恢复优先级建议：

1. 如果用户启用了默认云同步工作区，优先恢复 `cloud_workspace_tree_uri`。
2. 否则恢复 `default_dir_uri`。
3. 否则恢复上次 `workspace_tree_uri`。

恢复失败时不清空用户配置，先提示“权限失效或云盘不可用”，提供“重新选择”和“忽略本次”。

### 7.3 外部文档快照

编辑器打开外部文档时记录快照：

```kotlin
data class ExternalDocumentSnapshot(
    val uri: String,
    val displayName: String,
    val lastModified: Long?,
    val size: Long?,
    val contentHash: String,
    val capturedAt: Long
)
```

保存成功后刷新快照。`lastModified` 和 `size` 可为空，冲突检测以 hash 为最终依据。

### 7.4 文档元数据

新增轻量元数据查询结果：

```kotlin
data class ExternalDocumentStat(
    val uri: String,
    val displayName: String,
    val lastModified: Long?,
    val size: Long?,
    val canRead: Boolean,
    val canWrite: Boolean
)
```

## 8. 核心流程

### 8.1 打开云盘文件夹

流程：

1. 用户点击“打开云盘文件夹”。
2. App 展示说明：请选择云盘 App 中需要同步的 Markdown 文件夹。
3. 调起 `OpenDocumentTree()`。
4. 用户选择目录后，UI 获取读写持久授权。
5. 调用 `workspaceRepository.openCloudWorkspace(treeUri)`。
6. 扫描目录，构建文件树。
7. 保存 `cloud_workspace_tree_uri` 和 provider 信息。
8. 侧栏展示云同步工作区。

失败处理：

- 无读权限：提示重新选择。
- 无写权限：允许只读打开，但保存按钮显示只读状态。
- Provider 无法枚举：提示云盘不可用或未完成本地缓存。
- 扫描超限：沿用 `WorkspaceScanner.truncated`，提示只显示部分内容。

### 8.2 打开云盘文档

流程：

1. 用户点击文件树中的文档。
2. `EditorViewModel` 调用 `workspaceRepository.readDocument(uri)`。
3. 同时查询 `ExternalDocumentStat`。
4. 计算内容 hash，形成 `ExternalDocumentSnapshot`。
5. 编辑器进入正常编辑状态。

如果读取失败：

- 文件被删除：提示文件不存在，返回文件树并建议刷新。
- 云盘离线：提示云盘暂不可用。
- 编码异常：提示仅支持 UTF-8 Markdown 文本。

### 8.3 保存云盘文档

保存前必须做冲突检测：

1. 如果文档未 dirty，直接返回。
2. 查询当前远端 stat。
3. 如果 stat 明确无变化，直接写入。
4. 如果 stat 不可靠或疑似变化，重新读取当前远端内容并计算 hash。
5. 对比打开时快照 hash。
6. 如果远端 hash 未变化，写入新内容。
7. 如果远端 hash 已变化，进入冲突流程。
8. 写入成功后重新读取 stat 和 hash，更新快照。

判断规则：

```text
snapshot.contentHash == currentRemoteHash
  -> 可以保存

snapshot.contentHash != currentRemoteHash
  -> 远端已变化，不能静默覆盖
```

`lastModified` 和 `size` 只用于快速判断，不作为唯一依据。

### 8.4 冲突处理

当远端已变化时，弹出冲突对话框：

标题：云盘文件已在其他位置修改

说明：

- 当前编辑内容尚未保存。
- 云盘中的文件已有新版本。
- 请选择如何处理。

操作：

1. 重新加载云端版本：丢弃本地未保存内容，读取最新远端文件。
2. 覆盖云端版本：把当前编辑内容写回原文件。
3. 另存为副本：在同目录创建 `原文件名 - 本地副本.md`，写入当前内容。
4. 取消：回到编辑器，不写入。

默认高亮“另存为副本”，避免误覆盖。

### 8.5 手动刷新

侧栏提供刷新按钮：

1. 调用 `workspaceRepository.rescan()`。
2. 重新查询文件树。
3. 保持已展开文件夹状态。
4. 如果当前打开文件在刷新后不存在，编辑器提示“文件可能已被移动或删除”。

第一版不做后台轮询。可选增强是在编辑器恢复前台时做一次轻量 stat 检查，但不自动 reload。

## 9. API 设计

### 9.1 WorkspaceRepository 扩展

建议新增方法：

```kotlin
suspend fun openCloudWorkspace(treeUri: String): Result<Workspace>
suspend fun setDefaultCloudWorkspace(treeUri: String): Result<Workspace>
suspend fun clearDefaultCloudWorkspace()
suspend fun documentStat(docUri: String): Result<ExternalDocumentStat>
suspend fun readDocumentWithSnapshot(docUri: String): Result<Pair<String, ExternalDocumentSnapshot>>
suspend fun writeDocumentWithConflictCheck(
    snapshot: ExternalDocumentSnapshot,
    newContent: String
): Result<ExternalWriteResult>
suspend fun createSiblingDocument(
    sourceDocUri: String,
    fileName: String,
    content: String
): Result<String>
```

`ExternalWriteResult`：

```kotlin
sealed class ExternalWriteResult {
    data class Saved(val snapshot: ExternalDocumentSnapshot) : ExternalWriteResult()
    data class Conflict(
        val snapshot: ExternalDocumentSnapshot,
        val remoteSnapshot: ExternalDocumentSnapshot
    ) : ExternalWriteResult()
}
```

如果担心接口膨胀，可以将冲突检测拆到独立 `ExternalDocumentSyncUseCase`：

```kotlin
class ExternalDocumentSyncUseCase(
    private val workspaceRepository: WorkspaceRepository
)
```

推荐拆 use case，避免 `WorkspaceRepositoryImpl` 同时承担扫描、读写、冲突策略和 UI 语义。

### 9.2 CloudSyncProvider 预留

第一版接口只定义，不接具体 API：

```kotlin
interface CloudSyncProvider {
    val id: String
    val displayName: String
    suspend fun openWorkspace(config: CloudWorkspaceConfig): Result<Workspace>
    suspend fun stat(uri: String): Result<ExternalDocumentStat>
    suspend fun read(uri: String): Result<ByteArray>
    suspend fun write(uri: String, bytes: ByteArray): Result<Unit>
}
```

第一版实现：

```kotlin
class SafCloudSyncProvider : CloudSyncProvider
```

但实际可先由 `WorkspaceRepositoryImpl` 承担 SAF 实现，待未来 API 接入时再抽 Provider。设计上保留模型名称和边界即可。

## 10. UI 设计

### 10.1 文件列表页

入口：

- 打开文件夹
- 打开云盘文件夹
- 导入文件
- 导入文件夹

“打开云盘文件夹”点击前提示：

```text
请选择云盘 App 中用于同步的文件夹。
YuMark 会直接读写该文件夹中的 Markdown 文件；上传和下载由云盘 App 完成。
```

### 10.2 设置页

新增设置项：

```text
云同步文件夹
当前：未设置 / Drive: YuMark Notes
[选择] [清除]
```

可选开关：

```text
启动时自动打开云同步文件夹
```

默认开启。

### 10.3 侧栏

云同步工作区顶部显示：

```text
云同步：YuMark Notes
状态：已加载 / 权限失效 / 云盘不可用 / 文件树可能已过期
[刷新]
```

状态文案要克制，不能显示“已同步到云端”，因为 App 无法确认云端上传完成。

### 10.4 编辑器

保存区域状态：

- 保存中
- 已保存到云盘文件夹
- 保存失败
- 检测到云端变更
- 只读文件

冲突对话框必须阻止静默覆盖。

## 11. 错误处理

| 场景 | 处理 |
|---|---|
| 持久 URI 权限失效 | 保留配置，提示重新授权 |
| 云盘 Provider 离线 | 显示云盘不可用，提供重试 |
| 文件被删除 | 提示文件不存在，建议刷新文件树 |
| 文件只读 | 编辑器可读，保存禁用或保存时报错 |
| 保存前远端变化 | 弹冲突对话框 |
| 创建副本失败 | 保留本地编辑内容，提示用户另选处理方式 |
| 扫描目录过大 | 复用 truncated 提示，建议缩小文件夹范围 |
| Provider 返回空 lastModified | 使用 hash 检测，不阻塞功能 |

## 12. 测试策略

### 12.1 单元测试

- `WorkspaceRepositoryImplTest`
  - open cloud workspace saves cloud config
  - document stat maps nullable metadata
  - read document creates snapshot
  - write without remote change succeeds
  - write with remote hash change returns conflict
  - create sibling copy uses sanitized filename

- `EditorViewModelTest`
  - external cloud doc load stores snapshot
  - save dirty cloud doc calls conflict-check path
  - conflict result exposes dialog state
  - overwrite conflict writes current content
  - reload remote discards local dirty content only after user confirms

- `FileListViewModelTest`
  - open cloud workspace updates workspace state
  - restore launch prefers default cloud workspace when enabled
  - refresh failure surfaces action error without clearing existing tree

### 12.2 Fake Provider 测试

抽象 `DocumentTreeGateway` 以便单元测试，不直接依赖 Android `ContentResolver`：

```kotlin
interface DocumentTreeGateway {
    fun openTree(treeUri: String): Result<TreeHandle>
    fun listChildren(directoryUri: String): Result<List<DocumentEntry>>
    fun stat(uri: String): Result<ExternalDocumentStat>
    fun readText(uri: String): Result<String>
    fun writeText(uri: String, content: String): Result<Unit>
    fun createSibling(sourceUri: String, fileName: String, content: String): Result<String>
}
```

生产实现用 SAF，测试用内存 fake。

### 12.3 手动验证

设备上至少验证：

- Google Drive 文件夹选择、读取、保存。
- OneDrive 或其他云盘 Provider 读取失败/只读场景。
- 同一文件在另一设备修改后，本设备保存时出现冲突。
- 云盘 App 离线或未登录时的错误提示。
- App 重启后默认云同步目录恢复。

## 13. 兼容边界

必须保持：

- 内部 Room 文档库不受影响。
- 导入库仍然是复制语义，不自动同步云盘。
- 普通外部工作区仍可使用，不强制变成云同步工作区。
- `WorkspaceFileTree` 仍保持结构只读，不在第一版提供云盘文件删除、重命名、新建目录。
- 保存外部文档时继续使用不可取消写入，避免截断后协程取消造成半文件。

## 14. 分阶段落地

### 阶段 1：云同步工作区标识和恢复

- 增加 `WorkspaceKind`。
- 增加 cloud workspace DataStore key。
- 增加“打开云盘文件夹”和“默认云同步文件夹”入口。
- 恢复启动优先级。

### 阶段 2：文档快照和冲突检测

- 增加 `ExternalDocumentSnapshot`。
- 打开外部文档时记录 hash。
- 保存前比较远端 hash。
- 返回 conflict 而不是覆盖。

### 阶段 3：冲突 UI 和副本保存

- 编辑器展示冲突对话框。
- 支持重新加载、覆盖、另存为副本、取消。
- 支持同目录创建副本文档。

### 阶段 4：刷新和状态打磨

- 侧栏刷新按钮。
- 云同步状态提示。
- 权限失效、Provider 不可用提示。
- 手动验证主流云盘 Provider。

### 阶段 5：Provider 抽象预留

- 如果阶段 1-4 后需求继续扩大，再抽 `CloudSyncProvider`。
- 第一版不为抽象而抽象，避免过早复杂化。

## 15. 设计取舍

选择混合方案的理由：

- SAF 云盘文件夹能最快满足“云盘同步”的核心体验。
- 不需要登录授权和维护各家云盘 API。
- 与当前外部工作区架构天然匹配。
- 通过快照和 hash 冲突检测解决最重要的数据安全问题。
- Provider 边界保留未来扩展空间，但不在第一版承担过度复杂度。

最大风险：

- 云盘 Provider 行为不可控，无法保证上传完成状态。
- `lastModified` 不可靠会增加读取 hash 的开销。
- 大文件保存前读取远端内容会慢。

风险控制：

- 不显示“云端已同步”，只显示“已保存到云盘文件夹”。
- 保存前冲突检测以 hash 为准。
- 大文件可后续增加阈值和提示。
- 第一版保留手动刷新，不做后台同步。

## 16. 待评审决策

1. 云同步工作区是否在启动时优先于普通默认目录恢复？推荐是。
2. 冲突对话框默认按钮是否为“另存为副本”？推荐是。
3. 第一版是否允许只读云盘文件打开？推荐允许读取，保存时提示只读。
4. 是否在第一版实现 `CloudSyncProvider` 接口？推荐暂不实现完整接口，只保留模型边界和命名。
5. 云同步入口文案是否明确“不保证云端上传完成，由云盘 App 负责”？推荐明确写出。

