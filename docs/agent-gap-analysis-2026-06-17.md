# 设计文档：YuMark Agent 与成熟 AI 编辑器的差距分析及智能化改进路线

- 日期：2026-06-17
- 状态：现状报告 + 智能化路线（含 2026-06-17 代码核验校准）/ 待评审
- 关联代码：`domain/usecase/ai/*`、`data/ai/adapters/*`、`data/ai/StreamRetry.kt`、`core/util/AiErrorMapper.kt`、`presentation/ai/*`、`presentation/editor/AiQuickDialog.kt`

> 对比对象：Claude Code、Cursor、Continue.dev。
> 注意：YuMark 是**文档/笔记**编辑器，不是代码编辑器。本报告只比对可迁移的能力（工具回路、检索、上下文、差分、可观测），不套用代码专属能力（跑终端命令、LSP、代码索引语义搜索等）。

> **2026-06-17 校准说明**：§0–§5 为原始差距分析，其每条现状判断已对照当前代码逐条核验——**核验结论见 §0.1**，已就地标注「✅ 已完成 / ⚠️ 已预埋未接通 / ✓ 仍成立」。§6 为本次新增的「智能化增强路线」（回答"如何让 agent 更聪明"），§7 为结论。

---

## 0. 现状速览

当前"Agent"实质是**带文本协议解析的单轮流式聊天**（每条后附核验状态）：

- 单轮一次性 `sendChatStream`，模型只被请求一次。　**✓ 仍成立**
- 工具/函数调用**回路未接通**：3 个只读检索工具已定义、`ExecuteDocumentToolUseCase` 已实现、接口已加 `tools` 形参且 Agent 已传入，但三家适配器请求体仍不构造 `tools`、不解析 `tool_calls`、从不 emit 有效 `ToolCallDelta`，UseCase 收到 `ToolCallDelta` 直接空转。　**⚠️ 已预埋未接通（见 §0.1）**
- 动作意图靠**自定义文本协议** `[[ACTION]]`（Agent）/`[[EDIT]]`（Quick），弱模型不守约即失效。　**✓ 仍成立**
- 编辑是**整篇覆盖** `doc.copy(content = action.content)`，无 diff、无局部编辑。　**✓ 仍成立**
- 上下文**静态注入** system prompt，三处口径不一（Agent 200 字 / AiQuickDialog 12000 字 / Chat 无）。　**✓ 仍成立**
- ~~错误处理几乎为零：无重试无退避，HTTP 错误原文外泄，解析失败静默。~~　**❌ 已过时：P1.2 已完成（见 §0.1）**，仅"动作解析失败静默"一条遗留。
- 中间步骤**基本不可见**：仅流式文本 + 进度条。　**✓ 仍成立**

骨架（接口、工具、执行器、状态枚举、错误安全网）齐全，**缺"回路"与"智能"**。

---

## 0.1 现状校准（2026-06-17 代码核验）

逐条核验后，文档"缺什么"的判断**基本全部准确**，但"现状速览"落后代码约两个身位：最近几个提交（`Task 12` 等）做的是**接线前的预埋**——接口加形参、定义工具、写执行器、把 tools 传进去，但三个关键接头仍是 `// TODO`。

### A. 已完成（文档原写成"待做"）

| 项 | 实际状态 | 证据 |
|---|---|---|
| P1.2 错误重试/友好化 | **三家适配器全部完成** | `AiErrorMapper.kt`（401/403/404/400/429/5xx + 超时/网络分层映射，**不外泄响应体原文**）+ `StreamRetry.kt` 的 `withRetryAndEmissionGuard`（指数退避 3 次、首字节后失败不重试避免重复输出、`CancellationException` 透传）；接入点 `OpenAiAdapter.kt:77` / `ClaudeAdapter.kt:70` / `GeminiAdapter.kt:81` |

### B. 已预埋但未接通（"埋了线，三个接头没接"）

| 已就绪的预埋 | 证据 | 仍 TODO 的接头 | 证据 |
|---|---|---|---|
| 接口 `sendChatStream` 已加 `tools` 形参 | `AiApiAdapter.kt:23-27` | ①适配器请求体不构造 tools | `OpenAiAdapter.kt:57-74` |
| 3 个只读工具已定义 | `DocumentContextTools.kt` | ②适配器不解析 tool_calls/tool_use/functionCall（只取 content） | `OpenAiAdapter.kt:93-101` |
| 执行器 `ExecuteDocumentToolUseCase` 已实现 | `ExecuteDocumentToolUseCase.kt:24-29` | ③UseCase 收到 `ToolCallDelta` 空转 | `AgentUseCases.kt:102-110` |
| `ChatMessage` 已含 `toolCalls`/`toolCallId`（role=tool 回填字段就绪） | `AiApiModels.kt:43-44` | 三家 TODO 同构 | `ClaudeAdapter.kt:52-55`、`GeminiAdapter.kt:55-58` |
| `AiTool`/`ToolCall` 模型已定义 | `AiModels.kt:110-123` | | |
| Agent 已把 tools 传入 sendChatStream | `AgentUseCases.kt:92` | | |

> 含义：**从用户视角看工具回路完全没通**——这恰好印证文档 P2.1 的"伪 agent"判断。预埋降低了 P2.1 的部分成本（接口/模型/执行器不用重做），但真正的难点（适配器累积器 + 回路 + 事件完成语义，见 R1/R2）一分未动。

### C. 仍然准确成立的核心判断

| 判断 | 证据 |
|---|---|
| 单轮、无 ReAct 循环 | `AgentUseCases.kt` 整个 `collect` 无回填再请求 |
| 文本协议 `[[ACTION]]`/`[[EDIT]]` | `AgentUseCases.kt:229` `parseAgentAction`；`AiQuickDialog.kt:617-630` |
| EDIT 整篇覆盖无 diff | `AgentUseCases.kt:182` `doc.copy(content = action.content)` |
| 上下文三处口径不一 200/12000/0 | `AgentUseCases.kt:199` take(200)、`AiQuickDialog.kt:50` `DOC_CONTEXT_CHAR_BUDGET=12000`、`SendChatMessageUseCase.kt:70-76` 无 systemPrompt |
| `ToolCallDelta` 无"单次调用完成"信号 | `AiApiModels.kt:9-13` 仅 callId/name/argumentsDelta |
| `Message` 单一 content、无 steps | `AiModels.kt:68-76` |
| 只有 3 个只读工具，无写工具 | `DocumentContextTools.kt`、`AgentActionType` 仅 CREATE/EDIT (`AiModels.kt:80-83`) |
| `search_in_project` 是纯 `contains` 字符串匹配（非语义） | `ExecuteDocumentToolUseCase.kt:88` |
| 动作解析失败静默当普通对话 | `AgentUseCases.kt:113` 返回 null 即无提示 |

---

## 1. 四维差距对比

### 1.1 工具设计粒度

| 维度 | 成熟编辑器 | YuMark 现状 |
|---|---|---|
| 原则 | **原语化、正交、可组合**：Claude Code 的 Read/Write/Edit/Grep/Glob/Task；Cursor 的 read_file/edit_file/codebase_search | 3 个工具全是**只读检索**（已定义但回路未通）；编辑**不靠工具**，靠文本协议 |
| 编辑粒度 | 字符串级/行级 `str_replace`、定向编辑、可审 hunk | **整篇 `doc.copy(content = action.content)` 覆盖**，无局部编辑、无 diff |
| 写工具 | 有（Edit/Write） | **无**——突变(mutation)走"先吐全文再解析"，与检索工具是两套范式 |

**最大倒置**：成熟编辑器用**工具**来编辑（可靠、可观测、可拒绝单步），YuMark 用**文本协议**来编辑（脆弱、全篇覆盖、看不清改了啥）。`ExecuteDocumentToolUseCase` 存在却没有 `edit_document`/`create_document` 工具，编辑能力反而绕开了工具层。

### 1.2 上下文管理

| 维度 | 成熟编辑器 | YuMark 现状 |
|---|---|---|
| 取上下文 | **按需检索**：Cursor embedding 索引全库；Claude Code 用 Read/Grep 现取；Continue 可插拔 context provider + RAG | **静态注入 system prompt**，三处口径不一（Agent 200 / Quick 12000 / Chat 0）；检索工具已定义但未接通 |
| 预算 | token 级预算 + 长对话**自动压缩(compaction)** + **prompt caching** | 粗暴 char 截断（仅文档注入，非 token 预算），每次消息重发全文，无缓存 |
| 范围 | 多文档/项目级/workspace | 单文档，无项目/工作区上下文 |
| 检索质量 | 语义/embedding 检索，按相关度排序 | `search_in_project` 是纯 `contains` 大小写无关匹配，无相关度排序 |

**讽刺点**：已有 `search_in_project`/`read_document` 工具——正是"按需取上下文"的能力，却没接通，所以只能"系统提示词塞死"。

### 1.3 规划能力

| 维度 | 成熟编辑器 | YuMark 现状 |
|---|---|---|
| 模式 | 真 ReAct 循环 + 任务拆解 | 单轮一次性 |
| 计划表征 | Claude Code todo list + plan mode + 检查点；Cursor Composer plan + checkpoint | **无**：无 todo、无 plan、无检查点 |
| 复杂任务 | 先研究后改、可多轮迭代、可分派子 agent | 一锤子输出，模型必须"一次想对并写完整文档" |

没有循环 = 无法做"先搜索相关文档 → 阅读 → 再决定怎么改"这种多步任务。长任务全靠模型一次写对，脆弱。

### 1.4 用户可观测性

| 维度 | 成熟编辑器 | YuMark 现状 |
|---|---|---|
| 中间步骤 | **完整可追溯**：每个工具调用+结果可见、流式 narration、todo 进度 | 只有**流式文本 + 进度条/转圈**，`ToolCallDelta` 被 no-op 吞掉 |
| 编辑可见性 | **行级 diff、逐 hunk 审阅、checkpoint 回滚** | `AgentActionCard` 折叠"查看内容"——**无 diff**，整篇覆盖前看不清"改了哪几行" |
| 失败可读性 | 明确告警原因 | HTTP 错误**已友好化**（P1.2 完成）；但 `parseAgentAction` 返回 null 时**静默当普通对话**，用户困惑 |

整篇盲改 + 无 diff + 动作解析静默失败 = **信任风险最高**的环节，也是改进中**最该优先解决**的安全网。

---

## 2. 改进清单（按 ROI 排序）

ROI = 价值 ÷ 工作量。工作量以当前代码栈（Kotlin/Compose/Room/Ktor）为基准，单位"人天"。

### P1.1　编辑改为「定向编辑 + 差分预览」　⭐ ROI 最高

- **问题**：`EDIT_DOCUMENT` 整篇 `saveDocumentUseCase(doc.copy(content = action.content))` 覆盖，无 diff、不可逐块审阅、可能误删用户内容。当前最大信任风险。
- **改进方案**：
  1. 引入 diff 计算（标准 LCS 算法，无新依赖或加 `diff-match-patch` 这类小库）。
  2. `AgentActionCard` 增加"查看改动"视图：高亮增删行，支持**逐 hunk 接受/拒绝**。
  3. 增量编辑工具 `edit_document_range`（定位锚/原文片段 → 新片段），与整篇替换并存。
- **工作量**：3–4 人天（diff 算法 + UI 是主要成本，数据层已有 `SaveDocumentUseCase`）。
- **优先级**：**P0**。与工具回路解耦，可先行落地。

### P1.2　错误处理 / 重试 / 友好化　✅ 已完成（2026-06-17 核验）

- **状态**：**已落地，三家适配器全部接入**。
  - `core/util/AiErrorMapper.kt`：HTTP 状态码分层映射（401→"API Key 无效"、403/404/400、429→"限流稍后重试"、5xx→"服务暂不可用"）+ 异常映射（超时/网络），**不再外泄响应体原文**；并提供 `isRetryableStatus`/`isRetryableException`/`backoffMillis`（指数退避 + 抖动）。
  - `data/ai/StreamRetry.kt` 的 `withRetryAndEmissionGuard`：首字节前失败按指数退避重试最多 3 次；**已 emit Content 后的 mid-stream 失败不重试**（避免重复输出）；`CancellationException` 永远向上传播；重试耗尽 emit 友好 `StreamEvent.Error`。
  - 接入点：`OpenAiAdapter.kt:77`、`ClaudeAdapter.kt:70`、`GeminiAdapter.kt:81`。
- **遗留（仍待做）**：`parseAgentAction` 解析失败时仍静默（`AgentUseCases.kt:113` 返回 null 当普通对话），无可见提示。这条并入 P2.2 / §6.4（文本协议退场后自然消失，过渡期补一条提示即可，约 0.5 人天）。

### P2.1　接通工具调用回路（适配器解析 + ReAct 循环）　⭐ 基础性最高

- **现状校准**：骨架已预埋（接口 `tools` 形参 `AiApiAdapter.kt:23-27`、3 工具 `DocumentContextTools.kt`、执行器 `ExecuteDocumentToolUseCase.kt`、`ChatMessage.toolCalls/toolCallId` `AiApiModels.kt:43-44`、Agent 已传 tools `AgentUseCases.kt:92`）。**但三个接头仍 TODO**：①请求体不发 tools、②不解析 tool_calls、③`ToolCallDelta` 空转。预埋省下接口/模型/执行器的活，**真正难点（R1/R2）未动**。
- **问题**：三家适配器请求体不带 `tools`、不解析 tool_calls、不 emit 有效 `ToolCallDelta`；`ExecuteDocumentToolUseCase` 永不触发。这是"伪 agent"的根因。
- **改进方案**：
  1. **先纠正事件模型（前置）**：`StreamEvent.ToolCallDelta` 当前无"本次工具调用完成"信号（`AiApiModels.kt:9-13` 仅 callId/name/argumentsDelta），`Done` 是"整个流结束"而非"单个工具调用结束"。需补工具调用的完成语义（如 `ToolCallDelta(done: Boolean)` 或独立的 `ToolCallComplete` 事件）。
  2. **适配器实现"流式工具调用累积 + 完成检测"（本项真正的大头，见 §4 风险 R1）**：
     - OpenAI：`tool_calls` 按 `index` 跨 SSE 分片增量拼接 `arguments`，需按 index 累积、以 `finish_reason==tool_calls` 判完成；
     - Claude：`tool_use` 离散事件 + `input_json_delta`，按 id 累积、以 `content_block_stop` 判完成；
     - Gemini：`functionCall`，按候选累积。
     三家各需一个**有状态累积器**，不能简单"逐行 emit"。
  3. 三家适配器：请求体加 `tools`/`functions`（把 `AiTool.parameters` 这个 `Map<String,Any>` 序列化为各家 JSON Schema 格式）；请求体 messages 透传 `toolCalls`/`toolCallId`（字段已就绪）；完成检测后 emit 完整 `ToolCallDelta`。
  4. use case 实现循环：检测到工具调用完成 → `ExecuteDocumentToolUseCase` 执行 → **工具结果截断后**以 `role=tool` 回填上下文 → **再次请求** → 直到无工具调用。循环设最大步数（如 8 步）与超时防跑飞。
- **工作量**：**10–14 人天**（三家累积器各 ~3 天 + 事件模型修正 + 循环/回填/结果截断/防失控 3–4 天）。**先只做 OpenAI 兼容一家**验证闭环（约 5–6 人天），再复制到 Claude/Gemini。
- **优先级**：**P1**，分水岭。

### P2.2　用原生工具调用替换文本协议 `[[ACTION]]`/`[[EDIT]]`

- **问题**：动作意图靠自定义文本协议，弱模型不守约就失效——这正是「处理模式 edit 卡片依赖 `[[EDIT]]` 标记、无标记即无法应用」的同一根因。
- **改进方案**：编辑/建文档也做成工具（`create_document`/`edit_document`），随 P2.1 一起进 tools 列表，逐步**废弃 `[[ACTION]]`/`[[EDIT]]`**（过渡期双路并存，优先信任工具调用）。
- **工作量**：2 人天（在 P2.1 完成基础上）。
- **优先级**：**P1**，依赖 P2.1。

### P3.1　中间步骤可观测性（工具调用 / 思考步骤 UI）

- **问题**：用户看不到 agent 在做什么、调了哪个工具、读了哪篇文档。
- **改进方案**：
  1. `StreamEvent` 增加 `ToolCall`/`ToolResult`/`Step` 类型；`MessageBubble` 渲染"🔧 调用 read_document → 返回 N 字"这类折叠条目（参考 Claude Code 的 narration + tool 卡片）。
  2. 流式时显示当前进行步骤。
- **工作量**：2–3 人天。
- **优先级**：**P2**。依赖 P2.1 产出事件才有内容可显示。

### P3.2　上下文按需检索 + token 预算 + 缓存

- **问题**：静态塞死、三处口径不一、每次重发全文。
- **改进方案**：
  1. 借 P2.1 接通的 `list_documents`/`search_in_project`/`read_document` 让 agent **按需取**，默认只在 system prompt 注入轻量索引/大纲而非全文。
  2. 统一一个 `ContextBudget`（token 估算 char/4），Agent 也从 200 字提到"大纲 + 按需读取"。
  3. 复用对话已取过的文档结果（轮内缓存），避免重复 `read_document`。
- **工作量**：2–3 人天（部分随 P2.1 自然获得）。
- **优先级**：**P2**，与 P2.1 并行收益最大。

### P3.3　规划 / 任务拆解（todo list）

- **问题**：无规划，长任务靠模型一次写对。
- **改进方案**：对话级 todo（`TaskCreate` 风格的状态条），agent 先输出计划 → 逐步执行打钩；适合"整理多篇笔记 / 批量改写"等多步任务。
- **工作量**：2–3 人天。
- **优先级**：**P2**。笔记场景多数任务简单，收益不及代码场景，排在中段。

### P4.1　长对话上下文压缩（compaction）

- **问题**：长对话会撑爆窗口。当前只对**注入的文档**做了字符截断（AiQuickDialog 的 12000 字预算），但**对话历史**没有任何预算，长对话照样爆窗。
- **改进方案**：达到预算阈值时，把早期消息压成摘要再继续。
- **工作量**：3–4 人天。
- **优先级**：**P3**。笔记对话通常较短，可延后。

### P4.2　编辑 checkpoint / 撤销（Cursor 式）

- **问题**：编辑不可回滚，整篇覆盖后无后悔药。
- **改进方案**：应用编辑前存一个快照（内存或 Room 版本表），提供"撤销本次编辑"。
- **工作量**：3 人天（若与 P1.1 的 diff 视图合并设计，可降到 1–2 天）。
- **优先级**：**P3**。建议和 P1.1 合并做。

### P4.3　多文档 / 工作区上下文

- **问题**：单文档视野，无法跨文档联动。
- **改进方案**：workspace 级摘要 + 检索工具已天然支持多文档，配合 P2.1/P3.2。
- **工作量**：2 人天（基础设施随 P2.1）。
- **优先级**：**P3**。

---

## 3. ROI 一览与执行路径

| 优先级 | 项 | 工作量 | 价值 | ROI | 状态 |
|---|---|---|---|---|---|
| **P0** | P1.2 错误重试/友好化 | 1–1.5 d | 中高 | ★★★★★ | ✅ 已完成 |
| **P0** | P1.1 差分预览 + 定向编辑 | 3–4 d | 高（信任/安全） | ★★★★★ | 待做 |
| **P1** | P2.1 工具回路（先 OpenAI 兼容闭环） | 5–6 d 先行／ 10–14 d 全量 | 极高（分水岭） | ★★★★ | 已预埋·未接通 |
| **P1** | P2.2 文本协议退场 | 2 d | 高 | ★★★★ | 待做 |
| **P2** | P3.1 步骤可观测 UI | 2–3 d | 中 | ★★★ | 待做 |
| **P2** | P3.2 按需检索 + 预算/缓存 | 2–3 d | 中高 | ★★★ | 待做 |
| **P2** | P3.3 规划/任务拆解 | 2–3 d | 中 | ★★★ | 待做 |
| **P3** | P4.1 长对话压缩 | 3–4 d | 中 | ★★ | 待做 |
| **P3** | P4.2 checkpoint/撤销 | 3 d | 中 | ★★ | 待做 |
| **P3** | P4.3 多文档/工作区上下文 | 2 d | 中 | ★★ | 待做 |

> 智能化增量项（§6 新增：语义检索、自我纠错、统一意图入口、agent 策略/提示词、评测）单列在 **§6.6**，按"建立在 P2.1 回路之上"的依赖关系排入第三/四波。

> **ROI ≠ 重要性**：ROI 排的是"性价比（价值÷工作量）"，**不代表 P2 不如 P1 重要**。P1 因工作量小而性价比高、应先做；P2（工具回路）是**能力分水岭**——是"能不能成为真 agent"的关键，只是投入更大。两者是"先摘低垂果实"与"啃硬骨头"的关系，不是优先级倒挂。

### 建议执行波次

```
第一波（快赢，约 1 周）
  ✅ P1.2 错误重试（已完成） + P1.1 差分预览
  → 消除"整篇盲改"信任风险（错误外泄已解决）

第二波（分水岭，2–3 周）
  P2.1 工具回路（先 OpenAI 兼容一家闭环）
  → 真正变成 agent（达成智能成熟度 L1→L2，见 §6.0）

第三波（体验 + 智能，约 2–3 周）
  P2.2 文本协议退场 + P3.1 步骤可观测 + P3.2 检索上下文
  + §6 智能化增量：自我纠错(6.2) / 语义检索(6.3) / agent 策略(6.2)
  → 用户能看见、能信任、上下文精准，agent 会纠错（L3）

第四波（进阶，按需）
  P3.3 规划 / P4.1 压缩 / P4.2 checkpoint
  + §6 统一意图入口(6.3) / 跨文档自主(6.3) / 评测基线(6.5)
  → 懂整个笔记库、自动路由（L4）
```

---

## 4. 关键技术约束与风险

- **R1 工具调用累积/完成检测是 P2.1 的真正难点（工作量原被低估）**：流式 tool_call 跨 SSE 分片、三家协议各异（OpenAI 按 index 增量、Claude 按 id + `input_json_delta`、Gemini functionCall），且当前事件模型**无法判定"一次工具调用何时拼完"**（`ToolCallDelta` 无完成信号、`Done` 是流结束而非工具调用结束）。需先补事件语义，再为每家写有状态累积器。这是 P2.1 工作量从原估 6–8 天**上修到 10–14 天**的主因。预埋的形参/模型**不缓解**此风险。
- **R2 数据模型不支持多步循环（P2.1 的前置改造，最易被忽略）**：现 `Message` 实体（`AiModels.kt:68`）为"单一 content + 至多一个 agentAction + isStreaming"，`AgentMessageState`（`AgentUseCases.kt:26-33`）只有 `UserMessageSaved/AssistantMessageStarted/Streaming/ActionProposed/Completed/Error`——**只适配单流式**。ReAct 循环里一次提问会产生"多轮工具调用 → 多个 tool 结果 → 最终文本"的离散步骤，**塞不进现有 Message**。接通回路前必须扩 Message（加 `steps: List<AgentStep>` 之类）、扩状态机（`ToolCalled/ToolResult`）、做 Room schema 迁移。原未计入工作量，建议并入 P2.1 前置（+2–3 天）。
- **R3 工具结果必须截断（不应埋在 P3.2）**：`read_document` 接通后，模型可能一次读 8 万字笔记 → 单步爆窗。**每次 tool result 都要单独截断**，不是只给 system prompt 的文档注入做预算。属 P2.1 前置。
- **R4 安全网先行**：P1.1（diff）与工具回路解耦，应**在任何 agent 能力扩张之前**完成，避免"会跑了但会摔"。P1.2 错误安全网已就位。
- **R5 prompt caching 非通用**：成熟编辑器的 prompt caching 依赖提供商 API（Anthropic / 部分 OpenAI）。YuMark 大量用户走 Ollama / DeepSeek 等 OpenAI 兼容端点，未必支持。作为对标可写，但改进方向须标注"依赖提供商、非通用"。
- **R6 智能化能力受模型能力下限约束（§6 通用前提）**：YuMark 允许用户接任意 OpenAI 兼容端点（含小参数本地模型）。ReAct 多步、自我纠错、语义意图判断**依赖模型本身的工具调用与指令遵循能力**，弱模型可能：不发 tool_call、参数 JSON 畸形、循环不收敛。§6 各项必须配**降级路径**（工具调用不可用时回退到文本协议 / 单轮）与**防失控闸门**（最大步数、循环检测、超时），不能假设模型一定"聪明"。
- **资源依赖**：P3.1（可观测）依赖 P2.1 产出事件；P3.2（检索）天然随 P2.1 获得；P4.x 与 §6 大多建立在回路之上。

---

## 5. 设计决策记录

### D1 编辑安全范式：**混合模式**（已定 2026-06-17）

P1.1 的"diff 逐 hunk 审阅"与 P2.2 的"编辑做成自主工具"是**同一编辑安全机制的两种范式**，定调如下：

- **`CREATE_DOCUMENT` —— 直接落库**：创建不覆盖任何原有内容，无破坏性，不打断 agent 流；用户可在事后删除/导航到新文档。
- **`EDIT_DOCUMENT` —— 必经 diff 批准**：工具调用先生成 diff，**用户逐 hunk 批准后才写库**（把 P1.1 的 diff 卡片升级为 EDIT 工具执行的批准门）。改用户既有笔记前必须让其看清改了什么。

**对实施的影响**（决定 P1.1 / P2.2 / P4.2 如何合并）：

- **P1.1 的 diff 视图是 EDIT 路径的强制闸门**，不是可选美化——其优先级因此从"ROI 最高"进一步明确为"EDIT 工具落地的前置依赖"。
- **P2.2 落地 `edit_document` 工具时，调用即暂停循环等待用户批准**，批准后才写库并继续后续步骤；拒绝则中断该轮编辑。
- **P4.2 checkpoint** 降级为**二级保险**（用户手滑批准错时仍可撤销），而非 EDIT 的主安全机制。
- 排期：第一波 P1.1 必须在第二波 P2.2 之前完成，因为 diff 闸门是 EDIT 工具落地的硬前置。

### D2 与在途 attachment-support 设计的边界（已定 2026-06-17）

规划顺序：**先完成本 agent 路线（P1–P4 + §6），再做 attachment-support（图片/附件）**。读毕 `2026-06-15-ai-attachment-support-design.md`（v1.1，Phase 1 = `AiQuickDialog` 图片多模态 + OpenAI/Claude），边界划分如下：

**职责归属**：

| 归属 | 主题 | 负责文档 |
|---|---|---|
| Agent 路线 | 适配器 `tools`/tool-call 解析、ReAct 循环、`[[EDIT]]`/`[[ACTION]]` 协议、Message steps/状态机、上下文注入策略统一（200/12000）、错误/重试、§6 智能化 | **本文** |
| Attachment 路线 | `MessageContent`/`contentParts` 多模态模型、`ImageProcessor`、Photo Picker、附件 UI | attachment-support |

**关键结论**：两份文档改的是相邻代码、但**职责正交、无协议冲突**——
- attachment **不碰** `[[EDIT]]`/`[[ACTION]]` 协议 → **协议归本文**；
- attachment **不改**上下文注入策略（200/12000 字）→ **统一归本文 P3.2**；
- attachment 只在**输入侧**加图片，本路线只在**工具/回路侧**改造。

**共享改动面（都改但正交，靠"先后顺序"避免合并冲突）**：

| 共享代码 | 本路线改 | attachment 改 | 协调 |
|---|---|---|---|
| 适配器 `sendChatStream` 请求体 | 加 `tools` 字段 + tool-call SSE 解析 | content 数组化 | **先本路线**，attachment 在重构后的适配器上叠加 |
| `ChatMessage`（`AiApiModels.kt`） | 用 `role=tool` 回填（字段已就绪） | 加 `contentParts` | 正交，不冲突 |
| `ConversationMessage`（`AiQuickViewModel`） | 已改（edit fallback） | 加 `attachments` | 正交 |

**Room 迁移衔接点（重要）**：attachment Phase 2 要给 `MessageEntity` 加 `attachmentsJson` 列；本路线 P2.1/R2 也要给 Message 加 `steps`。**两项 Room schema 变更应合并到同一迁移版本号**，避免分两次演进 schema。本路线先行正好把 Message 模型扩好，attachment Phase 2 直接在同一结构上加 `attachmentsJson`。

> `docs/ai-improvements-2026-06-15.md` 多为已完成项（AI 输出 Markdown 渲染等），与本文无规划期冲突，仅作历史参考。

---

## 6. 智能化增强路线（让 agent "更聪明"）

§1–§5 解决的是**能力底座**——把"伪 agent"补成"能用的真 agent"（回路 + 安全网 + 可观测）。本节回答另一个问题：**底座之上，怎么让它更聪明**。两者是"先有手脚，再有脑子"的关系；脱离 §1–§5 单做本节没有意义（无回路则无从纠错、无从多步检索）。

### 6.0 智能成熟度分级（统一标尺）

用一条分级线给"更智能"一个可度量的目标，避免空谈：

| 级别 | 能力 | 对应实现 | 当前 |
|---|---|---|---|
| **L0** 文本协议单轮 | 模型一次输出，靠 `[[ACTION]]` 表达意图 | 现状 | **← YuMark 在此** |
| **L1** 单步工具调用 | 模型能调 1 次工具、拿结果、再答 | P2.1 闭环（接头接通） | |
| **L2** ReAct 多步 | 多轮"思考→调工具→观察"，先搜后读再改 | P2.1 循环 + R2 数据模型 | |
| **L3** 自我纠错 + 规划 | 工具失败会重试/换策略；先列 todo 再执行 | §6.2 | |
| **L4** 跨文档自主 + 懂意图 | 语义检索全库、自动判断问答/改写/创建并路由 | §6.3 | |

**目标**：第二波到 L2，第三波到 L3，第四波到 L4。每一级都必须带 R6 的降级路径——弱模型停在它能达到的最高级，不崩。

### 6.1 工具智能：从"3 个只读工具"到"可组合工具集 + 会选工具"

- **现状痛点**：只有 read/list/search 三个只读工具（`DocumentContextTools.kt`），且编辑绕开工具层走文本协议；工具描述虽有但未经"模型视角"打磨。
- **智能设计**：
  1. **补齐写工具**：`create_document` / `edit_document`（接 P2.2 + D1，edit 必经 diff 批准）/ `edit_document_range`（定向编辑，接 P1.1）。让"读—改"在同一工具范式内闭环。
  2. **工具描述即提示词工程**：每个工具的 `description` 要写清 **what / when / 不该用的场景 / 参数示例**（当前描述偏简）。工具选得准比工具多更重要。
  3. **正交、原语化**：避免"大而全"工具；宁可多个小工具组合（对标 Claude Code Read/Edit/Grep 的拆分）。
- **关系**：是 P2.1/P2.2 的"质量层"——回路通了之后，工具集与描述质量决定 agent 调得准不准。
- **工作量**：写工具随 P2.2（已计）；描述打磨 0.5–1 人天。

### 6.2 推理与自主智能：ReAct 质量 + 自我纠错 + 规划 + agent 策略

- **现状痛点**：单轮、无纠错；system prompt 仅几行（`AgentUseCases.kt:205-225`），只教模型输出 `[[ACTION]]`，没有角色设定、工具使用守则、错误恢复指引。
- **智能设计**：
  1. **ReAct 思考节奏**：循环中允许模型先输出简短"思考/计划"再调工具（可用 `<thinking>` 或独立 step），让多步任务有章法。
  2. **自我纠错（L3 核心）**：工具返回错误（如 `read_document` 文档不存在 `ExecuteDocumentToolUseCase.kt:37`）或结果不符预期时，把错误**作为 tool result 回填**让模型换策略重试，而不是直接失败或硬写。设单工具最大重试次数。
  3. **任务规划（接 P3.3）**：复杂任务先产出 todo 列表 → 逐步执行打钩；适合"把这 5 篇笔记整理成一篇综述"。
  4. **agent 策略/系统提示词升级**：把简陋 prompt 扩成结构化——角色、能力边界、工具使用守则（何时检索 vs 直接答）、输出规范、安全约束（改文档前必须 diff）、必要的 few-shot。这是"看起来更聪明"的最高杠杆、最低成本项。
- **关系**：建立在 P2.1 循环之上；与 R6 强绑定（弱模型降级为不纠错的单步）。
- **工作量**：提示词升级 1 人天；自我纠错循环 1–2 人天（在 P2.1 循环里加分支）；规划 = P3.3。

### 6.3 上下文与检索智能：语义检索 + 按需取数 + 统一意图入口

- **现状痛点**：
  - `search_in_project` 是纯 `contains` 匹配（`ExecuteDocumentToolUseCase.kt:88`），同义/相关内容找不到；
  - 上下文三处口径割裂（200/12000/0）；
  - **三套入口割裂**：Agent（`SendAgentMessageUseCase`）、Chat（`SendChatMessageUseCase`，无文档上下文、无工具）、Quick（`AiQuickDialog`）各走各的，用户得自己选"用哪个 AI"。
- **智能设计**：
  1. **检索升级**：`contains` → 关键词归一化 + 分块匹配 + 相关度排序；进阶可选 on-device embedding 语义检索（标注"可选、依赖额外模型/库"，受 R6 约束，非默认）。
  2. **按需取数 + 预算 + 缓存**（接 P3.2）：默认只注入大纲/索引，让 agent 用工具按需读；统一 `ContextBudget`；轮内缓存已读文档。
  3. **统一入口 + 意图识别（L4 体验关键）**：收敛到一个 AI 入口，由模型/轻量分类**自动判断意图**——纯问答、选区改写、整篇编辑、跨文档检索——再路由到对应能力，而不是让用户预先选模式。这是"更智能"最直接的用户感知项。
  4. **跨文档/工作区上下文**（接 P4.3）：检索工具天然支持多文档，配合语义检索实现"在我的笔记库里找答案"。
- **关系**：检索质量与按需取数随 P2.1/P3.2；统一入口是较大的 UX 重构，排第四波。
- **工作量**：检索增强 1–2 人天；语义检索（可选）3–5 人天；统一入口 + 意图路由 3–4 人天。

### 6.4 编辑与交互智能：可信 + 可见

- **现状痛点**：整篇盲改无 diff；中间步骤不可见；动作解析失败静默（P1.2 遗留）。
- **智能设计**：
  1. **diff 逐 hunk**（接 P1.1/D1）——EDIT 的强制闸门。
  2. **步骤可观测 + 智能 narration**（接 P3.1）：不只罗列"调用了 X"，而是用一句自然语言说明"正在查找与『预算』相关的笔记 → 读取《Q2 计划》→ 准备在第 3 节插入"。让过程像"看得懂的助手"。
  3. **解析失败可见化**：过渡期 `parseAgentAction` 返回 null 但疑似想操作时给提示（P1.2 遗留项）；P2.2 后由工具调用取代，问题消失。
  4. **checkpoint**（接 P4.2）：二级保险。
- **工作量**：多为引用既有 P 项；智能 narration 增量 0.5–1 人天（在 P3.1 事件上加文案层）。

### 6.5 智能化评测（怎么证明"更智能了"）

没有评测，"更智能"无法验收、容易退化。建立一组**固定验收任务集 + 指标**，每次改动回归：

- **任务集（覆盖各成熟度级）**：
  1. 单步问答（L1）：基于当前文档回答一个问题；
  2. 单步检索（L1/L2）："我之前写过关于 X 的笔记吗" → 期望调 `search_in_project`；
  3. 多步检索改写（L2）："参考《A》把《B》的引言改写" → search→read→edit；
  4. 跨文档整理（L4）："把这几篇周报汇总成月报"；
  5. 错误恢复（L3）：给一个不存在的文档名，看是否换策略而非硬失败。
- **指标**：工具调用成功率（JSON 合法 + 工具存在）、任务完成率、平均步数、无效/越界编辑率、用户编辑接受率、首 token / 总时延（已有 `ModelTestResult` 可复用思路）。
- **基线**：先在当前 L0 跑一遍记录基线（多数任务会失败/退化为纯文本），每完成一波对比。
- **工作量**：脚本化任务集 + 记录 1–2 人天；建议挑 1–2 个强模型 + 1 个弱模型（验证 R6 降级）作对照。

### 6.6 智能化项并入波次与 ROI

| 智能化项 | 依赖 | 工作量 | 价值 | 建议波次 |
|---|---|---|---|---|
| 6.1 工具描述打磨 | P2.1 | 0.5–1 d | 中高（调得准） | 第二波尾 |
| 6.2 agent 策略/提示词升级 | P2.1 | 1 d | **高（最高杠杆）** | 第二波尾 |
| 6.2 自我纠错循环 | P2.1 循环 | 1–2 d | 高（鲁棒性，L3） | 第三波 |
| 6.3 检索质量增强（非语义） | P2.1 | 1–2 d | 中高 | 第三波 |
| 6.5 评测基线 | P2.1 | 1–2 d | 中高（防退化） | 第二波起持续 |
| 6.3 语义检索（可选） | 检索增强 | 3–5 d | 中（受 R6） | 第四波/按需 |
| 6.3 统一入口 + 意图路由 | P2.1/P3.2 | 3–4 d | 高（体验，L4） | 第四波 |
| 6.4 智能 narration | P3.1 | 0.5–1 d | 中 | 第三波 |

> **最高 ROI 的智能化项是 6.2 的"提示词/agent 策略升级"**：成本约 1 人天，却直接决定模型在已通回路上的表现。建议 P2.1 闭环一通就立刻做，并以 6.5 评测固化收益。

---

## 7. 结论

YuMark agent 的**骨架已齐全且安全网已部分就位**——接口、工具定义、工具执行器、状态枚举、UI 动作卡片就位，**P1.2 错误处理/重试（友好化 + 指数退避 + emission guard）已三家适配器完成**。

缺的是两层，加一层"脑子"：

1. **安全网剩余**（P1.1 diff）——ROI 最高、风险最低，应立即补（P1.2 已完成）；
2. **回路**（P2.1 工具调用接通 + ReAct 循环）——工作量最大但真正的分水岭，把"伪 agent"变成真 agent（L1→L2）。注意：最近提交只完成了**预埋**（接口形参/工具/执行器/回填字段），三个接头（请求体发 tools、解析 tool_calls、UseCase 不空转）仍 TODO，真正难点 R1/R2 未动；
3. **智能**（§6：agent 策略与提示词、自我纠错、语义检索、统一意图入口，配 §6.5 评测）——在回路之上把 agent 从"能用"推到"聪明"（L3→L4），其中**提示词/策略升级是最高杠杆的低成本项**。

补完前两层、叠加第三层，YuMark 才能从一个"带文本协议的聊天框"成长为真正懂笔记库、会规划、能纠错的文档 agent。所有智能化项都须遵守 R6——配降级路径与防失控闸门，让弱模型停在能力上限而非崩溃。
