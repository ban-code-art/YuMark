# YuMark v0.6.4 - AI 助手交互优化

## 版本信息

**版本号**: v0.6.4  
**发布日期**: 2026-06-15  
**类型**: 功能优化 + Bug 修复

## 更新概述

本次更新专注于优化 AI 助手的交互体验，简化操作流程，提升使用便捷性。

## 核心改进

### 1. 统一 AI 入口 ✨

将选中文本后的两个独立按钮合并为一个统一入口。

**改进前**:
```
选中文本后显示：
┌─────────┐ ┌─────────┐
│ 💬 询问 AI │ │ 🤖 Agent │
└─────────┘ └─────────┘
```

**改进后**:
```
选中文本后显示：
┌──────────┐
│ ✨ AI 助手 │
└──────────┘
```

**优势**:
- 界面更简洁，减少视觉干扰
- 降低用户选择负担
- 无需提前决定使用哪种模式

### 2. 对话框内灵活切换 🔄

在对话框顶部添加模式切换器，支持即时切换。

```
┌────────────────────────────────────┐
│ ✨ AI 助手     [💬 询问] [🤖 处理]   │
├────────────────────────────────────┤
│ 选中的文本：                        │
│ [文本内容...]                       │
│                                    │
│ 你的问题/需求：                     │
│ [输入框]                    [发送]  │
│                                    │
│ AI 回复：                           │
│ [回复内容...]                       │
└────────────────────────────────────┘
```

**特性**:
- 💬 **询问模式**: 向 AI 提问，获取解释和建议
- 🤖 **处理模式**: 让 Agent 直接修改文本
- 🔄 **即时切换**: 无需关闭对话框
- 💾 **内容保留**: 切换时不丢失输入和回复

### 3. 智能状态管理 🧠

**对话历史保留**:
- ✅ 切换模式时保留用户输入
- ✅ 切换模式时保留 AI 回复
- ✅ 关闭对话框后重新打开恢复状态

**状态隔离**:
- ✅ 不同选中文本自动重置状态
- ✅ 相同选中文本恢复完整对话（包括模式）

**实际场景示例**:

**场景 1：渐进式需求**
```
1. 选中文本，打开 AI 助手
2. [询问模式] "这段话有什么问题？"
3. AI 回复：指出了语法和逻辑问题
4. 切换到 [处理模式]
5. "按照上面的建议修改"
6. Agent 直接生成修改后的文本
7. 点击"应用修改"
```

**场景 2：对比验证**
```
1. [处理模式] "改写成更专业的表达"
2. Agent 生成修改版本
3. 切换到 [询问模式]
4. "这个修改版本是否合适？"
5. AI 进行评估和建议
```

### 4. 自动清空输入框 🧹

**问题**: 发送问题后，输入框中的内容保留，可能导致误操作

**解决**: AI 回复完成后自动清空输入框

**细节**:
- ✅ 成功回复后清空（防止重复发送）
- ✅ 错误时保留（允许修改后重试）
- ✅ 加载中显示原内容（提供发送反馈）

### 5. 界面细节优化 🎨

| 项目 | 之前 | 现在 | 说明 |
|------|------|------|------|
| 对话框高度 | 70% | 75% | 提供更多内容显示空间 |
| 标题 | "💬 询问 AI" / "🤖 Agent" | "✨ AI 助手" | 统一品牌形象 |
| 分隔线 | 无 | HorizontalDivider | 增强视觉层次 |
| 按钮图标 | 💬 / 🤖 | ✨ | 简洁统一 |
| 模式切换器 | 无 | FilterChip | Material 3 风格 |

## 技术实现

### 架构改进

```kotlin
// ViewModel 状态管理
class AiQuickViewModel {
    // 新增：当前模式状态流
    private val _currentMode = MutableStateFlow(QuickAiMode.AI_QUERY)
    val currentMode: StateFlow<QuickAiMode> = _currentMode
    
    // 模式切换
    fun setMode(quickMode: QuickAiMode) {
        _currentMode.value = quickMode
    }
}

// UI 层简化
@Composable
fun AiQuickDialog(
    selectedText: String,
    initialMode: QuickAiMode = QuickAiMode.AI_QUERY,  // 可选参数
    onDismiss: () -> Unit,
    onApplyEdit: (String) -> Unit
) {
    val currentMode by viewModel.currentMode.collectAsState()
    // ...
}
```

### 模式切换 UI

```kotlin
Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
) {
    Row(modifier = Modifier.padding(4.dp)) {
        FilterChip(
            selected = currentMode == QuickAiMode.AI_QUERY,
            onClick = { viewModel.setMode(QuickAiMode.AI_QUERY) },
            label = { Text("💬 询问") },
            modifier = Modifier.height(32.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        FilterChip(
            selected = currentMode == QuickAiMode.AGENT_EDIT,
            onClick = { viewModel.setMode(QuickAiMode.AGENT_EDIT) },
            label = { Text("🤖 处理") },
            modifier = Modifier.height(32.dp)
        )
    }
}
```

## 修改文件列表

```
modified:   app/src/main/java/com/yumark/app/presentation/editor/AiQuickDialog.kt
modified:   app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt
```

## 向后兼容性

- ✅ API 完全兼容
- ✅ 数据结构无变化
- ✅ 仅 UI 层交互调整

## 测试清单

### 功能测试
- [x] 预览模式选中文本显示按钮
- [x] 编辑模式选中文本显示按钮
- [x] 点击按钮打开对话框
- [x] 默认为询问模式
- [x] 切换到处理模式
- [x] 模式切换内容保留
- [x] AI 回复后输入框清空
- [x] 错误时输入框保留
- [x] 关闭重开恢复状态
- [x] 不同文本状态重置

### UI 测试
- [x] 模式切换器样式正确
- [x] 选中状态视觉清晰
- [x] 分隔线显示正常
- [x] 按钮图标一致

### 交互测试
- [x] 处理模式显示"应用修改"
- [x] 询问模式隐藏"应用修改"
- [x] 退出保护对话框正常
- [x] 加载状态正确显示

## 性能指标

- ✅ 无性能回退
- ✅ 减少一个按钮渲染
- ✅ 状态管理开销可忽略
- ✅ 编译成功，无警告

**APK 大小**: 22MB (debug)

## 已知问题

无

## 用户反馈

基于用户反馈快速修复：
- ✅ AI 回复后输入框未清空 → 已修复

## 后续规划

### v0.6.5 计划
- [ ] 模式记忆功能（记住最常用模式）
- [ ] 快捷键支持（Ctrl+1/2 切换模式）
- [ ] 输入历史（快速重发）

### v0.7.0 计划
- [ ] 更多预设模式（翻译、总结、扩写、校对）
- [ ] 自定义模式配置
- [ ] 多轮对话支持

### v1.0.0 愿景
- [ ] AI 智能推荐模式
- [ ] 对话历史记录
- [ ] 跨会话状态恢复

## 相关文档

1. [AI 快捷对话框统一入口优化](./ai-quick-dialog-unified-entry.md)
2. [AI 回复后自动清空输入框修复](./ai-quick-dialog-auto-clear-input-fix.md)
3. [AI 快捷对话框退出保护](./ai-quick-dialog-exit-protection.md)
4. [AI 快捷对话框状态隔离修复](./ai-quick-dialog-state-isolation-fix.md)

## 致谢

感谢用户反馈和测试，帮助我们快速发现和修复问题！

---

**YuMark v0.6.4** - 让 AI 助手更智能、更好用 ✨
