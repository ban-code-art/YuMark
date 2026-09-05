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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yumark.app.R
import com.yumark.app.presentation.adaptive.HingeInfo
import com.yumark.app.presentation.adaptive.splitForHinge
import com.yumark.app.presentation.ai.config.AiConfigScreen
import com.yumark.app.presentation.editor.EditorScreen
import com.yumark.app.presentation.filelist.FileListScreen
import com.yumark.app.presentation.navigation.ExternalOpenRequest
import com.yumark.app.presentation.navigation.Screen
import com.yumark.app.presentation.navigation.YuMarkNavGraph
import com.yumark.app.presentation.settings.SettingsScreen
import com.yumark.app.presentation.sync.SyncSettingsScreen
import com.yumark.app.presentation.theme.AppSpacing

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
    externalOpen: ExternalOpenRequest?
) {
    // 「当前打开的是哪一篇」必须存在下面这个 if 的**外面**。
    //
    // 宽度类跨过 Expanded 边界时（平板转屏、折叠屏展开/合拢、Android 12L 之后的自由窗口拖拽），
    // 整支分支会被换掉：旧分支的 NavHost 连着它的返回栈一起离开组合，新分支从自己的
    // startDestination 从头开始。于是用户正在编辑的文档在转屏后直接从屏幕上消失——
    // 单窗格换双窗格后右侧是那句空白提示，反向则退回文件列表，两边都要重新点一次才能回到刚才那篇。
    //
    // 只存路由（一个短字符串），不存正文：正文由 `EditorViewModel` 持有，并在 `onCleared()` 里经
    // 应用级 appScope 落盘（那里正是为「配置变更走掉」写的），所以内容本来就不会丢；而
    // savedInstanceState 要过 Binder，把正文塞进去就是在赌 TransactionTooLargeException。
    var openEditorRoute by rememberSaveable { mutableStateOf<String?>(null) }

    if (widthSizeClass == WindowWidthSizeClass.Expanded) {
        TwoPaneScaffold(externalOpen, hinge, openEditorRoute) { openEditorRoute = it }
    } else {
        SinglePaneShell(externalOpen, openEditorRoute) { openEditorRoute = it }
    }
}

/** Compact / Medium：沿用既有单窗格图，只额外挂上跨分支的编辑器路由桥。 */
@Composable
private fun SinglePaneShell(
    externalOpen: ExternalOpenRequest?,
    restoreRoute: String?,
    onEditorRouteChanged: (String?) -> Unit
) {
    val nav = rememberNavController()
    YuMarkNavGraph(navController = nav, externalOpen = externalOpen)
    EditorRouteBridge(nav, externalOpen, restoreRoute, onEditorRouteChanged)
}

/**
 * 把 [nav] 上「编辑器开着没有、开的是哪一篇」同步给 [onEditorRouteChanged]，
 * 并在本分支首次挂载时把上一支留下的 [restoreRoute] 接回来。
 *
 * 刻意放在 NavHost **之后**组合：`navigate()` 在 `setGraph()` 之前调用会抛
 * IllegalStateException，而 NavHost 是在组合体里直接赋 graph 的，排在它后面就没有这个次序问题。
 */
@Composable
private fun EditorRouteBridge(
    nav: NavHostController,
    externalOpen: ExternalOpenRequest?,
    restoreRoute: String?,
    onEditorRouteChanged: (String?) -> Unit
) {
    // 挂载这一刻就把要恢复的目标抓进局部量。下面那个记录 effect 会在同一帧把
    // openEditorRoute 刷成 null（此刻栈顶还是 startDestination），先抓一次才不会被那次清空吃掉。
    //
    // 外部打开（从文件管理器点 .md 进来）优先：那是用户刚刚的明确动作，不该被上一次的残留
    // 抢在前面推一页进返回栈。
    val target = remember { restoreRoute?.takeIf { externalOpen == null } }
    LaunchedEffect(Unit) {
        val route = target ?: return@LaunchedEffect
        // 只有「换了分支、这个 NavHost 是全新的」才需要补这一次导航。
        // 同宽度类下的转屏与进程死亡恢复都不能走到这里：rememberNavController 自己保存并恢复
        // 返回栈，栈顶本来还是那一篇，再 navigate 一次就多压一页——返回键得按两下才退得回去。
        if (editorRouteOf(nav.currentBackStackEntry) != route) nav.navigate(route)
    }

    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(entry) {
        onEditorRouteChanged(editorRouteOf(entry))
    }
}

/**
 * 栈顶若是编辑器，重建一条可以直接 `navigate` 的路由；否则 null。
 *
 * 只负责从 [NavBackStackEntry] 里取参数——真正的取舍在 [editorRouteFor]，那部分是纯函数、有单测。
 */
private fun editorRouteOf(entry: NavBackStackEntry?): String? {
    if (entry == null || entry.destination.route != Screen.Editor.route) return null
    val args = entry.arguments ?: return null
    return editorRouteFor(args.getString("documentId"), args.getString("docUri"))
}

/**
 * 由编辑器的两个可选参数重建一条可 `navigate` 的路由。
 *
 * `documentId` 优先：两者同时存在时（理论上不会，但路由是字符串拼出来的，防一手）内部文档
 * 才是真正打开的那一篇，`docUri` 只在外部 SAF 打开时出现。
 *
 * 传进来的是 Navigation **解码后**的原值，交回 `createRoute` / `createExternalRoute` 会重新
 * 编码一次，正好与 [Screen.Editor] 那侧的编解码配对——含裸空格的外部 URI（`my note.md`）
 * 靠的就是这一对，少编一层就会在下一次 navigate 时把空格丢掉，表现为「转屏后说文件不存在」。
 */
internal fun editorRouteFor(documentId: String?, docUri: String?): String? = when {
    documentId != null -> Screen.Editor.createRoute(documentId)
    docUri != null -> Screen.Editor.createExternalRoute(docUri)
    else -> null
}

@Composable
private fun TwoPaneScaffold(
    externalOpen: ExternalOpenRequest?,
    hinge: HingeInfo?,
    restoreRoute: String?,
    onEditorRouteChanged: (String?) -> Unit
) {
    val detailNav = rememberNavController()

    // 外部打开的文件直接在详情窗格显示
    LaunchedEffect(externalOpen) {
        externalOpen?.let { detailNav.navigate(Screen.Editor.createExternalRoute(it.uri)) }
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
                HingeDivider(AppSpacing.None)
                Box(Modifier.weight(DETAIL_PANE_WEIGHT).fillMaxHeight()) {
                    DetailPaneHost(detailNav)
                }
            }
        }
    }

    EditorRouteBridge(detailNav, externalOpen, restoreRoute, onEditorRouteChanged)
}

/** 两窗格间的分隔/铰链间隙：至少 1dp 可见，物理间隙更宽时按实际宽度避让。 */
@Composable
private fun HingeDivider(hingeWidth: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .width(hingeWidth.coerceAtLeast(AppShellMetrics.MinDividerWidth))
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
    Box(Modifier.fillMaxSize().padding(AppSpacing.Group), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.shell_detail_empty_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 外壳特有的度量，刻意不并入全局标度：无物理铰链、退化为纯分隔线时的最小可见宽度（1dp 发丝，
 * 绘制轴）。物理铰链更宽时按实际像素避让折痕。离散于全局标度，保留原像素、不硬凑。
 * 按 FileListMetrics 先例落屏幕局部。
 */
private object AppShellMetrics {
    /** 双窗格分隔线最小可见宽度：1dp 发丝线（有物理铰链时按实际宽度避让）。 */
    val MinDividerWidth = 1.dp
}
