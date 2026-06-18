# 文本选择 AI 按钮优化

**优化内容**:
1. 修复没有选中文本时仍显示按钮的问题
2. 优化按钮样式，使用 SuggestionChip 更接近系统原生

**日期**: 2026-06-15

---

## 🐛 问题 1: 按钮不消失

### 原因
- JavaScript 监听到文本选择后设置 `selectedText`
- 用户取消选择时没有通知 Kotlin 清空
- 导致按钮一直显示

### 解决方案

**JavaScript 端**:
```javascript
function notifySelection() {
    var text = selection.toString().trim();
    
    if (text && text.length > 2) {
        // 有文本选中
        AndroidSelection.onTextSelected(text);
    } else {
        // 文本清空
        AndroidSelection.onTextSelected('');  // 发送空字符串
    }
}

// 监听点击事件，检测取消选择
document.addEventListener('click', function(e) {
    setTimeout(notifySelection, 100);
});
```

**Kotlin 端**:
```kotlin
@JavascriptInterface
fun onTextSelected(text: String) {
    scope.launch {
        if (text.isBlank()) {
            selectedText = ""  // 清空选中文本
        } else if (aiEnabled && text.trim().length > 2) {
            selectedText = text  // 更新选中文本
        }
    }
}
```

---

## 🎨 问题 2: 按钮样式不够原生

### 旧样式
```kotlin
Button(...) {
    Text("💬 询问 AI")
}
OutlinedButton(...) {
    Text("🤖 Agent")
}
```

**问题**:
- 矩形按钮，太突兀
- 不像系统原生样式

### 新样式
```kotlin
SuggestionChip(
    onClick = { ... },
    label = { Text("询问 AI") },
    icon = { Text("💬") }
)

SuggestionChip(
    onClick = { ... },
    label = { Text("Agent") },
    icon = { Text("🤖") }
)
```

**优势**:
- ✅ 圆角胶囊样式
- ✅ 更接近系统原生 Chip
- ✅ 图标和文字分离，更清晰
- ✅ 自动适配主题

---

## 🎨 视觉效果

### 旧样式
```
┌──────────────────────────────┐
│ [💬 询问 AI]  [🤖 Agent]    │ ← 矩形按钮
└──────────────────────────────┘
```

### 新样式
```
┌──────────────────────────────┐
│  (💬 询问 AI)  (🤖 Agent)   │ ← 圆角 Chip
└──────────────────────────────┘
```

更像这样：
```
┌────────────────────────────────────┐
│  💬  询问 AI     🤖  Agent        │
│  ‾‾‾‾‾‾‾‾‾     ‾‾‾‾‾‾‾‾          │
└────────────────────────────────────┘
```

---

## 📱 两种模式的样式

### 预览模式
- 居中显示
- 有阴影效果（更突出）
- 背景有 tonalElevation

```kotlin
Surface(
    tonalElevation = 2.dp,
    shadowElevation = 4.dp
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center  // 居中
    ) {
        SuggestionChip(...)
        SuggestionChip(...)
    }
}
```

### 编辑模式
- 左对齐显示
- 在底部状态栏
- 与字符计数器共存

```kotlin
Surface(tonalElevation = 1.dp) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row {  // 左侧：AI 按钮
            SuggestionChip(...)
            SuggestionChip(...)
        }
        Text("1250 字符")  // 右侧：字符计数
    }
}
```

---

## 🔄 完整的选择-取消流程

### 选中文本
```
1. 用户长按文本
        ↓
2. JavaScript 检测到选择
        ↓
3. 调用 AndroidSelection.onTextSelected("选中的文本")
        ↓
4. Kotlin 更新 selectedText = "选中的文本"
        ↓
5. 底部出现 SuggestionChip 按钮
```

### 取消选择
```
1. 用户点击空白处
        ↓
2. JavaScript 检测到选择清空
        ↓
3. 调用 AndroidSelection.onTextSelected("")
        ↓
4. Kotlin 更新 selectedText = ""
        ↓
5. 按钮消失 ✅
```

---

## 🧪 测试场景

### 测试 1: 按钮出现和消失
1. 打开文档（预览或编辑模式）
2. 选中一段文字
3. **期望**: 底部出现两个 Chip 按钮
4. 点击空白处取消选择
5. **期望**: 按钮消失

### 测试 2: 短文本过滤
1. 选中 1-2 个字符
2. **期望**: 按钮不出现（文本太短）
3. 选中 3+ 字符
4. **期望**: 按钮出现

### 测试 3: 快速切换选择
1. 选中文本 A
2. **期望**: 按钮出现
3. 不取消，直接选中文本 B
4. **期望**: 按钮保持显示，selectedText 更新为 B

### 测试 4: AI 未启用
1. 在设置中禁用 AI
2. 选中文本
3. **期望**: 按钮不出现

### 测试 5: Chip 点击
1. 选中文本
2. 点击 "💬 询问 AI" Chip
3. **期望**: 弹出对话框，模式为 AI_QUERY
4. 关闭对话框
5. 点击 "🤖 Agent" Chip
6. **期望**: 弹出对话框，模式为 AGENT_EDIT

---

## 🎯 SuggestionChip 特性

### Material 3 标准组件
- 圆角胶囊形状
- 自动适配主题颜色
- 支持图标 + 文字
- 有按压反馈动画
- 符合 Material Design 规范

### API
```kotlin
SuggestionChip(
    onClick: () -> Unit,          // 点击事件
    label: @Composable () -> Unit,  // 文字标签
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (() -> Unit)? = null,  // 前置图标
    shape: Shape = ChipDefaults.shape,
    colors: ChipColors = SuggestionChipDefaults.suggestionChipColors(),
    elevation: ChipElevation? = SuggestionChipDefaults.suggestionChipElevation(),
    border: BorderStroke? = SuggestionChipDefaults.suggestionChipBorder(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
)
```

---

## 📊 对比总结

| 方面 | 旧实现 | 新实现 |
|------|--------|--------|
| 按钮消失 | ❌ 不消失 | ✅ 自动消失 |
| 样式 | Button | SuggestionChip |
| 形状 | 矩形 | 圆角胶囊 |
| 原生感 | 一般 | ⭐⭐⭐ |
| 图标 | 在文字中 | 独立显示 |

---

## 📦 构建状态

```
BUILD SUCCESSFUL in 18s
```

✅ 所有修改已编译通过

---

## 📝 修改的文件

1. **EditorScreen.kt**
   - 修改 JavaScript 监听脚本（检测取消选择）
   - 修改 Kotlin 接口（处理空字符串）
   - 替换 Button 为 SuggestionChip

---

**修复版本**: v0.6.4 (计划)  
**优化类型**: UI + Bug 修复  
**用户价值**: 更自然的交互体验

---

**开发者**: Claude Opus 4.8  
**日期**: 2026-06-15
