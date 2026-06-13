# YuMark 应用更新功能配置指南

## 功能说明

YuMark 现在支持通过 GitHub Releases 进行应用内更新检查和下载。用户可以在设置界面点击"检查更新"来获取最新版本。

## 配置步骤

### 1. 修改 GitHub 仓库信息

打开 `app/src/main/java/com/yumark/app/data/remote/UpdateChecker.kt` 文件，修改以下两行：

```kotlin
// TODO: 替换为你的 GitHub 仓库信息
private val githubOwner = "yourusername"  // 改为你的 GitHub 用户名
private val githubRepo = "YuMark"         // 改为你的仓库名
```

### 2. 发布新版本到 GitHub Releases

#### 2.1 准备 APK 文件

构建 Release 版本的 APK：

```bash
./gradlew assembleRelease
```

APK 文件位置：`app/build/outputs/apk/release/app-release.apk`

#### 2.2 创建 GitHub Release

1. 进入你的 GitHub 仓库页面
2. 点击右侧的 "Releases" 或访问 `https://github.com/yourusername/YuMark/releases`
3. 点击 "Create a new release"
4. 填写以下信息：

   - **Tag version**: 格式必须是 `vX.Y.Z`（如 `v1.2.0`）
   - **Release title**: 版本标题（如 "v1.2.0 - 新功能更新"）
   - **Description**: 更新日志，支持 Markdown 格式

     ```markdown
     ## 新增功能
     - ✨ 应用内更新检查
     - 🔗 支持外部链接在浏览器中打开
     
     ## 改进
     - 🎨 优化预览模式返回逻辑
     - 🐛 修复链接点击错误
     
     ## 其他
     - 📝 更新文档
     ```

5. 上传 APK 文件：
   - 将 `app-release.apk` 拖拽到 "Attach binaries" 区域
   - 建议重命名为 `YuMark-v1.2.0.apk`

6. 点击 "Publish release"

### 3. 版本号管理

确保 `app/build.gradle.kts` 中的版本号与 GitHub Release 标签一致：

```kotlin
defaultConfig {
    versionCode = 5           // 递增的整数
    versionName = "1.2.0"     // 与 GitHub tag 对应（去掉 v 前缀）
}
```

版本号规则：
- **versionCode**: 每次发布必须递增（整数）
- **versionName**: 语义化版本号 `主版本.次版本.修订号`

## 用户使用流程

1. 用户打开应用 → 设置界面
2. 点击"检查更新"
3. 如果有新版本，显示更新对话框：
   - 版本号
   - 文件大小
   - 发布日期
   - 更新日志
4. 点击"立即更新"开始下载
5. 下载完成后自动触发系统安装界面

## 技术实现

- **更新检查**: 调用 GitHub API `GET /repos/{owner}/{repo}/releases/latest`
- **下载**: 使用 Android DownloadManager
- **安装**: 使用 FileProvider 共享 APK 文件
- **网络库**: Ktor Client

## 国内访问优化（可选）

如果 GitHub 在国内访问较慢，可以考虑：

1. **使用 Gitee (码云)**：
   - Gitee 也支持 Releases 功能
   - API 格式类似：`https://gitee.com/api/v5/repos/{owner}/{repo}/releases/latest`

2. **使用 jsDelivr CDN 加速**：
   - 修改下载链接，使用 CDN：
   - `https://cdn.jsdelivr.net/gh/{owner}/{repo}@{tag}/{file}`

3. **使用 GitHub Proxy**：
   - 使用国内代理服务（如 ghproxy.com）

## 测试

1. 修改 `build.gradle.kts` 中的 `versionName` 为较低版本（如 "1.0.0"）
2. 在 GitHub 发布一个较高版本（如 "v1.2.0"）
3. 运行应用，在设置中点击"检查更新"
4. 应该看到更新提示

## 注意事项

- ✅ Release tag 必须以 `v` 开头（如 `v1.2.0`）
- ✅ 必须上传 `.apk` 文件到 Release assets
- ✅ 需要在设备上开启"允许安装未知来源应用"权限
- ✅ 公开仓库无需 GitHub Token，私有仓库需要配置 Token

## 故障排查

### 检查更新失败

- 检查网络连接
- 确认 GitHub 仓库信息正确
- 查看 Logcat 日志：`adb logcat | grep UpdateChecker`

### 下载失败

- 检查存储权限
- 确认网络可以访问 GitHub
- 查看系统下载管理器

### 安装失败

- 检查"安装未知应用"权限
- 确认 APK 文件完整未损坏

## 示例 Release

完整示例请参考：[GitHub Release 示例](https://github.com/android/compose-samples/releases)
