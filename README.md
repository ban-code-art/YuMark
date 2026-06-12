# YuMark

Typora 风格的 Android Markdown 编辑器，使用 Jetpack Compose 构建。

## 功能

- **📂 文件夹工作区**：通过系统文件选择器打开手机里的任意文件夹，左侧栏浏览其中的 Markdown 文件树；编辑直接写回原文件（SAF 实时关联，不复制）
- **📑 大纲导航**：预览长文档时从右侧拉出大纲目录，点击标题精确定位
- **👁 预览优先**：打开文档默认进入渲染预览（可在设置关闭；空文档自动进编辑）
- **🎨 可切换主题**：默认灰白（Typora 风）与 Claude（米白 + 赤陶橙）两套主题，各配深色色板；深色模式支持跟随系统 / 手动三挡
- **🔍 全文搜索**：按文件名或正文内容搜索，结果带匹配片段预览
- **⚡ 渲染管线**：marked.js + KaTeX 数学公式 + Prism 代码高亮 + Mermaid 图表（延迟加载）
- **📤 导出**：文档可导出为 HTML 并分享
- **✍️ 编辑体验**：无边框通栏书写区、Markdown 语法工具栏、自动保存

## 技术栈

- **UI**：Jetpack Compose + Material 3
- **架构**：Clean Architecture（domain / data / presentation）+ MVVM
- **DI**：Hilt
- **存储**：Room（内部文档库）+ DataStore（设置）+ SAF DocumentFile（外部工作区）
- **渲染**：WebView 单实例 + JS 就绪握手

## 构建

```bash
# 需要 JDK 17 与 Android SDK（compileSdk 34，minSdk 26）
./gradlew :app:assembleDebug      # 构建 debug APK
./gradlew :app:testDebugUnitTest  # 运行单元测试
```

APK 产物：`app/build/outputs/apk/debug/app-debug.apk`

## 项目结构

```
app/src/main/java/com/yumark/app/
├── core/          # 导出、校验等基础能力
├── data/          # Room、DataStore、SAF、仓库实现
├── domain/        # 模型、仓库接口、用例
├── presentation/  # Compose 界面（编辑器/列表/侧栏/设置/主题）
└── di/            # Hilt 模块
```

设计文档与实施计划见 `docs/superpowers/`，历史过程报告见 `docs/archive/`。

## License

见 [LICENSE](LICENSE)。
