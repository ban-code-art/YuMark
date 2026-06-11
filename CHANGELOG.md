# YuMark v1.0.0 Release Notes

🎉 **首次发布** - 2024

YuMark 是一款为 Android 打造的优雅 Markdown 编辑器，灵感来自 Typora。

## ✨ 核心特性

### 📝 所见即所得编辑
- 实时 Markdown 渲染
- WebView + Marked.js 驱动
- 支持数学公式 (KaTeX)
- 代码高亮 (Prism)
- 图表支持 (Mermaid)

### 📁 文件管理
- 文件夹分层组织
- 拖拽移动文档
- 收藏夹快速访问
- 全文搜索
- 多种排序选项

### 🖼️ 图片管理
- 自动压缩上传图片
- 可配置压缩质量
- 孤立图片自动清理
- 支持本地图片存储

### 📤 导出功能
- Markdown (.md)
- HTML (.html)
- PDF (规划中)
- Word (规划中)

### ⚙️ 个性化设置
- 字体大小调节
- 自动保存配置
- 亮色/暗色主题
- 图片压缩选项

## 🏗️ 技术亮点

- **架构**: Clean Architecture + MVVM
- **UI**: 100% Jetpack Compose
- **数据库**: Room + DataStore
- **依赖注入**: Hilt
- **异步**: Coroutines + Flow
- **测试**: JUnit 5 + MockK

## 📊 项目统计

- **代码文件**: 60+ Kotlin 文件
- **代码行数**: ~6,000 行
- **测试覆盖**: 3个单元测试 (可扩展)
- **最小 SDK**: 26 (Android 8.0)
- **目标 SDK**: 34 (Android 14)

## 🚀 快速开始

```bash
# 克隆项目
git clone https://github.com/yourusername/yumark.git
cd yumark

# 下载 JS 库
bash download-js-libs.sh

# 构建并运行
./gradlew assembleDebug
```

## 📋 路线图

### v1.1.0 (下一版本)
- [ ] PDF 导出 (Android Print API)
- [ ] 主题自定义
- [ ] 更多 Markdown 扩展 (脚注、任务列表)
- [ ] 文档历史版本

### v1.2.0
- [ ] 云同步 (Google Drive)
- [ ] 分享功能
- [ ] 小部件支持
- [ ] 平板优化

### v2.0.0
- [ ] 协作编辑
- [ ] 插件系统
- [ ] 自定义快捷键
- [ ] Vim 模式

## 🐛 已知问题

- WebView 首次渲染可能略有延迟
- 大文件 (>10MB) 性能优化待完善
- 暂不支持复杂表格编辑

## 🙏 致谢

感谢以下开源项目：
- [Marked.js](https://marked.js.org/)
- [KaTeX](https://katex.org/)
- [Mermaid](https://mermaid.js.org/)
- [Prism](https://prismjs.com/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)

## 📄 许可证

MIT License - 详见 [LICENSE](LICENSE)

---

**下载**: [GitHub Releases](https://github.com/yourusername/yumark/releases)  
**问题反馈**: [GitHub Issues](https://github.com/yourusername/yumark/issues)  
**讨论交流**: [GitHub Discussions](https://github.com/yourusername/yumark/discussions)
