# 设计文档：性能与视觉优化

- 日期：2026-06-11
- 状态：已获用户批准（10 项全做）

## 卡顿根因（审查结论）

1. WebView 每次切预览全新创建，同步解析 3.3MB JS（mermaid 2.9MB），切回编辑即销毁
2. 渲染靠固定 postDelayed 800–1000ms 兜底，就绪了也干等
3. 每键输入 launch 协程 + Mutex 抢锁
4. 文件列表 combine 每次发射（搜索按键/排序）都重查 DB 构建文件夹树
5. 死代码：MarkdownRenderer/JsBridge/initializeRenderer、dao.search

## 优化项

### 性能

| # | 项 | 做法 |
|---|---|---|
| 1 | WebView 单实例 | `remember` 创建一次；AndroidView factory 复用实例（attach 前先从旧 parent remove）；编辑/预览用 if 切换组合但实例保留；DisposableEffect 退出销毁 |
| 2 | 就绪握手 | renderer.html 末尾调 `Android.onReady()`；Kotlin 置 `rendererReady`；`LaunchedEffect(editContent, rendererReady, isPreviewMode)` 就绪即渲染，删全部 postDelayed |
| 3 | mermaid 延迟加载 | `<script defer src=mermaidjs.js onload=...>`；onload 后若已有内容则 `mermaid.run` 补渲染；renderMarkdown 已有 typeof 守卫 |
| 4 | 输入直通 | `onContentChanged` 去协程去锁：`_document.update { it?.copy(content=new) }` + 脏标记 |
| 5 | 树缓存 | FileListViewModel：树构建移入 docs/folders 驱动的独立 flow（`combine(docs, folders)`+stateIn），主 combine 直接读取 |
| 6 | 死代码删除 | 删 core/webview/MarkdownRenderer.kt、JsBridge.kt；EditorViewModel 的 initializeRenderer/getRenderer/markdownRenderer/onCleared 清理；DocumentDao.search() |

### 视觉

| # | 项 | 做法 |
|---|---|---|
| 7 | 编辑区 Typora 化 | OutlinedTextField → BasicTextField（无边框、水平 padding 20dp、行高 1.6、光标 primary 色）；字符数移到底部状态条（surfaceVariant 细条、右对齐小字） |
| 8 | 卡片扁平化 | DocumentCard/SearchResultCard → `OutlinedCard`（outline 描边、无阴影） |
| 9 | 列表动画 | 文档 LazyColumn items 加 `Modifier.animateItemPlacement()` |
| 10 | 页面过渡 | NavHost 级 enter/exit：淡入 + 4% 横向滑动（编辑器、设置）；popEnter/popExit 对称 |

## 关键实现细节

- WebView 复用模式：
  ```kotlin
  val webView = remember { WebView(ctx).apply { /* 一次性配置 + load */ } }
  AndroidView(factory = { (webView.parent as? ViewGroup)?.removeView(webView); webView })
  DisposableEffect(Unit) { onDispose { webView.destroy() } }
  ```
- onReady 后渲染走现有 Base64 通道，新增内容 hash 守卫避免重复渲染同一内容
- JsBridge 对象增加 `@JavascriptInterface fun onReady()`（现有匿名对象上加方法）
- 大纲回传/深色注入逻辑保持，但触发点改为 rendererReady（删 900ms 延迟）

## 测试

- 现有 17 个单测必须全过（EditorViewModel 删字段不影响既有测试）
- FileListViewModel 树缓存：现有 2 测试回归
- WebView/动画为视觉行为：构建 + 真机验证

## 非目标

- 替换 WebView 为原生渲染（Markwon）
- 编辑器语法高亮
