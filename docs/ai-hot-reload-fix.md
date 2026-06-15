# AI 热更新修复说明

**问题**: Agent 完成文档编辑后，编辑器界面没有自动刷新显示最新内容

**日期**: 2026-06-15

---

## 🐛 问题分析

### 原始代码问题

在 `EditorScreen.kt` 中：

```kotlin
LaunchedEffect(document?.id) {
    document?.content?.let { content ->
        if (editValue.text.isEmpty() && content.isNotEmpty()) {
            editValue = TextFieldValue(content)
        }
    }
}
```

**问题点**：
1. 只监听 `document?.id`，不监听 `document?.content`
2. 条件 `editValue.text.isEmpty()` 导致编辑器有内容时不会更新
3. AI 编辑后，文档 ID 不变，内容变化不会触发 `LaunchedEffect`

---

## ✅ 修复方案

### 新代码

```kotlin
// 记录上次从文档加载的内容
var lastLoadedContent by remember { mutableStateOf(document?.content ?: "") }

// 监听文档内容变化（AI 编辑后的热更新）
LaunchedEffect(document?.id, document?.content) {
    document?.content?.let { content ->
        // 如果文档内容变化了，并且与编辑器内容不同
        if (content != lastLoadedContent && content != editValue.text) {
            editValue = TextFieldValue(content)
            lastLoadedContent = content
        }
    }
}
```

**修复要点**：
1. ✅ 同时监听 `document?.id` 和 `document?.content`
2. ✅ 移除 `isEmpty()` 条件，允许覆盖现有内容
3. ✅ 使用 `lastLoadedContent` 追踪上次加载的内容
4. ✅ 双重检查：内容变化 && 与编辑器不同

---

## 🔄 工作流程

### AI 编辑文档的完整流程

```
1. 用户在编辑器打开文档 A
   ↓
2. 打开 AI Agent 对话框
   ↓
3. Agent 调用工具编辑文档 A
   ↓
4. EditorViewModel.reloadDocumentFromRepository()
   ↓
5. _document.value 更新为新内容
   ↓
6. LaunchedEffect 检测到 document?.content 变化
   ↓
7. 更新 editValue = TextFieldValue(新内容)
   ↓
8. 编辑器界面自动刷新显示 ✅
```

---

## 🧪 测试步骤

### 测试 1：基础热更新

1. 打开一个文档（如 "测试文档.md"）
2. 内容：
   ```markdown
   # 测试文档
   这是原始内容
   ```
3. 打开 AI Agent
4. 让 Agent 修改文档内容为：
   ```markdown
   # 测试文档
   这是 AI 修改后的内容
   ```
5. **期望结果**：编辑器自动刷新，显示新内容

### 测试 2：预览模式热更新

1. 打开文档，切换到预览模式
2. 打开 AI Agent，让它修改文档
3. **期望结果**：
   - 编辑器底层内容更新
   - 预览界面自动重新渲染显示新内容

### 测试 3：多次编辑

1. 打开文档
2. 让 AI 修改一次 → 界面更新
3. 让 AI 再修改一次 → 界面再次更新
4. **期望结果**：每次 AI 编辑后都能看到最新内容

### 测试 4：用户编辑冲突（边界情况）

1. 打开文档
2. 用户手动输入一些内容（未保存）
3. 同时让 AI 编辑文档
4. **期望结果**：
   - AI 的修改会覆盖用户未保存的内容
   - 这是当前的设计行为（AI 优先）

---

## 📝 已知限制

### 1. 用户未保存内容会被覆盖

**场景**：
- 用户正在编辑文档，输入了一些文字
- 还没保存
- AI Agent 修改了文档
- 用户的未保存内容会被 AI 的内容覆盖

**解决方案选项**：

**方案 A：保留 AI 优先（当前）**
- 优点：实现简单，符合"AI 助手"的预期
- 缺点：可能丢失用户编辑

**方案 B：检测冲突并提示**
```kotlin
if (content != lastLoadedContent && editValue.text != lastLoadedContent) {
    // 用户有未保存的修改，AI 也修改了文档
    showConflictDialog = true
} else {
    editValue = TextFieldValue(content)
}
```

**方案 C：保留用户编辑（不更新）**
```kotlin
if (editValue.text == lastLoadedContent) {
    // 用户没有本地修改，可以安全更新
    editValue = TextFieldValue(content)
}
// 否则保留用户的编辑
```

**推荐**：暂时保持方案 A（AI 优先），因为：
1. 用户通常在查看模式下使用 AI
2. 如果在编辑，说明用户同意 AI 修改
3. 可以通过"撤销"功能恢复（如果实现了）

---

## 🔍 调试方法

### 添加日志

在 `EditorScreen.kt` 中：

```kotlin
LaunchedEffect(document?.id, document?.content) {
    android.util.Log.d("EditorHotUpdate", 
        "Document changed: lastLoaded='$lastLoadedContent', " +
        "newContent='${document?.content}', " +
        "editValue='${editValue.text}'"
    )
    
    document?.content?.let { content ->
        if (content != lastLoadedContent && content != editValue.text) {
            android.util.Log.d("EditorHotUpdate", "Updating editor content")
            editValue = TextFieldValue(content)
            lastLoadedContent = content
        } else {
            android.util.Log.d("EditorHotUpdate", "Skipping update")
        }
    }
}
```

### 使用 adb logcat

```bash
adb logcat | grep "EditorHotUpdate"
```

观察：
- Document changed 事件是否触发
- 条件判断是否通过
- 内容是否正确更新

---

## 📦 相关文件

- `app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt` - 编辑器 UI
- `app/src/main/java/com/yumark/app/presentation/editor/EditorViewModel.kt` - 编辑器逻辑
- `app/src/main/java/com/yumark/app/presentation/ai/AiAssistantHost.kt` - AI 助手容器

---

## 🎯 总结

**修复前**：
- ❌ AI 编辑后需要手动切换文档才能看到更新
- ❌ 用户体验差，流程不流畅

**修复后**：
- ✅ AI 编辑后立即自动刷新
- ✅ 无需任何手动操作
- ✅ 预览模式也能实时更新

**下一步**：
- [ ] 测试各种场景
- [ ] 考虑是否需要冲突处理
- [ ] 实现撤销/重做功能（可选）

---

**修复版本**: 即将发布  
**测试状态**: 待测试  
**优先级**: 高
