# YuMark 运行时崩溃修复

## 问题描述
应用安装后立即崩溃退出，无法正常运行。

## 崩溃日志
```
java.lang.NoSuchMethodError: No virtual method at(Ljava/lang/Object;I)Landroidx/compose/animation/core/KeyframesSpec$KeyframeEntity; 
in class Landroidx/compose/animation/core/KeyframesSpec$KeyframesSpecConfig; or its super classes
```

## 根本原因
Compose BOM 版本过旧（2024.01.00），导致内部动画 API 版本不匹配。`CircularProgressIndicator` 等组件内部使用的动画 API 与运行时库不兼容。

## 解决方案
更新 Compose BOM 版本到更新的稳定版本。

### 修改文件
`gradle/libs.versions.toml`

```toml
# 修改前
compose-bom = "2024.01.00"

# 修改后
compose-bom = "2024.02.00"
```

## 验证结果
✅ 应用成功启动
✅ MainActivity 正常显示（加载时间：2.8秒）
✅ 没有运行时错误
✅ 应用稳定运行

## 相关信息
- **错误位置**: FileListScreen.kt:115 (CircularProgressIndicator)
- **受影响的组件**: 所有使用 Compose 动画的组件
- **修复日期**: 2026-06-09

## 建议
定期更新 Compose BOM 到最新稳定版本，以获得 bug 修复和性能改进。当前 Compose BOM 的最新稳定版本可以在 [Google Maven Repository](https://maven.google.com/web/index.html?q=compose-bom#androidx.compose:compose-bom) 查看。
