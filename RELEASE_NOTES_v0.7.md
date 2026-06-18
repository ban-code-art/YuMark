# YuMark v0.7 更新说明

版本：v0.7  
versionCode：17  
APK：YuMark-v0.7.apk

## 重点修复

- 修复进入 Agent 页面可能闪退的问题：AI 流式请求的 Ktor timeout 改为 `HttpTimeout.INFINITE_TIMEOUT_MS`，避免非法的 0 timeout 配置导致运行时崩溃。
- 修复编辑页 lifecycle collect 拼写错误导致的编译爆红：恢复 `collectAsStateWithLifecycle()` 正确调用。
- 修复 Agent 任务在异常、取消、工具执行失败时卡在 `EXECUTING` 的问题：补齐失败、取消、阻塞等终态写入，避免任务和会话长期停留在工作中状态。
- 修复一轮 Agent 响应中多个工具调用被覆盖的问题：工具调用结果改为累积处理，避免丢失前面的工具调用。

## Agent 与 AI 能力

- Agent 执行流程面板支持展示任务状态、步骤、阻塞原因和执行证据，任务完成后仍可折叠保留查看。
- 新增 Agent 面板折叠状态持久化，重开应用后保留上次偏好。
- Agent 支持图片附件输入，图片会经过校验、压缩和私有目录存储后再交给视觉模型。
- OpenAI、Claude、Gemini 适配器补强多模态消息、工具调用消息和重试处理。
- Gemini API key 从 URL 查询参数移到 `x-goog-api-key` 请求头，降低日志和代理链路泄露风险。
- OpenAI / Claude 工具调用累积器在每次重试前重置，避免旧工具调用污染新请求。

## 稳定性与数据安全

- 文件保存改为临时文件加 rename 的原子写入方式，降低写入中断造成正文损坏的风险。
- 全文搜索迁移到 IO 调度，避免在主线程串行读取大量文档。
- 图片导入加入尺寸探测和 `inSampleSize` 下采样，减少大图导入时的内存压力。
- 文件夹排序改为基于 `MAX(order) + 1`，降低并发创建时顺序重复的概率。
- 多处 enum 反序列化改为安全兜底，减少历史数据或字段变更导致的崩溃。

## Compose / WebView 修复

- 多个页面改为 `collectAsStateWithLifecycle()`，减少后台页面继续收集状态带来的资源浪费和状态错乱。
- AI 消息 WebView 在 Compose 释放时显式 destroy，并清理 JS interface。
- 编辑器 WebView 销毁时完整移除 `Android`、`AndroidSelection`、`AndroidTouch` JS interface。
- 编辑器路由参数增加 URL 编码，修复特殊字符 documentId / docUri 导航异常。
- 修复 anchorId 注入脚本时的转义风险。

## 发布与仓库卫生

- Release 签名配置改为从本地 `keystore.properties` 读取，构建脚本不再硬编码签名密码。
- `.gitignore` 覆盖 `keystore.properties`、`release.keystore`、APK/AAB 构建产物，避免本地密钥和二进制产物误入库。
- 新增 `keystore.properties.example` 作为签名配置模板。
- 旧版被追踪的 APK 从源码仓库移除；本版本 APK 作为 GitHub Release 附件发布。

## 测试与验证

- 新增 NetworkModule timeout 回归测试，覆盖 Agent 闪退修复。
- 补强 Agent 任务生命周期、阻塞、取消和 ViewModel 状态映射测试。
- 补强流式重试测试，确保 `CancellationException` 不会被吞成普通错误。
- 本次发布已执行：
  - `cmd /c gradlew.bat :app:check --continue`
  - `cmd /c gradlew.bat :app:assembleRelease`

## 已知后续事项

- 历史 git 中仍可能存在旧签名密码和旧 APK blob，建议后续使用 `git filter-repo` 清理历史并强制协作者重新 clone。
- 当前 release keystore 已泄露过旧密码，应尽快轮换为全新 keystore 和强密码。
- Markdown HTML 消毒、全文搜索 FTS、备份/网络安全配置、Agent 架构拆分仍建议作为后续专项处理。
