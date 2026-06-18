# AI 快捷对话框重构 - 对话历史记录功能

## 问题分析

**用户反馈的问题**:
1. ❌ 发送消息后输入框内容仍然存在，容易误操作
2. ❌ 没有对话历史记录，无法查看之前的对话
3. ❌ 发送后还能切换模式，导致逻辑混乱
4. ❌ 无法进行多轮对话

## 解决方案

### 1. 实现对话历史记录 💬

**新增功能**:
- 使用 `LazyColumn` 显示完整的对话历史
- 每条消息包含角色（用户/AI）和内容
- 自动滚动到最新消息
- 支持流式显示 AI 回复

**数据结构**:
```kotlin
data class ConversationMessage(
    val role: MessageRole,      // USER 或 ASSISTANT
    val content: String,         // 消息内容
    val mode: QuickAiMode       // 发送时的模式（询问/处理）
)
```

**状态管理**:
```kotlin
private val _conversationHistory = MutableStateFlow<List<ConversationMessage>>(emptyList())
val conversationHistory: StateFlow<List<ConversationMessage>> = _conversationHistory.asStateFlow()
```

### 2. 模式锁定机制 🔒

**规则**:
- **有消息时**: 模式切换按钮禁用（灰色显示）
- **无消息时**: 可以自由切换模式
- **发送后**: 模式锁定在当前状态

**实现**:
```kotlin
val hasMessages: StateFlow<Boolean> = MutableStateFlow(false).apply {
    viewModelScope.launch {
        _conversationHistory.collect { history ->
            value = history.isNotEmpty()
        }
    }
}.asStateFlow()

fun setMode(quickMode: QuickAiMode) {
    // 只有在没有消息时才能切换模式
    if (_conversationHistory.value.isEmpty()) {
        _currentMode.value = quickMode
    }
}
```

**UI 反馈**:
```kotlin
FilterChip(
    selected = currentMode == QuickAiMode.AI_QUERY,
    onClick = { if (!hasMessages) viewModel.setMode(QuickAiMode.AI_QUERY) },
    enabled = !hasMessages  // 有消息时禁用
)
```

### 3. 改进的消息流程 📨

**发送流程**:
```
1. 用户输入消息
2. 点击发送按钮
3. 添加用户消息到历史记录
4. 立即清空输入框
5. 显示加载状态
6. 流式接收 AI 回复
7. 更新历史记录中的 AI 消息
8. 完成，可以继续输入下一条
```

**代码实现**:
```kotlin
fun send() {
    val userMessage = _userInput.value
    
    // 1. 添加用户消息
    _conversationHistory.value = _conversationHistory.value + ConversationMessage(
        role = MessageRole.USER,
        content = userMessage,
        mode = _currentMode.value
    )
    
    // 2. 立即清空输入框
    _userInput.value = ""
    
    // 3. 流式接收并更新 AI 回复
    adapter.sendChatStream(...).collect { event ->
        when (event) {
            is StreamEvent.Content -> {
                // 流式更新 AI 消息
                fullResponse.append(event.text)
                _conversationHistory.value = currentHistory + ConversationMessage(
                    role = MessageRole.ASSISTANT,
                    content = fullResponse.toString(),
                    mode = currentModeSnapshot
                )
            }
            is StreamEvent.Done -> {
                // 完成
                _isLoading.value = false
            }
        }
    }
}
```

### 4. 界面布局优化 🎨

**新布局结构**:
```
┌─────────────────────────────────────┐
│ ✨ AI 助手    [💬 询问] [🤖 处理]    │ ← 标题栏（有消息时按钮禁用）
├─────────────────────────────────────┤
│ ┌─────────────────────────────────┐ │
│ │ 选中的文本：（仅无消息时显示）    │ │
│ │ [文本内容...]                    │ │
│ └─────────────────────────────────┘ │
│                                     │
│ [用户消息 1]                        │ ← 对话历史（LazyColumn）
│     [AI 回复 1]                     │
│ [用户消息 2]                        │
│     [AI 回复 2...]                  │
│                                     │
│ [加载中...]                         │ ← 加载状态
├─────────────────────────────────────┤
│ [输入框...] [发送]                  │ ← 输入区域
│ [应用最新修改] (Agent 模式)          │
└─────────────────────────────────────┘
```

**关键改进**:
- 对话框高度从 75% 增加到 80%
- 使用 `LazyColumn` 支持长对话滚动
- 选中文本仅在没有消息时显示
- 输入框固定在底部
- 自动滚动到最新消息

### 5. Agent 模式应用修改 🤖

**改进**:
- 不再在每条回复后显示"应用修改"
- 在输入框下方显示"应用最新修改"按钮
- 应用最后一条 AI 消息的内容
- 支持多轮对话后选择最佳方案应用

```kotlin
if (currentMode == QuickAiMode.AGENT_EDIT && conversationHistory.isNotEmpty()) {
    val lastAssistantMessage = conversationHistory.lastOrNull { 
        it.role == MessageRole.ASSISTANT 
    }
    if (lastAssistantMessage != null && !isLoading) {
        Button(
            onClick = {
                onApplyEdit(lastAssistantMessage.content)
                onDismiss()
            }
        ) {
            Text("应用最新修改")
        }
    }
}
```

## 用户体验提升

### 场景 1：多轮询问

```
用户: 选中 "世界经济系指全球范围..."
1. 打开 AI 助手（默认询问模式）
2. 输入："这段话有什么问题？"
3. [发送] → 输入框清空，消息进入历史
4. AI 回复："有几个语法错误..."
5. 继续输入："具体哪些词有问题？"
6. [发送] → 又一轮对话
7. AI 回复："'系指'应该是'是指'..."
```

### 场景 2：Agent 多次迭代

```
用户: 选中一段文字
1. 打开 AI 助手，切换到"处理"模式
2. 输入："改写成更专业的表达"
3. [发送] → 看到修改版本 v1
4. 继续输入："再简洁一些"
5. [发送] → 看到修改版本 v2
6. 点击"应用最新修改" → 使用 v2 替换
```

### 场景 3：模式锁定保护

```
用户: 打开对话框
1. 选择"询问"模式
2. 发送第一条消息
3. 尝试切换到"处理"模式 → ❌ 按钮禁用
4. 提示：避免混合两种模式导致逻辑混乱
```

## 技术亮点

### 1. 流式显示优化

```kotlin
is StreamEvent.Content -> {
    fullResponse.append(event.text)
    val currentHistory = _conversationHistory.value
    val lastMessage = currentHistory.lastOrNull()
    
    if (lastMessage?.role == MessageRole.ASSISTANT) {
        // 更新现有的 AI 消息（避免重复添加）
        _conversationHistory.value = currentHistory.dropLast(1) + ConversationMessage(...)
    } else {
        // 添加新的 AI 消息
        _conversationHistory.value = currentHistory + ConversationMessage(...)
    }
}
```

### 2. 自动滚动

```kotlin
val listState = rememberLazyListState()

LaunchedEffect(conversationHistory.size) {
    if (conversationHistory.isNotEmpty()) {
        listState.animateScrollToItem(conversationHistory.size - 1)
    }
}
```

### 3. 状态隔离

- 不同选中文本 → 完全重置对话
- 相同选中文本 → 恢复之前的对话历史

## 对比总结

| 项目 | 之前 | 现在 |
|------|------|------|
| 对话历史 | ❌ 无 | ✅ 完整记录 |
| 多轮对话 | ❌ 不支持 | ✅ 支持 |
| 模式切换 | ⚠️ 随时可切换 | ✅ 发送后锁定 |
| 输入框 | ⚠️ 发送后不清空 | ✅ 立即清空 |
| 消息显示 | 📝 单次显示 | 💬 历史列表 |
| 应用修改 | 🤖 每条消息后 | 🤖 底部统一按钮 |
| 界面高度 | 75% | 80% |
| 滚动 | ❌ 无 | ✅ 自动滚动到最新 |

## 文件修改

```
modified:   app/src/main/java/com/yumark/app/presentation/editor/AiQuickDialog.kt
```

**代码行数**: 
- 之前: 441 行
- 现在: 560 行
- 新增: 119 行（主要是对话历史功能）

## 测试要点

- [ ] 发送消息后输入框立即清空
- [ ] 对话历史正确显示
- [ ] 流式回复实时更新
- [ ] 自动滚动到最新消息
- [ ] 有消息时模式按钮禁用
- [ ] 无消息时可以切换模式
- [ ] Agent 模式显示"应用最新修改"
- [ ] 询问模式不显示应用按钮
- [ ] 多轮对话正常工作
- [ ] 错误处理正确
- [ ] 相同文本恢复历史
- [ ] 不同文本重置历史

## 已知限制

1. 对话历史仅保存在内存中，关闭应用后清空
2. 不支持编辑或删除历史消息
3. 不支持复制单条消息
4. 长对话可能占用较多内存

## 后续优化建议

1. **持久化**: 保存对话历史到数据库
2. **消息操作**: 长按消息复制/删除
3. **上下文管理**: 自动总结长对话，减少 token 消耗
4. **导出功能**: 导出对话记录为 Markdown
5. **搜索功能**: 搜索历史对话
6. **引用功能**: 引用之前的回复继续提问

---

**更新完成** ✅  
**编译状态**: 成功  
**测试状态**: 待验证
