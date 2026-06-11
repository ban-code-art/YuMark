# YuMark

Android native Markdown editor inspired by Typora.

**WYSIWYG实时渲染** | **文件夹管理** | **Material 3 主题** | **多格式导出**

## 技术栈

- **语言**: Kotlin 1.9+
- **UI**: Jetpack Compose + Material Design 3
- **架构**: Clean Architecture + MVVM
- **数据库**: Room + DataStore
- **DI**: Hilt
- **渲染**: WebView + Marked.js + KaTeX + Mermaid + Prism

## 项目结构

```
app/src/main/java/com/yumark/app/
├── di/                     # Hilt 依赖注入
├── domain/model/           # 领域模型
├── domain/repository/      # 仓库接口
├── domain/usecase/         # 业务用例
├── data/repository/        # 仓库实现
├── data/local/db/          # Room 数据库
├── data/local/file/        # 文件管理
├── data/local/prefs/       # DataStore
├── data/mapper/            # 数据映射
├── core/webview/           # WebView 渲染引擎
├── core/export/            # 导出功能
└── presentation/           # UI
    ├── theme/              # 主题
    ├── navigation/         # 导航
    ├── editor/             # 编辑器
    ├── filelist/           # 文件列表
    └── settings/           # 设置
```

## 快速开始

1. 用 Android Studio 打开项目
2. 运行 `download-js-libs.sh` 下载 JavaScript 库
3. 构建并运行

## 开发阶段

- ✅ Stage 0: 项目初始化
- ✅ Stage 1: 核心编辑器
- ✅ Stage 2: 文件管理
- ✅ Stage 3: 图片管理
- ✅ Stage 4: 导出功能
- ✅ Stage 5: 设置和优化

## 许可证

MIT
