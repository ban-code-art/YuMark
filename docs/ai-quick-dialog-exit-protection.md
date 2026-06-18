# AI 快捷对话框 - 短期记忆和退出确认

**功能**: 防止用户误触返回键导致输入内容丢失

**日期**: 2026-06-15

---

## 🎯 问题场景

用户在 AI 快捷对话框中输入问题或需求时：

```
1. 用户选中文本 "Kotlin 是什么？"
2. 点击 "💬 询问 AI"
3. 输入问题："请详细解释这个概念的由来和应用"
4. 手误触碰返回键或屏幕边缘 ❌
5. 对话框关闭
6. 输入的内容全部丢失 😢
7. 用户需要重新输入
```

---

## ✅ 解决方案

### 1. 退出确认对话框

**触发条件**:
- 用户按返回键
- 有未发送的输入内容（`userInput` 不为空）
- 还没有获得 AI 回复（`aiResponse` 为空）
- 不在加载中（`!isLoading`）

**确认对话框**:
```
┌────────────────────────────┐
│ 确认退出                   │
├────────────────────────────┤
│ 你有未发送的内容，确定要   │
│ 退出吗？退出后内容将被保   │
│ 留，下次打开可以继续编辑。 │
│                            │
│     [继续编辑]    [退出]   │
└────────────────────────────┘
```

---

### 2. 短期记忆

**ViewModel 保留状态**:
- 用户输入的问题/需求
- 选中的文本信息
- 对话模式（AI 查询 / Agent 编辑）

**恢复机制**:
```kotlin
fun shouldRestore(currentSelectedText: String): Boolean {
    return currentSelectedText == lastSelectedText && lastSelectedText.isNotEmpty()
}
```

**工作流程**:
1. 用户关闭对话框（内容保留在 ViewModel）
2. 再次选中**相同文本**
3. 打开对话框
4. **自动恢复**之前输入的内容

---

## 🔧 技术实现

### 1. BackHandler 拦截

**AiQuickDialog.kt**:
```kotlin
// 处理返回按钮
BackHandler(enabled = true) {
    // 如果有未发送的输入内容，弹出确认对话框
    if (userInput.isNotBlank() && aiResponse.isBlank() && !isLoading) {
        showExitConfirmDialog = true
    } else {
        onDismiss()
    }
}
```

**逻辑**:
- `userInput.isNotBlank()` - 有输入内容
- `aiResponse.isBlank()` - 还没有回复
- `!isLoading` - 不在加载中
- → 显示确认对话框
- 否则直接退出

---

### 2. 确认对话框

```kotlin
if (showExitConfirmDialog) {
    AlertDialog(
        onDismissRequest = { showExitConfirmDialog = false },
        title = { Text("确认退出") },
        text = { 
            Text("你有未发送的内容，确定要退出吗？退出后内容将被保留，下次打开可以继续编辑。") 
        },
        confirmButton = {
            TextButton(onClick = {
                showExitConfirmDialog = false
                onDismiss()  // 确认退出
            }) {
                Text("退出")
            }
        },
        dismissButton = {
            TextButton(onClick = { showExitConfirmDialog = false }) {
                Text("继续编辑")
            }
        }
    )
}
```

---

### 3. 短期记忆

**ViewModel**:
```kotlin
private var lastSelectedText = ""  // 记录上次的选中文本

fun shouldRestore(currentSelectedText: String): Boolean {
    return currentSelectedText == lastSelectedText && lastSelectedText.isNotEmpty()
}

fun reset() {
    lastSelectedText = selectedText  // 保存当前文本
    _userInput.value = ""
    _aiResponse.value = ""
    _isLoading.value = false
    _error.value = null
}
```

**Dialog 初始化**:
```kotlin
LaunchedEffect(Unit) {
    viewModel.setMode(mode)
    if (viewModel.shouldRestore(selectedText)) {
        // 相同的选中文本，恢复之前的输入
        // 输入内容已经在 ViewModel 中保留
    } else {
        // 不同的选中文本，清空状态
        viewModel.reset()
    }
}
```

---

## 📊 场景对比

### 场景 1: 有未发送内容时退出

**旧行为**:
```
输入问题 → 按返回键 → 直接关闭 → 内容丢失 ❌
```

**新行为**:
```
输入问题 → 按返回键 → 确认对话框 → 选择：
    ├─ 继续编辑 → 回到输入
    └─ 退出 → 内容保留，下次恢复 ✅
```

---

### 场景 2: 已获得回复时退出

```
输入问题 → AI 回复 → 按返回键 → 直接关闭 ✅
(无需确认，因为已经完成交互)
```

---

### 场景 3: 重新打开恢复内容

**相同文本**:
```
1. 选中 "Kotlin"，输入问题 "这是什么？"
2. 退出（内容保留）
3. 再次选中 "Kotlin"
4. 打开对话框
5. ✅ 自动恢复："这是什么？"
```

**不同文本**:
```
1. 选中 "Kotlin"，输入问题 "这是什么？"
2. 退出（内容保留）
3. 选中 "Java"
4. 打开对话框
5. ✅ 清空状态，全新开始
```

---

## 🧪 测试场景

### 测试 1: 退出确认触发
1. 选中文本，打开对话框
2. 输入一些内容
3. 按返回键
4. **期望**: 弹出确认对话框

### 测试 2: 继续编辑
1. 触发确认对话框
2. 点击 "继续编辑"
3. **期望**: 对话框关闭，回到输入状态

### 测试 3: 确认退出
1. 触发确认对话框
2. 点击 "退出"
3. **期望**: 对话框关闭

### 测试 4: 内容恢复
1. 输入内容后退出
2. 选中相同文本，重新打开
3. **期望**: 之前的输入自动恢复

### 测试 5: 不同文本清空
1. 选中 "文本A"，输入内容后退出
2. 选中 "文本B"，打开对话框
3. **期望**: 输入框为空

### 测试 6: 已发送不确认
1. 输入问题并发送
2. 获得 AI 回复
3. 按返回键
4. **期望**: 直接退出，无确认对话框

### 测试 7: 空内容不确认
1. 打开对话框，不输入任何内容
2. 按返回键
3. **期望**: 直接退出，无确认对话框

### 测试 8: 加载中不确认
1. 输入问题并发送
2. 正在加载 AI 回复
3. 按返回键
4. **期望**: 直接退出（或者也可以弹确认，取决于设计）

---

## 💡 用户体验

### 防止误操作
- ✅ 返回键保护
- ✅ 手势误触保护
- ✅ 明确的提示信息

### 智能恢复
- ✅ 相同文本恢复内容
- ✅ 不同文本全新开始
- ✅ 无需手动保存

### 清晰的操作提示
```
"你有未发送的内容，确定要退出吗？
退出后内容将被保留，下次打开可以继续编辑。"
```

**信息传达**:
1. 有什么内容（未发送）
2. 退出会怎样（内容保留）
3. 如何恢复（下次打开）

---

## 🔒 保护机制总结

| 情况 | 行为 |
|------|------|
| 有未发送内容 | 弹出确认对话框 |
| 已获得回复 | 直接退出 |
| 没有输入内容 | 直接退出 |
| 正在加载中 | 直接退出 |

---

## 📦 构建状态

```
BUILD SUCCESSFUL in 48s
```

✅ 所有修改已编译通过

---

## 📝 修改的文件

1. **AiQuickDialog.kt**
   - 添加 BackHandler
   - 添加确认对话框
   - 添加内容恢复逻辑

2. **AiQuickViewModel.kt**
   - 添加 `lastSelectedText` 记录
   - 添加 `shouldRestore()` 方法

---

**功能版本**: v0.6.4 (计划)  
**功能类型**: 用户体验优化  
**优先级**: 高  
**用户价值**: 防止数据丢失，提升输入体验

---

**开发者**: Claude Opus 4.8  
**日期**: 2026-06-15
