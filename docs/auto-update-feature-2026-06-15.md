# 自动检查更新功能实现文档

**日期**: 2026-06-15  
**功能**: 应用启动时自动检查更新并弹窗提示

## 功能描述

### 旧行为
- 用户需要手动进入设置页面
- 点击"检查更新"按钮才能获取更新信息
- 无法及时感知到新版本发布

### 新行为
- 应用启动时自动在后台检查更新
- 如果发现新版本，自动弹出更新对话框
- 用户可以选择：
  - ✅ **立即更新** - 下载并安装新版本
  - ⏰ **稍后提醒** - 关闭对话框，下次启动时再次提示
- 如果没有更新或检查失败，静默处理，不打扰用户

---

## 实现细节

### 1. FileListViewModel 修改

**文件**: `app/src/main/java/com/yumark/app/presentation/filelist/FileListViewModel.kt`

#### 添加依赖注入
```kotlin
class FileListViewModel @Inject constructor(
    // ... 其他依赖
    private val updateChecker: UpdateChecker  // 新增
) : ViewModel() {
```

#### 添加状态管理
```kotlin
/** 启动时自动检查更新的结果 */
private val _autoUpdateInfo = MutableStateFlow<UpdateInfo?>(null)
val autoUpdateInfo: StateFlow<UpdateInfo?> = _autoUpdateInfo.asStateFlow()
```

#### 在 init 中启动检查
```kotlin
init {
    // 启动时自动检查更新（静默，仅在有更新时弹窗）
    checkUpdateOnStartup()
}

/** 启动时检查更新（静默，不显示"检查中"或"无更新"状态） */
private fun checkUpdateOnStartup() {
    viewModelScope.launch {
        try {
            val updateInfo = updateChecker.checkUpdate()
            if (updateInfo != null) {
                _autoUpdateInfo.value = updateInfo
            }
        } catch (e: Exception) {
            // 静默失败，不影响用户体验
            android.util.Log.w("FileListViewModel", "启动时检查更新失败: ${e.message}")
        }
    }
}

/** 关闭自动更新弹窗 */
fun dismissAutoUpdate() {
    _autoUpdateInfo.value = null
}
```

**技术要点**：
- ✅ 静默检查：失败时不弹错误提示，不影响用户体验
- ✅ 智能过滤：仅在有新版本时才弹窗
- ✅ 协程异步：不阻塞主线程和应用启动

---

### 2. FileListScreen 修改

**文件**: `app/src/main/java/com/yumark/app/presentation/filelist/FileListScreen.kt`

#### 添加状态监听和对话框
```kotlin
// 启动时自动检查更新
val autoUpdateInfo by viewModel.autoUpdateInfo.collectAsState()
var downloadingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

// 自动更新对话框
autoUpdateInfo?.let { updateInfo ->
    UpdateDialog(
        updateInfo = updateInfo,
        onDismiss = { viewModel.dismissAutoUpdate() },
        onUpdate = { update ->
            downloadingUpdate = update
            viewModel.dismissAutoUpdate()
        }
    )
}

// 下载对话框
downloadingUpdate?.let { updateInfo ->
    DownloadDialog(
        updateInfo = updateInfo,
        onDismiss = { downloadingUpdate = null },
        context = context
    )
}
```

**技术要点**：
- ✅ 复用现有组件：使用设置页面的 `UpdateDialog` 和 `DownloadDialog`
- ✅ 状态驱动：通过 StateFlow 自动响应更新状态
- ✅ 用户友好：提供"稍后提醒"和"立即更新"两个选项

---

### 3. SettingsScreen 修改

**文件**: `app/src/main/java/com/yumark/app/presentation/settings/SettingsScreen.kt`

#### 公开对话框组件
将 `UpdateDialog` 和 `DownloadDialog` 从 `private` 改为公开：

```kotlin
// 旧代码
@Composable
private fun UpdateDialog(...)

// 新代码
@Composable
fun UpdateDialog(...)
```

**目的**：允许其他界面（如文件列表）复用这些组件

---

## 用户体验流程

### 场景 1：有新版本可用

1. 用户打开应用
2. 应用后台自动检查更新（不阻塞界面）
3. 发现新版本，弹出更新对话框：
   ```
   ┌──────────────────────────────┐
   │  🔄 发现新版本                │
   │  v1.2.0                       │
   ├──────────────────────────────┤
   │  文件大小: 3.8 MB             │
   │  发布日期: 2026-06-15         │
   │                               │
   │  更新内容:                     │
   │  - 修复 AI 消息渲染问题       │
   │  - 实现文档热更新             │
   │  - 优化工具栏布局             │
   │                               │
   │  [稍后提醒]  [立即更新]       │
   └──────────────────────────────┘
   ```
4. 用户选择：
   - **稍后提醒** → 关闭对话框，继续使用应用
   - **立即更新** → 进入下载流程

### 场景 2：已是最新版本

1. 用户打开应用
2. 应用后台检查更新
3. 检测到已是最新版本
4. **静默处理，不显示任何提示**
5. 用户正常使用应用

### 场景 3：检查失败（网络错误等）

1. 用户打开应用
2. 应用尝试检查更新
3. 网络请求失败
4. **静默失败，记录日志**
5. 用户正常使用应用（不受影响）

---

## 与手动检查的区别

| 特性 | 启动时自动检查 | 设置页面手动检查 |
|-----|--------------|----------------|
| **触发时机** | 应用启动时 | 用户点击"检查更新" |
| **无更新提示** | 静默（不提示） | 显示"已是最新版本" |
| **失败提示** | 静默（记录日志） | 显示错误信息 |
| **检查中状态** | 无可见提示 | 显示加载指示器 |
| **适用场景** | 日常使用，自动提醒 | 主动检查，立即反馈 |

---

## 技术架构

```
┌─────────────────────────────────────────┐
│         FileListViewModel               │
│  (应用启动时的首个 ViewModel)            │
├─────────────────────────────────────────┤
│  init {                                 │
│    checkUpdateOnStartup()               │
│  }                                      │
│                                         │
│  ↓ 后台协程                              │
│  UpdateChecker.checkUpdate()            │
│  ↓                                      │
│  _autoUpdateInfo.value = result         │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│         FileListScreen                  │
│  (文件列表界面，应用首页)                 │
├─────────────────────────────────────────┤
│  autoUpdateInfo.collectAsState()        │
│  ↓                                      │
│  if (updateInfo != null) {              │
│    UpdateDialog(...)  ← 复用设置页组件   │
│  }                                      │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│      UpdateDialog (settings包)          │
│  显示版本信息、更新日志、按钮             │
├─────────────────────────────────────────┤
│  [稍后提醒] → dismissAutoUpdate()       │
│  [立即更新] → DownloadDialog()          │
└─────────────────────────────────────────┘
```

---

## 性能与安全

### 性能优化
- ✅ **异步执行**：使用协程，不阻塞主线程
- ✅ **静默失败**：网络错误不影响用户体验
- ✅ **单次检查**：每次启动仅检查一次，不重复请求

### 安全考虑
- ✅ **HTTPS 传输**：更新信息通过安全通道获取
- ✅ **签名验证**：APK 下载后由系统验证签名
- ✅ **用户确认**：任何下载/安装都需要用户明确同意

---

## 未来优化方向

1. **智能检查频率**
   - 记录上次检查时间
   - 24 小时内不重复检查
   - 减少不必要的网络请求

2. **跳过版本**
   - 用户点击"稍后提醒"后，当天不再提示该版本
   - 存储到 DataStore

3. **强制更新**
   - 服务端返回 `forceUpdate: true` 时
   - 对话框不显示"稍后提醒"按钮
   - 仅允许更新或退出应用

4. **增量更新**
   - 仅下载差异文件，节省流量
   - 需要服务端支持

5. **后台静默下载**
   - WiFi 环境下自动下载
   - 下载完成后通知用户安装

---

## 测试建议

### 测试场景

1. **正常更新流程**
   - 启动应用，有新版本
   - 弹出对话框，点击"立即更新"
   - 下载并安装成功

2. **延迟更新**
   - 启动应用，有新版本
   - 点击"稍后提醒"
   - 关闭应用，再次启动
   - 应再次弹出更新提示

3. **无更新**
   - 启动应用，已是最新版本
   - 不显示任何对话框
   - 正常进入应用

4. **网络异常**
   - 断开网络
   - 启动应用
   - 不显示错误提示
   - 正常进入应用

5. **手动检查对比**
   - 启动应用后，进入设置页面
   - 手动点击"检查更新"
   - 对比自动检查和手动检查的行为

---

## 构建与部署

**构建命令**：
```bash
./gradlew :app:assembleDebug
```

**构建结果**：
```
BUILD SUCCESSFUL in 48s
40 actionable tasks: 11 executed, 4 from cache, 25 up-to-date
```

**APK 位置**：
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 总结

✅ **功能完成**：应用启动时自动检查更新  
✅ **用户友好**：智能弹窗，静默失败  
✅ **代码复用**：复用现有 UI 组件  
✅ **性能优化**：异步执行，不阻塞  
✅ **向后兼容**：不影响现有手动检查功能  

**用户体验提升**：
- 从被动检查 → 主动推送
- 第一时间感知新版本
- 更流畅的更新流程

---

**贡献者**: Claude Code  
**审核状态**: 待测试
