# 设计文档：编辑 Diff 闸门（Agent 第一波 P1.1）

- 日期：2026-06-17
- 状态：详细设计 / 待评审 → 待实现
- 关联文档：`docs/agent-gap-analysis-2026-06-17.md`（§2 P1.1、§5 D1、§3 第一波）
- 关联代码：`presentation/ai/agent/AgentActionCard.kt`、`presentation/ai/agent/AgentChatSheet.kt`、`domain/usecase/ai/agent/AgentUseCases.kt`、`presentation/editor/AiQuickDialog.kt`、`presentation/editor/EditorScreen.kt`、`presentation/editor/EditorViewModel.kt`

> 本文是 gap-analysis「第一波」的落地设计。第一波 = P1.2（错误重试，**已完成**）+ **P1.1 差分预览 + 定向编辑**（本文）+ P1.2 遗留项（解析失败提示，§7）。

---

## 1. 目标与范围

### 1.1 目标

消除「整篇盲改」这一**当前最高信任风险**：AI 编辑用户既有文档前，必须让用户**看清逐行改动、并逐块决定接受或拒绝**，按所选改动合成最终内容后才落库（gap-analysis D1：`EDIT_DOCUMENT` 必经 diff 批准）。

### 1.2 决策回执（2026-06-17 已定）

| 决策 | 取值 | 来源 |
|---|---|---|
| 接受粒度 | **逐 hunk 接受/拒绝** + 部分应用合成 | 本轮确认 |
| 覆盖路径 | **Agent 整篇编辑 + Quick 选区改写** | 本轮确认 |
| diff 粒度 | **行级 LCS**（零依赖，自实现） | gap-analysis P1.1 + 本文 §3.3 |
| CREATE | **不走 diff，直接落库**（保留预览+确认） | gap-analysis D1 |

### 1.3 范围内

- ✅ 行级 diff 引擎（纯 Kotlin，可单测）
- ✅ 逐 hunk 接受/拒绝 UI + 部分应用合成
- ✅ Agent `EDIT_DOCUMENT`（整篇覆盖）接入 diff 闸门
- ✅ Quick 选区改写（`[[EDIT]]`）接入 diff 闸门
- ✅ P1.2 遗留：动作解析失败的可见提示

### 1.4 范围外（明确不做，避免范围蔓延）

- ❌ **`edit_document_range` 工具**：gap-analysis P1.1 方案 3 列了它，但它依赖**第二波 P2.1 工具回路**才能被模型调用（第一波无回路，模型无法发起工具调用）。本波只把"区间替换/部分应用"的**底层能力**建好，工具壳留给 P2.2。
- ❌ **checkpoint / 撤销**（P4.2）：本波 diff 闸门是**事前**防线；事后撤销是二级保险，留到第四波（D1 已降级）。本设计在 §8 预留合并点。
- ❌ 字符级 / 语义 diff：行级对 Markdown（标题/段落/列表项基本按行）足够，且零依赖。
- ❌ 三家适配器、ReAct 回路等第二波内容。

---

## 2. 现状：编辑落库的三条路径

| 路径 | 入口 | 落库 | 本波处理 |
|---|---|---|---|
| **Agent EDIT_DOCUMENT** | `AgentChatViewModel.approve` (`AgentChatSheet.kt:114`) → `ExecuteAgentActionUseCase` (`AgentUseCases.kt:170`) | `saveDocumentUseCase(doc.copy(content = action.content))` 整篇覆盖 (`AgentUseCases.kt:182`) | **加 diff 闸门** |
| **Agent CREATE_DOCUMENT** | 同上 → CREATE 分支 (`AgentUseCases.kt:172`) | 新建 + save | 不变（D1 直接落库） |
| **Quick 选区改写** | `AiQuickDialog` 卡片 `onApprove` (`AiQuickDialog.kt:252`) → `onApplyEdit` → `EditorViewModel.replaceSelectedText` (`EditorViewModel.kt:339`) | 选区/匹配替换 | **加 diff 闸门** |

**共同 UI**：三路审批都复用 `AgentActionCard`（`AgentActionCard.kt:23`）。它当前只有折叠「查看内容」显示**新全文**（`:48-68`），**无 diff、无逐块控制**，批准是整体的（`:70-83`）。→ 本波的核心改造对象。

**关键事实**：Quick 路径的 `replaceSelectedText(oldText, newText, range)`（`EditorViewModel.kt:339-369`）优先按精确 `range` 替换、兜底 `contains`/`trim` 匹配。**逐 hunk 合成只需替换其 `newText` 入参，不改该函数**。

---

## 3. 架构设计

### 3.1 分层

```
core/util/diff/                 ← 纯算法，无 Android 依赖，可 JVM 单测
  ├─ DiffModels.kt              DiffLine / DiffHunk / DiffResult
  ├─ LineDiffer.kt             行级 LCS：diff(old, new) → DiffResult
  └─ DiffComposer.kt           applyHunks(result, accepted[]) → String

presentation/common/
  └─ DiffView.kt               @Composable 渲染 hunk + 勾选（可复用）

presentation/ai/agent/
  └─ AgentActionCard.kt        改造：折叠区换成 DiffView（EDIT 路径）
presentation/editor/
  └─ AiQuickDialog.kt          改造：onApprove 传合成后的 newText
domain/usecase/ai/agent/
  └─ AgentUseCases.kt          ExecuteAgentActionUseCase 接受 finalContent
```

### 3.2 数据模型（`DiffModels.kt` 草案）

```kotlin
enum class DiffOp { UNCHANGED, ADDED, REMOVED }

/** diff 的一行。oldIndex/newIndex 便于行号显示，UNCHANGED 两者皆有。 */
data class DiffLine(
    val op: DiffOp,
    val text: String,
    val hunkId: Int,        // -1 表示不属于任何 hunk（UNCHANGED）
)

/** 一个连续变更块（相邻的 REMOVED/ADDED，被 UNCHANGED 打断）。 */
data class DiffHunk(
    val id: Int,
    val removed: List<String>,   // 该块删除的原文行
    val added: List<String>,     // 该块新增的行
    val accepted: Boolean = true // 默认接受
)

data class DiffResult(
    val lines: List<DiffLine>,   // 有序，用于渲染
    val hunks: List<DiffHunk>,   // 用于勾选与合成
    val degraded: Boolean = false // true=超大文档降级为整体预览（见 §3.3）
) {
    val hasChanges: Boolean get() = hunks.isNotEmpty()
}
```

### 3.3 行级 diff 引擎（`LineDiffer.kt`）

- **算法**：标准 LCS 动态规划 + 回溯。
  1. `old.split("\n")`、`new.split("\n")`；
  2. 构造 LCS DP 表 `dp[i][j]`；
  3. 回溯产出有序 `DiffLine`：在 LCS 中的行 → `UNCHANGED`；仅在 old → `REMOVED`；仅在 new → `ADDED`；
  4. 扫描 `DiffLine` 序列，把**连续的非 UNCHANGED 行**聚为 `DiffHunk`（纯增=新增块、纯删=删除块、删+增相邻=修改块）。
- **复杂度**：O(n·m) 时空（n、m 为两侧行数）。
- **降级闸门（性能 + 可用性）**：当 `n > MAX_DIFF_LINES`（建议 2000）或 `n*m > MAX_DIFF_CELLS`（建议 2_000_000）时，**不做行级 diff**，返回 `degraded=true` 的单 hunk（整段 removed=old、added=new），UI 退化为「整体预览 + 整体接受/拒绝」并标注「文档较大，已切换整体预览」。笔记文档绝大多数 < 2000 行，正常走逐 hunk。
- **可测性**：纯函数 `fun diff(old: String, new: String): DiffResult`，无 Android 依赖。

### 3.4 部分应用合成（`DiffComposer.kt`）

```kotlin
/** 按各 hunk 的接受状态合成最终文本。accepted 下标与 result.hunks 对齐。 */
fun applyHunks(result: DiffResult, accepted: List<Boolean>): String {
    val out = StringBuilder()
    for (line in result.lines) {
        when (line.op) {
            DiffOp.UNCHANGED -> out.appendLine(line.text)
            DiffOp.ADDED   -> if (accepted[line.hunkId]) out.appendLine(line.text)   // 接受新增
            DiffOp.REMOVED -> if (!accepted[line.hunkId]) out.appendLine(line.text)  // 拒绝→还原原文
        }
    }
    return out.toString().removeSuffix("\n")  // 保持与原文尾换行一致
}
```

**不变式（必须单测）**：
- 全部接受 ⇒ `applyHunks == newText`
- 全部拒绝 ⇒ `applyHunks == oldText`
- 任意组合 ⇒ 接受的改动生效、拒绝的还原原文

### 3.5 两条路径接线

**Agent EDIT_DOCUMENT**
- base（旧内容）= 目标文档当前内容。`parseAgentAction` 把 `targetDocumentId` 设为当前文档（`AgentUseCases.kt:252`），故 base = 当前打开文档内容（`AgentChatViewModel.docContent`，必要时落库前以 `loadDocumentUseCase(targetId)` 最新值为准，见 §8 base 漂移）。
- 卡片显示时：`diff(baseContent, action.content)` → 用户勾选 → 合成 `finalContent`。
- 落库：`ExecuteAgentActionUseCase` 接受 `finalContent`，EDIT 分支改为 `saveDocumentUseCase(doc.copy(content = finalContent))`。
  ```kotlin
  // AgentUseCases.kt 改造（示意）
  suspend operator fun invoke(message, action, finalContent: String? = null): Result<String> {
      // EDIT 分支：
      val content = finalContent ?: action.content   // 向后兼容；闸门走 finalContent
      saveDocumentUseCase(doc.copy(content = content)).getOrThrow()
  }
  ```
  `AgentChatViewModel.approve(message, action, finalContent)` 把卡片合成结果透传。

**Quick 选区改写**
- base = `editableSelectedText`（选区），new = `editContent`（AI 改写）。
- 卡片显示时：`diff(oldText, newText)` → 用户勾选 → 合成 `partialNewText`。
- 落库：`onApplyEdit(oldText, partialNewText)` → `replaceSelectedText(oldText, partialNewText, range)`（**该函数不变**）。

---

## 4. UI 设计

### 4.1 `AgentActionCard` 改造

签名增加 base 文本与新回调（CREATE 不传 base，走旧的整体批准）：

```kotlin
@Composable
fun AgentActionCard(
    action: AgentAction,
    baseContent: String? = null,         // 新增：EDIT 的旧内容；null=CREATE，不渲染 diff
    onApproveDiff: (finalContent: String) -> Unit = {},  // 新增：EDIT 部分应用
    onApprove: () -> Unit = {},          // 保留：CREATE 整体批准
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- **EDIT（baseContent != null）**：折叠区「查看内容」→「查看改动」，内嵌 `DiffView`；底部按钮 `[应用已选 N 处]`（合成后调 `onApproveDiff`）/ `[全部拒绝]`（`onReject`）。无改动时显示「AI 未提出改动」并禁用应用。
- **CREATE（baseContent == null）**：保持现状（查看内容 + 批准/拒绝）。

### 4.2 `DiffView`（`presentation/common/DiffView.kt`）

- 渲染 `DiffResult.lines`：`UNCHANGED` 灰、`ADDED` 绿底 `+`、`REMOVED` 红底删除线 `-`（用 `MaterialTheme.colorScheme`，深色模式适配）。
- 每个 hunk 头部一个 `Checkbox`（默认勾选）控制该块接受/拒绝；顶部「全选/全不选」。
- hunk 接受态：`var accepted by remember { mutableStateListOf(*hunks.map{true}) }`。
- 可滚动（`heightIn(max = 320.dp)`），复用现有折叠区的滚动样式。
- `degraded=true` 时只渲染「旧 / 新」两段全文对照 + 单一接受/拒绝，并显示降级说明条。

### 4.3 文案

沿用项目「AI 文案硬编码在 Compose」惯例（见 `AiErrorMapper.kt` 注释），不进 `strings.xml`。关键词：查看改动 / 收起 / 应用已选 N 处 / 全部拒绝 / AI 未提出改动 / 文档较大，已切换整体预览。

---

## 5. 改动清单（文件级）

**新增**
- `core/util/diff/DiffModels.kt`、`LineDiffer.kt`、`DiffComposer.kt`
- `presentation/common/DiffView.kt`
- 测试：`core/util/diff/LineDifferTest.kt`、`DiffComposerTest.kt`

**修改**
- `presentation/ai/agent/AgentActionCard.kt`：折叠区→`DiffView`；新增 `baseContent`/`onApproveDiff`。
- `presentation/ai/agent/AgentChatSheet.kt`：`approve` 透传 `finalContent`；卡片调用处传 `baseContent`（当前文档内容）。
- `domain/usecase/ai/agent/AgentUseCases.kt`：`ExecuteAgentActionUseCase.invoke` 加 `finalContent` 参数（EDIT 用之）。
- `presentation/editor/AiQuickDialog.kt`：卡片传 `baseContent = editableSelectedText`；`onApproveDiff` 合成后调 `onApplyEdit(oldText, partialNewText)`（`:252-255`）。
- `presentation/editor/EditorScreen.kt`：`onApplyEdit`（`:145`）保持，落到 `replaceSelectedText`。
- P1.2 遗留：见 §7。

---

## 6. 测试策略

- **`LineDifferTest`**：空↔空、全新增、全删除、纯修改、首尾改动、中间穿插、相同文本（0 hunk）、中文、CRLF/尾换行、降级阈值触发。
- **`DiffComposerTest`**（不变式）：全接受=new、全拒绝=old、奇偶 hunk 组合、单 hunk、相邻删+增的修改块。
- **`EditorViewModelTest`**（已存在）：扩展 `replaceSelectedText` 在「合成后的 partialNewText」下仍正确替换。
- 纯算法层全部走 JVM 单测，不需 Android instrumentation。

---

## 7. P1.2 遗留项：动作解析失败可见化

- **现状**：`parseAgentAction` 返回 null 时静默当普通对话（`AgentUseCases.kt:113`），用户以为"AI 没干活"。
- **改进**：`SendAgentMessageUseCase` 的 `Done` 分支，当文本含疑似动作标记（如出现 `[[ACTION]]` 但解析失败）时，emit 一条提示态（或在消息上挂一个轻量标记），UI 显示「AI 输出的操作块格式有误，未生成可应用的改动」。
- **工作量**：0.5 人天。P2.2 工具化后该问题随文本协议退场自然消失，本波仅补过渡提示。

---

## 8. 风险与边界

- **R-base 漂移**：diff 在卡片生成时以 base 为基准；若用户在批准前改了文档，落库会基于旧 base 合成。**缓解**：`ExecuteAgentActionUseCase` 落库前 `loadDocumentUseCase(targetId)` 取最新内容，若与生成 diff 时的 base 不一致则提示「文档已变化，请重新生成改动」并放弃本次应用（不静默覆盖）。
- **R-Quick 定位失败**：选区在对话期间变动，`replaceSelectedText` 三级匹配均失败 → 沿用现有报错（`EditorViewModel.kt:365`「无法定位选中文本」）。
- **R-大文档性能**：O(n·m) → §3.3 降级闸门兜底。
- **R-hunk 边界语义**：相邻「删+增」聚为一个「修改块」一起接受/拒绝，避免出现"接受新增但不接受对应删除"导致内容重复；纯增/纯删各自独立。该聚合规则在 `LineDiffer` 实现并单测。
- **与 P2.2 衔接**：第二波把编辑做成 `edit_document` 工具时，**工具执行前的审批门直接复用本 `DiffView` + `applyHunks`**——工具调用产出 (target, newContent)，走与 Agent EDIT 相同的闸门。本设计即 P2.2 的前置依赖（D1 排期：第一波必须先于第二波）。
- **与 P4.2 衔接**：未来 checkpoint 在 `applyHunks` 落库前存一份 base 快照即可，无需改本设计结构。

---

## 9. 工作量与里程碑

| 里程碑 | 内容 | 人天 |
|---|---|---|
| M1 引擎 | `LineDiffer` + `DiffComposer` + 单测（不变式绿） | 1–1.5 |
| M2 UI | `DiffView`（hunk 渲染 + 勾选 + 降级） | 1 |
| M3 接线 | `AgentActionCard` 改造 + Agent/Quick 两路接入 + base 漂移校验 | 1–1.5 |
| M4 收尾 | P1.2 遗留提示 + 联调 + 边界用例 | 0.5 |
| **合计** | | **3.5–4.5 人天** |

**实现顺序（TDD 友好）**：M1（先红后绿，不变式驱动）→ M2 → M3 → M4。引擎与合成是纯函数，先测稳了再接 UI，UI 只负责勾选与合成调用。

---

## 10. 验收标准

1. Agent `EDIT_DOCUMENT` 批准前**必然**展示行级 diff；不存在"无 diff 直接覆盖"的路径。
2. 逐 hunk 勾选后，落库内容 == `applyHunks` 合成结果（全接受=AI 全文、全拒绝=原文不变、部分=按选生效）。
3. Quick 选区改写同样走 diff 闸门，部分应用正确。
4. `CREATE_DOCUMENT` 行为不变（直接预览 + 批准落库）。
5. 大文档触发降级时有明确提示，仍可整体接受/拒绝。
6. 动作解析失败时用户能看到提示，不再静默。
7. `LineDifferTest` / `DiffComposerTest` 全绿。
