# YuMark Agent 功能测试指南

生成时间：2026-06-21

## 测试准备

**APK 位置**：`app/build/outputs/apk/debug/app-debug.apk`

**安装命令**：
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 测试任务清单

### ✅ Task 1: 测试 Agent 基础对话功能

**测试步骤：**
1. 启动 YuMark 应用
2. 打开侧边栏或菜单，找到 Agent/AI 聊天入口
3. 进入 Agent 对话界面（AgentChatSheet）
4. 输入简单问候："你好"
5. 观察回复是否正常流式输出

**预期结果：**
- ✅ Agent 界面正常打开
- ✅ 消息发送按钮可点击
- ✅ 回复正常流式显示（带动画）
- ✅ 对话历史正常显示

**验证点：**
- 消息气泡渲染正常（MessageBubble）
- 流式输出动画流畅（StreamingIndicator）
- 无 crash 或 ANR

---

### ✅ Task 2: 测试划词 Agent 功能

**测试步骤：**
1. 打开或创建一个文档
2. 输入一段中文文本："这是一个测试文本，需要进行修改。"
3. 长按选中部分文本（例如："测试文本"）
4. 等待划词 AI 快捷菜单弹出（AiQuickDialog）
5. 输入指令："翻译成英文"
6. 观察 AI 是否调用 `apply_edit` 工具
7. 点击"应用"按钮

**预期结果：**
- ✅ 选中文本后 AiQuickDialog 正常弹出
- ✅ 输入框预填充了选中的文本上下文
- ✅ AI 回复中包含工具调用（apply_edit）
- ✅ 显示 AgentActionCard 卡片（可应用/拒绝）
- ✅ 点击"应用"后，选中文本被替换为新内容
- ✅ 编辑器光标位置正确

**验证点：**
- `apply_edit` 工具是否正确调用（检查日志：`AiQuickDialogViewModel`）
- 文本替换是否准确无误
- 是否支持函数调用（检查 provider 配置）
- 回退到文本协议时是否有 `[[EDIT]]` 标记

**测试场景扩展：**
- 润色："让这段话更正式"
- 删除："删除选中的文本"
- 扩写："把这个想法展开成一段话"
- 精简："用一句话总结"

---

### ✅ Task 3: 测试意图检测与工具裁剪

**测试步骤：**
1. 打开 Agent 对话界面
2. **场景 A（文档读取）**：
   - 输入："总结这篇文档的核心观点"
   - 观察日志中的工具列表（应该只包含 DOCUMENT_READ 工具）
3. **场景 B（文档写入）**：
   - 输入："帮我改写第二段为更正式的语气"
   - 观察日志中是否包含 DOCUMENT_WRITE 工具
4. **场景 C（闲聊）**：
   - 输入："今天天气怎么样？"
   - 观察日志中的工具列表（应该是最小集合）

**预期结果：**
- ✅ IntentDetector 正确识别意图
- ✅ 不同意图触发不同的工具子集
- ✅ 日志中能看到意图打分和工具选择过程

**验证点（查看 Logcat）：**
```
标签：IntentDetector
内容：检查 capability 得分和 required 集合

标签：ToolSelector
内容：检查最终选择的工具列表
```

**关键日志过滤命令**：
```bash
adb logcat | grep -E "IntentDetector|ToolSelector|AgentUseCases"
```

---

### ✅ Task 4: 测试 Web 搜索工具

**前置条件：**
1. 进入"设置" > "AI 配置"
2. 启用 Web 搜索：`webSearchEnabled = true`
3. 选择搜索引擎：推荐 `DUCKDUCKGO`（无需 API key）
4. 如果选择其他引擎（tavily/serper/brave），需配置 API key

**测试步骤：**
1. 打开 Agent 对话
2. 输入需要联网信息的问题："Vue 3.4 有什么新特性？"
3. 观察 AI 是否调用 `web_search` 工具
4. 检查返回的搜索结果是否包含来源链接

**预期结果：**
- ✅ IntentDetector 识别出 WEB capability（日志打分 ≥4）
- ✅ `web_search` 工具被注入到工具列表
- ✅ AI 主动调用工具进行搜索
- ✅ 返回结果格式：`[来源 1: 标题]\n摘要\n链接: URL`
- ✅ Agent 基于搜索结果回答问题

**验证点：**
- 检查日志中 `WebSearchService` 的网络请求
- 如果 `webSearchEnabled=false`，工具应返回拒绝文本
- 超时和错误处理是否正常

**测试场景扩展：**
- "最近有什么科技新闻？"
- "搜索一下 Kotlin 2.0 的改进"
- "查查北京今天的天气"

---

### ✅ Task 5: 测试记忆系统工具

**测试步骤：**
1. 打开 Agent 对话
2. **保存记忆**：
   - 输入："记住我喜欢简洁的代码风格"
   - 观察是否调用 `save_memory` 工具
   - 确认参数：`content="喜欢简洁的代码风格"`, `category="preference"`
3. **检索记忆**：
   - 新建对话或清空上下文
   - 输入："我之前说过什么偏好？"
   - 观察是否调用 `search_memory` 工具
   - 检查返回的记忆是否包含之前保存的内容
4. **列出记忆**：
   - 输入："列出我的所有记忆"
   - 观察是否调用 `list_memories` 工具

**预期结果：**
- ✅ `save_memory` 成功保存到数据库（memories 表）
- ✅ `search_memory` 能检索到相似记忆（词法相似度 ≥0.68 会合并）
- ✅ 记忆按 category 优先级排序（project > profile > instruction > preference > learning）
- ✅ `list_memories` 返回分页结果

**验证点（查看数据库）：**
```bash
adb shell "run-as com.yumark.app sqlite3 /data/data/com.yumark.app/databases/yumark.db 'SELECT * FROM memories;'"
```

**验证字段：**
- `content`：记忆内容
- `category`：分类（preference/project/learning/profile/instruction）
- `source`：来源（user_explicit/auto_extracted）
- `status`：状态（active/candidate）

**测试场景扩展：**
- 保存项目记忆："记住这个项目使用 Kotlin Coroutines"
- 保存学习记忆："记住我学会了 Jetpack Compose"
- 保存画像："记住我是一个 Android 开发者"

---

### ✅ Task 6: 测试 RAG 知识库工具

**前置条件：**
1. 进入"设置" > "AI 配置"
2. 配置 Embedding 模型：`embeddingModel = "text-embedding-3-small"` 或其他 OpenAI 兼容模型
3. 确保 API 配置正确（baseUrl + apiKey）

**测试步骤：**

#### 6.1 索引文档
1. 创建一个较长的测试文档（至少 2000 字）
2. 文档内容示例：
   ```markdown
   # Kotlin Coroutines 指南
   
   ## 协程基础
   Kotlin 协程是一种轻量级的并发方案...
   
   ## 调度器
   Dispatchers.Main 用于主线程...
   Dispatchers.IO 用于 IO 操作...
   
   ## 挂起函数
   suspend fun 可以暂停执行...
   ```
3. 保存文档（触发自动索引）
4. 等待 30-60 秒（检查日志 `RagPipeline` 和 `EmbeddingJobEntity`）
5. 确认索引任务状态为 `done`

**验证点（查看日志）：**
```
标签：RagPipeline
内容：indexDocument 开始/完成、chunk 数量、embedding 成功
```

#### 6.2 检索知识
1. 打开 Agent 对话
2. 输入："Kotlin 协程的调度器有哪些？"
3. 观察是否调用 `search_knowledge` 工具
4. 检查返回的分块是否相关（应该命中"调度器"章节）

**预期结果：**
- ✅ 文档保存后自动创建 `EmbeddingJobEntity`（pending → running → done）
- ✅ 分块正确（按标题、段落切分，overlap=150）
- ✅ 向量存储成功（chunks 表 + embeddings 表）
- ✅ `search_knowledge` 混合检索返回相关分块（余弦相似度 + 关键词）
- ✅ 去重机制工作（同 contentHash 的分块不重复索引）

**验证点（查看数据库）：**
```bash
# 查看分块
adb shell "run-as com.yumark.app sqlite3 /data/data/com.yumark.app/databases/yumark.db 'SELECT id, document_id, heading, chunk_index FROM rag_chunks LIMIT 10;'"

# 查看向量（embedding 字段为 JSON 数组）
adb shell "run-as com.yumark.app sqlite3 /data/data/com.yumark.app/databases/yumark.db 'SELECT chunk_id, model, substr(embedding, 1, 50) FROM rag_embeddings LIMIT 5;'"

# 查看索引任务
adb shell "run-as com.yumark.app sqlite3 /data/data/com.yumark.app/databases/yumark.db 'SELECT * FROM rag_embedding_jobs ORDER BY created_at DESC LIMIT 5;'"
```

#### 6.3 知识库统计
1. 输入："我的知识库有多少文档？"
2. 观察是否调用 `knowledge_stats` 工具
3. 检查返回的统计信息

**预期结果：**
- ✅ 返回已索引文档数量
- ✅ 返回分块总数
- ✅ 格式清晰易读

**测试场景扩展：**
- 跨文档检索：保存多个文档，询问跨文档的概念
- 语义检索：用同义词查询（例如："并发方案"应该能命中"协程"）
- 更新幂等性：重复保存同一文档，检查是否重复索引（应通过 contentHash 去重）

---

## 日志监控命令

**实时查看所有 Agent 相关日志：**
```bash
adb logcat -c && adb logcat | grep -E "Agent|Intent|Tool|Memory|Rag|WebSearch|Embedding"
```

**过滤特定标签：**
```bash
# 意图检测
adb logcat | grep IntentDetector

# 工具选择
adb logcat | grep ToolSelector

# Agent 执行
adb logcat | grep AgentUseCases

# RAG 管线
adb logcat | grep RagPipeline

# 记忆服务
adb logcat | grep MemoryService

# Web 搜索
adb logcat | grep WebSearchService
```

---

## 已知限制与注意事项

1. **Embedding API 成本**：
   - RAG 索引需要调用 embedding API（收费）
   - 建议先用小文档测试
   - contentHash 去重机制会避免重复计费

2. **网络依赖**：
   - Web 搜索需要网络连接
   - DuckDuckGo 无需 API key 但可能被限流
   - Embedding API 需要网络访问

3. **线性扫描性能**：
   - VectorStore 当前使用线性扫描（非 ANN）
   - 文档数 < 1000 时性能可接受
   - 超过阈值需切换到 HNSW 或 sqlite-vss

4. **工具调用支持**：
   - 部分 AI provider 可能不支持函数调用
   - 划词 Agent 会回退到 `[[EDIT]]` 文本协议

5. **Room 迁移**：
   - 首次运行会执行 Migration_8_9 和 Migration_9_10
   - 旧版本升级应无数据丢失

---

## 测试通过标准

### 最低标准（Must Have）：
- ✅ Agent 基础对话正常
- ✅ 划词 Agent 可以应用编辑
- ✅ 意图检测正确触发工具
- ✅ 至少一个新工具可用（Web/Memory/Knowledge）

### 完整标准（Should Have）：
- ✅ 所有 6 个测试任务通过
- ✅ 数据库迁移无错误
- ✅ 无 crash 或 ANR
- ✅ 日志无严重错误（ERROR/FATAL）

### 理想标准（Nice to Have）：
- ✅ 混合检索准确率高（top-1 相关性 > 80%）
- ✅ 记忆检索响应时间 < 500ms
- ✅ Web 搜索结果质量高
- ✅ 所有边界情况处理正确

---

## 问题排查

### 问题 1：Agent 无响应
**排查步骤：**
1. 检查网络连接
2. 检查 AI 配置（API key/baseUrl）
3. 查看 Logcat 中的错误信息
4. 确认 provider 是否支持工具调用

### 问题 2：工具未被调用
**排查步骤：**
1. 检查 IntentDetector 日志（意图得分是否 ≥4）
2. 检查 ToolSelector 日志（工具是否被注入）
3. 确认配置开关（如 webSearchEnabled）
4. 尝试更明确的指令（例如："使用 web_search 搜索..."）

### 问题 3：RAG 索引失败
**排查步骤：**
1. 检查 embedding 配置
2. 查看 EmbeddingJobEntity 状态（failed 时检查 error 字段）
3. 验证 API 配额和网络
4. 检查文档内容是否过短（< MIN_MEANINGFUL_CHARS）

### 问题 4：划词 Agent 无反应
**排查步骤：**
1. 确认文本已被选中（selectedRange 不为空）
2. 检查 AiQuickDialog 是否正确弹出
3. 查看 apply_edit 工具是否被正确定义
4. 确认 provider 支持函数调用或文本协议回退

---

## 测试报告模板

```markdown
# YuMark Agent 测试报告

测试日期：YYYY-MM-DD
测试人员：XXX
设备型号：XXX
Android 版本：XXX
YuMark 版本：v0.8

## 测试结果总览

| 任务 | 状态 | 备注 |
|------|------|------|
| Agent 基础对话 | ✅/❌ | ... |
| 划词 Agent | ✅/❌ | ... |
| 意图检测 | ✅/❌ | ... |
| Web 搜索 | ✅/❌ | ... |
| 记忆系统 | ✅/❌ | ... |
| RAG 知识库 | ✅/❌ | ... |

## 详细测试记录

### Task 1: Agent 基础对话
- 测试时间：HH:MM
- 测试场景：...
- 实际结果：...
- 截图/日志：...

（重复其他任务）

## 发现的问题

1. **问题标题**
   - 严重程度：High/Medium/Low
   - 复现步骤：...
   - 预期结果：...
   - 实际结果：...
   - 日志/截图：...

## 改进建议

1. ...
2. ...

## 结论

- 通过率：X/6
- 是否可发布：是/否
- 其他说明：...
```

---

## 联系与反馈

如测试过程中遇到问题，请记录：
1. 完整的错误日志（Logcat）
2. 复现步骤
3. 设备信息
4. 预期与实际结果对比

提交 Issue 或联系开发团队。
