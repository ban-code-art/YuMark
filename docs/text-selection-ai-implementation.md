# 文本选择快捷 AI/Agent 功能

**功能**: 在编辑器中选中文本后，可以直接在当前界面询问 AI 或使用 Agent 处理，无需跳转

**日期**: 2026-06-15

---

## 🎯 功能概述

### 使用场景

#### 场景 1: 询问 AI 解释内容
```
用户正在编辑文档：
"Kotlin 是一种现代的编程语言"
         ↓ 选中这段文字
         ↓ 点击"💬 询问 AI"
         ↓ 输入问题："这是什么意思？"
         ↓ 获得 AI 解释

无需离开编辑器！
```

#### 场景 2: Agent 快速修改文本
```
用户正在编辑文档：
"Kotlin 很好用"
    ↓ 选中这段文字
    ↓ 点击"🤖 Agent"
    ↓ 输入需求："改写成专业表达"
    ↓ Agent 给出建议："Kotlin 是一种现代的、富有表现力的编程语言"
    ↓ 点击"应用修改"
    ↓ 文本自动替换

完成修改，继续编辑！
```

---

## 📱 交互流程

### 1. 选中文本

**操作**: 在编辑器中选中任意文本（长按或拖动）

**效果**: 
- 底部状态栏显示两个按钮
- `💬 询问 AI` - 实心按钮
- `🤖 Agent` - 空心按钮

```
┌─────────────────────────────────────┐
│ 编辑器内容...                       │
│                                     │
│ "Kotlin 是一种现代的编程语言"      │
│  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^      │
│  (选中状态)                         │
│                                     │
├─────────────────────────────────────┤
│ [💬 询问 AI] [🤖 Agent]    1250 字符│
└─────────────────────────────────────┘
```

---

### 2. 询问 AI 模式

**特点**: 只读对话，查看 AI 回复

**界面**:
```
┌─────────────────────────────┐
│ 💬 询问 AI                  │
├─────────────────────────────┤
│ 关于：                       │
│ ┌─────────────────────────┐ │
│ │ Kotlin 是一种现代的...  │ │
│ └─────────────────────────┘ │
│                             │
│ 你的问题：                   │
│ ┌─────────────────────────┐ │
│ │ 请解释这段内容        [▶]│ │
│ └─────────────────────────┘ │
│                             │
│ AI 思考中... ⏳              │
│                             │
│ AI 回复：                    │
│ ┌─────────────────────────┐ │
│ │ Kotlin 是由 JetBrains   │ │
│ │ 开发的一种现代静态类型  │ │
│ │ 编程语言... (Markdown)   │ │
│ └─────────────────────────┘ │
└─────────────────────────────┘
```

**流程**:
1. 显示选中的文本
2. 输入你的问题
3. 点击发送或回车
4. 流式显示 AI 回复（Markdown 渲染）
5. 查看完毕后关闭对话框

---

### 3. Agent 处理模式

**特点**: 修改建议，可应用到文档

**界面**:
```
┌─────────────────────────────┐
│ 🤖 Agent 处理               │
├─────────────────────────────┤
│ 选中的文本：                 │
│ ┌─────────────────────────┐ │
│ │ Kotlin 很好用           │ │
│ └─────────────────────────┘ │
│                             │
│ 你的需求：                   │
│ ┌─────────────────────────┐ │
│ │ 改写成专业表达        [▶]│ │
│ └─────────────────────────┘ │
│                             │
│ Agent 处理中... ◉◯◯          │
│                             │
│ 建议修改为：                 │
│ ┌─────────────────────────┐ │
│ │ Kotlin 是一种现代的、   │ │
│ │ 富有表现力的编程语言，  │ │
│ │ 具有简洁的语法和强大的  │ │
│ │ 功能特性。              │ │
│ └─────────────────────────┘ │
│                             │
│      [取消]      [应用修改]  │
└─────────────────────────────┘
```

**流程**:
1. 显示选中的文本
2. 输入修改需求
3. 点击发送
4. Agent 生成修改建议
5. 选择"应用修改"或"取消"
6. 应用后自动替换原文并保存

---

## 🔧 技术实现

### 1. 组件架构

```
EditorScreen
├─ BasicTextField (编辑器)
│  └─ onValueChange (检测文本选择)
├─ 底部状态栏
│  └─ 文本选择按钮 (条件显示)
└─ AiQuickDialog (对话框)
   ├─ QuickAiMode.AI_QUERY (询问模式)
   └─ QuickAiMode.AGENT_EDIT (编辑模式)
```

### 2. 文本选择检测

**EditorScreen.kt - BasicTextField**:
```kotlin
BasicTextField(
    value = editValue,
    onValueChange = { newValue ->
        editValue = newValue
        viewModel.onContentChanged(newValue.text)

        // 检测文本选择
        if (aiEnabled) {
            val selection = newValue.selection
            if (!selection.collapsed && selection.start < selection.end) {
                val selected = newValue.text.substring(
                    selection.start,
                    selection.end
                )
                if (selected.isNotBlank() && selected.trim().length > 2) {
                    selectedText = selected  // 保存选中文本
                }
            }
        }
    }
)
```

**条件**:
- AI 功能已启用
- 有文本被选中（selection 不为空）
- 选中文本至少 3 个字符

---

### 3. 底部按钮显示

**EditorScreen.kt - 底部状态栏**:
```kotlin
Surface(tonalElevation = 1.dp) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 文本选择操作按钮（仅当有选中文本且 AI 启用时显示）
        if (aiEnabled && selectedText.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 询问 AI
                Button(
                    onClick = {
                        quickAiMode = QuickAiMode.AI_QUERY
                        showQuickAiDialog = true
                    },
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("💬 询问 AI")
                }

                // Agent 处理
                OutlinedButton(
                    onClick = {
                        quickAiMode = QuickAiMode.AGENT_EDIT
                        showQuickAiDialog = true
                    },
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("🤖 Agent")
                }
            }
        }

        Text("${editValue.text.length} 字符")
    }
}
```

---

### 4. AI 对话框组件

**AiQuickDialog.kt**:
```kotlin
@Composable
fun AiQuickDialog(
    selectedText: String,      // 选中的文本
    mode: QuickAiMode,         // 模式：AI_QUERY / AGENT_EDIT
    onDismiss: () -> Unit,     // 关闭对话框
    onApplyEdit: (String) -> Unit  // 应用修改（Agent 模式）
)
```

**ViewModel**:
```kotlin
@HiltViewModel
class AiQuickViewModel @Inject constructor(
    private val configRepository: AiConfigRepository,
    private val adapterFactory: AiAdapterFactory
) : ViewModel() {

    fun send() {
        // 构建提示词
        val systemPrompt = buildSystemPrompt()  // 根据模式生成
        val userMessage = buildUserMessage()     // 包含选中文本和用户输入

        // 调用 AI API
        adapter.sendChatStream(messages, config).collect { event ->
            when (event) {
                is StreamEvent.Content -> {
                    // 流式显示回复
                    _aiResponse.value += event.text
                }
                is StreamEvent.Done -> {
                    _isLoading.value = false
                }
                is StreamEvent.Error -> {
                    _error.value = event.message
                }
            }
        }
    }
}
```

---

### 5. 文本替换功能

**EditorViewModel.kt**:
```kotlin
fun replaceSelectedText(oldText: String, newText: String) {
    val doc = _document.value ?: return
    val currentContent = doc.content

    // 替换第一次出现的选中文本
    val newContent = currentContent.replaceFirst(oldText, newText)

    if (newContent != currentContent) {
        _document.value = doc.copy(content = newContent)
        isDocumentDirty = true
        saveDocument()  // 自动保存
    }
}
```

---

### 6. 系统提示词

#### 询问 AI 模式
```
你是一个有帮助的 AI 助手。用户选中了一段文本并向你提问。
请根据用户的问题，结合选中的文本内容，给出清晰、准确的回答。
使用 Markdown 格式组织回复。
```

#### Agent 编辑模式
```
你是一个文本编辑助手。用户选中了一段文本，并提出了修改需求。
请根据用户的需求，对文本进行修改，只输出修改后的文本内容，不要有其他说明。
保持原文的格式和风格，只按用户要求进行必要的修改。
```

---

## 🎨 UI 设计细节

### 按钮样式

**询问 AI** (`Button`):
- 实心按钮，主题色
- 图标：💬
- 高度：32dp

**Agent** (`OutlinedButton`):
- 空心按钮，边框
- 图标：🤖
- 高度：32dp

### 对话框

**样式**: ModalBottomSheet
- 高度：70% 屏幕
- 圆角顶部
- 支持下滑关闭

**区域划分**:
1. 标题区（固定）
2. 内容区（可滚动）
   - 选中文本显示
   - 用户输入框
   - AI/Agent 回复
3. 底部按钮（Agent 模式，固定）

---

## 🔄 与完整 AI 界面的区别

| 特性 | 完整 AI 界面 | 快捷功能 |
|------|-------------|---------|
| 对话历史 | ✅ 保存 | ❌ 临时 |
| 多轮对话 | ✅ 支持 | ❌ 单次 |
| 文档关联 | ✅ | ✅ |
| 选中文本操作 | ❌ | ✅ |
| Agent 应用修改 | ✅ 全文档 | ✅ 选中部分 |
| 使用场景 | 深度对话 | 快速任务 |
| 界面 | 全屏 | 底部抽屉 |

---

## 🧪 测试场景

### 测试 1: 选中文本检测
1. 打开任意文档
2. 选中一段文字（至少 3 个字符）
3. **期望**: 底部状态栏显示"💬 询问 AI"和"🤖 Agent"按钮

### 测试 2: 询问 AI
1. 选中文字："Kotlin 是什么？"
2. 点击"💬 询问 AI"
3. 输入问题："请解释"
4. 点击发送
5. **期望**: 流式显示 AI 回复，Markdown 渲染

### 测试 3: Agent 修改文本
1. 选中文字："Kotlin 很好用"
2. 点击"🤖 Agent"
3. 输入需求："改写成更专业的表达"
4. 点击发送
5. **期望**: 显示修改建议
6. 点击"应用修改"
7. **期望**: 原文被替换，文档自动保存

### 测试 4: 取消操作
1. 选中文字，打开 Agent 对话框
2. 获得修改建议后点击"取消"
3. **期望**: 对话框关闭，原文不变

### 测试 5: 无 AI 配置
1. 未配置 API Key
2. 选中文字并尝试使用功能
3. **期望**: 显示错误提示"请先在设置中配置 API Key 和模型"

### 测试 6: 短文本过滤
1. 选中 1-2 个字符
2. **期望**: 不显示按钮（文本太短）

---

## 💡 使用技巧

### 询问 AI 的常见用途
- "这是什么意思？"
- "请详细解释这段内容"
- "这个概念有哪些应用场景？"
- "请举例说明"

### Agent 的常见需求
- "改写成更专业/通俗/简洁的表达"
- "翻译成英文/中文"
- "修正语法和拼写错误"
- "扩展这段内容"
- "生成代码注释"

---

## ⚡ 性能优化

### 文本选择检测
- 只在选中文本变化时更新
- 过滤太短的文本（< 3 字符）
- 只在 AI 启用时检测

### 对话框
- 使用 `rememberModalBottomSheetState`
- 支持手势下滑关闭
- 流式渲染减少等待感

---

## 📦 构建状态

```
BUILD SUCCESSFUL in 48s
```

✅ 所有修改已编译通过

---

## 📝 修改的文件

1. **AiQuickDialog.kt** - 新建快捷对话框组件
2. **EditorScreen.kt** - 添加文本选择检测和按钮
3. **EditorViewModel.kt** - 添加文本替换方法
4. **text-selection-ai-feature.md** - 设计文档

---

**开发版本**: v0.6.4 (计划)  
**功能类型**: 新增  
**用户价值**: 提升编辑效率，无需跳转即可使用 AI

---

**开发者**: Claude Opus 4.8  
**日期**: 2026-06-15
