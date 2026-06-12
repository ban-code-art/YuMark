# 功能设计：默认目录 + 手动选择文件夹 + 导入收纳库

- 日期：2026-06-12
- 项目：YuMark（Android Typora 风格 Markdown 编辑器）
- 状态：待评审（本文档为需求 + 设计，未开始编码）
- 关联现状文档：`docs/superpowers/specs/2026-06-11-folder-workspace-outline-design.md`

---

## 一、背景与用户需求

用户在使用中提出三项新需求，均为在**不破坏现有功能**的前提下新增：

1. **默认目录**：设置界面新增一项「默认目录」，可选择手机内的一个文件夹；以后再次打开 App 时**自动加载到这个目录**（直接显示其文件树，无需手动打开侧栏再选）。
2. **手动选择文件夹**：当前选择文件夹时，系统选择器「进入某文件夹即视为已选中」，缺少一个明确的「选择此文件夹」确认步骤；当文件夹多层嵌套时很不方便。需要改成**逐层浏览 + 手动确认选择**。
3. **导入收纳库**：导入进来的文件放进一个**专门的收纳文件树**统一管理，可在该树中打开或删除文件，也可导入多个文件夹；导入时同样要能**手动挑选具体导入哪个文件/文件夹**，而不是自动导入。

### 用户确认的关键决策

| 决策点 | 用户选择 | 说明 |
|---|---|---|
| 需求 1 启动行为 | 自动加载到上次/默认目录，**不弹出侧栏** | 启动即把默认目录的文件树准备好，进侧栏即可见 |
| 需求 2 痛点本质 | 系统选择器**缺确认步骤** | 进文件夹就自动选中，嵌套深时易误选 |
| 需求 3 与现有工作区的关系 | **全新独立的导入库** | 收纳库与「外部文件夹工作区」是两套独立体系，互不替代 |

---

## 二、现状摘要（与本需求相关）

- **外部文件夹工作区**（已有）：`WorkspaceRepository` + `WorkspaceDataStore`，单工作区模型。通过 `ActivityResultContracts.OpenDocumentTree()` 选文件夹，`takePersistableUriPermission` 持久授权，SAF 实时关联，**不复制文件、直接读写原文件**。treeUri 存 DataStore，启动时 `FileListViewModel.init` 调 `workspaceRepository.restoreOnLaunch()` 恢复。
- **导入**（已有）：`ImportDocumentUseCase`（`domain/usecase/import/`）支持 `.md` / `.txt` / `.markdown`，把外部文件内容**复制**进 Room（`createDocument` + `saveDocument`），落 `filesDir/documents/<uuid>.md`。但目前 **UI 上没有导入入口**（`FileListViewModel.exportDocument` 还是 `/* TODO */`，import 同样未接线）。
- **设置**：`UserSettings`（`domain/model/Models.kt`）+ `SettingsDataStore`，新增项即加字段 + DataStore key + `SettingsScreen` UI 三处。
- **侧栏**：`FileListScreen` 抽屉双态——无工作区显示内部文档库 + `SidebarFileTree`；有工作区显示 `WorkspaceFileTree`（结构只读）。
- **内部文档库**：Room 存元数据，`Folder` 模型有 `parentId` 支持层级，`ManageFoldersUseCase` 管理文件夹 CRUD。

### 一个绕不开的 Android SAF 约束（影响需求 2）

Android 自 10 起强制分区存储。App **无法在未授权的情况下用绝对路径遍历任意文件夹**。能拿到目录访问权的标准途径只有两条：

- `OpenDocumentTree()`：选**文件夹**（树授权）——就是现在「自动选中」的那个。
- `OpenDocument()` / `OpenMultipleDocuments()`：选**文件**（单文件授权），可多选，每个文件单独确认。

也就是说，「在 App 内自己画一个文件浏览器、逐层点进任意目录」这件事，**前提仍然是先拿到某个根目录的树授权**；拿不到授权就无法列出目录内容。下文方案会针对这一约束给出两种可选实现。

---

## 三、需求 1：设置中的「默认目录」

### 目标

- 设置界面新增「默认目录」项，显示当前默认目录名（未设置时显示「未设置」）。
- 点击 → 选择一个文件夹 → 记为默认目录。
- 启动 App 时自动加载该目录文件树到侧栏（不主动弹出侧栏）。
- 可清除默认目录，恢复为「无默认目录」。

### 与现有 `restoreOnLaunch` 的关系

现状已经做了「重启恢复上次工作区」。本需求是把它**显式化、可控化**：

- 现状：上次打开过哪个工作区，下次就自动恢复（隐式，用户无法在设置里查看或更改）。
- 目标：用户在设置里**显式指定**一个默认目录；这个目录就是启动时要恢复的工作区。

两者可以合二为一：把「默认目录的 treeUri」作为权威来源，启动恢复读它。

### 设计

1. **存储**：在 `WorkspaceDataStore` 复用现有 `workspace_tree_uri`，或新增一个语义更清晰的 key `default_dir_uri`。建议**复用现有 key**（默认目录 == 启动恢复的工作区），避免两个 URI 状态不一致。
   - 若希望「默认目录」与「当前临时打开的工作区」区分（临时打开别的文件夹不改变默认），则需两个 key：`default_dir_uri`（设置里指定，启动恢复用）+ `workspace_tree_uri`（当前会话打开的，可临时不同）。**推荐后者**，语义更贴合「默认目录」四个字。

2. **设置 UI**（`SettingsScreen`）：新增 ListItem
   ```
   默认目录
   <当前目录名 / 未设置>          [选择] [清除]
   ```
   点「选择」走需求 2 的文件夹选择流程；点「清除」清空 `default_dir_uri`。

3. **启动行为**（`FileListViewModel.init`）：`restoreOnLaunch()` 改为优先读 `default_dir_uri`。恢复成功 → `workspace` StateFlow 置位 → 侧栏文件树就绪（但**不调用 `drawerState.open()`**，符合「不弹出侧栏」）。

4. **设置项落点**：`UserSettings` 不一定要加字段（URI 属于工作区状态，更适合放 `WorkspaceDataStore`）。设置界面通过 ViewModel 读 `WorkspaceDataStore.defaultDirUriFlow` 显示当前目录名。

### 边界

- 默认目录授权失效（文件夹被删 / 撤销授权）：启动恢复静默失败，侧栏顶部显示提示条 + 「重新选择」（沿用现有 `workspaceError` 机制）。
- 设备无默认目录（首次安装）：设置显示「未设置」，启动不加载任何工作区，回到内部文档库视图。

---

## 四、需求 2：手动选择文件夹（替代「自动选中」）

### 痛点复述

`OpenDocumentTree()` 拉起的系统选择器，用户点进一个文件夹后顶部有「使用此文件夹」按钮，但**很多 ROM 上当前所在目录即被视为待选**，层级一深容易误选父目录或选错。用户希望「逐层进入 + 一个明确的『选择此文件夹』动作」。

### 两种实现方案

#### 方案 A：保留系统选择器，优化引导（改动小、零授权风险）

- 仍用 `OpenDocumentTree()`，但在拉起前用一个对话框/提示说明：「请逐层进入目标文件夹，然后点右上角『使用此文件夹 / SELECT』」。
- 选择回调里把选中的文件夹名回显给用户确认：「已选择『<名称>』，确认作为默认目录？」，确认后才 `takePersistableUriPermission` + 保存。
- **优点**：完全不碰 SAF 授权边界，最稳；**缺点**：没有真正解决「系统选择器交互不直观」，只是加了确认。

#### 方案 B：App 内置文件夹浏览器（体验最佳、改动大）

- 先用 `OpenDocumentTree()` 让用户**授权一个根目录**（如手机存储根、或 Download 目录），仅授权一次。
- 之后在 App 内用 `DocumentFile.fromTreeUri(root).listFiles()` 自绘一个浏览器：列出子文件夹，点击逐层进入，**每一层底部固定一个「选择此文件夹」按钮**，点了才确认选中当前层。
- 复用现有 `WorkspaceScanner` / `DocumentFile` 能力，技术上完全可行。
- **优点**：完全满足「逐层浏览 + 显式确认」，且可同一界面服务于需求 1（选默认目录）和需求 3（选导入源）；**缺点**：需新建一个浏览器 Composable + ViewModel，且受限于「只能浏览已授权根目录的子树」——用户首次仍要过一次系统授权。

### 推荐

**方案 B**，因为它同时是需求 1「选默认目录」和需求 3「选导入文件夹」的统一入口，一次投入三处受益。首次授权根目录后，后续所有「选文件夹」操作都在 App 内完成，体验一致。

### 新增组件（方案 B）

| 组件 | 位置 | 职责 |
|---|---|---|
| `FolderPickerScreen` | `presentation/picker/` | App 内文件夹浏览器，逐层进入 + 底部「选择此文件夹」 |
| `FolderPickerViewModel` | `presentation/picker/` | 持有当前浏览路径栈，调 `DocumentFile.listFiles()` 列目录 |
| 路由 `picker?rootUri=...&mode=...` | `Screen.kt` / `YuMarkNavGraph.kt` | `mode` 区分「设为默认目录」/「导入到收纳库」 |

浏览器只列**文件夹**（选目录场景）或**文件夹 + 可勾选文件**（导入场景，见需求 3）。

---

## 五、需求 3：导入收纳库

### 定位

一个**全新、独立**的导入库，与「外部文件夹工作区」并存：

| | 外部文件夹工作区 | 导入收纳库（本需求） |
|---|---|---|
| 文件位置 | 留在原位（SAF 实时关联） | **复制进 App 内部存储** |
| 生命周期 | 关闭工作区即断开 | 一直保留，直到用户删除 |
| 可否删除文件 | 否（结构只读） | **是**（在树中删除） |
| 数据存储 | 内存扫描，不入库 | Room（复用现有 Document/Folder 体系） |
| 典型用途 | 浏览/编辑手机里现有的笔记目录 | 把零散文件「收进」App 长期管理 |

### 导入语义：复制，而非关联

收纳库的文件**复制**进 App 内部存储（`filesDir/documents/`），原文件不动。这样：

- 用户可在树中删除而不影响原文件。
- 不依赖 SAF 授权长期有效（复制后即与原文件解耦）。
- 直接复用现有 `ImportDocumentUseCase`（已实现内容读取 + 落 Room）。

### 收纳库的「专门文件树」

复用现有 Room 的 `Folder` 层级体系，用一个**固定根文件夹**承载收纳库：

- 约定一个保留 ID 的根文件夹，如 `Folder(id = "__import_library__", name = "导入库", parentId = null)`，应用首次启动时确保存在（`DatabaseCallback` 预置，或首次访问时惰性创建）。
- 导入的文件 / 文件夹结构挂在这个根下：
  - 导入单个/多个**文件** → 作为文档创建在导入库根（或用户指定的子文件夹）下。
  - 导入一个**文件夹** → 在导入库下创建同名子文件夹，递归把其中的 `.md`/`.txt` 复制为文档（沿用 `WorkspaceScanner` 的过滤/限深/限量规则）。
- 在侧栏或主列表中，导入库作为一个可展开的特殊文件夹出现，复用 `SidebarFileTree` 的展开/打开/删除交互。

> 备选：不复用内部 Folder，而是新建独立的 `ImportLibraryRepository` + 专属 Room 表。**不推荐**——会与现有文档库逻辑大量重复，违背 YAGNI。复用 Folder 体系最省。

### 导入时「手动挑选」

导入入口提供两种，均要求用户显式选择，不自动全量导入：

1. **导入文件**（推荐用 `OpenMultipleDocuments`）：系统多选选择器，用户勾选一个或多个 `.md`/`.txt`，点确认才导入。每个文件单独授权，无遍历授权问题。
2. **导入文件夹**：走需求 2 的 App 内浏览器（方案 B），进入目标文件夹后：
   - 列出该文件夹内的 `.md`/`.txt` 文件，**每个文件前带勾选框**，默认全不选或全选可讨论。
   - 子文件夹可继续进入，也可整体勾选「连同子文件夹一起导入」。
   - 底部「导入选中项（N）」按钮，点了才执行复制。

### 收纳库内的操作

- **打开**：点文档 → 进编辑器（内部文档路径 `editor?documentId=`，与现有完全一致）。
- **删除**：长按 / 菜单 → 删除（复用 `DeleteDocumentUseCase`，删 Room 记录 + `filesDir` 文件）。删的是收纳库副本，不碰原文件。
- **再导入**：导入库根 / 任意子文件夹上提供「导入到此处」入口，可重复导入多个文件夹。

### 新增 / 修改组件

| 组件 | 位置 | 改动 |
|---|---|---|
| `ImportLibrary` 根文件夹 | Room 预置数据 / 惰性创建 | 保留 ID 常量 |
| `ImportFolderUseCase`（新） | `domain/usecase/import/` | 递归把一个 SAF 文件夹复制为「导入库子树 + 文档」 |
| `ImportDocumentUseCase`（已存在） | — | 复用；导入文件场景的 targetFolderId 传导入库子文件夹 ID |
| 导入入口 UI | `FileListScreen` 抽屉 / FAB 菜单 | 「导入文件」「导入文件夹」两个动作 |
| `FileListViewModel` | — | 接线 import use case（替换现有 `/* TODO */`），导入进度 / 错误处理 |
| `FolderPickerScreen` 导入模式 | `presentation/picker/` | 带文件勾选的浏览器（需求 2 方案 B 复用） |

---

## 六、对现有功能的影响与回归保障

| 现有功能 | 是否受影响 | 保障措施 |
|---|---|---|
| 内部文档库（Room） | 新增导入库复用其体系 | 导入库是其下一个保留 ID 文件夹，普通文档逻辑不变；列表过滤需排除/特殊处理该根 |
| 外部文件夹工作区 | 与收纳库并存，互不替代 | 不改 `WorkspaceRepository` 读写逻辑；默认目录可能复用其恢复路径（见需求 1） |
| 编辑器双源加载 | 不变 | 收纳库文件走内部 `documentId` 路径，与现有一致 |
| 启动恢复 `restoreOnLaunch` | 语义升级为「恢复默认目录」 | 保留授权失效静默清除 + 提示条机制 |
| 滚动性能修复（v1.0.1） | 不变 | 本需求不碰 `EditorScreen` WebView |

---

## 七、待评审决策点（编码前需确认）

1. **需求 1 的 URI 模型**：默认目录与「当前打开的工作区」共用一个 URI，还是分两个 key（推荐分两个）？
2. **需求 2 的实现方案**：方案 A（系统选择器 + 确认）还是方案 B（App 内浏览器，推荐）？方案 B 改动较大但一举三得。
3. **需求 3 导入语义**：确认是「复制进 App」（推荐，可删可独立管理）而非「关联原文件」？
4. **导入文件夹的勾选默认值**：进入文件夹后默认全选还是全不选？子文件夹是否默认递归？
5. **导入库根文件夹的呈现**：作为内部文档库里的一个特殊可展开文件夹，还是侧栏里独立一个区块？

---

## 八、非目标（本期不做）

- 导入库与外部工作区之间的文件互相移动。
- 导入时的格式转换（如 `.docx` → `.md`）。
- 导入去重（同名文件覆盖 / 跳过 / 重命名策略——可在评审点 4 一并定，但实现可后置）。
- 默认目录支持多个（仍是单一默认目录）。
- 云端目录（Google Drive 等）作为默认目录或导入源的特殊适配。

---

## 九、涉及文件清单（预估，方案 B + 复用 Folder）

**新增**
- `presentation/picker/FolderPickerScreen.kt`
- `presentation/picker/FolderPickerViewModel.kt`
- `domain/usecase/import/ImportFolderUseCase.kt`

**修改**
- `data/local/prefs/WorkspaceDataStore.kt`（默认目录 key）
- `presentation/settings/SettingsScreen.kt`（默认目录设置项 + ViewModel 方法）
- `presentation/filelist/FileListScreen.kt`（导入入口、抽屉接入导入库）
- `presentation/filelist/FileListViewModel.kt`（接线 import、导入库根处理、默认目录恢复）
- `presentation/navigation/Screen.kt`、`YuMarkNavGraph.kt`（picker 路由）
- `data/local/db/DatabaseCallback.kt`（导入库根文件夹预置，若选预置方案）
- `app/src/main/res/values/strings.xml`（新增文案）

**复用（不改或小改）**
- `domain/usecase/import/ImportDocumentUseCase.kt`
- `data/local/file/WorkspaceScanner.kt`（导入文件夹时的过滤/限深逻辑）
- `presentation/sidebar/SidebarFileTree.kt`（导入库树展示）
