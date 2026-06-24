# YuMark Agent 功能完成总结

**生成时间**：2026-06-21  
**基于项目**：guanmo (Tauri/React) → YuMark (Kotlin/Android)

---

## ✅ 已完成功能清单

根据设计文档 `C:\Users\luo13\.claude\plans\jaunty-herding-origami.md`，YuMark 已完成 **4.5/5** 个 Phase：

### **Phase 1: 意图检测 + 工具裁剪** ✅ 100%

**核心组件：**
- ✅ `IntentDetector.kt` - 意图打分系统（关键词+正则+上下文）
- ✅ `ToolSelector.kt` - Capability→工具映射与裁剪
- ✅ `ContextBudget.kt` - 工具预算管理（按工具名查表）
- ✅ `AgentUseCases.kt` - 集成意图驱动的工具选择

**新增 Capability 枚举：**
- DOCUMENT_READ（read_document, list_documents, search_in_project）
- DOCUMENT_WRITE（create_document, edit_document）
- WEB（web_search）
- MEMORY（save_memory, search_memory, list_memories）
- KNOWLEDGE（search_knowledge, knowledge_stats）
- TIME（get_current_time）

**效果：**
- 每轮根据用户消息动态裁剪工具集
- 避免全量注入 6+ 工具污染上下文
- 弱关键词 +1 分，强关键词 +3 分，正则 +2 分，得分 ≥4 判定为强依赖

---

### **Phase 2: Web 搜索工具** ✅ 100%

**核心组件：**
- ✅ `WebSearchService.kt` - 5 个搜索引擎适配
  - Tavily（POST Bearer）
  - Serper（POST X-API-KEY）
  - Brave（GET X-Subscription-Token）
  - DuckDuckGo（GET lite.duckduckgo.com，正则解析 HTML）
  - Custom（用户自定义 URL，自动探测 JSON 结构）
- ✅ `WebTools.kt` - `web_search` 工具定义
- ✅ `NetworkModule.kt` - 新增 `@WebSearchClient` 短超时 HttpClient
- ✅ `AiModels.kt` + `AiConfigDataStore.kt` - 新增配置字段
  - `webSearchEnabled: Boolean`
  - `webSearchProvider: WebSearchProvider`
  - `webSearchApiKey: String`

**工具参数：**
```kotlin
web_search(
    query: String,        // 搜索关键词
    max_results: Int = 5  // 返回结果数
)
```

**结果格式：**
```
[来源 1: 标题]
摘要文本
链接: https://example.com

[来源 2: 标题]
...
```

**集成位置：**
- `DocumentContextTools.getAllTools()` 聚合 `WebTools.all`

---

### **Phase 3: 记忆系统** ✅ 100%

**核心组件：**
- ✅ `MemoryEntity` - 记忆数据实体（独立表，与 RAG 完全分开）
- ✅ `MemoryDao.kt` - 数据访问层
- ✅ `MemoryService.kt` - 词法相似度检索与保存
  - `searchMemory(query, mode)` - 词法相似度打分（token 集合余弦 + 子串包含加成）
  - `saveMemory(content, category)` - 自动合并相似记忆（≥0.68）
- ✅ `MemoryTools.kt` - 3 个工具
  - `save_memory(content, category)`
  - `search_memory(query, top_k=5)`
  - `list_memories(limit, offset)`
- ✅ Room 迁移 `Migration_8_9` - 创建 memories 表
- ✅ Schema 文件：`app/schemas/.../9.json`

**记忆分类（category）：**
- `preference` - 用户偏好
- `project` - 项目信息
- `learning` - 学习记录
- `profile` - 用户画像
- `instruction` - 用户指令

**检索排序：**
1. 先按 category 优先级（project > profile > instruction > preference > learning）
2. 再按相似度降序

**Phase 3 不依赖 embedding**（纯词法检索）。Phase 4 embedding 基建就绪后可增强为语义检索。

---

### **Phase 4: RAG 知识库** ✅ 100%

**核心组件：**

#### 4.1 Embedding 基建
- ✅ `EmbeddingAdapter.kt` - Embedding 接口
- ✅ `OpenAiAdapter.kt` - 包含 embedding 实现（POST /embeddings）
- ✅ `AiAdapterFactory.kt` - 集成 embedding 适配器
- ✅ `AiModels.kt` - 新增 `embeddingModel: String` 配置字段

#### 4.2 向量存储
- ✅ `VectorStore.kt` - 纯线性扫描 + 手写余弦相似度
  - 内存 `Map<chunkId, Chunk>` + `Map<chunkId, FloatArray>`
  - 启动时 `hydrateFromDatabase` 全量加载
  - `cosine(a, b)` 手写公式
- ✅ **混合检索** `hybridSearch(query, queryEmbedding, topK, threshold)`
  - 向量 search 取 topK*3，关键词 search 取 topK*3
  - 双命中：`vectorScore*0.72 + keywordScore*0.28 + 0.04`
  - 单命中：取 max
  - contentHash 去重 → 按文档分组轮询填充 → 取 topK
  - preferCurrentFile +0.08

#### 4.3 文档分块
- ✅ `MarkdownChunker.kt` - 语义分块
  - `chunkMarkdown(content, documentId, chunkSize=900, overlap=150)`
  - 按 `#{1,6}` 标题切分，maintain headingStack 得 titlePath
  - 跳过 code fence 内的标题
  - 按空行分块，超长块按句断点切分
  - 聚合到接近 chunkSize 时 push，尾部 overlap 带入下段
  - `contentHash` 去重，`MIN_MEANINGFUL_CHARS=30` 过滤

#### 4.4 RAG 管线
- ✅ `RagPipeline.kt` - 索引与检索
  - `indexDocument(document)` - chunk → 去重 → batchEmbedding → 存储
  - `searchRelevant(query, topK)` - 查询向量 → 混合检索
  - 触发点：`EditorViewModel.saveDocument` 保存后入队 `EmbeddingJobEntity`
  - 后台协程执行，失败重试
  - 索引幂等：contentHash 相同则跳过

#### 4.5 数据层
- ✅ `RagEntities.kt` - 3 个实体
  - `ChunkEntity` - 分块（id, documentId, content, contentHash, titlePath, heading, chunkIndex, startLine, endLine）
  - `EmbeddingEntity` - 向量（chunkId PK, embedding JSON String, model）
  - `EmbeddingJobEntity` - 索引任务（id, documentId, status[pending/running/done/failed], contentHash）
- ✅ `RagDao.kt` - 数据访问层
- ✅ Room 迁移 `Migration_9_10` - 创建 3 张表
- ✅ Schema 文件：`app/schemas/.../10.json`

#### 4.6 工具
- ✅ `KnowledgeTools.kt` - 2 个工具
  - `search_knowledge(query, top_k=5)` - 语义检索
  - `knowledge_stats()` - 返回已索引文档数/分块数

**关键词打分权重：**
- 标题命中：1.8
- titlePath 命中：1.5
- 文件名命中：1.4
- 文档标题命中：1.2
- 正文命中：1.0

**取舍说明：**
- 线性扫描在文档数 < 1000 时足够
- 若用户文档库很大，后续换 HNSW 或 sqlite-vss
- embedding API 调用有成本，contentHash 去重避免重复付费

---

### **Phase 5: 解析器统一（富 HTML 导出）** ⚠️ 90%

**核心组件：**
- ✅ `WebViewDocumentRenderer.renderToRichHtml()` - 富 HTML 导出方法
  - ✅ `renderToRichHtmlString()` - 渲染并提取内部 HTML
  - ✅ `awaitContentInnerHtml()` - 等待 WebView 渲染完成，提取 `<div id="content">` 内的 HTML
  - ✅ `inlineFontDataUris()` - 字体转 Data URI
  - ✅ `buildStandaloneHtml()` - 拼接完整 HTML（内联 KaTeX CSS + Prism CSS）
- ✅ `ExportDocumentUseCase.exportRichHtml()` - 调用 WebView 渲染器
- ✅ `ExportFormat.RICH_HTML` 枚举已添加
- ✅ `Models.kt` - 枚举定义完整

**已实现：**
- ✅ 后端导出逻辑 100% 完成
- ✅ 富 HTML 包含 Prism 高亮、KaTeX 公式、Mermaid 图表
- ✅ 离线可打开（CSS/字体内联）
- ✅ 复用预览管线，与预览渲染一致

**待补充：**
- ❌ 导出 UI 界面未添加"富 HTML"选项
- ❌ 需在 `ExportSheet.kt` 中添加用户可选的格式

**补完步骤：**
1. 打开 `app/src/main/java/com/yumark/app/presentation/editor/ExportSheet.kt`
2. 在导出格式列表中添加 `ExportFormat.RICH_HTML` 选项
3. 显示名称："富 HTML（含样式）"
4. 图标：可复用 HTML 图标

---

## 🎯 新增工具总览

| 工具名 | Phase | 功能 | 参数 |
|--------|-------|------|------|
| `web_search` | Phase 2 | 联网搜索 | query, max_results |
| `save_memory` | Phase 3 | 保存记忆 | content, category |
| `search_memory` | Phase 3 | 检索记忆 | query, top_k |
| `list_memories` | Phase 3 | 列出记忆 | limit, offset |
| `search_knowledge` | Phase 4 | 语义检索知识库 | query, top_k |
| `knowledge_stats` | Phase 4 | 知识库统计 | 无 |

**工具总数：**
- 原有 6 个（read_document, list_documents, search_in_project, create_document, edit_document, update_plan）
- 新增 6 个（上表）
- **总计 12 个工具**

**工具注册机制：**
```kotlin
DocumentContextTools.getAllTools() = 
    listOf(原有 6 个) + 
    WebTools.all + 
    MemoryTools.all + 
    KnowledgeTools.all
```

---

## 📊 数据库变更

### Room 版本：8 → 10

**Migration_8_9（Phase 3）：**
```sql
CREATE TABLE memories (
    id TEXT PRIMARY KEY NOT NULL,
    content TEXT NOT NULL,
    category TEXT NOT NULL,
    source TEXT NOT NULL,
    locked INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'active',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
)
```

**Migration_9_10（Phase 4）：**
```sql
CREATE TABLE rag_chunks (
    id TEXT PRIMARY KEY NOT NULL,
    document_id TEXT NOT NULL,
    content TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    title_path TEXT NOT NULL,
    heading TEXT,
    source_type TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    start_line INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
)

CREATE TABLE rag_embeddings (
    chunk_id TEXT PRIMARY KEY NOT NULL,
    embedding TEXT NOT NULL,  -- JSON: FloatArray
    model TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(chunk_id) REFERENCES rag_chunks(id) ON DELETE CASCADE
)

CREATE TABLE rag_embedding_jobs (
    id TEXT PRIMARY KEY NOT NULL,
    document_id TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    status TEXT NOT NULL,
    error TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
)
```

**Schema 文件：**
- `app/schemas/com.yumark.app.data.local.db.AppDatabase/9.json` ✅
- `app/schemas/com.yumark.app.data.local.db.AppDatabase/10.json` ✅

---

## 🚀 核心改进

### 1. 意图驱动的工具裁剪
**Before：**
```kotlin
val tools = DocumentContextTools.getAllTools()  // 全量注入 6 个工具
```

**After：**
```kotlin
val intent = IntentDetector.detect(userMessage, appContext)
val allTools = DocumentContextTools.getAllTools()  // 12 个工具
val tools = ToolSelector.selectTools(intent, allTools)  // 按需裁剪
```

**效果：**
- "总结这篇文档" → 仅注入 DOCUMENT_READ 工具（3个）
- "改写第二段" → 注入 DOCUMENT_WRITE 工具（2个）
- 闲聊 → 最小工具集（1-2个）
- 减少上下文污染，提升推理效率

### 2. 多源知识增强
**Before：**
- 仅文档库（`search_in_project` 纯子串计数）

**After：**
- 文档库（子串计数）
- **RAG 向量检索**（语义相似度）
- **记忆系统**（用户偏好/项目信息）
- **Web 搜索**（实时外部信息）

### 3. 混合检索算法
**guanmo 原理复现：**
```
混合分数 = 
    if (向量命中 && 关键词命中):
        vectorScore * 0.72 + keywordScore * 0.28 + 0.04
    else:
        max(vectorScore, keywordScore)
```

**去重与多样化：**
- contentHash 去重（同内容分块只保留一个）
- 按文档分组轮询（避免同一文档占据所有 topK）

### 4. 解析器统一
**Before：**
- 预览：marked.js（assets/templates/renderer.html）
- HTML 导出：commonmark 0.21.0（纯 HTML，无高亮/公式）
- PDF/长图：WebView 离屏渲染（有高亮/公式）

**After：**
- 预览：marked.js（不变）
- **富 HTML 导出**：WebView 离屏渲染（与预览一致）
- 纯 HTML 导出：commonmark（保留，供其他工具消费）
- PDF/长图：WebView 离屏渲染（不变）
- DOCX：commonmark AST 遍历（不变）

**消除分叉**：富 HTML 导出与预览渲染 100% 一致。

---

## 🔧 配置项扩展

**新增 AI 配置字段：**
```kotlin
data class AiConfig(
    // 原有字段...
    val provider: AiProvider,
    val model: String,
    val apiKey: String,
    val baseUrl: String,
    
    // Phase 2: Web 搜索
    val webSearchEnabled: Boolean = false,
    val webSearchProvider: WebSearchProvider = WebSearchProvider.DUCKDUCKGO,
    val webSearchApiKey: String = "",
    
    // Phase 4: Embedding
    val embeddingModel: String = ""
)
```

**配置位置：**
- DataStore：`app/data/local/prefs/AiConfigDataStore.kt`
- UI：设置 > AI 配置（需补充 UI 控件）

---

## 📝 测试要点

### 必测场景：
1. **意图检测**：不同问题触发不同工具集（查看 Logcat）
2. **Web 搜索**：配置 DuckDuckGo，询问实时信息
3. **记忆保存与检索**：保存偏好 → 新会话检索
4. **RAG 索引**：保存长文档 → 等待索引完成 → 语义检索
5. **划词 Agent**：选中文本 → 翻译/润色 → 应用编辑
6. **富 HTML 导出**：（待 UI 补完后）导出含样式的 HTML

### 关键日志标签：
- `IntentDetector` - 意图打分
- `ToolSelector` - 工具选择
- `WebSearchService` - 网络搜索
- `MemoryService` - 记忆检索
- `RagPipeline` - 索引与检索
- `VectorStore` - 向量相似度计算
- `MarkdownChunker` - 文档分块

---

## 🎉 移植成果

**从 guanmo (Tauri/React/TS) 到 YuMark (Kotlin/Android)**：
- ✅ 架构完整移植（4.5/5 Phase）
- ✅ 算法精确复现（混合检索、词法相似度、意图打分）
- ✅ 分层设计一致（domain/data/presentation）
- ✅ 数据持久化升级（Room + DataStore）
- ✅ 多 Provider 支持保留（OpenAI/Claude/Gemini）
- ✅ 流式推理保留
- ✅ 版本快照可回退保留
- ✅ 多模态支持保留

**新增功能：**
- 12 个 AI 工具（原 6 个 + 新 6 个）
- 3 张新数据表（memories + 3 张 RAG 表）
- 意图检测系统（6 种 Capability）
- 5 个 Web 搜索引擎适配
- 混合检索算法（向量 + 关键词）
- 富 HTML 导出（后端完成）

**待完成：**
- Phase 5 的导出 UI（工作量 < 1 小时）

---

## 📦 交付物

1. **源代码**：完整的 Kotlin 实现
2. **数据库 Schema**：9.json, 10.json
3. **编译产物**：`app/build/outputs/apk/debug/app-debug.apk`
4. **测试指南**：`AGENT_TESTING_GUIDE.md`（详细测试步骤 + 验证清单）
5. **功能总结**：`AGENT_FEATURES_SUMMARY.md`（本文档）

---

## 🔗 参考文档

- 设计文档：`C:\Users\luo13\.claude\plans\jaunty-herding-origami.md`
- 记忆索引：`C:\Users\luo13\.claude\projects\D--CCguiPlay-Typora-YuMark\memory\MEMORY.md`
- guanmo 源码：`D:\CCguiPlay\guanmo\Guanmo-open\`

---

**总结**：YuMark 已完成从 guanmo 的全面能力移植，新增 6 个 AI 工具、意图检测系统、RAG 知识库、记忆系统、Web 搜索，以及富 HTML 导出（仅差 UI 入口）。代码已编译通过，可进行真机测试。
