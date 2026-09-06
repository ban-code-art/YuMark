# 发布流程（Release Runbook）

> 本文档描述当前实际生效的发布流程。签名发布已由 CI 承担（`.github/workflows/release.yml`），
> 人工步骤集中在「改版本号 → 提交 → 打标签」。文中版本号以 v0.10（versionCode 21 /
> versionName "0.10" / targetSdk 36 / compileSdk 37）为例，发布时顺延。

## 前置条件（一次性）

1. **配置签名 Secrets**（仓库 Settings → Secrets and variables → Actions）：

   | Secret | 内容 | 生成方式 |
   | --- | --- | --- |
   | `KEYSTORE_BASE64` | release.keystore 的 base64 | `base64 -w0 release.keystore` |
   | `KEYSTORE_PASSWORD` | keystore store 密码 | 本地 `keystore.properties` 里有 |
   | `KEY_ALIAS` | 签名 key 别名 | 同上 |
   | `KEY_PASSWORD` | 签名 key 密码 | 同上 |

   真实 keystore 与 keystore.properties **永远不入库**（.gitignore 已覆盖）。
2. ⚠️ **换 keystore 是破坏性操作**：本应用带自更新，安装包签名必须与用户设备上已装
   版本一致，否则覆盖安装会被系统与自更新链路的双重签名校验拒绝。除非旧 keystore
   确认泄露（README 有历史记录），否则不要轮换；轮换即意味着全体用户手动重装。

## 发布步骤

1. **准备提交**
   - 确认 `main` 分支 CI（`android.yml`）绿：单测、lint、R8、Room schema 守卫全过；
     迁移动过的话手动触发一次 `instrumented` job（模拟器真机迁移测试）。
   - 更新 `CHANGELOG.md`（本版本条目），检查 `README.md` 的版本相关描述。

2. **升版本号**（`app/build.gradle.kts`）：
   - `versionCode` +1（自更新链路比较的是 versionName 逐段数字，但 versionCode
     也要单调递增，别让它们脱钩）；
   - `versionName = "0.11"`（与 CHANGELOG / git tag 对应）。

3. **提交并打标签**：
   ```bash
   git add -A && git commit -m "release: v0.11 — <一句话摘要>"
   git tag v0.11
   git push origin main --tags
   ```

4. **CI 自动完成**（`release.yml`，Actions 页面可看进度）：
   JS 供应链哈希校验 → JVM 单测 → `assembleRelease`（签名）→ 计算 SHA-256 →
   上传 artifact → 创建 GitHub Release（APK + 摘要写进发布说明，自动生成变更日志）。

5. **人工核对**（只读，不修改 CI 产物）：
   - Release 页面的 APK 可下载，SHA-256 与发布说明一致；
   - 本地装一个旧版本，用应用内「检查更新」走一遍自更新（它校验同一摘要与签名）；
   - 多语言：`python scripts/check_strings.py` 已由 lint 的 MissingTranslation 门禁兜底，
     但发布前跑一遍能提前看到 diff。

## 回滚

发布本身不打回滚补丁：发现严重问题时**不要删除 Release**（自更新按 versionName
比较，删掉不会让已更新用户回退），而是立刻发布一个更高版本的修复 tag，并在旧
Release 页面置顶说明。
