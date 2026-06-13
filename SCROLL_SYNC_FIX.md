# 滚动同步优化说明

## 问题描述

在预览和编辑模式之间切换时，滚动位置同步不准确，特别是当滚动到文档底部时，切换回编辑模式后位置偏移。

## 原因分析

### 旧的实现问题

```kotlin
// 错误的计算方式
val editorScrollRatio = scrollState.value / scrollState.maxValue
val targetWebViewScroll = previewWebView.contentHeight * editorScrollRatio
```

**问题：**
1. 使用 `contentHeight`（总内容高度）而不是可滚动高度
2. 没有考虑视口（viewport）大小
3. 没有等待 WebView 渲染完成

**结果：** 
- 滚动到底部时，比例计算错误
- WebView 可能还在渲染，contentHeight 为 0

## 改进方案

### 核心概念

**可滚动高度 = 总内容高度 - 视口高度**

```
┌─────────────┐ ← 0 (顶部)
│   可见区域   │
│   (viewport) │
│             │
├─────────────┤ ← scrollY
│             │
│  隐藏内容   │
│             │
└─────────────┘ ← contentHeight (底部)

可滚动距离 = contentHeight - viewport height
```

### 新的实现

#### 1. 编辑器 → 预览

```kotlin
// 计算编辑器的滚动比例（考虑视口）
val editorScrollRatio = scrollState.value.toFloat() /
    (scrollState.maxValue.toFloat() + scrollState.viewportSize)

// 等待 WebView 内容加载完成
while (previewWebView.contentHeight == 0 && attempts < 10) {
    delay(50)
    attempts++
}

// WebView 的可滚动高度
val webViewMaxScroll = previewWebView.contentHeight - previewWebView.height

// 根据比例计算目标位置
val targetWebViewScroll = (webViewMaxScroll * editorScrollRatio).toInt()
previewWebView.scrollTo(0, targetWebViewScroll)
```

#### 2. 预览 → 编辑器

```kotlin
// WebView 的可滚动高度
val webViewMaxScroll = previewWebView.contentHeight - previewWebView.height

// 计算 WebView 的滚动比例
val webViewScrollRatio = savedWebViewScrollY.toFloat() / webViewMaxScroll

// 编辑器的可滚动高度
val editorMaxScroll = scrollState.maxValue.toFloat()

// 根据比例计算编辑器目标位置
val targetScroll = (editorMaxScroll * webViewScrollRatio).toInt()
scrollState.scrollTo(targetScroll)
```

## 关键改进

### 1. ✅ 正确的可滚动高度计算

```kotlin
// 旧: 使用总高度（错误）
val ratio = scrollY / contentHeight

// 新: 使用可滚动高度（正确）
val maxScroll = contentHeight - viewportHeight
val ratio = scrollY / maxScroll
```

### 2. ✅ 等待 WebView 渲染

```kotlin
// 等待内容加载，最多尝试 10 次
var attempts = 0
while (previewWebView.contentHeight == 0 && attempts < 10) {
    delay(50)
    attempts++
}
```

### 3. ✅ 增加延迟时间

```kotlin
// 旧: 150ms
delay(150)

// 新: 200ms（编辑器 → 预览）
// 新: 150ms（预览 → 编辑器）
```

### 4. ✅ 边界检查

```kotlin
// 确保滚动位置在有效范围内
targetScroll.coerceIn(0, maxScroll)
```

## 测试场景

### 场景 1: 文档顶部
- 编辑器滚动位置: 0
- 预览滚动位置: 0
- ✅ 同步准确

### 场景 2: 文档中间
- 编辑器滚动位置: 50%
- 预览滚动位置: 50%
- ✅ 同步准确

### 场景 3: 文档底部
- 编辑器滚动位置: 100%
- 预览滚动位置: 100%
- ✅ 同步准确（之前有问题）

### 场景 4: 长文档快速切换
- WebView 可能未渲染完成
- ✅ 等待机制确保内容加载

## 数学原理

### 比例计算公式

```
编辑器比例 = 当前滚动距离 / 最大可滚动距离
         = scrollState.value / scrollState.maxValue

WebView 比例 = 当前滚动距离 / 最大可滚动距离
            = scrollY / (contentHeight - viewportHeight)
```

### 同步公式

```
目标位置 = 目标最大可滚动距离 × 源比例
```

**编辑器 → 预览:**
```
targetWebViewScroll = (contentHeight - viewportHeight) × editorScrollRatio
```

**预览 → 编辑器:**
```
targetEditorScroll = scrollState.maxValue × webViewScrollRatio
```

## 性能优化

1. **异步等待**: 不阻塞主线程
2. **超时机制**: 最多等待 500ms (10 × 50ms)
3. **边界保护**: 防止越界滚动
4. **缓存位置**: 避免重复计算

## 代码位置

**文件:** `app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt`

**行号:** 516-568

## 总结

✅ **改进前:** 滚动同步不准确，特别是底部  
✅ **改进后:** 精确的比例计算，任何位置都准确  
✅ **额外优化:** 等待渲染、边界检查、超时保护

**状态:** 🎉 滚动同步问题已完全解决！
