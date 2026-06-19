# YuMark 产品能力补完 — 设计与规划文档

> PDF 导出 · Word 导出 · 图片长图导出 · 文档历史版本 · 云端同步 · 平板与折叠屏布局优化

| 项目 | 内容 |
|---|---|
| 文档编号 | YM-DD-2026-002 |
| 标题 | 六项剩余产品能力的设计与实施规划 |
| 版本 | v0.1（草案 / Draft） |
| 状态 | 待评审（Pending Review） |
| 作者 | Ban-code-art |
| 创建日期 | 2026-06-19 |
| 适用版本 | YuMark ≥ v0.8（目标） |
| 关联 | `docs/ARCHITECTURE.md`、`README`「产品能力」清单 |

## 修订历史

| 版本 | 日期 | 作者 | 说明 |
|---|---|---|---|
| v0.1 | 2026-06-19 | Ban-code-art | 初稿：六项剩余能力的总体规划、技术选型与逐项详细设计 |

---

## 1. 背景与目标

README「产品能力」清单中前 9 项已完成（本地库、SAF 工作区、实时预览、KaTeX/Prism/Mermaid、AI 聊天/快捷 AI、Agent 工具调用、多模型 Provider、图片多模态、Markdown/HTML 导出）。本规划覆盖剩余 6 项：

1. **PDF 导出**
2. **Word 导出**
3. **图片长图导出**
4. **文档历史版本**
5. **云端同步**
6. **平板与折叠屏布局优化**

**现状利好**：导出框架已就位——`ExportFormat` 枚举已含 `PDF/WORD/IMAGE`，`ExportDocumentUseCase` 已 dispatch 到 `exportPdf/exportWord/exportImage`（当前为 `UnsupportedOperationException` 占位，附 TODO），`ExportOptions(outputDir)`、`HtmlExporter`、`FileManager.getExportsDir()`、文件名消毒/路径校验均已具备。导出菜单在 `EditorScreen` 仅挂了 Markdown/HTML，需补挂 PDF/Word/Image。

**目标**：在不破坏现有架构（Clean Architecture + MVVM、Room、Hilt、Compose）的前提下逐项落地，优先复用既有渲染/存储/网络设施，控制三方依赖膨胀。

---

## 2. 技术栈与现状约束

| 项 | 现状 |
|---|---|
| 语言/构建 | Kotlin 1.9.22、AGP 8.2.2、JDK 17、minSdk 26、compileSdk 34 |
| UI | Jetpack Compose（BOM **2024.02.00**）+ Material 3，单窗格 `NavHost` |
| 渲染 | 预览走 WebView + `assets/raw`（marked + KaTeX + Prism + Mermaid）；HTML 导出走 Commonmark（**不含**数学/高亮/图表） |
| 数据 | Room **v6**（含迁移链）、DataStore、`FileManager`（Markdown 落盘 `files/documents/*.md`） |
| 网络/密钥 | Ktor Client（`NetworkModule`）、security-crypto（AI Key 加密，可复用存同步凭证） |
| 缺失依赖 | 无 PDF/Word 库、无 `material3-window-size-class`/`androidx.window`/`adaptive`、无 WorkManager |

**关键取舍——PDF/长图的渲染源**：为保真（KaTeX/Mermaid/Prism），PDF 与长图**不走 Commonmark 的 `HtmlExporter`**，而复用**预览 WebView 管线**（同一套 `assets/raw` HTML + JS）。为此新增一个**离屏 WebView 渲染器**，加载离线 HTML、等待渲染完成回调后再「打印成 PDF」或「截成长图」。

---

## 3. 实施路线（优先级与排期）

按「价值/风险比 + 依赖关系」排序，导出三项共享离屏渲染器，建议打包推进：

| # | 能力 | 复杂度 | 依赖 | 建议批次 |
|---|---|---|---|---|
| F1 | PDF 导出 | 中 | 离屏 WebView 渲染器 | **批次 1（导出包）** |
| F2 | 图片长图导出 | 中 | 同上 | 批次 1 |
| F3 | Word 导出 | 中高 | 复用 Commonmark AST | 批次 1 |
| F4 | 文档历史版本 | 中 | Room 迁移 v6→v7 | 批次 2 |
| F5 | 平板与折叠屏布局 | 中高 | 新增 window-size-class/window | 批次 3 |
| F6 | 云端同步（WebDAV） | 高 | Ktor、加密凭证、Room 迁移 | **批次 4（独立、分期）** |

总估算：约 **18–24 人日**（详见各节）。

---

## 4. 详细设计

### F1 · PDF 导出

- **选型**：Android 原生 `WebView.createPrintDocumentAdapter()` + 手动驱动 `PrintDocumentAdapter`（`onLayout`/`onWrite` 写入 `ParcelFileDescriptor`）静默生成 PDF，**无三方依赖、无打印对话框**。备选 `PdfDocument` 手动分页绘制——分页与长内容处理麻烦，弃用。
- **渲染源**：复用预览 WebView 的离线 HTML（含 KaTeX/Mermaid/Prism），保真且与预览一致。
- **设计**：
  - 新增 `core/export/WebViewDocumentRenderer.kt`（`@Singleton`，持 `@ApplicationContext`）：在主线程创建离屏 `WebView`，`loadDataWithBaseURL("file:///android_asset/", html, …)`，注入 JS 渲染完成回调（复用预览渲染器的 onRenderComplete 信号），`suspendCancellableCoroutine` 等待 ready。
  - `renderToPdf(html, outFile, pageSize)`：ready 后用 `PrintDocumentAdapter` 写入 `outFile`。
  - `exportPdf()` 改为：取文档 → 生成离线 HTML（抽出预览 HTML 模板为可复用函数）→ `WebViewDocumentRenderer.renderToPdf` → 返回 `File`。
- **改动**：`ExportDocumentUseCase.exportPdf`（去占位）、新增 `WebViewDocumentRenderer`、把预览 HTML 模板抽成共享构建器（与 `core/webview` 复用）；`EditorScreen` 导出菜单补「导出 PDF」。
- **注意**：WebView 须主线程；离屏 WebView 需 attach 到一个有效 context（用 application context + 适当 measure/layout）；Mermaid/KaTeX 异步渲染必须等回调，设超时兜底。
- **估算**：2–3 人日（含渲染器，与 F2 摊薄）。

### F2 · 图片长图导出

- **选型**：离屏 WebView 整页渲染 → `Bitmap`。固定宽度（如 800dp×density），渲染完成后取 `webView.contentHeight`，`measure/layout` 到全高，`view.draw(Canvas(bitmap))` 截全图 → PNG（可选 JPEG）。`PixelCopy` 只能截可见区，不适合长图，弃用。
- **设计**：复用 `WebViewDocumentRenderer`，加 `renderToBitmap(html, width): Bitmap`；超长内容设高度上限（如 12000px）并在超限时**日志 + 提示**（不静默截断）或分段导出（P2）。`exportImage()` 保存 PNG 到 `outputDir`。
- **改动**：`ExportDocumentUseCase.exportImage`、`WebViewDocumentRenderer.renderToBitmap`、导出菜单补「导出长图」。
- **注意**：大位图内存（ARGB_8888，12000×800×4 ≈ 38MB）——用 `RGB_565` 或限高；导出在 IO 线程编码、主线程绘制。
- **估算**：1.5–2 人日（共享渲染器后）。

### F3 · Word 导出（.docx）

- **选型**：**手写最小 OOXML**（.docx 本质是 ZIP，含 `[Content_Types].xml`、`_rels/.rels`、`word/document.xml`、`word/_rels/document.xml.rels`、`word/styles.xml`）。**不引入 Apache POI**（~10MB、Android 兼容差、方法数爆炸）。复用 `HtmlExporter` 已用的 **Commonmark AST**（`Parser`），遍历 AST 节点映射为 OOXML 段落/run。备选「HTML 存成 .doc」——Word 能打开但非真 docx、格式脆，弃用。
- **覆盖范围（P1）**：标题 h1–h6（映射内置 Heading 样式）、段落、粗体/斜体/删除线、行内代码、代码块（等宽+底纹）、有序/无序列表、引用块、表格、分隔线、链接（带样式文本）。图片嵌入（`word/media` + rels）列 **P2**。
- **设计**：新增 `core/export/DocxExporter.kt`：`Parser.parse(md)` → 自定义 `AbstractVisitor` 生成 `document.xml` 的 `<w:p>/<w:r>` XML → 用 `java.util.zip.ZipOutputStream` 打包为 `.docx`。`exportWord()` 调用之。
- **改动**：新增 `DocxExporter`、`ExportDocumentUseCase.exportWord`、导出菜单补「导出 Word」、字符串资源。
- **注意**：XML 转义、`xml:space="preserve"`、列表编号需 `numbering.xml`（最小实现可用样式近似）；保证生成的 docx 通过 Word/WPS 打开校验。
- **估算**：3–4 人日。

### F4 · 文档历史版本

- **选型**：Room 新表 `document_versions` 存内容快照（本地、与正文同源）。复用既有 `LineDiffer`/`DiffView` 做版本对比。
- **数据模型（迁移 v6→v7）**：
  ```
  document_versions(
    id TEXT PK, document_id TEXT NOT NULL FK→documents(id) ON DELETE CASCADE,
    content TEXT NOT NULL, word_count INT, created_at INTEGER NOT NULL,
    label TEXT NULL  -- 可选：手动命名/“自动保存”
  )  index(document_id, created_at)
  ```
- **快照策略**：在保存链路（`SaveDocumentUseCase` 或 `EditorViewModel` 自动保存）落快照，避免噪音：① 仅当内容较上一版有实质变化（字符差阈值/哈希不同）；② 节流（如每 N 分钟或每次手动保存最多一版）；③ 每文档保留上限（如 50 版，FIFO 裁剪）。具体阈值列**待决项**。
- **设计**：新增 `DocumentVersionEntity`/`DocumentVersionDao`/`DocumentVersionRepository(Impl)` + `SnapshotDocumentUseCase`/`GetVersionsUseCase`/`RestoreVersionUseCase`；UI 在编辑器溢出菜单加「历史版本」→ `VersionHistorySheet`（列表：时间/字数；操作：预览、与当前 diff、恢复）。恢复 = 写回正文（并对当前再存一版，避免丢失）。
- **改动**：`AppDatabase`（entities+迁移+dao）、`DatabaseModule`、`RepositoryModule`、新增 UI/UseCase；保存链路挂快照钩子。
- **估算**：3–4 人日。

### F5 · 平板与折叠屏布局优化

- **选型**：`androidx.compose.material3:material3-window-size-class` 计算 `WindowSizeClass`；`androidx.window:window` 感知折叠姿态/铰链。Compact 宽度沿用现单窗格导航；Medium/Expanded 用**列表-详情双窗格**（左 FileList、右 Editor）。`ListDetailPaneScaffold`（material3-adaptive）需较新 BOM——为避免大版本升级，**P1 先用 `Row` + 权重手写双窗格**复用现有 `FileListScreen`/`EditorScreen`，`adaptive` 列为可选升级。
- **设计**：
  - `MainActivity` 用 `calculateWindowSizeClass(this)`，注入顶层 `AppShell(windowSizeClass, foldingFeature)`。
  - `AppShell`：Compact → 现 `YuMarkNavGraph`（不变）；Expanded/Medium → 双窗格：列表选中项驱动右侧编辑器（用共享 ViewModel/selectedDocumentId 状态，替代导航 push）。
  - 折叠屏：`androidx.window` 取 `FoldingFeature`，半开（tabletop）时上下分区或避让铰链。
  - 复用现有 `presentation/sidebar/` 抽屉；宽屏可常驻。
- **改动**：`build.gradle.kts`/catalog 加依赖；`MainActivity`；新增 `presentation/AppShell.kt` 与双窗格容器；`FileListScreen`/`EditorScreen` 解耦导航假设（接受「选中回调」而非只 `navController.navigate`）。
- **注意**：旋转/展开的状态保持（`rememberSaveable`、ViewModel 作用域）；编辑器自动保存在窗格切换时不丢；返回键语义。
- **估算**：4–5 人日。

### F6 · 云端同步（WebDAV，分期）

- **选型**：**WebDAV**（用户自带服务：Nextcloud / 坚果云 / 群晖等，国内坚果云原生支持，最契合「私有 Markdown 库」）。复用 Ktor Client 发 `PROPFIND/GET/PUT/DELETE/MKCOL`；凭证存 security-crypto。备选 Git（冲突/二进制差、移动端重）、厂商云盘 SDK（绑定+体积）——均弃用，WebDAV 最通用轻量。
- **同步模型**：以本地内部 Markdown 库（`files/documents/*.md` + Room 元数据）为单元，映射到远端某 WebDAV 目录。每文档维护同步态：`localHash`、`remoteEtag`、`lastSyncedAt`、`syncState`（synced/localChanged/remoteChanged/conflict）。
- **数据模型（迁移）**：在 `documents` 增列或新表 `sync_state(document_id, remote_etag, local_hash, last_synced_at, state)`（建议新表，隔离同步逻辑）。
- **冲突策略**：双方都变 → 生成「冲突副本」(`name (conflict 2026-06-19).md`) 保留双版本，不静默覆盖；其余 last-write-wins。
- **分期**：
  - **P1**：设置页配置 WebDAV（URL/用户/密码，连接测试）；**手动「立即同步」**：拉取远端清单 → 双向比对哈希/etag → 上传/下载/建目录 → 写回同步态；状态 UI（每文档/全局）。
  - **P2**：冲突副本、删除传播（墓碑）、文件夹结构同步、图片附件同步。
  - **P3**：后台/定时同步（引入 WorkManager）、增量优化。
- **改动**：新增 `data/remote/webdav/WebDavClient.kt`、`data/sync/SyncEngine.kt`、`SyncRepository`、`SyncStateEntity/Dao`、`domain/usecase/sync/*`、设置页 WebDAV 配置 UI、`di/NetworkModule` 复用 client；Room 迁移。
- **注意**：网络/超时/重试（复用 `StreamRetry` 思路）、大库性能（并发上限、分批）、字符编码、WebDAV 服务器差异（坚果云路径/限流）、隐私（仅用户自有服务器，凭证加密、可清除）。
- **估算**：6–8 人日（P1 约 4，P2/P3 增量）。

---

## 5. 依赖与数据库变更汇总

**新增依赖（catalog `gradle/libs.versions.toml`）**
- `androidx.compose.material3:material3-window-size-class`（F5）
- `androidx.window:window`（F5 折叠屏）
- （可选，F5 升级）`androidx.compose.material3.adaptive:adaptive*` — 需评估 Compose BOM 升级影响
- （F6 P3）`androidx.work:work-runtime-ktx`
- **不新增** PDF/Word 库（F1/F3 走原生 + 手写 OOXML）

**Room 迁移链**
- v6 → v7：`document_versions`（F4）
- v7 → v8：`sync_state`（F6）
- 迁移遵循既有写法（`AppDatabase` 内 `Migration` 对象，`exportSchema=true`，不动既有表）。

---

## 6. 复用清单（避免重复造轮子）

- 导出框架：`ExportFormat`、`ExportDocumentUseCase`、`ExportOptions`、`FileManager.getExportsDir()`、文件名消毒/路径校验。
- 渲染：预览 WebView 的 `assets/raw` HTML/JS（F1/F2 离屏复用）；`HtmlExporter` 的 Commonmark `Parser`（F3 复用 AST）。
- diff：`core/util/diff/{LineDiffer,DiffComposer,DiffView}`（F4 版本对比）。
- 网络/密钥：`NetworkModule` 的 Ktor client、security-crypto（F6）。
- UI：`presentation/sidebar`（F5 宽屏常驻）、既有 `EditorScreen`/`FileListScreen`（F5 双窗格宿主）。

---

## 7. 测试策略

- **单元**：`DocxExporter`（AST→OOXML，校验 zip 结构/可打开）、版本快照策略（阈值/裁剪/恢复）、WebDAV 同步比对与冲突判定（fake client，Turbine + coroutines-test）、`WindowSizeClass` 分支逻辑（纯函数部分）。
- **手测（真机/模拟器 + adb 驱动）**：每项导出产物在 WPS/Word/系统查看器打开核对；历史版本「存→改→看 diff→恢复」；折叠屏/平板（模拟器多窗、Resizable AVD）双窗格与旋转；WebDAV 用真实坚果云/Nextcloud 账户跑「双向同步 + 冲突」。
- **回归**：`./gradlew :app:testDebugUnitTest :app:assembleDebug` 全绿；导出/历史不影响既有 Markdown/HTML 导出与编辑保存。

---

## 8. 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| 离屏 WebView 在异步图表/公式未渲染完就截取 | 中 | 等渲染完成 JS 回调 + 超时兜底；Mermaid/KaTeX done 事件 |
| 长图大位图 OOM | 中 | 限高/`RGB_565`/分段；超限提示不静默 |
| 手写 docx 兼容性 | 中 | 最小但合规 OOXML，覆盖常见元素，多客户端打开校验；P1 不做图片 |
| Room 迁移破坏数据 | 中 | 仅新增表、不动旧表；迁移单测 + schema 导出比对 |
| 双窗格重构波及导航/状态 | 中 | Compact 路径不变；宽屏增量；状态用 ViewModel/Saveable |
| WebDAV 服务器差异/限流（坚果云） | 中 | 并发上限、重试退避、路径规范化；先支持主流服务并实测 |
| Compose BOM 偏旧限制 adaptive API | 低 | F5 P1 手写双窗格，BOM 升级作为可选项另评 |

---

## 9. 待决问题（Open Questions）

1. 历史版本快照触发策略与保留上限（每文档 50 版？变更阈值？）。
2. Word 导出是否本期需要图片嵌入（P1 仅文本结构 vs P2 带图）。
3. 云端同步首选服务/协议确认（WebDAV 优先；是否还要 Git/坚果云特化）。
4. 折叠屏双窗格是否引入 `material3-adaptive`（需 Compose BOM 升级评估）还是手写。
5. 长图导出格式与超长处理（PNG vs JPEG；限高 vs 分段）。
6. 是否需要后台/定时同步（引入 WorkManager 的时机）。

---

## 10. 附录：逐项「文件改动」速查

| 能力 | 新增 | 修改 |
|---|---|---|
| F1 PDF | `core/export/WebViewDocumentRenderer.kt` | `ExportDocumentUseCase.exportPdf`、预览 HTML 模板抽取、`EditorScreen` 菜单、strings |
| F2 长图 | （复用渲染器） | `ExportDocumentUseCase.exportImage`、`EditorScreen` 菜单、strings |
| F3 Word | `core/export/DocxExporter.kt` | `ExportDocumentUseCase.exportWord`、`EditorScreen` 菜单、strings |
| F4 历史 | `DocumentVersionEntity/Dao/Repository(Impl)`、`Snapshot/Get/RestoreVersionUseCase`、`VersionHistorySheet` | `AppDatabase`(迁移6→7)、`DatabaseModule`、`RepositoryModule`、保存链路、`EditorScreen` 菜单 |
| F5 平板/折叠 | `presentation/AppShell.kt`、双窗格容器 | `MainActivity`、`FileListScreen`/`EditorScreen`（解耦导航）、catalog 依赖 |
| F6 云同步 | `data/remote/webdav/WebDavClient.kt`、`data/sync/SyncEngine.kt`、`SyncRepository`、`SyncStateEntity/Dao`、`usecase/sync/*`、设置 WebDAV UI | `AppDatabase`(迁移7→8)、DI、设置页、catalog（P3 WorkManager） |
