# 设计文档：工具调用回路 + ReAct 循环（Agent 第二波 P2.1）

- 日期：2026-06-17
- 状态：详细设计 / 待评审 → 待实现
- 关联文档：`docs/agent-gap-analysis-2026-06-17.md`（§2 P2.1、§4 R1/R2/R3/R6、§6.0 成熟度、§3 第二波）；`docs/superpowers/specs/2026-06-17-edit-diff-gate-design.md`（第一波，编辑安全闸门）
- 关联代码：`data/ai/adapters/OpenAiAdapter.kt`、`data/ai/AiApiAdapter.kt`、`data/ai/StreamRetry.kt`、`domain/model/AiApiModels.kt`、`domain/model/AiModels.kt`、`domain/usecase/ai/ExecuteDocumentToolUseCase.kt`、`domain/usecase/ai/DocumentContextTools.kt`、`domain/usecase/ai/agent/AgentUseCases.kt`、`presentation/ai/agent/AgentChatSheet.kt`

> 第二波是整条路线的**分水岭**：把"带文本协议的单轮聊天"变成"会按需检索再回答的真 agent"（智能成熟度 **L0→L2**）。本波只接通**回路**，编辑安全已由第一波 diff 闸门保证。

---

## 1. 目标与范围

### 1.1 目标

接通工具调用回路：模型能在一次提问内**自主多步**地 `search_in_project → read_document → 基于检索结果回答或提出编辑`，而不再依赖把全文塞进 system prompt。达成 ReAct 闭环（思考→调工具→观察→再思考）。

### 1.2 决策回执（2026-06-17 已定）

| 决策 | 取值 | 影响 |
|---|---|---|
| 适配器范围 | **先 OpenAI 兼容一家**（含 DeepSeek/Ollama 等 `/chat/completions`） | Claude/Gemini 累积器复制到第三波 |
| 闭环边界 | **只读工具 ReAct 闭环** | 编辑仍走 `[[ACTION]]` + 第一波 diff 闸门；写工具/`[[ACTION]]` 退场 = 第三波 P2.2 |
| 中间步骤存储 | **内存态先行，不动 Room** | 只持久化最终回答；steps 持久化 + 可观测 UI = 第三波 P3.1，与 attachment 合并迁移 |

### 1.3 范围内

- ✅ 事件模型补"工具调用完成"语义（`StreamEvent`）
- ✅ OpenAI 适配器：请求体带 `tools`、流式 `tool_calls` 有状态累积器、`assistant.tool_calls` / `role=tool` 消息回填
- ✅ ReAct 循环（`SendAgentMessageUseCase` 重写）：执行 → **结果截断** → 回填 → 再请求 → 收敛
- ✅ 防失控闸门（最大步数 / 循环检测 / 超时）
- ✅ 弱模型降级（不支持 tools → 回退单轮，R6）
- ✅ 内存态步骤展示（粗粒度"🔧 正在检索…"，不落库）

### 1.4 范围外（明确推迟）

- ❌ Claude / Gemini 累积器 → 第三波（本设计预留统一抽象，复制即可）
- ❌ 写工具 `create_document`/`edit_document`、`[[ACTION]]` 退场 → 第三波 P2.2
- ❌ steps 持久化（Room schema 迁移）、精细可观测 UI（逐工具卡片+回滚）→ 第三波 P3.1（与 attachment Phase 2 合并迁移，D2）
- ❌ 语义检索（`search_in_project` 仍 `contains`）→ §6.3
- ❌ prompt caching（R5，依赖 provider）

---

## 2. 现状：预埋已就绪，三个接头是 TODO

| 已就绪（预埋） | 证据 | 仍 TODO 的接头 | 证据 |
|---|---|---|---|
| 接口 `tools` 形参 | `AiApiAdapter.kt:23-27` | ①请求体不构造 tools | `OpenAiAdapter.kt:57-74` |
| 3 只读工具定义 | `DocumentContextTools.kt` | ②SSE 不解析 tool_calls（只取 content） | `OpenAiAdapter.kt:93-101` |
| 执行器（read/list/search） | `ExecuteDocumentToolUseCase.kt:24-29` | ③UseCase 收到 `ToolCallDelta` 空转 | `AgentUseCases.kt:102-110` |
| `ChatMessage.toolCalls/toolCallId` | `AiApiModels.kt:43-44` | — | |
| `AiTool`/`ToolCall` 模型 | `AiModels.kt:110-123` | — | |
| Agent 已传 tools | `AgentUseCases.kt:92` | — | |

**本波 = 接通这三个接头 + 套上循环。** 接口/模型/执行器不重做。

---

## 3. 架构设计

### 3.1 事件模型：补"工具调用完成"语义（R1 前置）

当前 `ToolCallDelta(callId, name, argumentsDelta)`（`AiApiModels.kt:9-13`）只有增量、无完成信号；`Done` 是流结束。

**设计原则**：**累积器放在适配器内部**（屏蔽 provider 协议差异，符合 `AiApiAdapter` 接口注释），UseCase 只消费**完整**的 `ToolCall`。

```kotlin
sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    /** 可选：流式增量，仅供 UI 粗提示"正在准备工具调用"，循环逻辑不依赖它 */
    data class ToolCallDelta(val callId: String, val name: String?, val argumentsDelta: String?) : StreamEvent()
    /** 新增：本轮工具调用已拼装完成（可能并行多个）。循环据此触发执行 */
    data class ToolCallComplete(val calls: List<ToolCall>) : StreamEvent()
    data class Done(val fullText: String) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}
```

一轮 OpenAI 响应**要么** `Content*`+`Done`（最终回答）**要么** `ToolCallComplete`（要调工具），由 `finish_reason` 区分（`stop` / `tool_calls`）。

### 3.2 OpenAI 工具调用累积器（R1 核心）

OpenAI 流式 `choices[0].delta.tool_calls[]`：每元素带 `index`（必有）、`id`（首片）、`function.name`（首片）、`function.arguments`（跨片增量）。按 `index` 累积：

```kotlin
// OpenAiAdapter.sendChatStream 内，逐 SSE 行解析（扩展现有 :88-101）
val acc = linkedMapOf<Int, ToolCallBuilder>()   // index -> builder
// ...每行 delta：
val choice = json...["choices"][0]
choice["delta"]["content"]?.let { emit(Content(it)); full.append(it) }
choice["delta"]["tool_calls"]?.jsonArray?.forEach { tc ->
    val idx = tc["index"].int
    val b = acc.getOrPut(idx) { ToolCallBuilder() }
    tc["id"]?.let { b.id = it }
    tc["function"]?["name"]?.let { b.name = it }
    tc["function"]?["arguments"]?.let { b.argsBuf.append(it) }
}
val finish = choice["finish_reason"]?.contentOrNull
when (finish) {
    "tool_calls" -> emit(ToolCallComplete(acc.values.map { it.build() }))  // 拼完
    "stop"       -> {}  // 文本结束，靠 [DONE]/Done
}
// 流末：emit Done(full)
```

`ToolCallBuilder.build()` → `ToolCall(id, name, arguments=argsBuf.toString())`（`ToolCall` 已存在 `AiModels.kt:119`）。`arguments` 是 JSON 字符串，执行器已按此解析（`ExecuteDocumentToolUseCase.kt:22`）。

### 3.3 请求体扩展

**(a) tools 序列化**：`AiTool.parameters` 是 `Map<String, Any>`（`AiModels.kt:113`），需递归转 `JsonElement`：

```kotlin
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this); is Number -> JsonPrimitive(this); is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> buildJsonObject { forEach { (k, v) -> put(k.toString(), v.toJsonElement()) } }
    is List<*> -> buildJsonArray { forEach { add(it.toJsonElement()) } }
    else -> JsonPrimitive(toString())
}
// 请求体（tools 非空时才加）：
if (tools.isNotEmpty()) putJsonArray("tools") {
    tools.forEach { addJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", it.name); put("description", it.description)
            put("parameters", it.parameters.toJsonElement())
        }
    } }
}
```

**(b) 消息回填**：现状只放 `role`+`content`（`OpenAiAdapter.kt:70-72`）。扩展为按 `ChatMessage` 的 `toolCalls`/`toolCallId`（`AiApiModels.kt:43-44`）构造：
- assistant 发起调用：`{"role":"assistant","content":null,"tool_calls":[{"id","type":"function","function":{"name","arguments"}}]}`
- 工具结果：`{"role":"tool","tool_call_id":<id>,"content":<截断后结果>}`

### 3.4 ReAct 循环（`SendAgentMessageUseCase` 重写）

```
构建 messages = [system(精简提示 + 工具守则), ...历史, user]
loop step in 1..MAX_STEPS:
    var toolCalls = null; val buf = StringBuilder()
    adapter.sendChatStream(messages, config, READONLY_TOOLS).collect:
        Content(t)            -> buf += t; emit Streaming(t)
        ToolCallComplete(cs)  -> toolCalls = cs           // 本轮要调工具
        Done(full)            -> /* 本轮流结束 */
        Error(m)              -> emit Error(m); return
    if (toolCalls == null || toolCalls.isEmpty()):
        // 收敛：最终回答
        val action = parseAgentAction(buf, currentDocumentId)   // 兼容第一波 [[ACTION]]+diff
        持久化最终 assistant Message(content=buf, agentAction=action)   // 仅此条落库
        if (action != null) emit ActionProposed(...)
        emit Completed(buf); return
    // 未收敛：执行工具并回填（均为内存态，不落 Room）
    messages += assistantMessage(toolCalls)
    for tc in toolCalls:
        emit ToolStep(ToolCall(tc.name, argsSummary(tc)))                // 内存态粗展示
        val raw = ExecuteDocumentToolUseCase(tc).getOrElse { "工具执行失败：${it.message}" }
        val result = truncateToolResult(raw)                             // R3
        messages += toolMessage(tc.id, result)
        emit ToolStep(ToolResult(tc.name, ok, summary(result)))
    if (循环检测命中) { 落库已有内容; emit Completed("（已停止：检测到重复调用）"); return }
// 超过 MAX_STEPS
落库已有内容; emit Completed("（已达最大步数 $MAX_STEPS，已停止）")
```

**关键：只有最终回答落 Room**（现有 `Message` 够用）。中间 `assistant.tool_calls` 与 `tool` 结果只活在本次循环的内存 `messages` 列表里——这就是"内存态先行"。下一轮用户提问时从 Room 重建上下文不含这些临时检索步骤，符合"工具结果是临时的、按需重取"的语义。

### 3.5 工具结果截断（R3，前置）

`read_document` 可能返回数万字（`ExecuteDocumentToolUseCase.kt:44-49` 全文）。回填前**每个 tool result 单独截断**：

```kotlin
private const val TOOL_RESULT_CHAR_BUDGET = 4000   // 单次工具结果上限（≈1k token）
fun truncateToolResult(s: String) =
    if (s.length <= BUDGET) s else s.take(BUDGET) + "\n…（结果过长已截断，可用更精确的查询或读取具体片段）"
```

> 截断在**循环层**做（执行器仍返回完整内容，调用方定预算），便于未来按 token 预算细化。

### 3.6 防失控闸门

| 闸门 | 取值 | 行为 |
|---|---|---|
| 最大步数 `MAX_STEPS` | 6 | 超过 → 停，落库已有内容 + 提示 |
| 循环检测 | 同名工具+相同 arguments 连续重复 ≥2 次 | 视为卡死 → 停 |
| 总时长 | 复用 Ktor 请求超时 + 循环总时长上限（如 90s） | 超时 → emit Error（经 `AiErrorMapper`） |
| 单步工具数 | 一轮并行工具调用上限（如 5） | 超过截断 |

### 3.7 弱模型降级（R6）

YuMark 大量用户走 Ollama/DeepSeek，部分模型不支持 function calling：

- **请求被拒**（400 / "tools not supported" 类）→ 捕获后**去掉 tools 重试一次**（等价现状单轮），并 `emit` 一条提示「当前模型不支持工具调用，已降级为直接对话」。判定收敛到 `AiErrorMapper`（加 `isToolUnsupported(status, body)`）。
- **模型不发 tool_calls 而用文本描述**：循环 step 1 即收敛为最终回答，`parseAgentAction` 仍兜底 `[[ACTION]]`——退化为第一波行为，不报错。
- 降级是**静默可用**：宁可少一次智能，不可崩。

### 3.8 内存态步骤模型（不落 Room）

```kotlin
// 仅内存，供 ViewModel 收集、UI 粗展示；P3.1 再持久化
sealed interface AgentStep {
    data class ToolCalling(val tool: String, val argsSummary: String) : AgentStep
    data class ToolDone(val tool: String, val ok: Boolean, val summary: String) : AgentStep
}
// AgentMessageState 增加：
data class ToolStep(val step: AgentStep) : AgentMessageState()
```

`AgentChatViewModel` 把 `ToolStep` 收进一个 `MutableStateFlow<List<AgentStep>>`，`AgentContent` 在流式期间渲染一行「🔧 正在检索『预算』… / ✓ 读取《Q2 计划》(1.2k 字)」。刷新后只剩最终回答（步骤未持久化）——可观测的完整形态是 P3.1。

---

## 4. ReAct 时序（一次"参考旧笔记改写"提问）

```
User: 参考我之前写的预算笔记，补充本文的预算章节
  │
  ├─ step1 → LLM ──(tool_calls)── search_in_project{query:"预算"}
  │            execute → 命中《Q2预算》→ 截断结果回填(role=tool)
  ├─ step2 → LLM ──(tool_calls)── read_document{document_id:"Q2预算"}
  │            execute → 全文截断 4000 字回填(role=tool)
  ├─ step3 → LLM ──(stop)──────── 文本回答 + [[ACTION]] EDIT 块
  │            parseAgentAction → AgentActionCard(diff 闸门, 第一波)
  └─ Completed（仅 step3 的回答落库；step1/2 的工具消息仅内存）
       用户在 diff 卡片逐 hunk 批准 → 落库
```

---

## 5. 改动清单（文件级）

**新增**
- `data/ai/JsonExt.kt`：`Any?.toJsonElement()`（tools 序列化）
- `data/ai/ToolCallAccumulator.kt`：OpenAI 累积器（`ToolCallBuilder`），便于单测
- `domain/model/AgentStep.kt`：内存态步骤
- 测试：`ToolCallAccumulatorTest`、`SendAgentMessageUseCaseTest`（fake adapter 驱动循环）、`ToolResultTruncateTest`

**修改**
- `domain/model/AiApiModels.kt`：`StreamEvent` 加 `ToolCallComplete`
- `data/ai/adapters/OpenAiAdapter.kt`：请求体加 tools + 消息回填；SSE 解析加 tool_calls 累积 + emit `ToolCallComplete`
- `data/ai/AiApiAdapter.kt` / `runConnectionTest`：处理新事件类型（连接测试忽略）
- `data/ai/StreamRetry.kt`：`ToolCallComplete` 也计入 emission guard（已发起工具调用的请求不重试，避免重复执行）
- `core/util/AiErrorMapper.kt`：加 `isToolUnsupported(...)`
- `domain/usecase/ai/agent/AgentUseCases.kt`：`SendAgentMessageUseCase` 重写为循环；`buildAgentSystemPrompt` 精简为"工具守则 + 大纲"（配合 §6.2，本波先给最小可用版）
- `presentation/ai/agent/AgentChatSheet.kt`：`AgentChatViewModel` 收集 `ToolStep`；`AgentContent` 渲染步骤行

**不改**：`ExecuteDocumentToolUseCase`、`DocumentContextTools`、`Message`/Room（内存态决策）、第一波 diff 闸门、Claude/Gemini 适配器（保持 TODO，第三波接）。

---

## 6. 测试策略

- **`ToolCallAccumulatorTest`**：单工具单片 / 跨多片拼 arguments / 并行多 index / `finish_reason==tool_calls` 触发完成 / 畸形分片容错。
- **`SendAgentMessageUseCaseTest`**（fake `AiApiAdapter`，无网络）：单轮收敛、两步检索后收敛、达 MAX_STEPS 停、循环检测停、工具执行失败回填错误后继续、降级（fake 抛 tools 不支持）。
- **`ToolResultTruncateTest`**：超长结果截断 + 提示。
- 复用第一波：最终回答含 `[[ACTION]]` → diff 闸门链路不回归。
- 纯逻辑层全部 JVM 单测；适配器累积器抽成纯函数以免 MockEngine。

---

## 7. 与相邻波次的衔接

- **承接第一波**：循环最终回答里的 `[[ACTION]]` 仍由 `parseAgentAction` → `AgentActionCard` diff 闸门处理，**编辑安全零回归**。
- **铺垫第三波**：
  - Claude/Gemini：实现各自 `ToolCallAccumulator`（Claude `content_block_start/input_json_delta/content_block_stop` 按 id；Gemini `functionCall` 按候选），统一 emit `ToolCallComplete`——循环层不变。
  - P2.2 写工具：`create_document`/`edit_document` 加入工具集 + 执行器分支；`edit_document` 执行即走第一波 diff 闸门（设计已就绪）。循环结构复用。
  - P3.1 持久化 + 可观测：把 `AgentStep` 落 Room（与 attachment `attachmentsJson` **合并到同一迁移版本**，D2），升级 UI 为逐工具卡片。
- **铺垫 §6.2**：本波 system prompt 先给"最小工具守则"；§6.2 再扩为结构化 agent 策略（最高 ROI 项）。
- **铺垫 §6.5**：本波完成后用评测任务集 2/3（单步检索、多步检索改写）跑基线。

---

## 8. 风险与边界

- **R-累积器是真难点（R1）**：跨片拼接 + 完成检测，靠 `ToolCallAccumulatorTest` 覆盖畸形流。这是本波最大不确定项。
- **R-重试与幂等**：工具轮（emit 了 `ToolCallComplete`）若断流，**不在适配器层自动重试**（已计入 emission guard），避免重复执行工具；由用户重发。首字节前的纯网络失败仍由 `withRetryAndEmissionGuard` 重试（现状能力）。
- **R-内存态的取舍**：中间步骤不持久化 → 刷新/重进对话后看不到"用过哪些工具"，且循环跨轮不复用检索结果（每轮重新按需取）。对只读检索可接受；P3.1 再补持久化。
- **R-上下文膨胀**：多步回填使单次请求 messages 增长；靠工具结果截断（§3.5）+ MAX_STEPS 限制；长对话压缩是 P4.1。
- **R-弱模型**：§3.7 降级兜底；评测须含一个不支持 tools 的模型验证降级路径（§6.5）。
- **R-provider 兼容差异**：OpenAI 兼容端点对 `tools`/`tool_choice`/`role=tool` 支持参差（Ollama 各模型不一）。降级路径是安全网；文档标注"工具调用效果依赖具体模型"。

---

## 9. 工作量与里程碑

| 里程碑 | 内容 | 人天 |
|---|---|---|
| M1 事件 + 累积器 | `StreamEvent.ToolCallComplete` + `ToolCallAccumulator` + 单测 | 1.5 |
| M2 适配器接通 | OpenAI 请求体 tools + 消息回填 + SSE 接累积器 + 降级判定 | 1.5–2 |
| M3 循环 | `SendAgentMessageUseCase` 重写 + 截断 + 闸门 + use-case 单测 | 2 |
| M4 步骤 UI | 内存态 `AgentStep` + ViewModel 收集 + 粗展示 | 1 |
| M5 收尾 | 联调真实 OpenAI/DeepSeek/Ollama + 边界 + 评测基线（§6.5 任务 2/3） | 1–1.5 |
| **合计** | （内存态省去 Room 迁移 2–3 天） | **7–8 人天** |

**实现顺序**：M1（纯函数累积器先测稳）→ M2（接 SSE）→ M3（循环，fake adapter 驱动）→ M4 → M5（真实联调）。

---

## 10. 验收标准

1. 接 OpenAI/DeepSeek 等支持 function calling 的模型，提问"我之前写过关于 X 的笔记吗"→ 模型**实际调用** `search_in_project` 并基于结果回答（可观察到工具步骤）。
2. 多步任务（search→read→答）能在一次提问内自主完成，步数 ≤ MAX_STEPS。
3. 工具结果按预算截断，超长不爆窗。
4. 达最大步数 / 循环重复 → 安全停止并给提示，不卡死、不空转。
5. 接不支持 tools 的模型 → 自动降级为单轮对话且有提示，不报错崩溃。
6. 最终回答含 `[[ACTION]]` 时仍走第一波 diff 闸门，编辑安全无回归。
7. 中间步骤仅内存展示、最终回答落库；Room schema **未改动**。
8. `ToolCallAccumulatorTest` / `SendAgentMessageUseCaseTest` 全绿。
