package com.yumark.app.presentation.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.yumark.app.R
import com.yumark.app.presentation.navigation.Screen
import kotlinx.coroutines.delay

/**
 * 应用内开屏页：图标 + YuMark 字标，淡入放大后进入文件列表。
 * 与系统开屏（仅图标）衔接：系统开屏在首帧绘制后消失，本页即首帧。
 */
@Composable
fun SplashScreen(navController: NavController) {
    val appear = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 500))
        delay(600)
        navController.navigate(Screen.FileList.route) {
            // 开屏页不留在返回栈，返回键不会再回到这里
            popUpTo(Screen.Splash.route) { inclusive = true }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = appear.value
                val scale = 0.85f + 0.15f * appear.value
                scaleX = scale
                scaleY = scale
            }
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_art),
                contentDescription = null,
                modifier = Modifier.size(96.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "YuMark",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
