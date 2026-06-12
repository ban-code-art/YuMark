package com.yumark.app

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
        // 兼容库开屏页：Android 12+ 走系统 SplashScreen，12 以下由库绘制等效开屏窗口，
        // 保证真机（任何版本/ROM）冷启动都有图标开屏动画
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    YuMarkNavGraph()
                }
            }
        }
    }
}
