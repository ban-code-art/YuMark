package com.yumark.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.yumark.app.core.security.IncomingUriGuard
import com.yumark.app.domain.model.UserSettings
import com.yumark.app.domain.repository.SettingsRepository
import com.yumark.app.presentation.navigation.ExternalOpenRequest
import com.yumark.app.presentation.theme.YuMarkTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** 外部打开请求；[ExternalOpenRequest.seq] 让同一个 URI 也能重复触发，理由见该类文档。 */
    private var externalOpen by mutableStateOf<ExternalOpenRequest?>(null)

    /** 进程内单调递增，不持久化。 */
    private var externalOpenSeq = 0L

    @OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // 兼容库开屏页：Android 12+ 走系统 SplashScreen，12 以下由库绘制等效开屏窗口，
        // 保证真机（任何版本/ROM）冷启动都有图标开屏动画
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 只在首次创建时消费 intent。
        //
        // Activity 重建（转屏、系统深色模式切换、字号变化、进程死亡恢复）后 getIntent() 返回的
        // 仍是当初那个 VIEW intent —— 无条件再消费一次，就会把用户从他当前所在的页面强行拽回
        // 那个外部文件，而导航还带 popUpTo(FileList) 清栈，当前位置直接丢。
        // 导航栈本身由 rememberSaveable 恢复，不需要靠重放 intent 来复位。
        // 后续再次从文件管理器打开走 onNewIntent（需要 manifest 的 launchMode 才会回调）。
        if (savedInstanceState == null) handleIncomingIntent(intent)

        setContent {
            val settings by settingsRepository.observeSettings()
                .collectAsState(initial = UserSettings())
            val darkTheme = when (settings.darkMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            YuMarkTheme(themeId = settings.themeId, darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val windowSizeClass =
                        androidx.compose.material3.windowsizeclass.calculateWindowSizeClass(this)
                    val hinge = com.yumark.app.presentation.adaptive.rememberHingeInfo(this)
                    com.yumark.app.presentation.AppShell(
                        widthSizeClass = windowSizeClass.widthSizeClass,
                        hinge = hinge,
                        externalOpen = externalOpen
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                if (!isAcceptableIncomingUri(uri)) {
                    // 静默丢弃会让用户以为应用卡住；合法的外部文件不会走到这里，出现即异常
                    android.widget.Toast
                        .makeText(this, R.string.open_external_rejected, android.widget.Toast.LENGTH_LONG)
                        .show()
                    return
                }
                // 请求持久化读写权限
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                externalOpen = ExternalOpenRequest(uri.toString(), ++externalOpenSeq)
            }
        }
    }

    /**
     * 外部 VIEW intent 的 URI 准入。
     *
     * `content://` 直接放行——SAF 的授权由系统记账。`file://` 必须额外确认它不指向本应用私有目录：
     * `ContentResolver` 对 `file://` 不经提供器、以本应用 UID 直接读写，而编辑器保存最终会
     * `openOutputStream(uri, "wt")` 截断写入（[com.yumark.app.data.repository.WorkspaceRepositoryImpl.writeDocument]）。
     * 不加这层校验，任何第三方应用都能发一个指向 YuMark 数据库/配置的 `file://` intent，
     * 借本应用的权限改写它自己的数据。判定逻辑见 [IncomingUriGuard]（有单测覆盖边界）。
     *
     * 这里不直接删掉 manifest 的 `scheme="file"`：老式文件管理器仍靠它打开 .md，
     * 校验路径归属既堵住越权写入，也保住这条合法入口。
     */
    private fun isAcceptableIncomingUri(uri: android.net.Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (!IncomingUriGuard.isAllowedScheme(scheme)) return false
        if (scheme != IncomingUriGuard.SCHEME_FILE) return true
        // 拿不到规范路径就无法证明它在私有目录之外，保守拒绝
        val path = IncomingUriGuard.canonicalOrNull(uri.path) ?: return false
        return !IncomingUriGuard.isInsidePrivateRoots(path, privateRoots())
    }

    /**
     * 本应用私有目录的规范化根列表。
     *
     * `dataDir` 一棵覆盖 filesDir / cacheDir / databases / shared_prefs / no_backup；
     * 外部私有目录不在 `dataDir` 下，须单列，且多存储卷设备上每个卷各有一份
     * （`getExternalFilesDirs` 在卷未挂载时会给出 null 元素）。
     */
    private fun privateRoots(): List<String> = buildList {
        add(dataDir.absolutePath)
        add(applicationInfo.dataDir)
        getExternalFilesDirs(null).forEach { dir -> dir?.let { add(it.absolutePath) } }
        externalCacheDir?.let { add(it.absolutePath) }
    }.mapNotNull { IncomingUriGuard.canonicalOrNull(it) }.distinct()
}
