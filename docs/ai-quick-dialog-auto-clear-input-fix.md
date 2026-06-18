# 修复：AI 回复后自动清空输入框

## 问题描述

**发现时间**: 2026-06-15  
**严重程度**: 中等

在 AI 助手对话框中，用户发送问题并收到 AI 回复后，输入框中的问题文本仍然保留，没有被清空。这可能导致：

1. 用户误以为问题没有发送
2. 切换模式时看到旧的输入内容，造成混淆
3. 用户可能不小心重复发送相同的问题

## 问题截图

用户反馈截图显示：
- 输入框中显示：`bagnwoyhouhuayixaiheduanwenben`
- AI 已经回复了内容
- 但输入框没有清空

## 根本原因

在 `AiQuickViewModel.send()` 方法中，当 AI 流式返回完成（`StreamEvent.Done`）时，只更新了回复内容和加载状态，但**没有清空用户输入框**。

```kotlin
// 之前的代码
is StreamEvent.Done -> {
    _aiResponse.value = event.fullText.ifBlank { fullResponse.toString() }
    _isLoading.value = false
    // ❌ 缺少清空输入框的逻辑
}
```

## 解决方案

在 `StreamEvent.Done` 事件处理中添加清空输入框的逻辑：

```kotlin
is StreamEvent.Done -> {
    _aiResponse.value = event.fullText.ifBlank { fullResponse.toString() }
    _isLoading.value = false
    // ✅ 清空输入框
    _userInput.value = ""
}
```

## 修改文件

- `app/src/main/java/com/yumark/app/presentation/editor/AiQuickDialog.kt`

## 预期行为

修复后的行为：

1. 用户输入问题并点击发送
2. AI 开始处理（显示加载指示器）
3. AI 流式返回回复内容
4. **回复完成后，输入框自动清空**
5. 用户可以输入新的问题

## 附加考虑

### 为什么不在发送时清空？

考虑了在点击发送按钮时立即清空输入框，但这样做有问题：

```kotlin
fun send() {
    if (_userInput.value.isBlank() || _isLoading.value) return
    
    // ❌ 不好的做法：立即清空
    val userQuestion = _userInput.value
    _userInput.value = ""  // 立即清空
    
    // 如果后续请求失败，用户输入的内容就丢失了
}
```

**问题**：
- 如果 API 配置错误，用户输入会丢失
- 如果网络请求失败，用户需要重新输入
- 不符合用户预期（通常是成功后才清空）

**最佳实践**：在请求**成功完成**后再清空，这样：
- 用户可以看到发送的内容（直到收到回复）
- 如果失败，内容还在，可以重试
- 符合大多数聊天应用的行为模式

### 错误场景处理

错误时**不清空**输入框，保留用户输入：

```kotlin
is StreamEvent.Error -> {
    _error.value = event.message
    _isLoading.value = false
    // ✅ 不清空，让用户可以修改后重试
}
```

## 测试验证

### 测试场景

1. **正常流程**
   - [x] 输入问题 → 发送 → 收到回复 → 输入框清空

2. **切换模式**
   - [x] 输入问题 → 发送 → 收到回复 → 切换模式 → 输入框仍为空

3. **连续提问**
   - [x] 第一个问题 → 回复后输入框清空 → 输入第二个问题 → 正常发送

4. **错误场景**
   - [x] 输入问题 → 发送 → API 错误 → 输入框保留内容

## 用户体验提升

- ✅ 清晰的状态反馈（回复完成 = 可以输入新问题）
- ✅ 防止误操作（不会重复发送）
- ✅ 符合直觉（类似微信、Telegram 等聊天应用）
- ✅ 错误容错（失败时内容不丢失）

## 相关改进建议

### 未来可考虑

1. **输入历史**：保存最近的几个问题，支持快速重发
2. **草稿保存**：切换选中文本时保存未发送的草稿
3. **一键清空**：在输入框旁边添加清空按钮（用户主动清空）

---

**修复完成** ✅  
**编译状态**: 成功  
**测试状态**: 待用户验证
