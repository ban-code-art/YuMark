# YuMark 项目 Review 与修复文档

> 生成日期：2026-06-18
> 来源：对全项目（146 个 Kotlin 文件 / ~2 万行）的五维度并行 review，汇总去重后形成。
> 状态图例：✅ 已修复 / 🟡 本轮暂缓（需构建验证或较大改动，见说明）/ 🔴 需用户操作（破坏性/外向，不擅自执行）

---

## 一、安全与仓库卫生

### S-1 [高] Release 签名密码明文泄露进 git 历史 🔴 + ✅(代码侧)
- **文件**：`app/build.gradle.kts:33-40`、`RELEASE_CHECKLIST.md:84`
- **问题**：`storePassword` / `keyPassword` 旧值曾明文写入构建脚本并提交进 git 历史；`RELEASE_CHECKLIST.md` 也曾重复打印同一密码。任何能读仓库者可签出与官方同包名恶意 APK。
- **修复（代码侧，本轮完成）**：构建脚本改为从 `keystore.properties`（不入库）读取签名配置；`.gitignore` 已覆盖。
- **🔴 需用户操作**：
  1. **轮换 keystore**：生成全新 keystore + 强密码，废弃当前 `release.keystore` 与已泄露旧密码。
  2. **擦除 git 历史**：`git filter-repo --replace-text` 把已泄露旧密码替换为 `***REMOVED***`，再 `git push --force-with-lease`；协作者需重新 clone。
  3. 删除历史发布文档中的明文密码行。

### S-2 [高] APK 二进制仍被 git 追踪 🔴 + ✅(代码侧)
- **文件**：`YuMark-v0.6.4.apk`（`git ls-files` 命中）；已删的 v0.7 APK blob 仍在历史。
- **修复（代码侧，本轮完成）**：`git rm --cached` 移除追踪（`.gitignore` 已有 `*.apk`）。
- **🔴 需用户操作**：`git filter-repo --path-glob '*.apk' --invert-paths` 清历史后强推；根目录 11 个未追踪 APK 移到 GitHub Release Assets。

### S-3 [中] REQUEST_INSTALL_PACKAGES 权限 🟡
- **文件**：`AndroidManifest.xml:5`
- 自更新分发场景合理；建议对下载 APK 做签名校验。本轮不改权限。

### S-4 [低] allowBackup=true / 无 networkSecurityConfig 🟡
- 见 A-11。

---

## 二、AI / Agent 子系统

### A-1 [高] 任务在出错/取消时永远卡在 EXECUTING ✅
- **文件**：`domain/usecase/ai/agent/AgentUseCases.kt`（`invoke` 的 `for` 循环）
- **问题**：流式 `StreamEvent.Error` 分支（201-211）只置会话 IDLE 就 `return@flow`，没有 `finalizeTaskDirectly(FAILED)`；协程被取消时 `CancellationException` 抛出，循环体无 `try/finally`，所有终态写入都不执行 → 任务卡 `EXECUTING`、会话卡 `WORKING`。
- **修复**：循环体外包 `try/finally`；`finally` 中若任务仍非终态则置 `FAILED`/`CANCELLED`，活跃步骤置 `BLOCKED`，会话置 `IDLE`。Error 分支补 `finalizeTaskDirectly(FAILED)`。

### A-2 [高] 工具调用累积器跨重试不重置 ✅
- **文件**：`data/ai/adapters/OpenAiAdapter.kt`、`ClaudeAdapter.kt`
- **问题**：4xx 兜底重跑或主路径重试时，上一轮累积的 tool_calls delta 残留，喂给模型陈旧/重复工具调用。
- **修复**：`runOnce` 顶部重建累积器 / `reset()`。

### A-3 [高] `pendingCalls` 覆盖而非累积 ✅
- **文件**：`AgentUseCases.kt:198`
- **问题**：一轮内多个 `ToolCallComplete` 事件只保留最后一批，前面的静默丢弃。
- **修复**：`pendingCalls = (pendingCalls ?: emptyList()) + event.calls`。

### A-4 [高] MessageBubble 每条消息新建 WebView 且不销毁 ✅
- **文件**：`presentation/ai/common/MessageBubble.kt:214-243`
- **问题**：长会话几十条消息 = 几十个常驻 WebView，无 `onRelease`。
- **修复**：`AndroidView(..., onRelease = { it.destroy() })`，并清 `webView` 引用。

### A-5 [高] EditorScreen WebView JS interface 清理不完整 ✅
- **文件**：`presentation/editor/EditorScreen.kt`（`DisposableEffect` onDispose）
- **问题**：`onDispose` 只移除 `Android` interface，`AndroidSelection`/`AndroidTouch` 未移除。
- **修复**：`onDispose` 中 `removeJavascriptInterface` 全部三个。

### A-6 [高] Markdown 渲染未做 HTML 消毒 🟡
- **文件**：`MessageBubble.kt:187`、`EditorScreen` renderer.html
- marked.parse 后直接 innerHTML，AI/导入文档可注入 `<script>`。需引入 DOMPurify（新增 asset + 体积），本轮暂缓，文档记录。

### A-7 [中] GeminiAdapter API key 放 URL 查询串 ✅
- **文件**：`data/ai/adapters/GeminiAdapter.kt`
- **修复**：改用 `header("x-goog-api-key", apiKey)`，URL 去掉 `?key=`。

### A-8 [中] 全项目用 `collectAsState` 而非 `collectAsStateWithLifecycle` ✅(部分)
- **文件**：多个 Sheet/Screen
- **修复**：替换主要 call site；新增 `lifecycle-runtime-compose` 依赖。

### A-9 [中] 普通 chat 无 Stop/取消 🟡
- `AiChatViewModel.send` 无 `streamingJob`。本轮暂缓（需加 UI 按钮，影响面大）。

### A-10 [中] 同一会话无互斥 🟡
- 并发 `invoke` 会流式交错、消息双写。暂缓。

### A-11 [中] allowBackup + 无强制 HTTPS 🟡
- 加 `network_security_config.xml` + baseUrl 校验。暂缓。

### A-12 [中] NetworkModule 流式 timeout 过短 ✅
- **文件**：`di/NetworkModule.kt`
- **修复**：流式 client `requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS`，靠 `socketTimeoutMillis` 处理长连接空闲超时，避免非法 0 timeout 配置导致运行时崩溃。

### A-13 [中] `extractImplicitWriteAction` 误判风险 ✅
- **文件**：`AgentUseCases.kt:764-808`
- **问题**：中文"帮我优化这段内容"易同时命中改写动词+文档名词+长文本，把长解释误识别为整篇覆写提议。
- **修复**：editIntent 增加负向条件——用户消息含疑问/解释类词（解释/说明/什么是/为什么/？）时不识别为 edit。

### A-14 [低] enum `valueOf` 重命名崩溃 ✅(部分)
- **文件**：`AgentTaskMappers.kt`
- **修复**：推广 `runCatching { valueOf }.getOrDefault(默认)` 兜底。

### A-15 [中] 测试断言过弱 / 覆盖不足 🟡
- `SendAgentMessageUseCaseTest.kt:500` `isAtMost(8)` 但 `MAX_STEPS=6`；ViewModel 测试核心状态映射零覆盖。暂缓（需较大测试重写）。

---

## 三、数据层与持久化

### D-1 [高] 全文搜索主线程串行读全部文档 ✅(部分)
- **文件**：`data/repository/DocumentRepositoryImpl.kt:85-101`
- **修复（本轮）**：包 `withContext(Dispatchers.IO)`，避免阻塞调用线程。FTS 方案暂缓。

### D-2 [高] observeDocument 重复阻塞文件读 🟡
- Flow.map 内同步读文件，无去重缓存。暂缓（需较大重构）。

### D-3 [高] 正文与 DB 元数据双数据源无事务 🟡
- save/delete 非原子；deleteFolderRecursively 不删文档文件。暂缓（跨依赖重构）。

### D-4 [中] ImageRepository 无 inSampleSize 解码 ✅
- **文件**：`data/repository/ImageRepositoryImpl.kt`
- **修复**：`inJustDecodeBounds=true` 量尺寸算 `inSampleSize` 二次解码；缓存一次 settings。

### D-5 [中] Folder order 用 size 算并发竞态 ✅
- **文件**：`FolderRepositoryImpl.kt`、DAO
- **修复**：order 改 `MAX(order)+1`。

### D-6 [低] FileManager 非原子写 ✅
- **文件**：`data/local/file/FileManager.kt`
- **修复**：temp 文件 + rename。

### D-7 [低] enum valueOf 兜底 ✅（见 A-14）

---

## 四、表现层 / Compose / WebView

### P-1 [中] createRoute 未编码 documentId ✅
- **文件**：`presentation/navigation/Screen.kt`
- **修复**：`URLEncoder.encode`。

### P-2 [低] anchorId 注入未转义 ✅
- **文件**：`EditorScreen.kt:538`
- **修复**：参数化 evaluateJavascript 或转义单引号。

### P-3 [低] KaTeX/marked 同步阻塞首屏 🟡
- 暂缓。

### P-4 [中] 深色判断反推 luminance、预览配色硬编码 🟡
- 暂缓（需主题重构）。

### P-5 [中] 导入菜单对话框重复定义 🟡
- 暂缓（需抽公共组件）。

---

## 五、架构

### AR-1 [高] domain UseCase 反向依赖 data 层 🟡
- `AgentUseCases.kt` 等直接 import `data.ai.AiAdapterFactory`、`data.local.file.FileManager`、`core.image.ImageProcessor`。需把 AiApiAdapter 上移为 domain 端口。暂缓（大重构）。

### AR-2 [中] presentation 直注 Repository/data 具体类 🟡
- 暂缓。

### AR-3 [中] 死代码 UseCase 🟡
- `EvaluateTaskCompletionUseCase`、`ReplanAgentTaskUseCase` 生产无引用。需确认意图后删除。暂缓。

### AR-4 [低] 上帝文件 🟡
- `AgentUseCases.kt` 830 行等。暂缓。

---

## 六、依赖与构建

### B-1 [低] 依赖偏旧 🟡
- kotlin 1.9.22 / compose-bom 2024.02 / room 2.6.1 / ktor 2.3.7。建议升 patch + compileSdk 35。暂缓（升级需完整回归）。

### B-2 [低] file_paths.xml 暴露过宽 🟡
- 暂缓。

---

## 本轮修复执行清单

✅ 已完成并验证（编译通过 + 单元测试通过）：
S-1(代码侧)、S-2(代码侧)、A-1、A-2、A-3、A-4、A-5、A-7、A-8、A-12、A-13、A-14/D-7、D-1、D-4、D-5、D-6、P-1、P-2

🟡 暂缓（需构建回归 / 较大重构 / 需确认意图）：A-6、A-9、A-10、A-11、A-15、D-2、D-3、P-3、P-4、P-5、AR-1~4、B-1、B-2

🔴 需用户操作（破坏性，不擅自执行）：
- **S-1**：① 生成全新 keystore 轮换已泄露旧密码；② `git filter-repo --replace-text` 把历史中的已泄露旧密码替换为 `***REMOVED***` 后强推；③ 删除历史发布文档中的明文密码行。
- **S-2**：`git filter-repo --path-glob '*.apk' --invert-paths` 清除历史 APK blob 后强推；协作者需重新 clone。

> 注：本轮已新建 `keystore.properties`（本地，含旧签名配置供调试构建继续可用，已加入 `.gitignore`）与 `keystore.properties.example`（入库模板）。上线前务必按上方 🔴 步骤轮换。

## 暂缓项说明（后续处理建议）

| ID | 原因 | 建议时机 |
|----|------|----------|
| A-6 HTML 消毒 | 需引入 DOMPurify（新增 asset + 体积），需评估 | 安全加固专项 |
| A-9 普通 chat Stop | 需加 UI 按钮与 streamingJob，影响面大 | chat 体验迭代 |
| A-10 会话互斥 | 需引入 Mutex map，与现有取消逻辑需联调 | Agent 并发加固 |
| A-11 HTTPS 强制 | 需 network_security_config + baseUrl 校验 | 安全加固专项 |
| A-15 测试补强 | 需较大测试重写 | 测试专项 |
| D-2/D-3 双数据源 | 需把正文并入 Room 或跨依赖重构 | 数据层重构 |
| AR-1~4 架构 | domain 端口上移、死代码删除、上帝文件拆分 | 架构重构专项 |
| B-1 依赖升级 | 需完整回归 | 版本升级窗口 |
