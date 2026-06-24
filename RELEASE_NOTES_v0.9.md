# YuMark v0.9 更新说明

版本：v0.9
versionCode：19
APK：YuMark-v0.9.apk

本次以 **AI Agent 能力大扩展** 为主线：在 v0.8 单工具循环的基础上，补齐意图驱动的工具裁剪、Web 联网搜索、长期记忆、RAG 知识库与富 HTML 导出，Agent 可用工具由 6 个增至 12 个，Room 由 v8 升至 v10。同时修复了三处 AI 对话页长回复上滑"跳顶/白屏"的滚动顽疾，并为划词与普通对话补上手动停止。

## Agent 能力扩展（移植自 guanmo）

### 意图检测 + 工具裁剪
- 新增 `IntentDetector` / `ToolSelector` / `ContextBudget`：每轮按用户消息（关键词+正则+上下文）打分，动态裁剪注入模型的工具集，避免 12 个工具全量污染上下文。
- 6 种 Capability：DOCUMENT_READ / DOCUMENT_WRITE / WEB / MEMORY / KNOWLEDGE / TIME。例如「总结这篇」只注入读取类工具，「改写第二段」只注入写入类，闲聊给最小集。

### Web 联网搜索
- `web_search` 工具，适配 5 个引擎：Tavily / Serper / Brave / DuckDuckGo（免 Key）/ 自定义 URL（自动探测 JSON 结构）。
- 新增 `@WebSearchClient` 短超时 HttpClient；AI 设置页新增 Web 搜索开关、引擎选择与 API Key。

### 长期记忆
- `save_memory` / `search_memory` / `list_memories` 三工具，`MemoryService` 词法相似度检索（token 余弦 + 子串加成），保存时自动合并相似记忆（≥0.68）。
- 5 类记忆（preference / project / learning / profile / instruction），按类别优先级 + 相似度排序。独立 `memories` 表，Room 迁移 v8→v9。

### RAG 知识库
- Embedding 基建：`EmbeddingAdapter` + OpenAI `/embeddings`，`AiAdapterFactory` 集成，新增 `embeddingModel` 配置。
- `VectorStore` 纯线性扫描 + 手写余弦；**混合检索** = 向量命中且关键词命中时 `vector*0.72 + keyword*0.28 + 0.04`，否则取 max；contentHash 去重、按文档分组轮询填充、偏好当前文档 +0.08。
- `MarkdownChunker` 语义分块（按标题切、跳过代码块内标题、超长按句断、尾部 overlap），`RagPipeline` 在保存文档后后台索引（幂等、失败重试）。
- `search_knowledge` / `knowledge_stats` 两工具。新增 `rag_chunks` / `rag_embeddings` / `rag_embedding_jobs` 三表，Room 迁移 v9→v10。

### 富 HTML 导出
- `WebViewDocumentRenderer.renderToRichHtml()`：复用预览管线（marked + KaTeX + Prism + Mermaid），CSS 与字体内联，离线单文件可打开，与预览渲染 100% 一致。
- 导出菜单已挂「富 HTML」入口（`ExportFormat.RICH_HTML`）。

### 工具总览
- 原有 6 个（read/list/search_in_project/create/edit_document/update_plan）+ 新增 6 个（web_search、save/search/list_memory、search_knowledge、knowledge_stats）= **12 个**，由 `DocumentContextTools.getAllTools()` 聚合，经 `ToolSelector` 按意图裁剪后注入。

## 对话滚动体验修复

- 划词 AI、Agent、普通对话三处的消息列表由 `LazyColumn` 改为非懒 `Column + verticalScroll(ScrollState)`。
- 根因：AI 气泡是 WebView、高度由 marked.js 异步重排决定；`LazyColumn` 回收/重排条目时 WebView 瞬态测出 ~0 高度，导致列表总高塌缩、滚动被钳到顶部（"长回复上滑直接跳顶"），以及强制高度时出现的"白屏"。
- 非懒列表不回收条目 → WebView 不被重建 → 无异步高度塌缩，跳顶与白屏一并消除。自动跟随改为 `scrollState.scrollTo(maxValue)`，逻辑更简、不再依赖 item 高度。

## 手动停止

- 划词 AI（询问/处理）、普通对话新增「思考过程中可手动停止」，与外部 Agent 一致。
- 停止即取消本轮流式协程；半截助手消息收尾——空内容删除不留空气泡、有内容置为非流式（不再显示流式光标），状态复位后可立即继续输入。

## 数据库与配置

- Room v8 → v10：新增 `memories`、`rag_chunks`、`rag_embeddings`、`rag_embedding_jobs` 四表，附 `MIGRATION_8_9` / `MIGRATION_9_10` 与 schema 9.json / 10.json。
- AI 配置新增：`webSearchEnabled` / `webSearchProvider` / `webSearchApiKey` / `embeddingModel`。

## 安全与发布

- Release 签名继续从本地 `keystore.properties` 读取，构建脚本不硬编码密码；`keystore.properties`、`release.keystore`、`local.properties`、`*.apk`/`*.aab` 均被 `.gitignore` 覆盖，不入库。
- APK 作为 GitHub Release 附件发布，不进入源码历史。

## 已知后续事项

- RAG 向量检索为纯线性扫描，文档库 < 1000 足够；更大规模后续换 HNSW / sqlite-vss。
- Web 搜索走网络，DuckDuckGo 免 Key 但限速，生产建议配置付费引擎。
- 富 HTML 导出的样式在各类浏览器/Word 打开的观感建议真机验收。
- v0.8 遗留：WebDAV 同步的文件夹/图片/删除传播/后台自动同步、Markdown HTML 消毒、Room FTS 全文搜索仍为后续专项。
