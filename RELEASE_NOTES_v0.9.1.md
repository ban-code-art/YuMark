# YuMark v0.9.1 更新说明

版本：v0.9.1
versionCode：20
APK：YuMark-v0.9.1.apk

本次为 **Agent 体验修复版**：聚焦 v0.9 暴露的几处 Agent 与划词编辑顽疾——创建文档内容丢失、划词删除/改写匹配失败、弹窗间歇性不弹、写操作不可控，均在此版修复。无数据库变更，可直接覆盖安装。

## 划词 Agent：YY 显式触发修改

- **写操作改为用户显式触发**：仅「处理」模式 + 用户输入含 `yy`（大小写不敏感）时，才下发 `apply_edit` 工具并挂「应用修改」卡片；询问模式或不含 `yy` 的输入一律纯文本回复，不调工具、不改动选中文本。
- 发送时自动剥离 `yy`，AI 收到干净指令（剥离后为空则用占位「请改写选中文本」）；用户消息气泡仍显示原始输入。
- 处理模式输入框 placeholder 与标题下方说明同步提示「仅当输入含 yy 时才会修改选中文本，否则仅作答」。
- **解决**：原先「让 AI 删除/修改选中文本却不起作用」——根因是修改通道依赖 AI 自主意图判断 + 弱模型不调工具，现改为用户用 `yy` 显式授权，可控可预期。

## 划词删除/改写匹配增强

- 预览模式选区是渲染纯文本（Markdown 语法已去掉），源码含语法时原匹配的 token 间 `\s+` 连不上，导致 `| a | b |`→`a b`、`[文本](url)`→`文本`、`# 标题`→`标题`、`hello **world**`→`hello world` 等场景匹配失败、报「无法定位」。
- `locateByNormalizedMatch` 改为 token 间允许任意非单词字符穿插匹配，命中后前后扩展吞紧邻的语法符号（`**bold**` 的 `**`、表格的 `|`、链接的 `[]()`），不吞空白避免跨段。
- 预览模式（`range==null`）直接走语法感知匹配，跳过 `contains`/`trim`——避免单 token 子串命中留下 `****` 残留。
- **解决**：预览模式下选中含加粗/代码的文本删除不再报「无法定位」，整段去掉不留 `****`；标题/表格等结构主体可删。

## 划词弹窗竞态修复

- **根因**：点「AI 助手」chip 瞬间 `BasicTextField` 失焦、选区 collapse，`onValueChange` 清空 `selectedText`，弹窗条件 `selectedText.isNotEmpty()` 不成立 → 间歇性不弹。
- 改为点击时把 `selectedText to selectedRange` 快照存入 `pendingQuickAiSelection`，弹窗读快照、关闭时复位。
- 短选区过滤 `trim().length > 2` 放宽为 `isNotBlank()`（Kotlin 端 + 预览 JS 桥两端），1–2 字符选区也能触发。

## 普通 Agent：创建文档内容丢失修复

- **根因**：`extractDocumentBody` 用非贪婪正则 `([\\s\\S]*?)` ``` 提取 ```markdown 围栏内容，文档内部一旦含 ``` 代码块（技术笔记几乎必然），正则在内部首个 ``` 处提前闭合，**代码块及之后正文全部丢失**。系统提示又引导模型用 ```markdown 围栏输出整篇文档，故每次必触发。
- 改为取首个围栏开启行之后到末尾，再剥掉末尾最后一个独占一行的 ``` 闭合标记，内部代码块完整保留。
- 新增回归测试 `extractDocumentBody preserves inner code block when fenced doc contains one`。

## 普通 Agent：稳定性

- `AgentChatSheet` 消息列表 `forEach` 加 `key(msg.id)`，稳定重组、降低 WebView 错配风险（亦作为「拒绝闪退」的防御）。
- `awaitingBase` 分支加 `action.status == PENDING` 条件：reject 后的 EDIT 动作不再卡在「正在加载目标文档」卡片里反复触发 `ensureBaseContent`。

## 已知后续事项

- **拒绝闪退仍未定位**：CREATE + 点「拒绝」的崩溃在静态分析下路径安全（序列化有 `runCatching`、Room `@Update` 无异常、`WholeContentCard` 的 `if(PENDING)` 分支移除为合法重组），本次已加 `forEach key` 防御；若仍复现需 `adb logcat` 崩溃栈精确定位。
- `yy` 触发用 `contains("yy")`，理论上会误匹配含连续 yy 的英文词（极罕见），后续可改为词边界匹配。
- `locateByNormalizedMatch` 对 `**a**bold` 这类无空格病态输入仍可能误吞前元素闭合语法（正常 Markdown 行内元素间有空格/换行，不触发）。
