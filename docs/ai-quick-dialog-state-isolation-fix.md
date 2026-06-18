# 文本选择 AI 功能 - 状态隔离修复

**问题**: 选中同一段文本，询问 AI 和 Agent 会共用回复消息

**原因**: AiQuickViewModel 被 Hilt 单例复用，状态没有重置

**日期**: 2026-06-15

---

## 🐛 问题描述

### 重现步骤
1. 选中文本 "Kotlin 是什么？"
2. 点击 "💬 询问 AI"
3. 输入问题："请解释"
4. 获得 AI 回复："Kotlin 是一种现代的编程语言..."
5. 关闭对话框
6. 再次选中同一段文本
7. 点击 "🤖 Agent"
8. **问题**: 对话框中仍然显示之前的 AI 回复

### 根本原因

**Hilt ViewModel 单例**:
```kotlin
@HiltViewModel
class AiQuickViewModel @Inject constructor(...) : ViewModel() {
    private val _aiResponse = MutableStateFlow("")
    // ViewModel 实例被复用，状态没有清理
}
```

**生命周期**:
- `AiQuickDialog` 关闭时，ViewModel 不会被销毁
- 下次打开时复用同一个实例
- 之前的状态仍然存在

---

## ✅ 解决方案

### 添加状态重置

**AiQuickViewModel.kt**:
```kotlin
fun reset() {
    _userInput.value = ""
    _aiResponse.value = ""
    _isLoading.value = false
    _error.value = null
}
```

### 打开对话框时重置

**AiQuickDialog.kt**:
```kotlin
LaunchedEffect(Unit) {
    viewModel.setMode(mode)
    // 清空之前的状态
    viewModel.reset()
}
```

**效果**:
- 每次打开对话框都重置状态
- AI 查询和 Agent 处理完全独立
- 不会共享回复消息

---

## 🔄 状态生命周期

### 修复前
```
打开对话框（询问 AI）
    ↓
ViewModel 状态:
  - userInput: "请解释"
  - aiResponse: "Kotlin 是..."
    ↓
关闭对话框
    ↓
ViewModel 保持状态（未重置）
    ↓
打开对话框（Agent）
    ↓
ViewModel 状态:
  - userInput: "请解释"  ← 旧状态
  - aiResponse: "Kotlin 是..."  ← 旧状态
    ↓
❌ 显示错误的内容
```

### 修复后
```
打开对话框（询问 AI）
    ↓
reset() 被调用
    ↓
ViewModel 状态:
  - userInput: ""  ← 清空
  - aiResponse: ""  ← 清空
    ↓
用户输入："请解释"
AI 回复："Kotlin 是..."
    ↓
关闭对话框
    ↓
打开对话框（Agent）
    ↓
reset() 被调用
    ↓
ViewModel 状态:
  - userInput: ""  ← 重新清空
  - aiResponse: ""  ← 重新清空
    ↓
✅ 全新的干净状态
```

---

## 🧪 测试场景

### 测试 1: 多次询问 AI
1. 选中文本 A
2. 询问 AI，获得回复
3. 关闭对话框
4. 选中同一段文本 A
5. 再次询问 AI
6. **期望**: 对话框是空的，没有之前的回复

### 测试 2: AI 和 Agent 切换
1. 选中文本 B
2. 询问 AI，输入 "这是什么？"
3. 获得 AI 回复
4. 关闭对话框
5. 选中同一段文本 B
6. 点击 Agent
7. **期望**: 用户输入框为空，没有 "这是什么？"
8. **期望**: 回复区域为空，没有之前的 AI 回复

### 测试 3: 不同文本独立
1. 选中文本 C，询问 AI
2. 获得回复 X
3. 关闭对话框
4. 选中文本 D，询问 AI
5. **期望**: 没有回复 X，是全新状态

### 测试 4: 错误状态清除
1. 选中文本，询问 AI
2. 如果出错（如无 API Key）
3. 关闭对话框
4. 重新打开
5. **期望**: 错误提示已清除

---

## 🎯 状态管理最佳实践

### 问题：为什么不在 onDismiss 时重置？

**方案 A**（不推荐）:
```kotlin
onDismiss = {
    viewModel.reset()
    showQuickAiDialog = false
}
```

**问题**:
- onDismiss 在对话框关闭动画**期间**调用
- reset() 立即清空状态
- 对话框关闭动画显示空白内容
- 用户体验不好

**方案 B**（推荐，当前实现）:
```kotlin
LaunchedEffect(Unit) {
    viewModel.reset()  // 打开时清空
}
```

**优势**:
- 打开时重置，不影响关闭动画
- 确保每次打开都是干净状态
- 用户体验流畅

---

## 📊 重置范围

### 需要重置的状态
```kotlin
_userInput.value = ""          // 用户输入
_aiResponse.value = ""         // AI 回复
_isLoading.value = false       // 加载状态
_error.value = null            // 错误信息
```

### 不需要重置的状态
```kotlin
selectedText                   // 选中的文本（由外部传入）
mode                          // 模式（由外部传入）
```

---

## 🔍 调试技巧

### 验证状态是否重置

**添加日志**:
```kotlin
fun reset() {
    android.util.Log.d("AiQuickDialog", "reset() called")
    _userInput.value = ""
    _aiResponse.value = ""
    _isLoading.value = false
    _error.value = null
    android.util.Log.d("AiQuickDialog", "State cleared")
}
```

**观察日志**:
```
D/AiQuickDialog: reset() called
D/AiQuickDialog: State cleared
```

---

## 💡 延伸思考

### 为什么不使用 ViewModelStoreOwner？

**方案 C**（更复杂）:
```kotlin
@Composable
fun AiQuickDialog(...) {
    val viewModelStoreOwner = rememberViewModelStoreOwner()
    val viewModel: AiQuickViewModel = hiltViewModel(viewModelStoreOwner)
    
    DisposableEffect(Unit) {
        onDispose {
            viewModelStoreOwner.viewModelStore.clear()
        }
    }
}
```

**对比**:
- 方案 C: 更复杂，需要手动管理 ViewModelStore
- 方案 B: 简单，一个 `reset()` 调用搞定
- **结论**: 简单场景用简单方案

---

## 📦 构建状态

```
BUILD SUCCESSFUL in 55s
```

✅ 所有修改已编译通过

---

## 📝 修改的文件

1. **AiQuickDialog.kt**
   - 添加 `viewModel.reset()` 调用
   
2. **AiQuickViewModel.kt**
   - 添加 `reset()` 方法

---

**修复版本**: v0.6.4 (计划)  
**问题类型**: Bug  
**优先级**: 高  
**影响范围**: 文本选择 AI/Agent 功能

---

**开发者**: Claude Opus 4.8  
**日期**: 2026-06-15
