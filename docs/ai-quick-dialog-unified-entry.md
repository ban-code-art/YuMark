# AI 快捷对话框统一入口优化

## 改进概述

**日期**: 2026-06-15  
**版本**: v0.6.3+

### 优化目标

将选中文本后显示的两个独立按钮（"询问 AI" 和 "Agent"）合并为一个统一的 "AI 助手" 按钮，在对话框内提供模式切换功能。

## 主要改进

### 1. 统一入口按钮

**之前：** 选中文本后显示两个按钮
- 💬 询问 AI
- 🤖 Agent

**现在：** 选中文本后仅显示一个按钮
- ✨ AI 助手

### 2. 对话框内模式切换

在对话框顶部添加了模式切换控件：
- **💬 询问**：询问 AI 模式（只读显示）
- **🤖 处理**：Agent 处理模式（可应用修改）

使用 `FilterChip` 组件实现，支持即时切换。

### 3. 消息互通

两种模式共享对话历史：
- 切换模式时保留用户输入内容
- 切换模式时保留 AI 回复内容
- 相同选中文本时恢复之前的完整状态（包括模式选择）

### 4. 界面优化

- 对话框高度从 70% 增加到 75%，提供更多显示空间
- 添加分隔线，使界面层次更清晰
- 模式切换按钮采用圆角背景，视觉效果更统一
- 标题改为通用的 "✨ AI 助手"

## 技术实现

### 1. AiQuickDialog.kt 修改

#### 函数签名变更

```kotlin
// 之前
fun AiQuickDialog(
    selectedText: String,
    mode: QuickAiMode,  // 外部传入固定模式
    onDismiss: () -> Unit,
    onApplyEdit: (String) -> Unit,
    ...
)

// 现在
fun AiQuickDialog(
    selectedText: String,
    initialMode: QuickAiMode = QuickAiMode.AI_QUERY,  // 初始模式，可选
    onDismiss: () -> Unit,
    onApplyEdit: (String) -> Unit,
    ...
)
```

#### ViewModel 状态管理

添加 `currentMode` 状态流：

```kotlin
private val _currentMode = MutableStateFlow(QuickAiMode.AI_QUERY)
val currentMode: StateFlow<QuickAiMode> = _currentMode.asStateFlow()

fun setMode(quickMode: QuickAiMode) {
    _currentMode.value = quickMode
}
```

#### UI 布局更新

```kotlin
// 标题和模式切换
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Text(text = "✨ AI 助手", style = MaterialTheme.typography.titleLarge)
    
    // 模式切换按钮
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            FilterChip(
                selected = currentMode == QuickAiMode.AI_QUERY,
                onClick = { viewModel.setMode(QuickAiMode.AI_QUERY) },
                label = { Text("💬 询问") }
            )
            FilterChip(
                selected = currentMode == QuickAiMode.AGENT_EDIT,
                onClick = { viewModel.setMode(QuickAiMode.AGENT_EDIT) },
                label = { Text("🤖 处理") }
            )
        }
    }
}
```

### 2. EditorScreen.kt 修改

#### 移除模式状态

```kotlin
// 移除
var quickAiMode by remember { mutableStateOf(QuickAiMode.AI_QUERY) }
```

#### 更新调用代码

```kotlin
// 预览模式 - 合并为单个按钮
SuggestionChip(
    onClick = { showQuickAiDialog = true },
    label = { Text("AI 助手") },
    icon = { Text("✨") }
)

// 编辑模式 - 合并为单个按钮
SuggestionChip(
    onClick = { showQuickAiDialog = true },
    label = { Text("AI 助手", style = MaterialTheme.typography.labelSmall) },
    icon = { Text("✨") }
)
```

## 用户体验改进

### 操作流程简化

**之前的流程：**
1. 选中文本
2. 看到两个按钮，需要预先决定使用哪种模式
3. 点击对应按钮
4. 在对话框中操作

**现在的流程：**
1. 选中文本
2. 点击 "AI 助手" 按钮
3. 在对话框中根据需要切换模式
4. 可以灵活切换模式，无需关闭重开

### 优势

1. **减少认知负担**：不需要提前决定使用哪种模式
2. **增强灵活性**：可以在对话中随时切换模式
3. **简化界面**：减少按钮数量，界面更简洁
4. **提升效率**：模式切换不需要关闭重开对话框

## 向后兼容

- 保留 `QuickAiMode` 枚举
- 保留 ViewModel 的核心逻辑
- 仅调整 UI 层的交互方式

## 测试要点

- [ ] 预览模式下选中文本，显示 "AI 助手" 按钮
- [ ] 编辑模式下选中文本，显示 "AI 助手" 按钮  
- [ ] 点击按钮打开对话框，默认为 "询问" 模式
- [ ] 在对话框中切换到 "处理" 模式
- [ ] 输入内容后切换模式，内容应保留
- [ ] AI 回复后切换模式，回复应保留
- [ ] 关闭对话框后重新打开，状态应恢复（相同选中文本）
- [ ] 选中不同文本打开对话框，状态应重置
- [ ] "处理" 模式下应显示 "应用修改" 按钮
- [ ] "询问" 模式下不显示 "应用修改" 按钮

## 相关文件

- `app/src/main/java/com/yumark/app/presentation/editor/AiQuickDialog.kt`
- `app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt`

## 后续优化建议

1. **添加模式快捷切换快捷键**：例如 Ctrl+1 切换到询问，Ctrl+2 切换到处理
2. **记住用户偏好**：记录用户最常使用的模式，作为默认模式
3. **添加更多模式**：例如"翻译"、"总结"、"扩写"等预设模式
4. **模式图标优化**：根据当前模式动态改变按钮图标
