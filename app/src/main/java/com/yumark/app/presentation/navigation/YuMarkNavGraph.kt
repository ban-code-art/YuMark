package com.yumark.app.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yumark.app.presentation.ai.config.AiConfigScreen
import com.yumark.app.presentation.editor.EditorScreen
import com.yumark.app.presentation.filelist.FileListScreen
import com.yumark.app.presentation.settings.SettingsScreen
import com.yumark.app.presentation.sync.SyncSettingsScreen

@Composable
fun YuMarkNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.FileList.route,
    externalFileUri: String? = null
) {
    // 如果有外部文件 URI，直接导航到编辑器
    androidx.compose.runtime.LaunchedEffect(externalFileUri) {
        externalFileUri?.let { uri ->
            navController.navigate(Screen.Editor.createExternalRoute(uri)) {
                // 清除返回栈，避免按返回键回到启动器
                popUpTo(Screen.FileList.route) { inclusive = false }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 24 }
        },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = {
            fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { it / 24 }
        }
    ) {
        composable(Screen.FileList.route) {
            FileListScreen(navController = navController)
        }
        composable(
            route = Screen.Editor.route,
            arguments = listOf(
                navArgument("documentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("docUri") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            EditorScreen(navController = navController)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
        composable(Screen.AiConfig.route) {
            AiConfigScreen(navController = navController)
        }
        composable(Screen.Sync.route) {
            SyncSettingsScreen(navController = navController)
        }
    }
}
