# 设计文档：界面主题重设计（去绿色化 + 可切换主题 + Claude 主题）

- 日期：2026-06-11
- 项目：YuMark
- 状态：已获用户批准（色板预览已确认）

## 背景与目标

1. 删除绿色启动屏动画，仅保留系统自带的白色启动过渡
2. 默认主题改为 Typora 风格的灰白色（克制、几乎无色）
3. 设置页可切换主题色，其中一种为 Claude 主题（米白底 + 赤陶橙 accent）
4. 深色模式：跟随系统，每个主题配浅/深两套色板（已确认）

## 架构（方案 A：主题注册表 + 设置驱动）

- `theme/AppThemes.kt`（新建）：`AppTheme(id, label, light: ColorScheme, dark: ColorScheme)` + 注册表 `AppThemes.all` + `AppThemes.byId(id)`
- `theme/Color.kt`：整体重写为两套主题的色值常量
- `theme/Theme.kt`：`YuMarkTheme(themeId: String, darkTheme: Boolean = isSystemInDarkTheme())`；状态栏颜色改为 `colorScheme.background`（不再涂主色）
- `MainActivity`：注入 `SettingsRepository`，`collectAsState` 观察 `themeId` 传入 `YuMarkTheme`，切换即时生效
- `UserSettings` 新增 `themeId: String = "default"`；`SettingsDataStore` 加 `theme_id` key；旧 `lightThemeId/darkThemeId` 字段保留不用
- `SettingsScreen`：新增"主题"区块，单选两个主题（带色点预览），调 `repo.updateSettings(copy(themeId = ...))`

## 色板

### 默认·灰白 `"default"`（Typora 风格）

| 角色 | 浅色 | 深色 |
|---|---|---|
| background | #FCFCFC | #1E1E1E |
| surface | #FFFFFF | #252526 |
| surfaceVariant | #F3F3F3 | #2D2D2D |
| onBackground/onSurface | #333333 | #DADADA |
| onSurfaceVariant | #6B6B6B | #9E9E9E |
| primary | #4B5A68（石板灰蓝） | #8FA1B3 |
| onPrimary | #FFFFFF | #1B2733 |
| primaryContainer | #E8ECEF | #37424D |
| onPrimaryContainer | #2A3540 | #D5DEE6 |
| secondary | #757575 | #9E9E9E |
| secondaryContainer | #EEEEEE | #333333 |
| onSecondaryContainer | #424242 | #CFCFCF |
| error/onError | #B3261E / #FFFFFF | #F2B8B5 / #601410 |

### Claude `"claude"`（米白 + 赤陶橙）

| 角色 | 浅色 | 深色 |
|---|---|---|
| background | #F5F4ED | #262624 |
| surface | #FAF9F5 | #30302E |
| surfaceVariant | #EAE8E0 | #3A3A37 |
| onBackground/onSurface | #3D3929 | #F0EEE6 |
| onSurfaceVariant | #87867F | #B8B5A9 |
| primary | #D97757（赤陶橙） | #D97757 |
| onPrimary | #FFFFFF | #FFFFFF |
| primaryContainer | #F1E0D8 | #4F352A |
| onPrimaryContainer | #8A4A2F | #F0C9B8 |
| secondary | #8A8775 | #A8A595 |
| secondaryContainer | #EAE8E0 | #3F3F3C |
| onSecondaryContainer | #4A4738 | #DDDACE |
| error/onError | #B3261E / #FFFFFF | #F2B8B5 / #601410 |

## 启动屏删除

- 删除 `presentation/splash/SplashScreen.kt`、`Screen.Splash`、NavGraph 中 splash 路由
- `startDestination` 改为 `Screen.FileList.route`
- 保留系统默认启动窗口（白色过渡），不引入 SplashScreen API

## 预览区跟随深浅色

- `EditorScreen` 的 WebView `onPageFinished` 后注入一次 JS：深色模式时设置 `document.body` 背景/文字色（default 深色 #1E1E1E/#DADADA；claude 深色 #262624/#F0EEE6；浅色主题不注入，保持模板白底）
- 需要把当前 themeId + 深色状态传到 EditorScreen（`isSystemInDarkTheme()` + 设置流，EditorViewModel 已注入 `loadSettingsUseCase` 可观察）

## 错误处理

- DataStore 读到未知 themeId（如未来删除主题）→ `AppThemes.byId` 回退到 default
- 设置流首帧未到达时 MainActivity 用 `UserSettings()` 默认值（default 主题），无闪烁风险（同为灰白）

## 测试

- `AppThemes.byId` 未知 id 回退逻辑：JUnit 单测
- 其余为视觉改动：构建验证 + 真机手动确认（两主题 × 浅/深 × 主要界面）

## 非目标

- 预览区 Markdown 排版主题化（代码高亮配色等）本期不做，仅背景/文字基色跟随
- 旧 `lightThemeId/darkThemeId` 字段清理
- 手动深浅色三挡开关

## 涉及文件

新增：`theme/AppThemes.kt`；修改：`theme/Color.kt`、`theme/Theme.kt`、`MainActivity.kt`、`navigation/Screen.kt`、`navigation/YuMarkNavGraph.kt`、`domain/model/Models.kt`、`data/local/prefs/SettingsDataStore.kt`、`presentation/settings/SettingsScreen.kt`、`presentation/editor/EditorScreen.kt`；删除：`presentation/splash/SplashScreen.kt`
