# YuMark 代码审查问题清单

- 审查日期：2026-06-12
- 审查范围：全项目（提交 `Initial commit` 时点的代码）
- 状态标记：`[ ]` 待修复 / `[x]` 已修复

---

## 🔴 P0 — 正确性问题（会丢数据 / Release 版直接损坏）

### P0-1 返回键保存竞态，可能损坏外部文件

- [x] 已修复（2026-06-12）

**位置**：`app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt`（返回键 `navigationIcon` 回调）、`EditorViewModel.kt`（`saveDocument`）、`WorkspaceRepositoryImpl.kt`（`writeDocument`）

**问题**：返回键执行 `viewModel.saveDocument()` 后立刻 `navController.navigateUp()`。保存是 `viewModelScope` 里的异步协程，导航返回销毁 ViewModel 时协程被取消。外部文档写回使用 `openOutputStream(uri, "wt")` 截断模式：先清空原文件再写入，**协程若在截断后、写完前被取消，用户的原文件只剩半截**。

**修复建议**：
1. 保存协程改用不随 ViewModel 取消的作用域（注入 application 级 `CoroutineScope`），或返回键改为 `suspend` 等待保存完成后再导航
2. 外部写回改为"写临时文件 + 成功后改名/覆盖"两段式，或至少先写内存缓冲、单次原子输出

### P0-2 Release 混淆破坏 WebView JS 桥（预览/大纲全部失效）

- [x] 已修复（2026-06-12）

**位置**：`app/proguard-rules.pro`、`EditorScreen.kt`（`addJavascriptInterface` 匿名对象）

**问题**：proguard 规则只 keep 了 `com.yumark.app.core.webview.JsBridge`——该类已删除。现在的 JS 桥是 `EditorScreen` 内的匿名对象，没有任何 keep 规则，R8 会剔除/重命名 `@JavascriptInterface` 方法（`log` / `onOutline` / `onReady`）。**Release 包中预览渲染、大纲回传、就绪握手全部静默失效**，debug 包测不出来。

**修复建议**：proguard-rules.pro 删除指向已删类的规则，新增：

```proguard
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
```

### P0-3 自动保存不检查脏标记，每 30 秒重写外部原文件

- [x] 已修复（2026-06-12）

**位置**：`EditorViewModel.kt`（`startAutoSave` / `saveDocument`）

**问题**：自动保存定时器每 30 秒无条件调 `saveDocument()`，不判断 `isDocumentDirty`。工作区模式下意味着每 30 秒重写一次用户的外部原文件——mtime 持续变化触发云盘反复同步，同时放大 P0-1 的截断风险窗口。

**修复建议**：`saveDocument()` 开头（或自动保存调用处）增加 `if (!isDocumentDirty) return`。

---

## 🟠 P1 — 功能缺陷

### P1-1 Markdown 工具栏完全失效

- [x] 已修复（2026-06-12）

**位置**：`EditorScreen.kt`（`editContent` 本地状态 / `BasicTextField`）、`EditorViewModel.kt`（`insertSyntax`、`onCursorPositionChanged`、`_cursorPosition`）

**问题**：
1. `insertSyntax` 更新的是 ViewModel 的 `_document.content`，但 UI 渲染的 `editContent` 只在文档首次加载时同步一次——点工具栏按钮**没有任何可见效果**
2. `onCursorPositionChanged` 从未被 UI 调用（`BasicTextField` 用的是 `String` 而非 `TextFieldValue`，根本拿不到光标），`_cursorPosition` 恒为 0，即使修复了同步，插入位置也永远在文档开头

**修复建议**：`BasicTextField` 改用 `TextFieldValue`（携带 selection），插入逻辑移到 UI 层直接操作 `TextFieldValue`（在光标处拼接并更新 selection），ViewModel 只接收最终文本。`insertSyntax`/`_cursorPosition`/`onCursorPositionChanged` 可随之删除。

### P1-2 列表页错误处理摧毁整页

- [x] 已修复（2026-06-12）

**位置**：`FileListViewModel.kt`（`setError`，被 create/delete/rename 等全部失败路径调用）

**问题**：`setError` 把整个 `uiState` 置为 `Error`——删除一个文档失败，文档列表整页消失只剩一行错误文字，且没有恢复路径（要等下一次数据流发射才能回来）。

**修复建议**：错误改为独立的一次性事件流（如 `MutableStateFlow<String?>` + Snackbar，参照 `EditorViewModel.saveError` 的做法），`uiState` 保持列表数据。

### P1-3 编辑器错误态 Retry 按钮行为错误

- [x] 已修复（2026-06-12）

**位置**：`EditorScreen.kt`（`EditorUiState.Error` 分支）

**问题**：加载失败的"Retry"按钮调用的是 `viewModel.saveDocument()`，应为重新加载文档。

**修复建议**：ViewModel 把 init 的加载逻辑抽成 `fun loadDocument()`，Retry 调用它。

### P1-4 一整套"幽灵功能"（已实现未接线）

- [x] 已处理（2026-06-12）：HTML 导出已接线（编辑器菜单 → 导出 → 系统分享，FileProvider）；WelcomeScreen 已删除；图片插入的死 UI（工具栏按钮/菜单项/空方法）已删除；ImportDocumentUseCase 与 ProcessImageUseCase 保留为休眠的 domain 代码，待后续接线

**位置**：
- `core/export/HtmlExporter.kt` + `domain/usecase/export/ExportDocumentUseCase.kt` —— 编辑器"导出"菜单项是空操作（`onClick = { showMenu = false }`）
- `domain/usecase/import/ImportDocumentUseCase.kt` —— 无任何 UI 入口
- `domain/usecase/image/ProcessImageUseCase.kt` + `ImageRepositoryImpl` —— "插入图片"是 TODO（`onInsertImageClick` 空方法）
- `presentation/welcome/WelcomeScreen.kt` —— 不在导航图中，无法到达
- `FileListViewModel.exportDocument()` —— 方法体是 `/* TODO */`

**修复建议**：要么接线（导出 HTML 是现成的，工作量最小），要么删除以减少维护面。每项单独决策。

### P1-5 WebView 调试开关在 Release 也开启

- [x] 已修复（2026-06-12）

**位置**：`EditorScreen.kt`（WebView 创建块）

**问题**：`WebView.setWebContentsDebuggingEnabled(true)` 无条件执行，release 包可被 Chrome DevTools 远程调试，暴露调试面。

**修复建议**：改为 `if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)`。

---

## 🟡 P2 — 性能与卫生

### P2-1 构建文件夹树读取全部文档的文件内容

- [x] 已修复（2026-06-12）

**位置**：`GetFolderTreeUseCase.kt` → `DocumentRepositoryImpl.getAllDocuments()`

**问题**：树只需要元数据（名称/folderId/updatedAt），但 `getAllDocuments()` 为每个文档做一次文件 IO 读全文。文档数量增长后这是侧栏的下一个卡顿来源（树缓存只是降低了频率，单次代价仍是 O(全部文件 IO)）。

**修复建议**：仓库增加 `getAllDocumentMetas()`（content 置空，与 `observeAllDocuments` 同策略），树构建改用它。

### P2-2 死资源进 APK

- [x] 已修复（2026-06-12）

**位置**：
- `app/src/main/res/raw/`（katexjs/markedjs/mermaidjs 为 60-80 字节占位文件 + 19KB prismjs，代码中无任何 `R.raw` 引用）
- `app/src/main/assets/__test_renderer.html`（8KB 测试页）
- `app/src/main/assets/templates/test.html`

**修复建议**：直接删除（release 的 shrinkResources 只处理 res/，assets 不会被裁剪）。

### P2-3 仓库观感与文档

- [x] 已修复（2026-06-12）

**问题**：
- 根目录 16 个 AI 过程报告（`BUG_FIX_REPORT.md`、`FINAL_SUMMARY.md`、`TYPORA_P0_COMPLETION.md` 等）推上了 GitHub，仓库首页杂乱
- `README.md` 还是初版内容：未提及文件夹工作区、大纲导航、主题系统、深色模式等核心功能

**修复建议**：过程报告移入 `docs/archive/` 或删除；重写 README（功能清单 + 截图 + 构建说明）。

### P2-4 小件清理

- [x] 已修复（2026-06-12）

- `EditorViewModel` 注入的 `@ApplicationContext context` 已无使用方
- `EditorViewModel.insertImage()` 无调用方
- `proguard-rules.pro` 中 keep 已删除的 `JsBridge` 类（随 P0-2 一并清理）
- `app/src/main/res/ICON_README.md` 内容已过时（图标方案已三换）

---

## ⚙️ CI（当前必然失败）

### CI-1 GitHub Actions 版本过期

- [x] 已修复（2026-06-12）

**位置**：`.github/workflows/android.yml`

**问题**：`actions/upload-artifact@v3` 已被 GitHub 停用（强制失败）；`checkout@v3`、`setup-java@v3` 也已过期。

**修复建议**：全部升级到 v4。

### CI-2 流程冗余与资产覆盖风险

- [x] 已修复（2026-06-12）

**问题**：
- `build` 和 `test` 两个 job 重复执行单测（`gradlew build` 已含 `test`）
- `Download JS Libraries` 步骤运行 `download-js-libs.sh` 会覆盖仓库中已提交的 assets，CI 构建产物与本地版本可能不一致
- `gradlew build` 包含 lint，未验证过 lint 是否通过

**修复建议**：合并为单 job（`assembleDebug` + `testDebugUnitTest`）；删除下载步骤（assets 已入库）；lint 先跑一次确认或显式排除。

---

## 🧪 测试缺口

### T-1 EditorViewModel 零测试

- [x] 已修复（2026-06-12）

**问题**：全项目最复杂的状态机（双源加载、默认预览判定、双路保存、自动保存、大纲解析）没有任何单元测试。当前 17 个测试覆盖的是 scanner / 仓库 / 用例 / 主题 / 文件列表。

**修复建议**：补充核心用例——内部/外部加载分支、`defaultPreviewMode` 与空文档例外、保存失败走 `saveError`、`onOutlineReceived` 解析容错。

---

## 建议修复顺序

1. **P0-1 / P0-2 / P0-3**（数据安全 + release 可用性，改动小价值高）
2. **P1-1 工具栏**（用户可感知的功能修复）
3. **CI-1 / CI-2**（让仓库的 CI 转绿）
4. **P2-1 树构建去 IO**（防患于未然）
5. **P1-2 ~ P1-5、P2-2 ~ P2-4、T-1**（陆续清理）
6. **P1-4 幽灵功能**（需要产品决策：接线还是删除）
