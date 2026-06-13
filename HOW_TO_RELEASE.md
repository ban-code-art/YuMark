# 🚀 快速发布 GitHub Release

## 📋 你现在需要做的

### 1️⃣ 创建 GitHub Release

**访问这个链接：**
```
https://github.com/ban-code-art/YuMark/releases/new
```

### 2️⃣ 填写以下信息

**Tag version:**（必须是 v 开头）
```
v1.1.2
```

**Release title:**
```
YuMark v1.1.2 - 应用内更新功能
```

**Description:**（复制粘贴以下内容）

```markdown
# YuMark v1.1.2 更新日志

## ✨ 新增功能

### 🔄 应用内更新检查
- 在设置界面新增"检查更新"功能
- 通过 GitHub Releases API 自动检查最新版本
- 显示版本号、文件大小、发布日期和完整更新日志
- 一键下载和自动安装新版本
- 优雅的更新对话框和下载进度显示

### 🔗 外部链接浏览器打开
- Markdown 预览中的外部链接（http/https）现在会在系统浏览器中打开
- 锚点链接仍在应用内跳转
- 修复了之前点击链接报错的问题

### ⬅️ 优化预览返回体验
- 预览模式下按返回键现在会先切换回编辑模式
- 编辑模式下按返回键才会退出文档
- 类似 Grok 应用的直观交互体验

---

## 🐛 问题修复

### 📜 滚动同步优化
- 改进了预览和编辑模式之间的滚动位置同步算法
- 修复了滚动到文档底部时位置不准确的问题
- 增加了 WebView 渲染等待时间，确保内容加载完成
- 添加了详细的调试日志便于问题排查

### 🔨 构建和代码质量
- 修复了所有编译错误和警告
- 完善了 ProGuard 混淆规则
- 使用了更现代的 Kotlin API
- 提升了代码质量和可维护性

---

## 📦 技术细节

- **APK 大小:** 3.8 MB（已压缩和混淆）
- **最低 Android 版本:** 8.0 (API 26)
- **目标 Android 版本:** 14 (API 34)
- **新增依赖:** Ktor Client 2.3.7（用于网络请求）

---

## 📥 安装方法

1. 下载 `YuMark-v1.1.2.apk`
2. 在设置中开启"允许安装未知应用"
3. 点击 APK 文件安装

---

## ⚠️ 注意事项

1. **首次安装**需要开启"允许安装未知应用"权限
2. **更新功能**需要网络连接和 GitHub 访问权限
3. **滚动同步**已大幅改进，但极端情况下可能还有轻微偏差

---

🔗 **完整文档:** https://github.com/ban-code-art/YuMark/blob/test/UPDATE_GUIDE.md
```

### 3️⃣ 上传 APK 文件

在页面底部的 "Attach binaries by dropping them here or selecting them." 区域：

1. 点击或拖拽文件
2. 选择：`D:\CCguiPlay\Typora\YuMark\YuMark-v1.1.2.apk`
3. 等待上传完成（3.8 MB）

### 4️⃣ 发布

点击绿色按钮：**Publish release**

---

## ✅ 完成后

Release 会出现在：
```
https://github.com/ban-code-art/YuMark/releases
```

APK 下载链接会是：
```
https://github.com/ban-code-art/YuMark/releases/download/v1.1.2/YuMark-v1.1.2.apk
```

---

## 🧪 测试更新功能

### 修改代码
1. 打开：`app/src/main/java/com/yumark/app/data/remote/UpdateChecker.kt`
2. 找到第 31-32 行
3. 修改为：
```kotlin
private val githubOwner = "ban-code-art"  // 你的 GitHub 用户名
private val githubRepo = "YuMark"         // 仓库名
```

### 提交更改
```bash
cd D:/CCguiPlay/Typora/YuMark
git add app/src/main/java/com/yumark/app/data/remote/UpdateChecker.kt
git commit -m "chore: 配置更新检查仓库信息"
git push origin test
```

### 在手机上测试
1. 安装 `YuMark-v1.1.2.apk`
2. 打开应用 → 设置
3. 点击"检查更新"
4. 应该显示"已是最新版本"

---

## 📸 截图建议

可以在 Release 中添加截图展示新功能：
- 更新检查界面
- 更新详情对话框
- 下载进度
- 外部链接打开效果

---

**就是这么简单！** 🎉

现在去 GitHub 创建 Release 吧！
