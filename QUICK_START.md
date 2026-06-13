# YuMark 更新功能快速配置清单

## ✅ 已完成的工作

### 1. 依赖配置
- [x] 添加 Ktor HTTP 客户端依赖
- [x] 添加 Kotlinx Serialization JSON 支持
- [x] 配置 Hilt 依赖注入

### 2. 权限配置
- [x] 添加 INTERNET 权限
- [x] 添加 REQUEST_INSTALL_PACKAGES 权限
- [x] 配置 FileProvider 路径

### 3. 核心功能
- [x] UpdateChecker - 更新检查服务
- [x] ApkDownloader - APK 下载管理器
- [x] UpdateInfo - 数据模型
- [x] NetworkModule - 依赖注入模块

### 4. UI 界面
- [x] 设置界面添加"检查更新"按钮
- [x] 更新详情对话框
- [x] 下载进度对话框
- [x] 错误提示

## 🔧 你需要配置的内容

### 必须配置（否则无法工作）

1. **修改 GitHub 仓库信息**
   
   文件：`app/src/main/java/com/yumark/app/data/remote/UpdateChecker.kt`
   
   ```kotlin
   // 第 31-32 行
   private val githubOwner = "yourusername"  // ← 改为你的 GitHub 用户名
   private val githubRepo = "YuMark"         // ← 改为你的仓库名
   ```

### 可选配置

2. **修改应用版本号**
   
   文件：`app/build.gradle.kts`
   
   ```kotlin
   defaultConfig {
       versionCode = 4        // ← 递增
       versionName = "1.1.2"  // ← 当前版本
   }
   ```

## 📦 发布新版本流程

### 步骤 1: 构建 APK
```bash
./gradlew assembleRelease
```

APK 位置：`app/build/outputs/apk/release/app-release.apk`

### 步骤 2: 创建 GitHub Release

1. 访问：`https://github.com/你的用户名/YuMark/releases/new`

2. 填写信息：
   - **Tag**: `v1.2.0` （必须以 v 开头）
   - **Title**: `v1.2.0 - 新功能更新`
   - **Description**: 写更新日志（支持 Markdown）

3. 上传 APK：
   - 拖拽 `app-release.apk` 到附件区域
   - 建议重命名为 `YuMark-v1.2.0.apk`

4. 点击 **Publish release**

### 步骤 3: 测试更新

1. 在设备上安装较低版本的应用
2. 打开应用 → 设置 → 检查更新
3. 应该看到新版本提示

## 🧪 本地测试

### 模拟有新版本

1. 临时修改 `build.gradle.kts` 中的 `versionName` 为 `"1.0.0"`
2. 在 GitHub 发布 `v1.2.0`
3. 运行应用，检查更新
4. 应该提示有新版本

### 检查日志
```bash
adb logcat | grep -E "UpdateChecker|WebView"
```

## 📱 用户权限设置

安装更新需要用户授权：

**Android 8.0+**
1. 设置 → 应用 → YuMark → 高级 → 安装未知应用
2. 开启"允许来自此来源"

**Android 13+**
- 首次安装会自动弹出授权对话框

## ⚠️ 注意事项

1. **版本号格式**
   - GitHub Tag: `v1.2.0` （有 v）
   - versionName: `"1.2.0"` （无 v）

2. **versionCode 必须递增**
   - 每次发布必须比上一版本大
   - Android 系统用此判断新旧

3. **APK 签名**
   - 更新的 APK 必须与原应用使用相同签名
   - Release 构建会自动签名

4. **网络访问**
   - 首次使用需要联网
   - GitHub 在国内可能较慢

## 🐛 故障排查

### 检查更新失败

**可能原因：**
- 网络未连接
- GitHub 仓库信息错误
- API 限流（每小时 60 次）

**解决方法：**
```bash
# 查看日志
adb logcat | grep UpdateChecker

# 手动测试 API
curl https://api.github.com/repos/你的用户名/YuMark/releases/latest
```

### 下载失败

**可能原因：**
- 网络中断
- 存储空间不足

**解决方法：**
- 检查 DownloadManager：设置 → 应用 → 下载管理器
- 清理存储空间

### 无法安装

**可能原因：**
- 未开启"安装未知应用"权限
- APK 签名不匹配
- APK 文件损坏

**解决方法：**
- 检查权限设置
- 重新下载 APK
- 卸载后全新安装

## 🎉 完成！

所有代码已实现，只需：
1. 修改 `UpdateChecker.kt` 中的 GitHub 仓库信息
2. 发布一个 Release 到 GitHub
3. 在应用中测试更新功能

如有问题，查看 `UPDATE_GUIDE.md` 获取详细说明。
