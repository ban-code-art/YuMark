# YuMark v0.8 更新说明

版本：v0.8  
versionCode：18  
APK：YuMark-v0.8.apk

本次是一个以**产品能力补完**为主线的版本：补齐导出矩阵（PDF / Word / 长图）、文档历史版本、平板与折叠屏自适应布局、WebDAV 云端同步，并对 AI Agent 运行时做了架构级重写。README「产品能力」清单至此全部完成。

## 导出能力

- **PDF 导出**：复用预览渲染管线（marked + KaTeX + Prism + Mermaid）经离屏 WebView 渲染，再用 `PdfDocument` 按 A4 比例分页输出，保真度与预览一致，无第三方依赖、无打印对话框。
- **Word 导出（.docx）**：将 Markdown 经 Commonmark AST 转为手写最小 OOXML 打包成 `.docx`，覆盖标题、段落、粗/斜/删除线、行内代码、代码块、有序/无序列表、引用、表格、分隔线、链接。不引入 Apache POI，体积可控。
- **长图导出**：整页离屏渲染截成 PNG 长图，超高自动限高（防 OOM）并提示。
- 编辑器导出菜单补挂 PDF / Word / 长图入口。

## 文档历史版本

- 保存时按内容变化自动落本地快照（去重 + 每文档最多保留 50 版，FIFO 裁剪）。
- 编辑器溢出菜单新增「历史版本」：按时间倒序列出，可展开查看与当前内容的逐行 diff，并一键恢复（恢复前先把当前内容存为一版，避免丢失）。
- 新增 Room 迁移 v6→v7（`document_versions` 表，仅加表不动旧表）。

## 平板与折叠屏布局

- 引入 `material3-window-size-class` 计算窗口尺寸：手机/窄屏沿用单窗格导航，行为不变；平板/展开态（Expanded 宽度）启用「列表-详情」双窗格，左侧文件列表常驻、右侧打开文档。
- 引入 `androidx.window` 感知折叠姿态：存在竖直分隔铰链（book 姿态 / 双屏间隙）时，两个窗格沿铰链对齐，内容不跨折痕。

## 云端同步（WebDAV · 首个版本）

- 设置页新增「云端同步」：配置 WebDAV 服务器（Nextcloud / 坚果云 / 群晖等自有账号）、测试连接、立即同步、查看上次同步时间与结果。
- 手动双向同步：把本地库**根级文档正文**与远端目录对齐——本地新增/变更上传、远端新增/变更下载，按修改时间 last-write-wins。
- 冲突安全：双边自上次同步都改动时生成本地冲突副本而非静默覆盖。
- 凭证（密码/授权码）经 AndroidX Security Crypto 加密存储，不入日志。新增 Room 迁移 v7→v8（`sync_state` 表）。
- 说明：本版仅同步根级文档正文；文件夹层级、图片附件、删除传播、后台自动同步将在后续版本支持。

## AI Agent 架构重写

- 以**单一工具循环**替换原「先规划 JSON 计划、再把 ReAct 循环硬绑步骤」的两阶段结构：模型返回工具调用就执行回填，返回纯文本即收敛。
- 新增**外科式编辑**工具 `edit_document`（多段 `old_string → new_string` 替换），改一行只产生一行级 diff，token 更省、不易截断、不再整篇重写。
- 计划改为模型驱动 `update_plan` 工具，复用既有执行时间轴 UI。
- 编辑命中失败/不唯一以结构化工具错误回填，模型可在同一会话内自我修正。
- 删除 4 个旧两阶段用例类，净减代码量；保留并复用既有审批/diff 闸门、Provider 适配层与 Compose UI。

## 运行期修复（本次发布前 review 发现并修复）

- WebDAV「测试连接」在同步目录尚未创建（HTTP 404）时不再误报失败——凭证有效即视为连接成功，首次同步会自动建目录。
- 同步设置页：「立即同步」按钮与「启用同步」开关联动，避免开关关闭时点同步报「未启用」的费解错误。
- 离屏导出渲染器：渲染超时的异常返回路径补上 WebView 销毁，修复极端超时下的离屏 WebView 泄漏。

## 测试与验证

- 新增单测：DocxExporter（OOXML 结构/可打开）、文档版本仓库、WebDAV PROPFIND 解析、同步决策（SyncPlanner）、同步执行器（SyncRepositoryImpl，含上传/下载/冲突路径）、折叠/双窗格分支逻辑。
- 全量回归：`:app:testDebugUnitTest`（186 个用例全绿）+ `:app:assembleDebug` / `:app:assembleRelease` 构建通过。
- 说明：导出产物在 Word/WPS 打开、长图观感、WebDAV 端到端往返、平板/折叠屏双窗格观感仍建议在真机上验收。

## 安全与发布

- Release 签名继续从本地 `keystore.properties` 读取，构建脚本不硬编码密码；`keystore.properties`、`release.keystore`、`local.properties`、`*.apk`/`*.aab`、本地诊断产物均已被 `.gitignore` 覆盖，不入库。
- APK 作为 GitHub Release 附件发布，不进入源码历史。

## 已知后续事项

- 历史 git 中可能仍存在旧签名信息，生产发布前建议轮换全新 keystore。
- WebDAV 走 http 明文需配合 `networkSecurityConfig`，建议使用 https 服务。
- 云端同步的文件夹/图片/删除传播/后台同步、Markdown HTML 消毒、Room FTS 全文搜索仍作为后续专项。
