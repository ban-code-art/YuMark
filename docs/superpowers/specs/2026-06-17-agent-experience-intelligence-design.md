# 设计文档：可观测 + 工具化 + 上下文智能（Agent 第三波 P2.2 / P3.1 / P3.2 / §6.2 / §6.3）

- 日期：2026-06-17
- 状态：详细设计 / 待评审 → 待实现
- 关联文档：`docs/agent-gap-analysis-2026-06-17.md`（§2 P2.2/P3.1/P3.2、§6.2/§6.3、§3 第三波）；第一波 `specs/2026-06-17-edit-diff-gate-design.md`；第二波 `specs/2026-06-17-tool-loop-react-design.md`
- 关联代码：`domain/usecase/ai/agent/AgentUseCases.kt`、`domain/usecase/ai/ExecuteDocumentToolUseCase.kt`、`domain/usecase/ai/DocumentContextTools.kt`、`data/local/db/AppDatabase.kt`、`data/local/db/entity/AiEntities.kt`、`data/mapper/AiMappers.kt`、`presentation/ai/agent/AgentChatSheet.kt`、`presentation/editor/AiQuickDialog.kt`

> 第三波是**组合波**：在第二波回路之上，让 agent「**看得见、信得过、上下文准、会纠错**」——把智能成熟度从 **L2 推到 L3**。包含 5 个子项，下面先给全景与内部依赖，再逐项展开。

---

## 1. 目标与范围

### 1.1 目标

| 子项 | 目标 | 成熟度 |
|---|---|---|
| **P3.1** 步骤持久化 + 可观测 | 把第二波的内存态步骤落库 + UI 逐工具卡片/narration，刷新可见 | 可观测 |
| **P2.2** 编辑工具化 + 文本协议退场 | `create_document`/`edit_document` 工具化，`edit_document` 走 diff 闸门，渐进废弃 `[[ACTION]]`/`[[EDIT]]` | 工具统一 |
| **P3.2** 上下文按需检索 + 预算 + 缓存 | 统一 200/12000/0 三处口径为一个 `ContextBudget`，默认注入大纲、按需读取、轮内缓存 | 上下文准 |
| **§6.2** 自我纠错 + agent 策略 | 工具失败/结果不符 → 换策略重试；结构化 system prompt（最高 ROI） | **L3 自我纠错** |
| **§6.3** 检索质量增强 | `search_in_project` 从 `contains` → 关键词归一化 + 分块 + 相关度排序 | 检索准 |

### 1.2 决策回执（沿用前序，不再追问）

| 点 | 立场 | 来源 |
|---|---|---|
| 步骤持久化 | 第三波做 Room 4→5 迁移，`stepsJson` 与 attachment `attachmentsJson` **合并到同一版本** | 第二波决策 + D2 |
| 文本协议退场 | **双路并存**：工具调用优先，`[[ACTION]]`/`[[EDIT]]` 兜底，渐进废弃 | gap-analysis P2.2 |
| 语义检索 | 第三波只做**关键词增强**（零依赖）；on-device embedding 推迟第四波/按需（R5/R6） | gap-analysis §6.3 |
| 编辑安全 | `edit_document` 工具执行 = 暂停循环等用户 **diff 逐 hunk 批准**（复用第一波） | D1 |

### 1.3 范围外

- ❌ Claude/Gemini 累积器（仍第三波并行补齐工具回路，但本设计聚焦 OpenAI 已通后的上层；三家协议细节见第二波 §7）
- ❌ on-device embedding 语义检索、prompt caching（第四波/按需）
- ❌ 长对话压缩 P4.1、checkpoint P4.2、统一入口意图路由 §6.3-L4、跨文档自主（第四波）

---

## 2. 第三波全景与内部实施顺序

```
        第二波产出：OpenAI 工具回路 + 内存态 AgentStep + ReAct 循环
                              │
   ┌──────────────────────────┼───────────────────────────┐
   ▼                          ▼                           ▼
 P3.1 持久化+可观测      6.2 提示词+自我纠错         P3.2 上下文  +  6.3 检索增强
 (基础设施, 先做)       (最高ROI, 早做)            (独立, 并行)    (增强 search 工具)
   │                                                        
   └──────────────► P2.2 写工具 (edit_document 暂停循环等批准)
                    依赖 P3.1(循环状态持久化) + 第一波(diff 闸门)
```

**顺序依据**：
1. **P3.1 先行**——不仅为"看得见"，更因 **P2.2 的 `edit_document` 要暂停循环等用户批准**，挂起的循环状态需要可持久化（用户可能离开 app）。P3.1 把"步骤/循环状态落库"建好，P2.2 才能安全挂起-恢复。
2. **6.2 早做**——结构化 system prompt 是最高 ROI 低成本项（约 1 人天），且自我纠错让回路鲁棒，越早越受益。
3. **P3.2 / 6.3 并行**——都独立建立在第二波回路上，互不阻塞。
4. **P2.2 最后**——依赖 P3.1 + 第一波，是本波最复杂的 human-in-the-loop。

---

## 3. P3.1　步骤持久化 + 可观测

### 3.1 从"内存态"演进到"持久化"

第二波的 `AgentStep`（`domain/model/AgentStep.kt`）是内存 sealed interface。本波令其 `@Serializable`，随消息落库。

**Room 4 → 5 迁移**（沿用现有 `ALTER TABLE ADD COLUMN` 模式，`AppDatabase.kt:89-94`）：

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN stepsJson TEXT DEFAULT NULL")
        // D2：同一迁移预留 attachment 列，避免二次演进 schema
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT DEFAULT NULL")
    }
}
// AppDatabase: version = 5；ALL_MIGRATIONS += MIGRATION_4_5
```

- `MessageEntity`（`AiEntities.kt:32`）加 `val stepsJson: String? = null`、`val attachmentsJson: String? = null`（后者本波不写入，占位给 attachment Phase 2）。
- `domain.Message`（`AiModels.kt:68`）加 `val steps: List<AgentStep> = emptyList()`。
- `AiMappers.kt`（`:42-62`）`toDomain`/`toEntity` 增加 `steps` ↔ `stepsJson` 的 `aiJson` 序列化（与 `agentActionJson` 同模式）。

> 这正是 gap-analysis R2 提的"扩 Message + Room 迁移"，只是按第二波决策**延后到这里**，并与 attachment 合并为一次迁移。

### 3.2 循环写步骤

第二波循环（`SendAgentMessageUseCase`）原本"只落最终回答"。本波改为：每完成一个工具步骤，把 `AgentStep` 追加到该助手消息的 `steps` 并 `updateMessage` 落库。最终回答仍是同一条消息的 `content`。

### 3.3 可观测 UI

- `MessageBubble`/`AgentContent`（`AgentChatSheet.kt`）渲染 `message.steps`：每步一个折叠条「🔧 search_in_project『预算』→ 命中 3 篇」「📄 read_document《Q2 计划》→ 1.2k 字」。
- 流式期间用第二波的 `ToolStep` 事件实时显示；完成后从持久化的 `steps` 重建（刷新仍可见）。
- **智能 narration（§6.4 增量）**：步骤行用自然语言而非裸工具名（"正在查找与『预算』相关的笔记…"），文案层 0.5 天。

### 3.4 工作量：3.5 人天（迁移 1.5 + UI 2）

---

## 4. P2.2　编辑工具化 + 文本协议退场

### 4.1 写工具定义（扩 `DocumentContextTools`）

```kotlin
val CREATE_DOCUMENT = AiTool("create_document",
    "创建一篇新 Markdown 文档。不覆盖任何已有内容。",
    params(title:string, content:string))
val EDIT_DOCUMENT = AiTool("edit_document",
    "编辑指定文档。调用后会向用户展示逐行改动，经用户批准后才生效。",
    params(document_id:string, new_content:string))
// edit_document_range（定向编辑，第一波预留的底层能力）可作为 edit_document 的细粒度变体后续加
```

### 4.2 `edit_document` = human-in-the-loop 工具（D1 闸门）

只读工具自动执行回填；**写工具不同**——`edit_document` 调用要**暂停循环、走第一波 diff 闸门**：

```
循环中 ToolCallComplete 含 edit_document(target, new_content):
  1. 加载 target 当前内容 oldContent
  2. diff(oldContent, new_content) → 生成 DiffResult（第一波 LineDiffer）
  3. 暂停循环，持久化挂起态（见 §4.3），emit AwaitingEditApproval(diff)
  4. UI 展示 AgentActionCard diff 视图（第一波），用户逐 hunk 批准/拒绝
  5a. 批准 → applyHunks 合成 finalContent → save → 工具结果回填 "编辑已应用：接受 N/M 处改动"
  5b. 拒绝 → 工具结果回填 "用户拒绝了本次编辑"
  6. 恢复循环 → 模型基于结果继续（或结束）
```

`create_document` 无破坏性（D1）→ 自动执行落库，回填"已创建《标题》"，不暂停。

### 4.3 挂起态持久化（依赖 P3.1）

暂停等批准可能跨 app 生命周期。把挂起的循环上下文（已累积 `messages` 快照 + 待批准的 `ToolCall`）序列化存入会话级字段（复用 P3.1 的序列化设施，或会话表加 `pendingLoopJson`）。

- **基础形态**（推荐先做）：进程内挂起——app 存活期间循环对象保活等批准；够覆盖绝大多数交互。
- **增强形态**（可选）：持久化挂起态，杀进程后重进仍可继续未决编辑。建议先基础，增强按需。

### 4.4 文本协议退场（双路并存 → 废弃）

- 过渡期：system prompt **同时**告知工具与（弱模型兜底的）`[[ACTION]]`。收敛逻辑：**优先信任工具调用**；仅当模型没发工具调用、却在最终文本里出现 `[[ACTION]]`/`[[EDIT]]` 时，才走 `parseAgentAction` 兜底（第一波链路）。
- 工具调用稳定后（评测达标，§6.5），从 system prompt 移除协议说明，`parseAgentAction` 降级为纯兜底，最终在第四波删除。
- AiQuickDialog 的 `[[EDIT]]` 同理：选区改写也可走 `edit_document`（target=当前文档 + 选区范围），逐步退场。

### 4.5 工作量：2.5–3 人天

---

## 5. P3.2　上下文按需检索 + 预算 + 缓存

### 5.1 统一 `ContextBudget`（消灭 200/12000/0 三口径）

现状：Agent 200 字（`AgentUseCases.kt:199`）、Quick 12000（`AiQuickDialog.kt:50`）、Chat 0。统一为：

```kotlin
object ContextBudget {
    const val SYSTEM_DOC_CHARS = 1500     // system 里只放当前文档大纲/开头摘要
    const val TOOL_RESULT_CHARS = 4000    // 工具结果上限（第二波已用）
    const val HISTORY_CHARS = 8000        // 历史消息预算（超出触发 P4.1 压缩）
    fun estimateTokens(s: String) = s.length / 4   // 粗估
}
```

### 5.2 默认注入大纲，按需读取

- system prompt 不再塞全文/200 字硬切，而是注入**当前文档大纲**（标题层级 + 首段）+ "需要全文用 `read_document` 自取"的指引。
- 真要全文时模型调 `read_document`（第二波回路已通），结果按 `TOOL_RESULT_CHARS` 截断。
- Chat 模式（`SendChatMessageUseCase` 当前无上下文）也接入同一策略，按需带工具。

### 5.3 轮内缓存

一次对话内，`read_document(id)` 命中过的内容缓存（内存 `Map<docId, content>`），同轮重复读直接返回，省 token 与延迟。

### 5.4 工作量：2–3 人天（部分随第二波回路自然获得）

---

## 6. §6.2　自我纠错 + agent 策略（最高 ROI）

### 6.1 结构化 system prompt（替换 `buildAgentSystemPrompt` 的简陋版）

当前仅几行（`AgentUseCases.kt:205-225`）。升级为结构化：

```
[角色] 你是 YuMark 的文档助手，帮用户检索、整理、改写笔记。
[能力边界] 只能操作本应用内的 Markdown 文档；不杜撰不存在的文档。
[工具守则]
 - 不确定文档是否存在 → 先 search_in_project / list_documents，不要凭空假设 ID
 - 需要文档全文再 read_document；已读过的不要重复读
 - 改写用户文档 → edit_document（会经用户逐行批准）；新建 → create_document
[输出规范] 先简述方案再行动；改动前说明改了什么
[安全] 破坏性操作前必须经用户批准
[当前文档] 大纲：{outline}
```

few-shot 示例 1–2 个（"查旧笔记再改写"的工具序列）。这是 L2→L3 体验跃升的最高杠杆项。

### 6.2 自我纠错循环（L3 核心）

第二波循环遇到工具错误时已回填错误字符串；本波**显式引导纠错**：

- 工具返回错误（如 `read_document` 文档不存在，`ExecuteDocumentToolUseCase.kt:37`）→ 回填结构化错误 + 提示"可改用 search 重新定位"，让模型换策略而非放弃/硬编。
- 同一工具相同参数失败 ≥2 次 → 循环检测（第二波闸门）兜底停。
- 设单步纠错重试上限，计入 `MAX_STEPS`。

### 6.3 工作量：2.5 人天（提示词 1 + 自我纠错 1.5）

---

## 7. §6.3　检索质量增强

### 7.1 `search_in_project`：`contains` → 关键词增强

现状纯 `contains` 大小写无关（`ExecuteDocumentToolUseCase.kt:88`）。增强（零依赖）：

- **查询归一化**：去标点、按空白/分词切关键词，多词 OR/AND；
- **分块匹配**：按段落/标题块匹配而非整行，返回更有意义的片段；
- **相关度排序**：按命中关键词数 + 命中密度 + 标题命中加权排序，截断 top-N；
- 返回结构：`【文档】相关度★ + 命中片段（带定位）`，便于模型决定 `read_document` 谁。

### 7.2 明确不做（标注）

on-device embedding 语义检索：需额外模型/库、受 R5/R6 约束，**推迟第四波/按需**，本波只把"检索结构与排序"打好底，未来 embedding 可替换排序层。

### 7.3 工作量：1.5–2 人天

---

## 8. 改动清单（文件级）

**新增**
- `domain/model/AgentStep.kt`：加 `@Serializable`（第二波已建则补注解）
- `core/util/ContextBudget.kt`：统一预算
- `data/.../SearchRanker.kt`：检索归一化 + 排序（可单测）
- 测试：`SearchRankerTest`、`SelfCorrectionLoopTest`、迁移测试 `MigrationTest(4→5)`

**修改**
- `data/local/db/AppDatabase.kt`：version 5 + `MIGRATION_4_5`
- `data/local/db/entity/AiEntities.kt`：`MessageEntity` 加 `stepsJson`/`attachmentsJson`
- `data/mapper/AiMappers.kt`：`steps` ↔ `stepsJson` 序列化
- `domain/model/AiModels.kt`：`Message` 加 `steps`
- `domain/usecase/ai/DocumentContextTools.kt`：加 `create_document`/`edit_document`
- `domain/usecase/ai/ExecuteDocumentToolUseCase.kt`：加写工具分支；`edit_document` 触发闸门（不在执行器直接写，而是返回"待批准"信号给循环）
- `domain/usecase/ai/agent/AgentUseCases.kt`：循环加 `edit_document` 暂停-批准-恢复；写 steps；结构化 prompt；自我纠错引导；按需上下文
- `presentation/ai/agent/AgentChatSheet.kt`：渲染 `steps`；处理 `AwaitingEditApproval`（复用第一波 `AgentActionCard` diff）
- `domain/usecase/ai/chat/SendChatMessageUseCase.kt`：接入 `ContextBudget` + 工具（可选）
- `presentation/editor/AiQuickDialog.kt`：`[[EDIT]]` 路径渐进切到 `edit_document`

---

## 9. 风险与边界

- **R-挂起态复杂度（P2.2 最大风险）**：`edit_document` 暂停循环是 human-in-the-loop，挂起态管理（尤其跨进程恢复）复杂。**缓解**：先做进程内挂起（基础形态），跨进程恢复列为可选增强。
- **R-迁移安全**：4→5 仅 `ADD COLUMN ... DEFAULT NULL`，对存量数据安全；必加 `MigrationTest`。`attachmentsJson` 占位列本波不写，attachment Phase 2 直接用。
- **R-双路歧义**：过渡期工具与 `[[ACTION]]` 并存，须明确"工具优先、文本兜底"，避免一次编辑被双重应用。收敛逻辑单测覆盖。
- **R-检索增强回归**：改 `search_in_project` 返回格式可能影响模型解读；用 §6.5 评测任务 2/3 回归。
- **R-上下文策略变更**：从"塞全文"改"注入大纲+按需读"可能让弱模型漏读全文 → 提示词明确引导 + 评测验证；弱模型可保留"小文档直接全文"快捷路径。
- **R-成熟度依赖**：自我纠错、按需检索都依赖模型指令遵循能力（R6），弱模型降级为不纠错/直接全文。

---

## 10. 工作量与里程碑

| 里程碑 | 内容 | 人天 |
|---|---|---|
| M1 持久化 | Room 4→5 迁移 + `MessageEntity`/`Message`/映射器 + 迁移测试 | 1.5 |
| M2 可观测 | `steps` 渲染 + narration | 2 |
| M3 提示词+纠错 | 结构化 system prompt + 自我纠错引导 + 测试 | 2.5 |
| M4 上下文 | `ContextBudget` 统一 + 按需 + 轮内缓存 | 2–3 |
| M5 检索增强 | `SearchRanker`（归一化+分块+排序）+ 测试 | 1.5–2 |
| M6 写工具 | `create_document`/`edit_document` + 暂停-批准-恢复 + 退场双路 | 2.5–3 |
| **合计** | | **12–14 人天（约 2–3 周，吻合 gap-analysis）** |

**实施顺序**：M1 → M3 → (M4 ∥ M5) → M2 → M6。提示词/纠错（M3）尽早做以最快兑现体验；写工具（M6）压最后（依赖 M1 持久化 + 第一波 diff）。

---

## 11. 验收标准

1. agent 工具步骤刷新后仍可见（持久化生效），UI 以自然语言展示每步。
2. Room 4→5 迁移对存量对话/消息无损（`MigrationTest` 绿）；`attachmentsJson` 列已就位待 attachment 使用。
3. 模型改写文档走 `edit_document` 工具 → 暂停 → 用户逐 hunk 批准 → 生效；拒绝则不写库且模型据此调整。
4. `create_document` 自动落库不打断循环。
5. 过渡期工具调用优先、`[[ACTION]]` 仅兜底，无双重应用。
6. 三处上下文口径统一为 `ContextBudget`；Agent 不再 200 字硬切，改"大纲+按需读取"。
7. `search_in_project` 返回按相关度排序的分块片段，优于裸 `contains`（评测任务 2/3 对比有提升）。
8. 给一个不存在的文档名，agent 会 search 重定位而非硬失败（自我纠错生效）。
9. 弱模型下各项安全降级，不崩。

---

## 12. 第三波之后

完成第三波，agent 达 **L3**（自我纠错 + 工具统一 + 上下文准 + 可观测）。剩余通往 **L4** 的进阶项归第四波：统一入口意图路由（§6.3-L4）、跨文档自主、长对话压缩（P4.1）、checkpoint（P4.2）、on-device 语义检索、Claude/Gemini 工具回路补齐、§6.5 评测体系化。届时可视产品反馈按需展开。
