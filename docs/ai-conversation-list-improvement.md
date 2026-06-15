# AI 对话列表优化 - 显示时间和关联文档

**问题**: AI 对话列表中无法区分哪个对话是上次使用的，也不知道对话关联了哪个文档

**日期**: 2026-06-15

---

## 🎯 改进目标

### 改进前
```
新建 Agent 对话
Agent 对话

新建 Agent 对话
Agent 对话

新建对话
普通聊天
```
**问题**：
- ❌ 无法区分不同对话
- ❌ 不知道上次使用时间
- ❌ 不知道关联的文档

### 改进后
```
新建 Agent 对话
📄 项目计划.md
14:30

新建 Agent 对话  
📄 技术文档.md
昨天

新建对话
周一
```
**优势**：
- ✅ 显示关联的文档名称（带文档图标）
- ✅ 显示上次使用时间（智能格式化）
- ✅ 一眼就能找到需要的对话

---

## 📊 功能详情

### 1. 显示关联文档

**位置**: 对话标题下方第一行  
**格式**: 📄 文档名称  
**颜色**: 主题色（Primary）  
**显示条件**: 仅当对话关联了文档时显示

**效果**:
```
新建 Agent 对话
📄 README.md          ← 关联文档
14:30                 ← 使用时间
```

### 2. 智能时间格式化

**今天**: 显示具体时间
- `14:30` - 下午 2 点 30 分
- `09:15` - 上午 9 点 15 分

**昨天**: 显示"昨天"
- `昨天`

**本周**: 显示星期
- `周一`、`周二`、`周三` ...

**更早**: 显示月/日
- `06/13` - 6 月 13 日
- `05/28` - 5 月 28 日

---

## 🔧 技术实现

### 1. 数据模型更新

#### Conversation 模型
```kotlin
data class Conversation(
    val id: String,
    val title: String,
    val type: ConversationType,
    val createdAt: Long,
    val updatedAt: Long,              // 上次活动时间
    val messages: List<Message>,
    val relatedDocumentId: String?,   // 新增：关联文档 ID
    val relatedDocumentName: String?  // 新增：关联文档名称
)
```

### 2. 数据库迁移

**版本**: 2 → 3

**迁移脚本**:
```sql
ALTER TABLE conversations ADD COLUMN relatedDocumentId TEXT DEFAULT NULL;
ALTER TABLE conversations ADD COLUMN relatedDocumentName TEXT DEFAULT NULL;
```

**文件**: `AppDatabase.kt`
- 添加 `MIGRATION_2_3`
- 更新 `version = 3`

### 3. 自动关联文档

#### 在 AgentChatViewModel.bind() 中
```kotlin
fun bind(id: String, documentId: String?, documentName: String?, ...) {
    conversationId.value = id
    docId = documentId
    docName = documentName
    
    // 自动更新对话的关联文档信息
    viewModelScope.launch {
        conversationRepository.observeConversation(id).collect { conversation ->
            if (conversation != null &&
                (conversation.relatedDocumentId != documentId || 
                 conversation.relatedDocumentName != documentName)) {
                conversationRepository.updateConversation(
                    conversation.copy(
                        relatedDocumentId = documentId,
                        relatedDocumentName = documentName,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                return@collect
            }
        }
    }
}
```

#### 在 SendAgentMessageUseCase 中
```kotlin
// 每次消息完成时更新 updatedAt
emit(AgentMessageState.Completed(text))
conversationRepository.observeConversation(conversationId).first()?.let { conversation ->
    conversationRepository.updateConversation(
        conversation.copy(updatedAt = System.currentTimeMillis())
    )
}
```

### 4. UI 显示

#### ConversationListSheet.kt
```kotlin
supportingContent = {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // 关联文档信息
        if (conv.relatedDocumentName != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, null, 
                     modifier = Modifier.size(14.dp),
                     tint = MaterialTheme.colorScheme.primary)
                Text(conv.relatedDocumentName,
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.primary)
            }
        }
        
        // 上次使用时间
        Text(formatRelativeTime(conv.updatedAt),
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```

### 5. 时间格式化函数

```kotlin
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val nowCalendar = Calendar.getInstance()

    return when {
        // 今天
        calendar.get(Calendar.DAY_OF_YEAR) == nowCalendar.get(Calendar.DAY_OF_YEAR) -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        // 昨天
        calendar.get(Calendar.DAY_OF_YEAR) == nowCalendar.get(Calendar.DAY_OF_YEAR) - 1 -> {
            "昨天"
        }
        // 本周
        diff < 7 * 24 * 60 * 60 * 1000 -> {
            val weekdays = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
            weekdays[calendar.get(Calendar.DAY_OF_WEEK) - 1]
        }
        // 更早
        else -> {
            SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
```

---

## 📝 修改的文件

1. **AiModels.kt** - 添加 `relatedDocumentId` 和 `relatedDocumentName` 字段
2. **AiEntities.kt** - 更新 `ConversationEntity` 数据库实体
3. **AiMappers.kt** - 更新 mapper 以处理新字段
4. **AppDatabase.kt** - 添加数据库迁移 2→3
5. **AgentChatSheet.kt** - 在 bind 方法中自动关联文档
6. **AgentUseCases.kt** - 消息完成时更新 updatedAt
7. **ConversationListSheet.kt** - 显示文档名称和时间

---

## 🎨 视觉效果

### 对话列表项布局
```
┌─────────────────────────────────────┐
│ 🤖  新建 Agent 对话          🗑️    │
│     📄 README.md                    │ ← 文档名（主题色）
│     14:30                           │ ← 时间（灰色）
├─────────────────────────────────────┤
│ 🤖  技术讨论                 🗑️    │
│     📄 技术文档.md                  │
│     昨天                            │
├─────────────────────────────────────┤
│ 💬  新建对话                 🗑️    │
│     周一                            │ ← 无文档时只显示时间
└─────────────────────────────────────┘
```

### 图标说明
- 🤖 Agent 对话
- 💬 普通聊天
- 📄 关联的文档
- 🗑️ 删除按钮

---

## 🧪 测试场景

### 测试 1：关联文档显示
1. 打开任意文档（如 "测试.md"）
2. 打开 AI Agent
3. 发送一条消息
4. 返回对话列表
5. **期望**：对话显示 "📄 测试.md"

### 测试 2：时间显示（今天）
1. 创建一个新对话并发送消息
2. 立即查看对话列表
3. **期望**：显示当前时间（如 "14:30"）

### 测试 3：时间显示（昨天）
1. 找一个昨天创建的对话
2. 查看对话列表
3. **期望**：显示 "昨天"

### 测试 4：时间显示（本周）
1. 找一个本周早些时候的对话
2. 查看对话列表
3. **期望**：显示星期（如 "周一"）

### 测试 5：updatedAt 更新
1. 打开一个旧对话
2. 发送新消息
3. 返回对话列表
4. **期望**：该对话排序靠前，时间更新为最新

### 测试 6：无关联文档
1. 创建一个普通聊天（不关联文档）
2. 查看对话列表
3. **期望**：只显示时间，不显示文档图标和名称

---

## 🔄 数据迁移

### 升级路径

**旧用户（数据库版本 2）**：
1. 打开应用
2. 自动执行 MIGRATION_2_3
3. 为 conversations 表添加新字段（默认 NULL）
4. 旧对话不显示文档信息（NULL）
5. 新对话自动关联文档

**新用户（全新安装）**：
1. 直接创建版本 3 的数据库
2. 所有字段齐全

---

## 💡 未来优化

### 1. 按时间排序
```kotlin
conversations.sortedByDescending { it.updatedAt }
```
最近使用的对话排在最前面

### 2. 按文档分组
```
📄 项目计划.md
  - Agent 对话 1 (14:30)
  - Agent 对话 2 (昨天)

📄 技术文档.md
  - Agent 对话 3 (周一)
```

### 3. 搜索过滤
- 按文档名搜索
- 按对话标题搜索
- 按时间范围过滤

### 4. 快速导航
点击文档名直接跳转到对应文档

---

## 📦 构建状态

```
BUILD SUCCESSFUL in 42s
```

✅ 所有修改已编译通过

---

**修复版本**: v0.6.3 (开发中)  
**优先级**: 高  
**用户价值**: 显著提升 AI 对话管理体验
