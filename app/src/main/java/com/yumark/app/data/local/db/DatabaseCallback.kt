package com.yumark.app.data.local.db

import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.Folder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Provider

class DatabaseCallback(
    private val database: Provider<AppDatabase>,
    private val applicationScope: CoroutineScope
) : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        applicationScope.launch(Dispatchers.IO) {
            populateDatabase()
        }
    }

    private suspend fun populateDatabase() {
        val documentDao = database.get().documentDao()
        val folderDao = database.get().folderDao()

        // Create sample folder
        val welcomeFolder = Folder.create(
            id = "folder-welcome",
            name = "入门指南"
        )
        folderDao.insert(com.yumark.app.data.mapper.FolderMapper().toEntity(welcomeFolder))

        // Create welcome document
        val welcomeDoc = Document.create(
            id = "doc-welcome",
            name = "欢迎使用 YuMark",
            folderId = "folder-welcome"
        ).copy(
            content = """# 欢迎使用 YuMark！👋

YuMark 是一款美观的 Android Markdown 编辑器，灵感来自 Typora。

## 主要特性

- **实时渲染** - WebView 实时预览 Markdown
- **文件夹管理** - 轻松整理你的文档
- **图片支持** - 自动压缩和管理图片
- **多种导出格式** - 支持 HTML、PDF、Word 等
- **深色模式** - 保护眼睛的夜间模式

## 快速开始

### 1. 创建文档
点击右下角的 **+** 按钮创建新文档

### 2. 编辑内容
在编辑器中输入 Markdown 文本，你的更改会自动保存

### 3. 预览效果
点击右上角的眼睛图标切换预览模式

### 4. 整理文档
使用左侧菜单创建文件夹，将文档分类管理

## Markdown 语法速查

### 标题
使用 # 号表示标题，# 的数量表示标题级别

```
# 一级标题
## 二级标题
### 三级标题
```

### 文字样式
```
**粗体文字**
*斜体文字*
~~删除线~~
`代码`
```

效果：**粗体** *斜体* ~~删除线~~ `代码`

### 列表

#### 无序列表
```
- 项目 1
- 项目 2
- 项目 3
```

- 项目 1
- 项目 2
- 项目 3

#### 有序列表
```
1. 第一项
2. 第二项
3. 第三项
```

1. 第一项
2. 第二项
3. 第三项

### 链接和图片
```
[链接文字](https://example.com)
![图片描述](图片路径)
```

### 引用
```
> 这是一段引用文字
> 可以多行
```

> 这是一段引用文字
> 可以多行

### 代码块

使用三个反引号包裹代码：

\`\`\`kotlin
fun main() {
    println("Hello, YuMark!")
}
\`\`\`

### 表格
```
| 表头1 | 表头2 | 表头3 |
|------|------|------|
| 内容1 | 内容2 | 内容3 |
| 内容4 | 内容5 | 内容6 |
```

| 表头1 | 表头2 | 表头3 |
|------|------|------|
| 内容1 | 内容2 | 内容3 |
| 内容4 | 内容5 | 内容6 |

### 数学公式 (KaTeX)

#### 行内公式
使用 ${'$'}${'$'} 包裹：${'$'}${'$'}E = mc^2${'$'}${'$'}

#### 块级公式
使用 ${'$'}${'$'}${'$'}${'$'} 包裹：

${'$'}${'$'}${'$'}${'$'}
\int_{-\infty}^{\infty} e^{-x^2} dx = \sqrt{\pi}
${'$'}${'$'}${'$'}${'$'}

### 分隔线
```
---
***
```

---

## 实用技巧

### 📱 快速操作
- **收藏文档**：点击文档卡片上的心形图标
- **重命名文档**：点击文档的三点菜单 → 重命名
- **删除文档**：点击文档的三点菜单 → 删除

### 📁 文件夹管理
- **创建文件夹**：打开左侧抽屉菜单 → 点击"新建文件夹"
- **切换文件夹**：在抽屉菜单中点击文件夹名称
- **查看所有文档**：点击"所有文档"

### 🔍 搜索文档
在文档列表界面使用搜索功能快速找到想要的文档

### 🎨 个性化设置
前往设置页面可以调整：
- 字体大小
- 自动保存间隔
- 图片压缩选项

## 键盘快捷键

- **保存**：编辑完成后自动保存
- **返回**：点击返回按钮或系统返回键

## 注意事项

⚠️ **自动保存**：默认每 30 秒自动保存一次，你可以在设置中调整

⚠️ **图片管理**：插入的图片会自动压缩以节省空间

⚠️ **数据安全**：所有数据存储在本地，不会上传到服务器

## 获取帮助

如果遇到问题或有建议，欢迎：
- 访问项目主页：https://github.com/yourusername/yumark
- 提交 Issue 反馈问题
- 查看在线文档获取更多帮助

---

开始享受写作的乐趣吧！✨

*提示：你可以删除这个欢迎文档，开始创建自己的内容。*
""",
            wordCount = 450,
            characterCount = 2800
        )

        documentDao.insert(com.yumark.app.data.mapper.DocumentMapper().toEntity(welcomeDoc))
    }
}
