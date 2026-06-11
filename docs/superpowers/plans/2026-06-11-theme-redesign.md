# 界面主题重设计 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除绿色启动屏、主题改为可切换的注册表（默认灰白 / Claude，各配浅深色板，跟随系统），设置页可切换，预览区深色跟随。

**Architecture:** `AppThemes.kt` 注册表持有各主题浅/深 `ColorScheme`；`MainActivity` 观察 `UserSettings.themeId` 传入 `YuMarkTheme` 即时生效；状态栏改为背景色。启动屏整体删除，直达文件列表。

**Tech Stack:** Jetpack Compose Material3 + Hilt + DataStore

**注意：项目非 git 仓库，提交步骤替换为编译/测试验证。** 设计文档：`docs/superpowers/specs/2026-06-11-theme-redesign-design.md`

---

### Task 1: AppThemes 注册表（TDD）

**Files:**
- Create: `app/src/main/java/com/yumark/app/presentation/theme/AppThemes.kt`
- Delete: `app/src/main/java/com/yumark/app/presentation/theme/Color.kt`（色值全部内联进 AppThemes，旧 md_theme 常量随 Task 2 的 Theme.kt 重写一并失去引用）
- Test: `app/src/test/java/com/yumark/app/presentation/theme/AppThemesTest.kt`

- [ ] **Step 1.1: 写失败测试**

```kotlin
package com.yumark.app.presentation.theme

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AppThemesTest {

    @Test
    fun `byId 返回对应主题`() {
        assertThat(AppThemes.byId("claude").id).isEqualTo("claude")
    }

    @Test
    fun `byId 未知 id 回退默认主题`() {
        assertThat(AppThemes.byId("deleted-theme").id).isEqualTo(AppThemes.DEFAULT_ID)
    }

    @Test
    fun `byId null 回退默认主题`() {
        assertThat(AppThemes.byId(null).id).isEqualTo(AppThemes.DEFAULT_ID)
    }

    @Test
    fun `注册表按序包含灰白与 claude`() {
        assertThat(AppThemes.all.map { it.id }).containsExactly("default", "claude").inOrder()
    }
}
```

- [ ] **Step 1.2: 运行确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.yumark.app.presentation.theme.AppThemesTest"`
Expected: FAIL — `Unresolved reference: AppThemes`

- [ ] **Step 1.3: 实现 AppThemes.kt（色值与设计文档色板一致）**

```kotlin
package com.yumark.app.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 应用主题：每个主题包含浅色与深色两套 ColorScheme，深浅由系统深色模式决定
 */
data class AppTheme(
    val id: String,
    val label: String,
    val light: ColorScheme,
    val dark: ColorScheme
)

object AppThemes {
    const val DEFAULT_ID = "default"
    const val CLAUDE_ID = "claude"

    /** 默认·灰白（Typora 风格：克制、几乎无色） */
    private val DefaultTheme = AppTheme(
        id = DEFAULT_ID,
        label = "默认·灰白",
        light = lightColorScheme(
            primary = Color(0xFF4B5A68),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE8ECEF),
            onPrimaryContainer = Color(0xFF2A3540),
            secondary = Color(0xFF757575),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFEEEEEE),
            onSecondaryContainer = Color(0xFF424242),
            background = Color(0xFFFCFCFC),
            onBackground = Color(0xFF333333),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF333333),
            surfaceVariant = Color(0xFFF3F3F3),
            onSurfaceVariant = Color(0xFF6B6B6B),
            outline = Color(0xFFE0E0E0),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF)
        ),
        dark = darkColorScheme(
            primary = Color(0xFF8FA1B3),
            onPrimary = Color(0xFF1B2733),
            primaryContainer = Color(0xFF37424D),
            onPrimaryContainer = Color(0xFFD5DEE6),
            secondary = Color(0xFF9E9E9E),
            onSecondary = Color(0xFF1E1E1E),
            secondaryContainer = Color(0xFF333333),
            onSecondaryContainer = Color(0xFFCFCFCF),
            background = Color(0xFF1E1E1E),
            onBackground = Color(0xFFDADADA),
            surface = Color(0xFF252526),
            onSurface = Color(0xFFDADADA),
            surfaceVariant = Color(0xFF2D2D2D),
            onSurfaceVariant = Color(0xFF9E9E9E),
            outline = Color(0xFF3D3D3D),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410)
        )
    )

    /** Claude（米白 + 赤陶橙） */
    private val ClaudeTheme = AppTheme(
        id = CLAUDE_ID,
        label = "Claude",
        light = lightColorScheme(
            primary = Color(0xFFD97757),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFF1E0D8),
            onPrimaryContainer = Color(0xFF8A4A2F),
            secondary = Color(0xFF8A8775),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFEAE8E0),
            onSecondaryContainer = Color(0xFF4A4738),
            background = Color(0xFFF5F4ED),
            onBackground = Color(0xFF3D3929),
            surface = Color(0xFFFAF9F5),
            onSurface = Color(0xFF3D3929),
            surfaceVariant = Color(0xFFEAE8E0),
            onSurfaceVariant = Color(0xFF87867F),
            outline = Color(0xFFDDD9CC),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF)
        ),
        dark = darkColorScheme(
            primary = Color(0xFFD97757),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFF4F352A),
            onPrimaryContainer = Color(0xFFF0C9B8),
            secondary = Color(0xFFA8A595),
            onSecondary = Color(0xFF262624),
            secondaryContainer = Color(0xFF3F3F3C),
            onSecondaryContainer = Color(0xFFDDDACE),
            background = Color(0xFF262624),
            onBackground = Color(0xFFF0EEE6),
            surface = Color(0xFF30302E),
            onSurface = Color(0xFFF0EEE6),
            surfaceVariant = Color(0xFF3A3A37),
            onSurfaceVariant = Color(0xFFB8B5A9),
            outline = Color(0xFF4A4A46),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410)
        )
    )

    val all: List<AppTheme> = listOf(DefaultTheme, ClaudeTheme)

    /** 未知/null id 回退默认主题（容忍 DataStore 中的过期值） */
    fun byId(id: String?): AppTheme = all.find { it.id == id } ?: DefaultTheme
}
```

- [ ] **Step 1.4: 运行确认测试通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.yumark.app.presentation.theme.AppThemesTest"`
Expected: 4 个测试 PASS

---

### Task 2: Theme.kt 重写 + MainActivity 接入 + 删 Color.kt

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/theme/Theme.kt`（整体重写）
- Modify: `app/src/main/java/com/yumark/app/MainActivity.kt`
- Delete: `app/src/main/java/com/yumark/app/presentation/theme/Color.kt`

- [ ] **Step 2.1: Theme.kt 整体替换**

```kotlin
package com.yumark.app.presentation.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun YuMarkTheme(
    themeId: String = AppThemes.DEFAULT_ID,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val theme = AppThemes.byId(themeId)
    val colorScheme = if (darkTheme) theme.dark else theme.light

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 状态栏与背景同色，不再使用主题色块
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
```

- [ ] **Step 2.2: 删除 Color.kt**

Run: `rm app/src/main/java/com/yumark/app/presentation/theme/Color.kt`

- [ ] **Step 2.3: MainActivity 整体替换**

```kotlin
package com.yumark.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.repository.SettingsRepository
import com.yumark.app.presentation.navigation.YuMarkNavGraph
import com.yumark.app.presentation.theme.YuMarkTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.observeSettings()
                .collectAsState(initial = UserSettings())
            YuMarkTheme(themeId = settings.themeId) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    YuMarkNavGraph()
                }
            }
        }
    }
}
```

（依赖 Task 3 的 `UserSettings.themeId` 字段——执行时先做 Task 3 的 Step 3.1/3.2 亦可，编译检查放在 Task 3 末尾。）

- [ ] **Step 2.4: 编译验证（与 Task 3 合并验证亦可）**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL（若 themeId 未加则先完成 Task 3）

---

### Task 3: UserSettings.themeId + 设置页主题切换

**Files:**
- Modify: `app/src/main/java/com/yumark/app/domain/model/Models.kt`
- Modify: `app/src/main/java/com/yumark/app/data/local/prefs/SettingsDataStore.kt`
- Modify: `app/src/main/java/com/yumark/app/presentation/settings/SettingsScreen.kt`

- [ ] **Step 3.1: UserSettings 加字段**

`UserSettings` 末尾（`defaultPreviewMode` 之后）加：

```kotlin
val themeId: String = "default"
```

- [ ] **Step 3.2: SettingsDataStore 读写**

`Keys` 加：

```kotlin
val THEME_ID = stringPreferencesKey("theme_id")
```

`settingsFlow` 构造加：

```kotlin
themeId = prefs[Keys.THEME_ID] ?: "default"
```

`updateSettings` 加：

```kotlin
prefs[Keys.THEME_ID] = settings.themeId
```

- [ ] **Step 3.3: SettingsScreen 主题区块**

`SettingsViewModel` 加方法：

```kotlin
fun updateThemeId(id: String) {
    viewModelScope.launch {
        repo.updateSettings(settings.value.copy(themeId = id))
    }
}
```

import 区加：

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.yumark.app.presentation.theme.AppThemes
```

`SettingsScreen` 的 Column 顶部（"字体大小" ListItem 之前）插入：

```kotlin
// 主题
Text(
    "主题",
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
)
AppThemes.all.forEach { theme ->
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.updateThemeId(theme.id) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = settings.themeId == theme.id,
            onClick = { viewModel.updateThemeId(theme.id) }
        )
        Spacer(modifier = Modifier.width(4.dp))
        // 主题色点预览
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(theme.light.primary)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(theme.label, style = MaterialTheme.typography.bodyLarge)
    }
}

Divider()
```

注意：文件中自定义的 `private fun ListItem` 不影响以上代码；`Box/Spacer/Row` 已由现有 `androidx.compose.foundation.layout.*` import 覆盖。

- [ ] **Step 3.4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

---

### Task 4: 删除启动屏

**Files:**
- Delete: `app/src/main/java/com/yumark/app/presentation/splash/SplashScreen.kt`
- Modify: `app/src/main/java/com/yumark/app/presentation/navigation/Screen.kt`
- Modify: `app/src/main/java/com/yumark/app/presentation/navigation/YuMarkNavGraph.kt`

- [ ] **Step 4.1: 删除文件**

Run: `rm app/src/main/java/com/yumark/app/presentation/splash/SplashScreen.kt`

- [ ] **Step 4.2: Screen.kt 移除 Splash**

删除行：

```kotlin
data object Splash : Screen("splash")
```

- [ ] **Step 4.3: NavGraph 移除 splash 路由、改起点**

`startDestination` 参数默认值改为 `Screen.FileList.route`；删除：

```kotlin
composable(Screen.Splash.route) {
    SplashScreen(navController = navController)
}
```

及 import `com.yumark.app.presentation.splash.SplashScreen`。

- [ ] **Step 4.4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

---

### Task 5: 预览区深色跟随

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt`

- [ ] **Step 5.1: EditorViewModel 暴露 themeId**

import 加 `import com.yumark.app.presentation.theme.AppThemes`，状态区（`_saveError` 之后）加：

```kotlin
/** 当前主题 id（驱动预览区深色配色） */
val themeId: StateFlow<String> = loadSettingsUseCase.observe()
    .map { it.themeId }
    .stateIn(viewModelScope, SharingStarted.Eagerly, AppThemes.DEFAULT_ID)
```

- [ ] **Step 5.2: EditorScreen 注入深色样式**

import 加：

```kotlin
import androidx.compose.foundation.isSystemInDarkTheme
```

状态区（`previewWebView` 之后）加：

```kotlin
val themeId by viewModel.themeId.collectAsState()
val isDarkMode = isSystemInDarkTheme()
// 深色模式下注入的预览配色（背景 to 文字）；浅色保持模板默认白底
val previewDarkColors = remember(themeId, isDarkMode) {
    if (!isDarkMode) null else when (themeId) {
        "claude" -> "#262624" to "#F0EEE6"
        else -> "#1E1E1E" to "#DADADA"
    }
}
```

预览分支内（现有 `LaunchedEffect(editContent, isWebViewReady)` 之后）加：

```kotlin
// 深色模式：注入覆盖样式（含代码块/表格/引用的深色适配）
LaunchedEffect(isWebViewReady, previewDarkColors) {
    if (isWebViewReady && previewDarkColors != null) {
        val (bg, fg) = previewDarkColors
        previewWebView?.postDelayed({
            previewWebView?.evaluateJavascript(
                """
                (function() {
                    var s = document.getElementById('yumark-dark');
                    if (!s) { s = document.createElement('style'); s.id = 'yumark-dark'; document.head.appendChild(s); }
                    s.textContent = 'body{background:$bg;color:$fg;}' +
                        'code,pre{background:#2E2E2C;color:#E0DED6;}' +
                        'th{background:#343432;}' + 'th,td{border-color:#4A4A46;}' +
                        'blockquote{border-color:#5A5A56;color:#A8A59B;}' +
                        'a{color:#7FB2E5;}';
                })();
                """.trimIndent(), null
            )
        }, 900)
    }
}
```

- [ ] **Step 5.3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

---

### Task 6: 完整构建与验收

- [ ] **Step 6.1: 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS（16 个：原 12 + AppThemesTest 4）

- [ ] **Step 6.2: 完整构建**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6.3: 手动验收清单**

1. 启动应用：无绿色启动屏，白色过渡后直接进文件列表
2. 默认界面：灰白 Typora 风格，状态栏与背景同色
3. 设置 → 主题 → 切到 Claude：全界面立即变为米白+赤陶橙
4. 系统切深色模式：两主题分别变为夜黑/炭黑色板
5. 深色模式打开文档预览：背景/文字/代码块均为深色

## Self-Review 结果

- **Spec 覆盖**：启动屏删除（Task 4）、注册表+状态栏（Task 1/2）、themeId 设置链路（Task 3）、预览深色（Task 5）、未知 id 回退测试（Task 1）——全覆盖
- **占位符**：无
- **类型一致性**：`AppThemes.byId(String?)`、`AppThemes.DEFAULT_ID`、`UserSettings.themeId` 各任务引用一致；`previewWebView`/`isWebViewReady` 与现有 EditorScreen 变量名一致 ✓
