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
import com.yumark.app.presentation.editor.EditorScreen
import com.yumark.app.presentation.filelist.FileListScreen
import com.yumark.app.presentation.settings.SettingsScreen
import com.yumark.app.presentation.splash.SplashScreen

@Composable
fun YuMarkNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Splash.route
) {
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
        composable(Screen.Splash.route) {
            SplashScreen(navController = navController)
        }
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
    }
}
