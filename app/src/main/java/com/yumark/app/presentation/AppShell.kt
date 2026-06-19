package com.yumark.app.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yumark.app.presentation.adaptive.HingeInfo
import com.yumark.app.presentation.adaptive.splitForHinge
import com.yumark.app.presentation.ai.config.AiConfigScreen
import com.yumark.app.presentation.editor.EditorScreen
import com.yumark.app.presentation.filelist.FileListScreen
import com.yumark.app.presentation.navigation.Screen
import com.yumark.app.presentation.navigation.YuMarkNavGraph
import com.yumark.app.presentation.settings.SettingsScreen
import com.yumark.app.presentation.sync.SyncSettingsScreen

private const val DETAIL_EMPTY = "detail_empty"

/** 默认双窗格权重（无铰链可对齐时）：列表 40% / 详情 60%。 */
private const val LIST_PANE_WEIGHT = 0.4f
private const val DETAIL_PANE_WEIGHT = 0.6f

/**
 * 顶层自适应外壳：
 * - Compact / Medium 宽度（手机、折叠屏折叠态）：沿用既有单窗格 [YuMarkNavGraph]，行为不变。
 * - Expanded 宽度（平板、折叠屏展开态）：列表-详情双窗格——左侧文件列表常驻，右侧详情用独立
 *   NavHost 承载编辑器/设置/AI 配置；点选文档即在右侧打开，无需整屏跳转。
 *
 * 当存在竖直分隔铰链（[hinge]，如 book 姿态或双屏间隙）时，两个窗格沿铰链对齐，内容不跨折痕。
 */
@Composable
fun AppShell(
    widthSizeClass: WindowWidthSizeClass,
    hinge: HingeInfo?,
    externalFileUri: String?
) {
    if (widthSizeClass == WindowWidthSizeClass.Expanded) {
        TwoPaneScaffold(externalFileUri, hinge)
    } else {
        YuMarkNavGraph(externalFileUri = externalFileUri)
    }
}

@Composable
private fun TwoPaneScaffold(externalFileUri: String?, hinge: HingeInfo?) {
    val detailNav = rememberNavController()

    // 外部打开的文件直接在详情窗格显示
    LaunchedEffect(externalFileUri) {
        externalFileUri?.let { uri ->
            detailNav.navigate(Screen.Editor.createExternalRoute(uri))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split = splitForHinge(constraints.maxWidth, hinge)
        val density = LocalDensity.current
        Row(Modifier.fillMaxSize()) {
            if (split != null) {
                // 沿铰链对齐：左/右窗格固定到铰链两侧，铰链区作为间隙（避让折痕）。
                Box(Modifier.width(with(density) { split.leftWidthPx.toDp() }).fillMaxHeight()) {
                    FileListScreen(navController = detailNav)
                }
                HingeDivider(with(density) { split.hingeWidthPx.toDp() })
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    DetailPaneHost(detailNav)
                }
            } else {
                // 无可对齐铰链：按权重分栏。
                Box(Modifier.weight(LIST_PANE_WEIGHT).fillMaxHeight()) {
                    FileListScreen(navController = detailNav)
                }
                HingeDivider(0.dp)
                Box(Modifier.weight(DETAIL_PANE_WEIGHT).fillMaxHeight()) {
                    DetailPaneHost(detailNav)
                }
            }
        }
    }
}

/** 两窗格间的分隔/铰链间隙：至少 1dp 可见，物理间隙更宽时按实际宽度避让。 */
@Composable
private fun HingeDivider(hingeWidth: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .width(hingeWidth.coerceAtLeast(1.dp))
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** 右侧详情窗格的独立 NavHost：承载编辑器 / 设置 / AI 配置。 */
@Composable
private fun DetailPaneHost(detailNav: NavHostController) {
    NavHost(navController = detailNav, startDestination = DETAIL_EMPTY) {
        composable(DETAIL_EMPTY) { EmptyDetailPane() }
        composable(
            route = Screen.Editor.route,
            arguments = listOf(
                navArgument("documentId") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                },
                navArgument("docUri") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                }
            )
        ) { EditorScreen(navController = detailNav) }
        composable(Screen.Settings.route) { SettingsScreen(navController = detailNav) }
        composable(Screen.AiConfig.route) { AiConfigScreen(navController = detailNav) }
        composable(Screen.Sync.route) { SyncSettingsScreen(navController = detailNav) }
    }
}

@Composable
private fun EmptyDetailPane() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "从左侧选择或新建文档开始编辑",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
