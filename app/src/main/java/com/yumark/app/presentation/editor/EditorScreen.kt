package com.yumark.app.presentation.editor

import android.content.Intent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.yumark.app.BuildConfig
import com.yumark.app.R
import com.yumark.app.core.crash.CrashLogFormatter
import com.yumark.app.core.text.MarkdownAction
import com.yumark.app.core.text.applyTo
import com.yumark.app.core.text.insertImageRef
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserAction
import com.yumark.app.core.webview.WebViewFailure
import com.yumark.app.domain.model.ExportFormat
import com.yumark.app.presentation.ai.AiAssistantHost
import com.yumark.app.presentation.common.SnackbarEffect
import com.yumark.app.presentation.common.displayName
import com.yumark.app.presentation.common.resolve
import com.yumark.app.presentation.common.resolveMessage
import com.yumark.app.presentation.common.resolveOrNull
import com.yumark.app.presentation.navigation.Screen
import com.yumark.app.presentation.sidebar.SidebarActions
import com.yumark.app.presentation.sidebar.SidebarFileTree
import com.yumark.app.presentation.sidebar.MoveToFolderDialog
import com.yumark.app.presentation.sidebar.selfAndDescendantFolderIds
import com.yumark.app.presentation.sidebar.WorkspaceFileTree
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppShapes
import com.yumark.app.presentation.theme.AppSpacing
import com.yumark.app.presentation.theme.extendedColors
import com.yumark.app.presentation.theme.toCssHex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    navController: NavController,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val document by viewModel.document.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val isPreviewMode by viewModel.isPreviewMode.collectAsStateWithLifecycle()
    val outline by viewModel.outline.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()
    val applyError by viewModel.applyError.collectAsStateWithLifecycle()
    val imageError by viewModel.imageError.collectAsStateWithLifecycle()
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()
    var showAiSheet by remember { mutableStateOf(false) }
    var showVersionHistory by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    // 查找/替换栏。预览模式没有可编辑文本，切过去就当它没展开（状态留着，切回来还在）
    var showFindBar by remember { mutableStateOf(false) }
    val versions by viewModel.versions.collectAsStateWithLifecycle()

    // 文本选择快捷 AI/Agent 功能
    var showQuickAiDialog by remember { mutableStateOf(false) }
    var selectedText by remember { mutableStateOf("") }
    // 编辑模式选区的半开区间 (start, end)，供 Agent 按精确区间替换；预览模式为 null
    var selectedRange by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // 点击「AI 助手」chip 时对选区的快照：点击会让 BasicTextField 失焦、选区 collapse，
    // onValueChange 随之清空 selectedText；若弹窗仍读 selectedText 则间歇性不弹。
    // 弹窗改读此快照，关闭时复位。
    var pendingQuickAiSelection by remember { mutableStateOf<Pair<String, Pair<Int, Int>?>?>(null) }

    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val outlineDrawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val editorFontSize by viewModel.editorFontSize.collectAsStateWithLifecycle()

    // 左侧文件树抽屉：顶栏按钮打开，点文档直接切换（替代旧的返回箭头）
    val fileDrawerState = rememberDrawerState(DrawerValue.Closed)
    var fileTreeExpanded by remember { mutableStateOf(setOf<String>()) }
    val folderTree by viewModel.folderTree.collectAsStateWithLifecycle()
    val editorWorkspace by viewModel.workspace.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()

    val findBarVisible = showFindBar && !isPreviewMode

    // 返回键处理优先级（从高到低）：
    // 1. 抽屉打开时先收抽屉
    BackHandler(enabled = fileDrawerState.isOpen) {
        scope.launch { fileDrawerState.close() }
    }

    // 2. 查找栏展开时先收查找栏（用条件互斥而非声明顺序来定优先级，与下面的退出处理保持同一套写法）
    BackHandler(enabled = !fileDrawerState.isOpen && findBarVisible) {
        showFindBar = false
    }

    // 3. 预览模式或编辑模式下返回键直接退出并保存（防抖避免连退两页）
    var isExiting by remember { mutableStateOf(false) }
    BackHandler(enabled = !fileDrawerState.isOpen && !findBarVisible) {
        if (!isExiting) {
            isExiting = true
            scope.launch {
                viewModel.saveAndWait()
                navController.navigateUp()
            }
        }
    }

    // 切换到侧栏点选的文档：先保存当前文档，再用新编辑器替换当前页（返回栈不增长）
    val openFromSidebar: (String) -> Unit = { route ->
        scope.launch {
            fileDrawerState.close()
            viewModel.saveAndWait()
            navController.navigate(route) {
                popUpTo(Screen.Editor.route) { inclusive = true }
            }
        }
    }

    // 预览区的底色/正文色直接取当前生效的 ColorScheme，不按主题 id 查表：主题列表里加一套配色、
    // 或者动态取色从壁纸算出一套编译期根本不存在的配色时，预览区都自动跟上。从前这里和
    // previewBgColor 各写着一张 `when (themeId)` 表，把 AppThemes 的深色背景抄了两遍——加主题
    // 时漏改哪张表都不报错也不崩，只是预览区继续用上一个主题的底色。
    val previewBg = MaterialTheme.colorScheme.background
    val previewFg = MaterialTheme.colorScheme.onBackground
    // 以实际生效的主题亮度判断深浅（兼容设置里手动选择的深色模式）
    val isDarkMode = previewBg.luminance() < 0.5f

    // AI 助手 BottomSheet
    if (showAiSheet) {
        AiAssistantHost(
            currentDocumentId = viewModel.currentDocumentId,
            currentDocumentName = document?.name,
            currentDocumentContent = document?.content,
            onNavigateToDocument = { docId ->
                scope.launch {
                    viewModel.saveAndWait()
                    navController.navigate(Screen.Editor.createRoute(docId)) {
                        popUpTo(Screen.Editor.route) { inclusive = true }
                    }
                }
            },
            onDocumentUpdated = {
                // AI 编辑完成后刷新当前文档
                viewModel.reloadDocumentFromRepository()
            },
            onDismiss = { showAiSheet = false }
        )
    }

    // 历史版本弹层（仅内部文档）
    if (showVersionHistory) {
        VersionHistorySheet(
            versions = versions,
            currentContent = document?.content.orEmpty(),
            onRestore = { viewModel.restoreVersion(it) },
            onDismiss = { showVersionHistory = false }
        )
    }

    // 导出格式收纳弹层（仅内部文档）
    if (showExportSheet) {
        ExportSheet(
            onExport = { format ->
                viewModel.exportAs(format)
                showExportSheet = false
            },
            onDismiss = { showExportSheet = false }
        )
    }

    // 文本选择快捷 AI/Agent 对话框：读点击时的选区快照，避免失焦清空导致间歇性不弹。
    val quickAiSelection = pendingQuickAiSelection
    if (showQuickAiDialog && quickAiSelection != null && quickAiSelection.first.isNotEmpty()) {
        AiQuickDialog(
            selectedText = quickAiSelection.first,
            onDismiss = {
                showQuickAiDialog = false
                pendingQuickAiSelection = null
            },
            onApplyEdit = { oldText, newText ->
                // Agent 模式下应用修改：编辑模式用精确选区，预览模式按原文匹配
                viewModel.replaceSelectedText(oldText, newText, quickAiSelection.second)
            },
            allowEditSelectedText = isPreviewMode,  // 预览模式下允许编辑选中文本
            documentName = document?.name,
            documentContent = document?.content   // 整篇文档作为「询问/处理」的上下文
        )
    }

    // 只在深色下注入：浅色模式 renderer.html 的 body 背景是模板写死的 #fff，注入等于白底刷白。
    val previewDarkColors = remember(previewBg, previewFg, isDarkMode) {
        if (!isDarkMode) null else previewBg.toCssHex() to previewFg.toCssHex()
    }

    // 渲染器就绪标记：JS 桥在页面脚本加载完后回调置位
    val rendererReady = remember { mutableStateOf(false) }

    // 主文档失败的可见错误态：非空时预览区换成错误卡片。
    // 类型是 UiMessage：产出侧 WebViewFailure 在 core，只带资源 id，解析在下面的组合期。
    // 绝不放 URL 或 error.description（预览加载的是应用私有路径）。
    val previewError = remember { mutableStateOf<UiMessage?>(null) }

    // 实例代号：渲染进程一旦消失，这个 WebView 就永久不可用（后续调用全是 no-op），
    // 唯一的恢复手段是换一个新实例。自增此值即触发下面的 remember 重建。
    var previewGeneration by remember { mutableIntStateOf(0) }

    // 下面那个 WebViewClient 要用的文案。它不能写成 `context.getString(...)`：
    // WebViewClient 建在 remember 里、活得比一次组合长，而 `context` 是组合当时的那一个，
    // 配置（语言/字号）变了它就是旧的，读出来的文案也是旧语言的。
    // rememberUpdatedState 把「取值」推迟到真正用的那一刻：组合期用 stringResource 正常解析，
    // 配置变化触发重组后 State 里换成新值，闭包里持有的是同一个 State 引用，于是自动跟上。
    // lint 的 LocalContextGetResourceValueCall 规则（error 级）拦的正是那种旧写法。
    val linkNoAppMessage = rememberUpdatedState(stringResource(R.string.editor_link_no_app))
    val sslBlockedMessage = rememberUpdatedState(stringResource(R.string.editor_preview_ssl_blocked))

    // 同一个道理，但用于「文案要到回调里才产生」的分支：ErrorHandler 分类结果是运行期才知道的
    // UiMessage，没法在组合期预解析成字符串，只能把 Resources 本体按上面的方式跟住配置变化，
    // 回调里再 resolveMessage(...)。取的是 LocalResources 而非 context.resources，理由同上。
    val resourcesState = rememberUpdatedState(LocalResources.current)

    // 上一次弹「证书被拦截」提示的时刻。一篇文档里十张坏证书图片会触发十次回调，
    // 不节流就是十条 Snackbar 排队；而彻底只提示一次又会让后续新插入的坏图彻底无声。
    val sslHintAtMs = remember { mutableStateOf(0L) }

    // WebView 单实例：整个编辑器生命周期只创建/加载一次，编辑/预览切换零成本
    val previewWebView = remember(previewGeneration) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                // 渲染管线不使用 localStorage/sessionStorage/indexedDB，关掉可去掉一层持久化面
                domStorageEnabled = false
                // 这两项预览必须保留：导入库图片解析成 file:///data/data/.../import_assets/…，
                // 外部工作区图片解析成 content://。改用 WebViewAssetLoader 才能收紧（Stage B）。
                allowFileAccess = true
                allowContentAccess = true
                // 预览页永不需要开新窗口；关掉可防内容侧 window.open 弹窗
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)

                // 启用缩放功能
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false  // 隐藏默认的+/-按钮
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            // 不透明背景：透明 WebView 会禁用分块光栅化/合成优化，长文档滚动时持续重绘导致卡顿。
            // 用与渲染页一致的纯色背景，既保持硬件加速滚动流畅，又避免深色模式进预览闪白。
            setBackgroundColor(previewBgColor(isDarkMode, previewBg))

            // 预览模式下外层 ModalNavigationDrawer 的水平拖拽手势会与 WebView 竞争触摸事件，
            // 导致垂直滚动需经 Compose 手势竞技场判定方向后才响应，表现为滚动卡顿/划不动。
            // 触摸落在 WebView 上时请求父级不拦截，让 WebView 独占滚动（大纲仍可由顶栏按钮打开）。
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        v.performClick()
                    }
                    android.view.MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }

            // 添加 JavaScript 接口用于文本选择监听
            //
            // 桥接口是 WebView 里唯一能回调进宿主的通道，一律按「不可信输入」处理：
            // 长度设上限，避免恶意/畸形文档把整篇内容灌进 Compose 状态或 AI 提示词。
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onTextSelected(text: String) {
                    val safe = if (text.length > MAX_BRIDGE_SELECTION_CHARS) {
                        text.take(MAX_BRIDGE_SELECTION_CHARS)
                    } else text
                    scope.launch {
                        if (safe.isBlank()) {
                            // 清空选中文本
                            selectedText = ""
                            selectedRange = null
                        } else if (aiEnabled) {
                            // 更新选中文本（预览选区来自渲染文本，无可靠源码区间，置 null 走原文匹配）
                            selectedText = safe
                            selectedRange = null
                        }
                    }
                }
            }, "AndroidSelection")

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onOutline(json: String) {
                    // 超长大纲直接丢弃：正常文档的大纲 JSON 远小于此阈值
                    if (json.length > MAX_BRIDGE_OUTLINE_CHARS) {
                        android.util.Log.w("WebView", "outline JSON too large (${json.length}), dropped")
                        return
                    }
                    viewModel.onOutlineReceived(json)
                }

                @JavascriptInterface
                fun onReady() {
                    // JS 桥回调在 WebView 线程，post 回主线程改 Compose 状态
                    post { rendererReady.value = true }
                }

                @JavascriptInterface
                fun resetZoom() {
                    // 在主线程恢复缩放
                    post {
                        // 重置viewport缩放
                        evaluateJavascript("""
                            (function() {
                                var meta = document.querySelector('meta[name="viewport"]');
                                if (!meta) {
                                    meta = document.createElement('meta');
                                    meta.name = 'viewport';
                                    document.head.appendChild(meta);
                                }
                                meta.content = 'width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=3.0, user-scalable=yes';
                            })();
                        """, null)

                        // 重置WebView缩放级别
                        setInitialScale(100)
                    }
                }
            }, "Android")

            addJavascriptInterface(object {
                @JavascriptInterface
                fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
                    // 在主线程通知父容器
                    post {
                        (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(disallow)
                    }
                }
            }, "AndroidTouch")

            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                    // console 消息可能带有文档正文（渲染错误会把片段写进异常消息），
                    // release 构建不写 logcat，避免文档内容外泄到系统日志。
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("WebView", consoleMessage.message())
                    }
                    return true
                }
            }

            // 处理链接点击：外部链接用系统浏览器打开，锚点链接在 WebView 内跳转
            webViewClient = object : android.webkit.WebViewClient() {

                /**
                 * 跳系统浏览器。没有浏览器（精简 ROM / 车机 / 企业管控设备上真实存在）时
                 * 原来只打一行 logcat，用户点了没反应且不知道为什么，这里必须给出反馈。
                 */
                private fun openExternal(url: String) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    } catch (e: android.content.ActivityNotFoundException) {
                        // 文案在组合期就解析好放进 State（见 linkNoAppMessage 的注释），
                        // 这里只读值，不碰 context
                        scope.launch {
                            snackbarHostState.showSnackbar(linkNoAppMessage.value)
                        }
                    } catch (e: Exception) {
                        // 其余形态（安全策略拦截、Intent 解析失败等）交给统一分类，同时留一笔非致命。
                        // ErrorHandler 在 core，只能给出带资源 id 的 UiMessage；这里进不了组合作用域，
                        // 用 resourcesState 里那份跟着配置更新的 Resources 解析（见其声明处注释）。
                        val message = ErrorHandler.report(e, UserAction.OPEN_LINK)
                        scope.launch {
                            snackbarHostState.showSnackbar(resourcesState.value.resolveMessage(message))
                        }
                    }
                }

                /** 只有主文档失败才该盖住整页；子资源（图片/字体）失败不能挡住正文。 */
                private fun isMainFrame(request: android.webkit.WebResourceRequest?): Boolean =
                    request?.isForMainFrame == true

                /** 失败详情只在 DEBUG 下进 logcat，且 URL 先过脱敏（可能带图床查询串里的 token）。 */
                private fun logFailure(tag: String, url: String?, detail: String?) {
                    if (!BuildConfig.DEBUG) return
                    android.util.Log.w(
                        "WebView",
                        "$tag: ${CrashLogFormatter.redact(url.orEmpty())} / ${CrashLogFormatter.redact(detail.orEmpty())}"
                    )
                }

                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()

                    // 外部链接（http/https）用系统浏览器打开
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        openExternal(url)
                        return true
                    }

                    // 锚点链接（以 # 开头）允许 WebView 内部处理
                    if (url.contains("#")) {
                        return false
                    }

                    // 其他所有链接都拦截，防止加载到错误页面
                    return true
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, url: String): Boolean {
                    // 兼容旧版 Android
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        openExternal(url)
                        return true
                    }

                    // 锚点链接允许 WebView 内部处理
                    if (url.contains("#")) {
                        return false
                    }

                    // 其他所有链接都拦截
                    return true
                }

                override fun onReceivedError(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    // 不调用 super：系统那张英文错误页会顶掉整个渲染容器，之后连重试都没有入口
                    logFailure("onReceivedError", request?.url?.toString(), error?.description?.toString())
                    // 子资源失败（图片、字体）不算文档加载失败，正文照常显示
                    if (!isMainFrame(request)) return
                    previewError.value = WebViewFailure.loadMessage(
                        error?.errorCode ?: WebViewFailure.ERROR_UNKNOWN
                    )
                }

                override fun onReceivedHttpError(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    logFailure("onReceivedHttpError", request?.url?.toString(), errorResponse?.statusCode?.toString())
                    if (!isMainFrame(request)) return
                    previewError.value = WebViewFailure.httpMessage(errorResponse?.statusCode ?: 0)
                }

                override fun onReceivedSslError(
                    view: android.webkit.WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    // 显式取消，把「证书有问题就中止」这条默认行为钉死在代码里：
                    // 任何时候都不允许改成 proceed()，否则预览里的远端图片会变成中间人的注入点。
                    logFailure("onReceivedSslError", error?.url, error?.primaryError?.toString())
                    handler?.cancel()

                    // 拦截本身是对的，但不能悄悄拦：预览的主文档是本地内容，走到这里的几乎
                    // 只有远端图片这类子资源，取消后页面上就是一个空白占位。用户看不到任何
                    // 说明，只会以为是本应用渲染坏了（企业代理签发自签证书时会整篇图片全没）。
                    // 用 Snackbar 而不是 previewError：子资源失败不该盖住已经渲染好的正文。
                    val now = System.currentTimeMillis()
                    if (now - sslHintAtMs.value < SSL_HINT_INTERVAL_MS) return
                    sslHintAtMs.value = now
                    scope.launch { snackbarHostState.showSnackbar(sslBlockedMessage.value) }
                }

                override fun onRenderProcessGone(
                    view: android.webkit.WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    // 返回 false（默认）会让框架直接杀掉整个应用进程：低端机上系统回收渲染器
                    // 属于常态，表现出来就是「编辑器莫名闪退且未保存内容全丢」。这里必须接住。
                    val didCrash = detail?.didCrash() == true
                    rendererReady.value = false
                    previewError.value = WebViewFailure.renderProcessMessage(didCrash)
                    // 这个实例已永久不可用，真正的恢复在错误卡片的「重试」里换新实例
                    return true
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(
                    view: android.webkit.WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    // minSdk 26 下框架只会走上面的三参版本；这里保持只记日志，
                    // 万一某个厂商内核为子资源回调旧签名，也不会误盖一张全屏错误卡片。
                    logFailure("onReceivedError(legacy)", failingUrl, description)
                }

                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 设置viewport：最小100%，最大300%
                    view?.evaluateJavascript("""
                        (function() {
                            var meta = document.querySelector('meta[name="viewport"]');
                            if (!meta) {
                                meta = document.createElement('meta');
                                meta.name = 'viewport';
                                document.head.appendChild(meta);
                            }
                            meta.content = 'width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=3.0, user-scalable=yes';
                        })();
                    """, null)
                    // 注入文本选择监听脚本
                    view?.evaluateJavascript(textSelectionListenerJs(), null)
                }
            }

            val template = context.assets.open("templates/renderer.html")
                .bufferedReader().use { it.readText() }
            loadDataWithBaseURL("file:///android_asset/", template, "text/html", "UTF-8", null)
        }
    }

    // 键在实例上而不是 Unit：重试换新实例时旧实例必须在这里销毁，否则每次重试泄漏一个 WebView
    DisposableEffect(previewWebView) {
        onDispose {
            // 移除全部三个 JS interface，避免 WebView 销毁后仍持有指向宿主的接口引用。
            previewWebView.removeJavascriptInterface("Android")
            previewWebView.removeJavascriptInterface("AndroidSelection")
            previewWebView.removeJavascriptInterface("AndroidTouch")
            previewWebView.destroy()
        }
    }

    // 就绪看门狗：模板脚本没跑起来时 onReady 永远不到，页面会无声空白。
    // 超时给一个可见错误 + 重试入口，与离屏导出的 READY_TIMEOUT_MS 取同一档。
    LaunchedEffect(previewGeneration) {
        delay(PREVIEW_READY_TIMEOUT_MS)
        if (!rendererReady.value && previewError.value == null) {
            previewError.value = WebViewFailure.NOT_READY
        }
    }

    // 深色模式覆盖样式：就绪即注入，无延迟
    LaunchedEffect(rendererReady.value, previewDarkColors) {
        if (rendererReady.value && previewDarkColors != null) {
            val (bg, fg) = previewDarkColors
            previewWebView.evaluateJavascript(darkStyleJs(bg, fg), null)
        }
    }

    // 相对路径图片解析基址（导入库/外部工作区文档）：就绪即注入，渲染前后到达均生效
    val imageResolver by viewModel.imageResolver.collectAsStateWithLifecycle()
    LaunchedEffect(rendererReady.value, imageResolver) {
        if (rendererReady.value && imageResolver != null) {
            val json = kotlinx.serialization.json.Json.encodeToString(
                ImageResolverConfig.serializer(), imageResolver!!
            )
            previewWebView.evaluateJavascript("window.setImageResolver($json)", null)
        }
    }

    // WebView 单实例创建时只取了初始背景色，主题/深浅切换后同步更新原生背景，保持与渲染页一致
    LaunchedEffect(isDarkMode, previewBg) {
        previewWebView.setBackgroundColor(previewBgColor(isDarkMode, previewBg))
    }

    // 预览字号跟随设置
    LaunchedEffect(rendererReady.value, editorFontSize) {
        if (rendererReady.value) {
            previewWebView.evaluateJavascript(
                "document.body.style.fontSize='${editorFontSize}px';", null
            )
        }
    }

    // 退出预览时收起大纲抽屉
    LaunchedEffect(isPreviewMode) {
        if (!isPreviewMode && outlineDrawerState.isOpen) {
            outlineDrawerState.close()
        }
    }

    // 保存失败 Snackbar（编辑内容保留在内存，不打断编辑）
    // 文案先在组合期解析：LaunchedEffect 的 block 不是 @Composable，里面调不了 stringResource。
    val saveErrorText = saveError.resolveOrNull()
    SnackbarEffect(saveErrorText, snackbarHostState) { viewModel.clearSaveError() }

    // Agent 应用修改失败 Snackbar（无法在原文中定位选中文本）
    val applyErrorText = applyError.resolveOrNull()
    SnackbarEffect(applyErrorText, snackbarHostState) { viewModel.clearApplyError() }

    // 插图失败 Snackbar（读不到相册图 / 落盘失败 / 解码失败）。
    // 与保存失败同一套模板：文案在组合期解析，LaunchedEffect 里调不了 stringResource。
    val imageErrorText = imageError.resolveOrNull()
    SnackbarEffect(imageErrorText, snackbarHostState) { viewModel.clearImageError() }

    // 相册选图。用 PickVisualMedia 而不是 GetContent/OpenDocument：
    // 系统照片选择器不需要任何存储权限（Android 13 以下由 Play 服务回填），
    // 返回的 uri 只带一次性读权限，正好够 ImageRepository 立刻复制进应用私有目录。
    // 只收一张：插入点是当前光标，一次插多张的顺序与光标推进没有直观语义。
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.insertLocalImage(it) } }

    // 导出成功 → 系统分享（mime 按导出文件实际格式）
    val exportedFile by viewModel.exportedFile.collectAsStateWithLifecycle()
    LaunchedEffect(exportedFile) {
        exportedFile?.let { file ->
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                // 别再按 text/plain 兜底：PDF / DOCX / PNG 谎称纯文本发出去，接收方的选择器
                // 只列纯文本类目标（图库、PDF 阅读器根本不出现），到对面也是打不开的附件。
                type = ExportFormat.mimeForExtension(file.extension)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null))
            viewModel.clearExportedFile()
        }
    }

    // 侧边栏的对话框状态
    var showCreateDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showSubfolderDialog by remember { mutableStateOf<String?>(null) }
    var folderToRename by remember { mutableStateOf<Pair<String, String>?>(null) }
    var folderToDelete by remember { mutableStateOf<String?>(null) }
    var documentToRename by remember { mutableStateOf<com.yumark.app.domain.model.Document?>(null) }
    var documentToDelete by remember { mutableStateOf<com.yumark.app.domain.model.Document?>(null) }
    var documentToMove by remember { mutableStateOf<com.yumark.app.domain.model.Document?>(null) }
    var folderToMove by remember { mutableStateOf<String?>(null) }
    var showImportMenu by remember { mutableStateOf(false) }

    // 图片来源选择对话框。开关放在这里（组合的常驻位置）而不是编辑分支里：
    // 对话框要盖在整个屏幕上，且它触发的相册回调是顶层的 pickImage。
    var showImageSourceDialog by remember { mutableStateOf(false) }
    // 「手动输入链接」被选中的一次性信号。不能在这里直接改 editValue——
    // editValue 只存在于 Success 分支内（见那里的 collect），所以用一个 Boolean
    // 当信箱，由分支里的 effect 取走并复位。
    var pendingManualImage by remember { mutableStateOf(false) }

    // 「导入到库」与文件列表页共用同一份实现：这个函数自带对话框、进度提示和结果 Snackbar，
    // 必须在这里（组合的常驻位置）调用，不能塞进抽屉的 drawerContent —— 抽屉一关，
    // 正在复制的进度对话框和随后的结果提示会跟着组合一起消失。
    val importFlow = com.yumark.app.presentation.filelist.rememberImportFlow(
        folders = folders,
        snackbarHostState = snackbarHostState
    )

    // 外层左侧文件树抽屉（LTR）；内层沿用 RTL 包裹的右侧大纲抽屉
    ModalNavigationDrawer(
        drawerState = fileDrawerState,
        // 仅打开时允许手势（滑动关闭），关闭时不抢编辑区/WebView 的水平手势
        gesturesEnabled = fileDrawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RoundedCornerShape(topEnd = AppShapes.Large, bottomEnd = AppShapes.Large)
            ) {
                EditorSidebarContent(
                    isExternal = viewModel.isExternal,
                    workspace = editorWorkspace,
                    folderTree = folderTree,
                    currentDocumentId = viewModel.currentDocumentId,
                    currentDocUri = viewModel.currentDocUri,
                    expandedFolders = fileTreeExpanded,
                    onToggleFolder = { key ->
                        fileTreeExpanded = if (key in fileTreeExpanded) fileTreeExpanded - key
                        else fileTreeExpanded + key
                    },
                    onEnsureFoldersExpanded = { ids ->
                        fileTreeExpanded = fileTreeExpanded + ids
                    },
                    onOpenInternal = { id -> openFromSidebar(Screen.Editor.createRoute(id)) },
                    onOpenExternal = { uri -> openFromSidebar(Screen.Editor.createExternalRoute(uri)) },
                    onCloseDrawer = { scope.launch { fileDrawerState.close() } },
                    fileDrawerState = fileDrawerState,
                    showImportMenu = showImportMenu,
                    onShowImportMenu = { showImportMenu = true },
                    onDismissImportMenu = { showImportMenu = false },
                    onImportFile = importFlow.pickFiles,
                    onImportFolder = importFlow.pickFolder,
                    onShowFolderDialog = { showFolderDialog = true },
                    onShowCreateDialog = { folderId ->
                        viewModel.selectFolderForNewDoc(folderId)
                        showCreateDialog = true
                    },
                    onShowSubfolderDialog = { showSubfolderDialog = it },
                    onRenameFolder = { folderId, name -> folderToRename = folderId to name },
                    onDeleteFolder = { folderToDelete = it },
                    onRenameDocument = { documentToRename = it },
                    onDeleteDocument = { documentToDelete = it },
                    onMoveDocument = { documentToMove = it },
                    onMoveFolder = { folderToMove = it },
                    onMoveDocumentTo = { id, target -> viewModel.moveDocument(id, target) },
                    onMoveFolderTo = { id, target -> viewModel.moveFolder(id, target) }
                )
            }
        }
    ) {
        // 用 RTL 包裹实现右侧大纲抽屉（内容区恢复 LTR）
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = outlineDrawerState,
            gesturesEnabled = isPreviewMode,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ModalDrawerSheet(
                        drawerShape = RoundedCornerShape(topStart = AppShapes.Large, bottomStart = AppShapes.Large)
                    ) {
                        OutlinePanel(
                            outline = outline,
                            onItemClick = { item ->
                                // 参数化传参，避免 anchorId 含单引号/反斜杠时拼进 JS 字符串造成注入。
                                previewWebView.evaluateJavascript(
                                    "(function(id){ if(window.scrollToHeading) window.scrollToHeading(id); })(\"${item.anchorId.replace("\\", "\\\\").replace("\"", "\\\"")}\")",
                                    null
                                )
                                scope.launch { outlineDrawerState.close() }
                            }
                        )
                    }
                }
            }
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    document?.name ?: stringResource(R.string.editor),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = {
                                // 打开文件树侧栏（退出编辑器走系统返回手势/键）
                                IconButton(onClick = { scope.launch { fileDrawerState.open() } }) {
                                    Icon(Icons.Default.Menu, stringResource(R.string.toggle_sidebar))
                                }
                            },
                            actions = {
                                // 大纲（仅预览模式）
                                if (isPreviewMode) {
                                    IconButton(onClick = { scope.launch { outlineDrawerState.open() } }) {
                                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, stringResource(R.string.outline))
                                    }
                                }

                                // AI 助手（仅启用时显示）
                                if (aiEnabled) {
                                    IconButton(onClick = { showAiSheet = true }) {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            stringResource(R.string.editor_ai_assistant)
                                        )
                                    }
                                }

                                // 编辑/预览切换（核心功能，始终可见）
                                IconButton(onClick = { viewModel.togglePreviewMode() }) {
                                    Icon(
                                        if (isPreviewMode) Icons.Default.Edit else Icons.Default.Visibility,
                                        if (isPreviewMode) stringResource(R.string.edit_mode) else stringResource(R.string.preview_mode)
                                    )
                                }

                                // 更多菜单（收纳保存、导出、设置）
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.cd_more_options))
                                }

                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    // 保存
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.save)) },
                                        onClick = { viewModel.saveDocument(); showMenu = false },
                                        leadingIcon = {
                                            if (isSaving) {
                                                CircularProgressIndicator(modifier = Modifier.size(AppIconSize.Large), strokeWidth = EditorMetrics.SaveSpinnerStroke)
                                            } else {
                                                Icon(Icons.Default.Save, null)
                                            }
                                        },
                                        enabled = !isSaving
                                    )

                                    // 导出（仅内部文档）：收纳到一个入口，点开弹出格式选择
                                    if (!viewModel.isExternal) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.export)) },
                                            onClick = { showExportSheet = true; showMenu = false },
                                            leadingIcon = { Icon(Icons.Default.Download, null) }
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.history_versions)) },
                                            onClick = { showVersionHistory = true; showMenu = false },
                                            leadingIcon = { Icon(Icons.Default.History, null) }
                                        )
                                    }

                                    // 设置
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.settings)) },
                                        onClick = { navController.navigate(Screen.Settings.route); showMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Settings, null) }
                                    )
                                }
                            }
                        )
                    }
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        when (val state = uiState) {
                            is EditorUiState.Loading -> {
                                // 延迟显示加载指示器：快速加载（绝大多数情况）不闪 spinner，内容直接出现；
                                // 仅当加载确实较慢（>200ms）才显示，消除「先 loading 再显示」的不适感。
                                var showSpinner by remember { mutableStateOf(false) }
                                LaunchedEffect(Unit) {
                                    delay(200)
                                    showSpinner = true
                                }
                                if (showSpinner) {
                                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                                }
                            }
                            is EditorUiState.Success -> {
                                // 光标/选区起点单独存进 savedInstanceState。
                                //
                                // editValue 是普通 remember，转屏（或任何 Activity 重建）会让整份组合
                                // 从头来过，选区于是回到 0——长文档里的表现就是「转个屏光标跳回开头」，
                                // 用户刚才在写第 800 行，回来得重新滚下去找。
                                //
                                // 只存这一个 Int，不把 editValue 整体做成 rememberSaveable：
                                // savedInstanceState 要过 Binder，几百 KB 的正文塞进去就是在赌
                                // TransactionTooLargeException（而这是**转屏**路径，崩在这里等于文档打不开）。
                                // 正文本来也没有丢的风险：每次输入都镜像进了 VM 的 _document，
                                // VM 又在 onCleared() 里经应用级 appScope 落盘。
                                var savedCaret by rememberSaveable { mutableStateOf(0) }
                                // 本地编辑状态（含光标/选区），避免依赖异步的 document 状态
                                var editValue by remember {
                                    val loaded = document?.content ?: ""
                                    mutableStateOf(
                                        TextFieldValue(
                                            loaded,
                                            TextRange(savedCaret.coerceIn(0, loaded.length))
                                        )
                                    )
                                }
                                // 光标一动就镜像回 savedCaret。snapshotFlow 自带合流去重，
                                // 连续移动光标不会每帧都写一次。
                                LaunchedEffect(Unit) {
                                    snapshotFlow { editValue.selection.start }
                                        .collect { savedCaret = it }
                                }
                                // 记录上次从文档加载的内容，用于检测 AI 编辑后的热更新
                                var lastLoadedContent by remember { mutableStateOf(document?.content ?: "") }

                                // 保存滚动状态，在预览和编辑模式间切换时保持位置
                                val scrollState = rememberScrollState()

                                // 撤销/重做栈。刻意用 remember(docKey) 而不是 LaunchedEffect(docKey) + reset()：
                                // 切文档时直接换一个全新的栈，不留「effect 还没跑到、用户已经打了一个字」的时序缝隙
                                // ——那一下会把上一篇文档压进历史，撤销出完全不相干的正文。
                                val docKey = document?.id ?: viewModel.currentDocUri ?: ""
                                val history = remember(docKey) {
                                    EditorHistory().also { it.reset(TextFieldValue(document?.content ?: "")) }
                                }

                                // 查找定位后要把匹配滚进可见区域。这里的 BasicTextField 自己不滚（外层套了
                                // verticalScroll），指望不上它内建的选区跟随，只能自己按 TextLayoutResult 算 Y 坐标。
                                var editorLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

                                /**
                                 * 待滚动到的字符偏移。查找定位/替换/撤销都只往这里写一个数字，
                                 * 真正的滚动交给下面那个 effect。
                                 *
                                 * 为什么必须隔一层：这些操作都是**先改文本、再要求滚动**，而
                                 * [editorLayout] 要等下一次 `onTextLayout` 才更新。在同一帧里直接
                                 * 拿旧布局去算新文本的坐标，`getBoundingBox` 会因 offset 超出旧布局的
                                 * 合法区间抛 IllegalArgumentException——主线程未捕获，进程直接被杀。
                                 * 「全部替换」把 3 字正文换成 5 字、或撤销一次大段删除，都能稳定复现。
                                 */
                                var pendingScrollTarget by remember { mutableStateOf<Int?>(null) }

                                /** 请求把 [offset] 处的文本滚进可见区域（实际滚动等布局刷新后进行）。 */
                                val scrollToOffset: (Int) -> Unit = { offset ->
                                    pendingScrollTarget = offset
                                }

                                // 布局一刷新就把待滚动请求消费掉。
                                // 判据是「布局测量的那份文本 == 当前文本」而不是长度或非空：
                                // 只有两者一致时，布局里的行高与换行才是这份文本的，算出来的 Y 坐标才对得上。
                                // 不一致就什么都不做，等下一次 onTextLayout 把 effect 重新拉起来。
                                LaunchedEffect(editorLayout, pendingScrollTarget) {
                                    val layout = editorLayout ?: return@LaunchedEffect
                                    val target = pendingScrollTarget ?: return@LaunchedEffect
                                    val laidOut = layout.layoutInput.text.text
                                    if (laidOut != editValue.text) return@LaunchedEffect
                                    if (laidOut.isNotEmpty()) {
                                        // getBoundingBox 要求 offset 落在 [0, length)，末尾光标会越界。
                                        // 上界取自 laidOut 而不是 editValue：钳的必须是布局自己的区间。
                                        val safe = target.coerceIn(0, laidOut.length - 1)
                                        val top = layout.getBoundingBox(safe).top.toInt()
                                        // 减一段留白，避免匹配紧贴顶栏
                                        scrollState.scrollTo((top - 120).coerceIn(0, scrollState.maxValue))
                                    }
                                    pendingScrollTarget = null
                                }

                                /** 撤销/重做的公共写回。null = 无可撤销/重做，必须原样不动（写空文本等于清空文档）。 */
                                val applyHistory: (TextFieldValue?) -> Unit = { restored ->
                                    if (restored != null) {
                                        editValue = restored
                                        viewModel.onContentChanged(restored.text)
                                        scrollToOffset(restored.selection.start)
                                    }
                                }

                                // 持续保存编辑器滚动位置（防抖避免频繁更新）
                                LaunchedEffect(Unit) {
                                    snapshotFlow { scrollState.value }
                                        .debounce(200)
                                        .collect { position ->
                                            viewModel.saveEditScrollPosition(position)
                                        }
                                }

                                // 预览模式持续保存滚动比例（镜像上面的编辑器持续保存）。
                                // onDispose 里的保存会与 WebView 销毁竞速、常常存不进；这里周期性落库，
                                // 保证导航离开前 VM 里已有最新比例可供返回时恢复。
                                LaunchedEffect(isPreviewMode, rendererReady.value) {
                                    if (isPreviewMode && rendererReady.value) {
                                        while (true) {
                                            delay(350)
                                            if (previewWebView.contentHeight > 0) {
                                                previewWebView.evaluateJavascript("window.getScrollRatio()") { result ->
                                                    result?.trim('"')?.toFloatOrNull()?.let {
                                                        viewModel.savePreviewScrollRatio(it)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 这里从前还有一个 DisposableEffect(Unit)，在 onDispose 里
                                // previewWebView.post { evaluateJavascript("window.getScrollRatio()") }
                                // 想「最后再存一次滚动比例」。它不可能成功：post 出去的 runnable
                                // 一定跑在本次组合拆解之后，而拆解里另一个 DisposableEffect 已经
                                // destroy() 了这个 WebView——JS 不会再执行。真正的害处是它那句
                                // `?: 0f`：回调带着 null 回来时会把「0」当成有效比例存进 VM，
                                // 把上面周期保存好的位置抹掉，下次进来直接回到顶部。
                                // 上面那个 350ms 周期保存已经覆盖了「离开前存最新值」，直接删掉。

                                // 从设置返回后恢复滚动位置。
                                // 只按 document?.id 触发（重进 composition 即重跑一次）；不再带 isPreviewMode，
                                // 避免与下方「模式切换同步」effect 在每次切换时互相打架。
                                val savedScrollState by viewModel.scrollState.collectAsStateWithLifecycle()
                                LaunchedEffect(document?.id) {
                                    // 先把目标位置抓进局部变量：此后编辑/预览的「持续保存」会把 VM 里的值
                                    // 刷成新视图的 0，但恢复用的是这里抓到的旧值，不受影响。
                                    val targetRatio = savedScrollState.previewScrollRatio
                                    val targetPos = savedScrollState.editScrollPosition

                                    if (isPreviewMode && targetRatio > 0f) {
                                        // 等渲染器就绪且内容已有高度，再按比例定位（布局完成前滚动会被钳到顶部）
                                        var attempts = 0
                                        while ((!rendererReady.value || previewWebView.contentHeight == 0) && attempts < 50) {
                                            delay(40)
                                            attempts++
                                        }
                                        delay(80)  // 留一帧让布局稳定
                                        previewWebView.evaluateJavascript(
                                            "window.scrollToRatio($targetRatio)", null
                                        )
                                    } else if (!isPreviewMode && targetPos > 0) {
                                        // 等编辑器内容布局完成（maxValue 就绪）再滚动，否则会被钳到 0
                                        var attempts = 0
                                        while (scrollState.maxValue == 0 && attempts < 50) {
                                            delay(40)
                                            attempts++
                                        }
                                        scrollState.scrollTo(targetPos)
                                    }
                                }

                                // 监听文档内容变化（AI 编辑后的热更新）
                                LaunchedEffect(document?.id, document?.content) {
                                    document?.content?.let { content ->
                                        // 如果文档内容变化了（AI 编辑），并且用户没有本地未保存的修改
                                        if (content != lastLoadedContent && content != editValue.text) {
                                            val hot = TextFieldValue(content)
                                            editValue = hot
                                            lastLoadedContent = content
                                            // 整篇被外部换掉（AI 改写、版本恢复）：旧历史套在新正文上会撤出不相干的内容，
                                            // 按 UndoRedoStack 的契约直接清空重来。注意本 effect 每次输入都会重跑
                                            // （key 里带 content），reset 必须留在这个 if 里，否则每打一个字就清一次历史。
                                            history.reset(hot)
                                        }
                                    }
                                }

                                // 「正文被整体换掉」的显式事件（版本恢复 / AI 划词替换 / Agent 改写后重读）。
                                //
                                // 上面那条 effect 是兜底，它有一个绕不过去的盲区：比对成立才覆写，
                                // 而**恢复到与当前内容完全相同的一版**（改了几个字又撤回、或恢复打开时那一版）
                                // 比对结果是「没变化」，文件已被写成旧版、编辑框还是新版，用户下一次敲键
                                // 就把恢复覆盖回去——界面上看是「点恢复没反应」。
                                //
                                // 事件流没有当前值，收到即无条件覆写，天然没有这个盲区。key 用 Unit：
                                // 收集必须跨内容变化一直活着，keyed 到 document 上会在每次改动时重启收集，
                                // 重启窗口里发出的事件（SharedFlow 无重放）就永久丢了。
                                LaunchedEffect(Unit) {
                                    viewModel.contentReplaced.collect { content ->
                                        val hot = TextFieldValue(content)
                                        editValue = hot
                                        lastLoadedContent = content
                                        history.reset(hot)
                                    }
                                }

                                // 语法插入的唯一落点。抽成局部函数是因为有三个调用方：
                                // 工具栏按钮、图片来源对话框的「手动输入链接」、相册回写。
                                // 插入规则（行首前缀 / 行内包裹 / 独占块、光标落哪）全在
                                // core/text 里，是纯函数、有单测；这里只做 Compose 类型
                                // 与 TextSnapshot 之间的来回转换。
                                //
                                // 声明在 if (isPreviewMode) 之外：editValue / history 只存在于
                                // 本分支，而调用点在下面的 else 分支里，放进 else 就只有它自己能用。
                                fun applyInsert(next: TextFieldValue) {
                                    editValue = next
                                    // forceBoundary：插入语法是一次语义操作。不强制开新单元的话，
                                    // 它会被合并窗口并进用户刚才的连续打字，撤销时连正文一起消失。
                                    history.record(next, forceBoundary = true)
                                    viewModel.onContentChanged(next.text)
                                }

                                val insertSyntax: (MarkdownAction) -> Unit = { action ->
                                    applyInsert(action.applyTo(editValue.toSnapshot()).toTextFieldValue())
                                }

                                // 相册选图落盘成功后，把 images/<uuid>.<ext> 写回光标处。
                                // 走事件流而不是「状态 + consume()」：同一张图连插两次必须触发两次，
                                // 而带值的状态第二次赋同一个值不会重组。key 用 Unit，理由同上面那条。
                                LaunchedEffect(Unit) {
                                    viewModel.insertedImagePath.collect { path ->
                                        applyInsert(
                                            insertImageRef(editValue.toSnapshot(), path).toTextFieldValue()
                                        )
                                    }
                                }

                                // 对话框选了「手动输入链接」：对话框在顶层组合里，够不到 editValue，
                                // 用一个 Boolean 当信箱，这里取走并立刻复位。
                                LaunchedEffect(pendingManualImage) {
                                    if (pendingManualImage) {
                                        pendingManualImage = false
                                        insertSyntax(MarkdownAction.IMAGE)
                                    }
                                }

                                // 就绪即渲染；内容变化时重渲染（守卫避免重复渲染同一内容）
                                //
                                // 刻意**不**加 `editValue.text.isNotEmpty()` 守卫：全选删除后正文是空串，
                                // 被守卫挡住就不下发渲染，WebView 里仍是删除前的整篇内容，切回预览看到的是
                                // 一篇「已经删掉的文档」，用户会以为删除没生效（输入任意字符才自愈）。
                                // 空串本身就是合法的渲染目标——渲染出来的空预览才是文档的真实状态。
                                // 首帧（文档还没加载完，text 为空、lastRendered 为 null）会多渲染一次空内容，
                                // 那是个视觉上的 no-op：WebView 本来就是空的。
                                var lastRendered by remember { mutableStateOf<String?>(null) }
                                LaunchedEffect(isPreviewMode, rendererReady.value, editValue.text) {
                                    if (isPreviewMode && rendererReady.value && editValue.text != lastRendered) {
                                        val encoded = android.util.Base64.encodeToString(
                                            editValue.text.toByteArray(Charsets.UTF_8),
                                            android.util.Base64.NO_WRAP
                                        )
                                        previewWebView.evaluateJavascript(renderJs(encoded), null)
                                        lastRendered = editValue.text
                                    }
                                }

                                // 切换模式时同步滚动位置：统一用 JS 内的 CSS px 比例
                                // (renderer 的 getScrollRatio/scrollToRatio)，避免 contentHeight(CSS px)
                                // 与 WebView 物理 px 混算——后者在高密度屏会算成 0 而跳到顶部。
                                LaunchedEffect(isPreviewMode) {
                                    if (isPreviewMode) {
                                        // 切到预览：等内容渲染就绪后，按编辑器滚动比例定位 WebView
                                        kotlinx.coroutines.delay(180)
                                        var attempts = 0
                                        while (previewWebView.contentHeight == 0 && attempts < 20) {
                                            kotlinx.coroutines.delay(40)
                                            attempts++
                                        }
                                        val ratio = if (scrollState.maxValue > 0) {
                                            scrollState.value.toFloat() / scrollState.maxValue.toFloat()
                                        } else 0f
                                        previewWebView.evaluateJavascript(
                                            "window.scrollToRatio($ratio)", null
                                        )
                                    } else {
                                        // 切到编辑：异步读回 WebView 当前比例（此时 WebView 仍存活），再定位编辑器
                                        previewWebView.evaluateJavascript("window.getScrollRatio()") { result ->
                                            // 读不出比例就什么都不做：从前这里 `?: 0f`，一次失败的读取
                                            // 会被当成「比例 0」，把编辑器滚回顶部——比停在原地更糟。
                                            val ratio = result?.trim('"')?.toFloatOrNull()
                                            // 验证 scrollState 仍有效（避免 WebView 销毁后回调执行导致崩溃）
                                            if (ratio != null && scrollState.maxValue >= 0) {
                                                scope.launch {
                                                    kotlinx.coroutines.delay(160)
                                                    if (scrollState.maxValue > 0) {
                                                        val target = (scrollState.maxValue * ratio).toInt()
                                                            .coerceIn(0, scrollState.maxValue)
                                                        scrollState.scrollTo(target)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (isPreviewMode) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        val previewFailure = previewError.value
                                        if (previewFailure != null) {
                                            // 主文档失败/渲染进程消失：整块换成错误卡片。
                                            // 空白的 WebView 会让用户以为文档没了，而不是加载失败。
                                            PreviewFailureCard(
                                                message = previewFailure,
                                                onRetry = {
                                                    // 顺序要紧：lastRendered 不清的话，新实例就绪后
                                                    // 渲染守卫会判定"内容没变"而跳过渲染，页面照旧留白。
                                                    previewError.value = null
                                                    rendererReady.value = false
                                                    lastRendered = null
                                                    previewGeneration++
                                                },
                                                modifier = Modifier.weight(1f).fillMaxWidth()
                                            )
                                        } else {
                                            AndroidView(
                                                factory = {
                                                    // 复用单实例：attach 前先脱离旧 parent
                                                    (previewWebView.parent as? ViewGroup)?.removeView(previewWebView)
                                                    previewWebView
                                                },
                                                update = { },
                                                modifier = Modifier.weight(1f).fillMaxWidth()
                                            )
                                        }

                                        // 预览模式下的 AI 按钮（仅当有选中文本且 AI 启用时显示）
                                        if (aiEnabled && selectedText.isNotBlank()) {
                                            Surface(
                                                tonalElevation = EditorMetrics.AiBarTonalElevation,
                                                shadowElevation = EditorMetrics.AiBarShadowElevation
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = AppSpacing.Cozy, vertical = AppSpacing.Default),
                                                    horizontalArrangement = Arrangement.Center,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // AI 助手按钮 - 统一入口
                                                    SuggestionChip(
                                                        onClick = {
                                                            pendingQuickAiSelection = selectedText to selectedRange
                                                            showQuickAiDialog = true
                                                        },
                                                        label = { Text(stringResource(R.string.editor_ai_assistant)) },
                                                        icon = { Text("✨") }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    // 编辑模式：Typora 式无边框书写区
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        MarkdownToolbar(
                                            onAction = { action ->
                                                // 图片按钮是唯一有两条去路的：能落盘的文档先问
                                                // 来源（相册 / 手填），外部 SAF 文档没有 Room 行、
                                                // images.document_id 这个外键挂不上，直接走手填。
                                                if (action == MarkdownAction.IMAGE &&
                                                    viewModel.canInsertLocalImage
                                                ) {
                                                    showImageSourceDialog = true
                                                } else {
                                                    insertSyntax(action)
                                                }
                                            },
                                            canUndo = history.canUndo,
                                            canRedo = history.canRedo,
                                            onUndo = { applyHistory(history.undo()) },
                                            onRedo = { applyHistory(history.redo()) },
                                            onToggleFindReplace = { showFindBar = !showFindBar },
                                            findReplaceActive = findBarVisible
                                        )

                                        if (findBarVisible) {
                                            FindReplaceBar(
                                                value = editValue,
                                                onSelect = { range ->
                                                    val located = TextFieldValue(editValue.text, range)
                                                    editValue = located
                                                    // 文本没变，引擎走「纯光标移动」分支：不压栈、不清 redo，
                                                    // 所以定位也可以放心 record，顺手把快照里的选区跟上
                                                    history.record(located)
                                                    scrollToOffset(range.start)
                                                },
                                                onReplace = { replaced ->
                                                    editValue = replaced
                                                    // 查找替换是一次原子操作，必须能被一次撤销整体收回
                                                    history.record(replaced, forceBoundary = true)
                                                    viewModel.onContentChanged(replaced.text)
                                                    scrollToOffset(replaced.selection.start)
                                                },
                                                onMessage = { message ->
                                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                                },
                                                onClose = { showFindBar = false }
                                            )
                                        }

                                        BasicTextField(
                                            value = editValue,
                                            onValueChange = { newValue ->
                                                editValue = newValue
                                                // 每次变更都记录。纯移光标（文本未变）时引擎只更新选区，
                                                // 既不压栈也不清 redo，所以这里不需要自己判断是不是真的改了字
                                                history.record(newValue)
                                                viewModel.onContentChanged(newValue.text)

                                                // 检测文本选择（记录精确区间供 Agent 替换；折叠时清空避免 chip 带过期文本）
                                                if (aiEnabled) {
                                                    val sel = newValue.selection
                                                    if (!sel.collapsed) {
                                                        val s = minOf(sel.start, sel.end).coerceIn(0, newValue.text.length)
                                                        val e = maxOf(sel.start, sel.end).coerceIn(0, newValue.text.length)
                                                        val selected = newValue.text.substring(s, e)
                                                        if (selected.isNotBlank()) {
                                                            selectedText = selected
                                                            selectedRange = s to e
                                                        }
                                                    } else if (selectedText.isNotEmpty()) {
                                                        selectedText = ""
                                                        selectedRange = null
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth()
                                                .verticalScroll(scrollState)
                                                .padding(horizontal = AppSpacing.Roomy, vertical = AppSpacing.Screen),
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onBackground,
                                                fontSize = editorFontSize.sp,
                                                lineHeight = (editorFontSize * 1.6f).sp
                                            ),
                                            cursorBrush = SolidColor(extendedColors.primaryText),
                                            // 查找定位要按字符偏移算 Y 坐标，只能从这里拿到布局结果
                                            onTextLayout = { layout -> editorLayout = layout },
                                            decorationBox = { innerTextField ->
                                                Box {
                                                    if (editValue.text.isEmpty()) {
                                                        Text(
                                                            stringResource(R.string.editor_write_placeholder),
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                            }
                                        )

                                        // 底部状态条
                                        Surface(tonalElevation = EditorMetrics.StatusBarTonalElevation) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Snug),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // 文本选择操作按钮（仅当有选中文本且 AI 启用时显示）
                                                if (aiEnabled && selectedText.isNotBlank()) {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Default)) {
                                                        // AI 助手 - 统一入口
                                                        SuggestionChip(
                                                            onClick = {
                                                                pendingQuickAiSelection = selectedText to selectedRange
                                                                showQuickAiDialog = true
                                                            },
                                                            label = {
                                                                Text(
                                                                    stringResource(R.string.editor_ai_assistant),
                                                                    style = MaterialTheme.typography.labelSmall
                                                                )
                                                            },
                                                            icon = { Text("✨") }
                                                        )
                                                    }
                                                } else {
                                                    Spacer(modifier = Modifier.width(EditorMetrics.StatusBarSpacerWidth))
                                                }

                                                Text(
                                                    // 长度传两次：第一个实参选 quantity 分支
                                                    // （英文 1 → "1 character"），第二个填 %1$d
                                                    pluralStringResource(
                                                        R.plurals.editor_char_count,
                                                        editValue.text.length,
                                                        editValue.text.length
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            is EditorUiState.Error -> {
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(state.message.resolve(), color = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.height(AppSpacing.Default))
                                    Button(onClick = { viewModel.loadDocument() }) {
                                        Text(stringResource(R.string.editor_retry))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

    // ===== 侧边栏对话框 =====
    // 创建文档
    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.create_document)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.document_name)) },
                    placeholder = { Text(stringResource(R.string.hint_document_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createDocument(name)
                        showCreateDialog = false
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 创建文件夹
    if (showFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text(stringResource(R.string.create_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    placeholder = { Text(stringResource(R.string.hint_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createFolder(name)
                        showFolderDialog = false
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 创建子文件夹
    showSubfolderDialog?.let { parentId ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSubfolderDialog = null },
            title = { Text(stringResource(R.string.create_subfolder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    placeholder = { Text(stringResource(R.string.hint_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createSubfolder(name, parentId)
                        showSubfolderDialog = null
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubfolderDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 重命名文件夹
    folderToRename?.let { (folderId, oldName) ->
        var newName by remember { mutableStateOf(oldName) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameFolder(folderId, newName)
                        folderToRename = null
                    },
                    enabled = newName.isNotBlank() && newName != oldName
                ) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 删除文件夹
    folderToDelete?.let { folderId ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.delete_folder)) },
            text = { Text(stringResource(R.string.delete_folder_with_contents)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFolder(folderId)
                        folderToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 重命名文档
    documentToRename?.let { doc ->
        var newName by remember { mutableStateOf(doc.name) }
        AlertDialog(
            onDismissRequest = { documentToRename = null },
            title = { Text(stringResource(R.string.rename_document)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.document_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameDocument(doc.id, newName)
                        documentToRename = null
                    },
                    enabled = newName.isNotBlank() && newName != doc.name
                ) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { documentToRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 删除文档
    // 移动文档到其他文件夹
    documentToMove?.let { doc ->
        MoveToFolderDialog(
            title = stringResource(R.string.editor_move_to_title, doc.name),
            folders = folders,
            onDismiss = { documentToMove = null },
            onPick = { target ->
                viewModel.moveDocument(doc.id, target)
                documentToMove = null
            }
        )
    }

    // 移动文件夹到其他文件夹
    folderToMove?.let { folderId ->
        // 走收 String 的重载而不是 @Composable 重载：这里的接收者可空，?. 会把 composable
        // 调用变成条件调用，没必要为一个文案冒这个风险。
        val importLibraryName = stringResource(R.string.import_library)
        val name = folders.find { it.id == folderId }?.displayName(importLibraryName) ?: ""
        MoveToFolderDialog(
            title = stringResource(R.string.editor_move_to_title, name),
            folders = folders,
            disabledFolderIds = selfAndDescendantFolderIds(folders, folderId),
            onDismiss = { folderToMove = null },
            onPick = { target ->
                viewModel.moveFolder(folderId, target)
                folderToMove = null
            }
        )
    }

    documentToDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { documentToDelete = null },
            title = { Text(stringResource(R.string.delete_document)) },
            text = { Text(stringResource(R.string.delete_document_confirm, doc.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDocument(doc.id)
                        documentToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { documentToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 插入图片：先问来源。
    //
    // 不做成两个工具栏按钮：工具栏已经 16 个按钮、要横向滚，再加一个只会让人更难找到。
    // 两项都用整行可点的 Column（不是 IconButton），触摸区与无障碍名字的处理与
    // ToolbarButton 同一套理由——一行一个节点，读屏念一遍。
    if (showImageSourceDialog) {
        AlertDialog(
            onDismissRequest = { showImageSourceDialog = false },
            title = { Text(stringResource(R.string.editor_image_source_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ImageSourceRow(
                        icon = Icons.Default.PhotoLibrary,
                        title = stringResource(R.string.editor_image_from_gallery),
                        description = stringResource(R.string.editor_image_from_gallery_desc),
                        onClick = {
                            showImageSourceDialog = false
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    ImageSourceRow(
                        icon = Icons.Default.Link,
                        title = stringResource(R.string.editor_image_manual_link),
                        description = stringResource(R.string.editor_image_manual_link_desc),
                        onClick = {
                            showImageSourceDialog = false
                            pendingManualImage = true
                        }
                    )
                }
            },
            // 只留取消：两个来源都是「选了就走」，再加确认键等于多点一次
            confirmButton = {
                TextButton(onClick = { showImageSourceDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 图片来源对话框里的一行：图标 + 标题 + 一句话说明。
 *
 * 整行可点而不是「行尾一个按钮」：说明文字本身就是用户会去点的地方，
 * 而 `clickable` 会合并子节点，读屏把标题和说明连起来念一次，正好是这一项的完整语义。
 */
@Composable
private fun ImageSourceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppShapes.Medium))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = AppSpacing.Cozy, horizontal = AppSpacing.Default),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            // 名字由下面的标题给，避免读屏念两遍
            contentDescription = null,
            tint = extendedColors.primaryText
        )
        Spacer(modifier = Modifier.width(AppSpacing.Screen))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 编辑器左侧抽屉内容：内部文档显示库文件树，外部文档显示工作区文件树。
 * 点击当前文档仅收起抽屉；点击其他文档经 onOpenInternal/onOpenExternal 切换。
 */
@Composable
private fun EditorSidebarContent(
    isExternal: Boolean,
    workspace: com.yumark.app.domain.model.Workspace?,
    folderTree: List<com.yumark.app.domain.model.FolderTreeNode>?,
    currentDocumentId: String?,
    currentDocUri: String?,
    expandedFolders: Set<String>,
    onToggleFolder: (String) -> Unit,
    onEnsureFoldersExpanded: (List<String>) -> Unit,
    onOpenInternal: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
    onCloseDrawer: () -> Unit,
    fileDrawerState: DrawerState,
    showImportMenu: Boolean,
    onShowImportMenu: () -> Unit,
    onDismissImportMenu: () -> Unit,
    /** 拉起「导入文件」；实现在 [com.yumark.app.presentation.filelist.rememberImportFlow]，与文件列表页共用一份 */
    onImportFile: () -> Unit,
    /** 拉起「导入文件夹」，同上 */
    onImportFolder: () -> Unit,
    onShowFolderDialog: () -> Unit,
    onShowCreateDialog: (String) -> Unit,
    onShowSubfolderDialog: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onRenameDocument: (com.yumark.app.domain.model.Document) -> Unit,
    onDeleteDocument: (com.yumark.app.domain.model.Document) -> Unit,
    onMoveDocument: (com.yumark.app.domain.model.Document) -> Unit,
    onMoveFolder: (String) -> Unit,
    onMoveDocumentTo: (String, String?) -> Unit,
    onMoveFolderTo: (String, String?) -> Unit
) {
    if (isExternal) {
        val ws = workspace
        if (ws == null) {
            Text(
                stringResource(R.string.workspace_main_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(AppSpacing.Screen)
            )
            return
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Cozy),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                tint = extendedColors.primaryText
            )
            Spacer(modifier = Modifier.width(AppSpacing.Default))
            Text(
                ws.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        HorizontalDivider()
        WorkspaceFileTree(
            root = ws.root,
            expandedFolders = expandedFolders,
            onDocumentClick = { doc ->
                if (doc.uri == currentDocUri) onCloseDrawer() else onOpenExternal(doc.uri)
            },
            onFolderToggle = onToggleFolder
        )
    } else {
        // 内部文档库模式 - 顶部标题 + 操作按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Screen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.all_documents),
                style = MaterialTheme.typography.titleLarge
            )
            Row {
                Box {
                    IconButton(onClick = onShowImportMenu) {
                        Icon(Icons.Default.FileDownload, stringResource(R.string.import_to_library))
                    }
                    // 导入菜单下拉框
                    DropdownMenu(
                        expanded = showImportMenu,
                        onDismissRequest = onDismissImportMenu
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_file)) },
                            onClick = {
                                onDismissImportMenu()
                                onImportFile()
                            },
                            leadingIcon = { Icon(Icons.Default.Description, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_folder)) },
                            onClick = {
                                onDismissImportMenu()
                                onImportFolder()
                            },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) }
                        )
                    }
                }
                IconButton(onClick = onShowFolderDialog) {
                    Icon(Icons.Default.CreateNewFolder, stringResource(R.string.create_folder))
                }
            }
        }
        HorizontalDivider()

        val tree = folderTree
        if (tree == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            SidebarFileTree(
                tree = tree,
                currentDocumentId = currentDocumentId,
                expandedFolders = expandedFolders,
                onDocumentClick = { id ->
                    if (id == currentDocumentId) onCloseDrawer() else onOpenInternal(id)
                },
                onFolderExpand = onToggleFolder,
                onFolderCollapse = onToggleFolder,
                scrollToCurrentDocument = fileDrawerState.isOpen,
                // 自动展开深处当前文档的祖先文件夹链(并集语义)，使其在侧栏可见并可定位
                onEnsureFoldersExpanded = onEnsureFoldersExpanded,
                actions = SidebarActions(
                    onCreateDocument = { folderId -> onShowCreateDialog(folderId ?: "") },
                    onCreateSubfolder = { folderId -> folderId?.let { onShowSubfolderDialog(it) } },
                    onRenameFolder = { folderId ->
                        // 需要从 folderTree 中找到文件夹名称
                        val folder = findFolderInTree(tree, folderId)
                        folder?.folder?.let { onRenameFolder(folderId, it.name) }
                    },
                    onDeleteFolder = onDeleteFolder,
                    onRenameDocument = onRenameDocument,
                    onDeleteDocument = onDeleteDocument,
                    onMoveDocument = onMoveDocument,
                    onMoveFolder = onMoveFolder,
                    onMoveDocumentTo = onMoveDocumentTo,
                    onMoveFolderTo = onMoveFolderTo
                )
            )
        }
    }
}

/** 在文件树中递归查找文件夹 */
private fun findFolderInTree(
    tree: List<com.yumark.app.domain.model.FolderTreeNode>,
    folderId: String
): com.yumark.app.domain.model.FolderTreeNode? {
    for (node in tree) {
        if (node.folder?.id == folderId) return node
        val found = findFolderInTree(node.children, folderId)
        if (found != null) return found
    }
    return null
}

/**
 * 预览 WebView 的原生背景色。必须与 renderer.html 实际生效的 body 背景一致：
 * 浅色模式 body 恒为 #fff（模板写死，不随主题变），深色模式由 darkStyleJs 注入主题背景色。
 * 所以深色传当前 ColorScheme 的 background、浅色一律纯白——浅色下传主题色只会在模板白底
 * 之外露出一圈别的颜色。
 * 用不透明纯色（而非透明）是为了保留 WebView 的硬件加速合成路径，长文档滚动才不卡。
 */
private fun previewBgColor(isDarkMode: Boolean, themeBackground: Color): Int =
    (if (isDarkMode) themeBackground else Color.White).toArgb()

/** JS 桥回传选中文本的长度上限（字符）。超出截断。 */
private const val MAX_BRIDGE_SELECTION_CHARS = 20_000

/** JS 桥回传大纲 JSON 的长度上限（字符）。超出直接丢弃。 */
private const val MAX_BRIDGE_OUTLINE_CHARS = 512_000

/**
 * 渲染器就绪的等待上限（毫秒）。超时判定为"模板脚本没跑起来"，给可见错误 + 重试入口。
 * 与离屏导出 [com.yumark.app.core.export.WebViewDocumentRenderer] 的 READY_TIMEOUT_MS 同一档，
 * 两处对"多久算没起来"保持一致的判断。
 */
private const val PREVIEW_READY_TIMEOUT_MS = 12_000L

/**
 * 「证书被拦截」提示的最小间隔（毫秒）。
 *
 * onReceivedSslError 是按**每个子资源**回调的：一篇引用了十张自签证书图片的文档会连着触发
 * 十次，不节流就是十条 Snackbar 排队顶掉彼此。取 10 秒是因为同一次渲染里的图片请求都发生
 * 在这个量级之内，用户拿到一条提示；等他改完文档再渲染出新的坏图，又能重新收到提示。
 */
private const val SSL_HINT_INTERVAL_MS = 10_000L

/**
 * 预览加载失败时替换 WebView 的错误卡片。
 *
 * [message] 只接受 [WebViewFailure] 产出的 [UiMessage]（一律是资源 id，不含任何运行期文本）；
 * 这里不做字符串拼接，从签名上就杜绝把 URL / error.description 带上界面。
 */
@Composable
private fun PreviewFailureCard(
    message: UiMessage,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(AppSpacing.Section),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(AppIconSize.Hero)
        )
        Spacer(modifier = Modifier.height(AppSpacing.Screen))
        Text(
            text = message.resolve(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(AppSpacing.Roomy))
        Button(onClick = onRetry) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(AppIconSize.Small)
            )
            Spacer(modifier = Modifier.width(AppSpacing.Default))
            Text(stringResource(R.string.editor_retry))
        }
    }
}

/**
 * 编辑器屏特有的度量，刻意不并入全局间距 / 图标标度：进度环描边、若干 tonal/shadow elevation（z 轴，
 * 非间距）、状态条无 chip 时的 1dp 占位 spacer。离散于全局标度，保留原像素、不硬凑。
 * 按 FileListMetrics 先例落屏幕局部。
 */
private object EditorMetrics {
    /** 保存中进度环描边宽度：绘制轴，非间距。 */
    val SaveSpinnerStroke = 2.dp
    /** 预览选区 AI 浮条 tonal elevation：z 轴，非间距。 */
    val AiBarTonalElevation = 2.dp
    /** 预览选区 AI 浮条 shadow elevation：z 轴，非间距。 */
    val AiBarShadowElevation = 4.dp
    /** 编辑模式底部状态条 tonal elevation：z 轴，非间距。 */
    val StatusBarTonalElevation = 1.dp
    /** 状态条无操作 chip 时左侧占位 spacer 宽度：1dp（SpaceBetween 需左子项把字数推到右侧）。 */
    val StatusBarSpacerWidth = 1.dp
}

/** 渲染 Markdown（Base64 通道，避免字符转义问题） */
private fun renderJs(encodedContent: String): String = """
    (function() {
        try {
            var decodedContent = decodeURIComponent(escape(atob('$encodedContent')));
            if (window.renderMarkdown) {
                window.renderMarkdown(decodedContent);
            } else {
                console.error('renderMarkdown not found');
            }
        } catch(e) {
            console.error('Render error:', e);
        }
    })();
""".trimIndent()

/**
 * 深色模式覆盖样式：只改 renderer.html 在 `:root` 上定义的那组自定义属性。
 *
 * 原先是逐个选择器重写（`code,pre{...}`、`th{...}`、`blockquote{...}`），那种写法盖不住
 * prism.css：`pre[class*=language-]` 比 `code,pre` 高一档特异性，**带语言标记的代码块**
 * 因此在深色页面上一直保持 prism 浅色主题的 `#f5f2f0` 底 + `#000` 字 + 白色 text-shadow。
 * 模板里现在按 prism 自己的选择器复述了同一批属性并统一读变量，改这一组变量就能一起生效。
 *
 * 另外原先漏掉的几处（h1/h2 下划线、hr、h6 的次要色、渲染错误卡片）也一并跟着变量走了。
 *
 * token 取色换成一套深底配色（One Dark 一路）：prism 默认那八个颜色都是为浅底调的，
 * `#905`、`#07a` 这种直接放到 `#2E2E2C` 上对比度不够。
 *
 * 引用条的边框色由原先的 `#5A5A56` 并入 `--ym-border`（`#4A4A46`）：两处本无理由分开，
 * 合成一个变量后表格线与引用条同色。
 */
private fun darkStyleJs(bg: String, fg: String): String = """
    (function() {
        var s = document.getElementById('yumark-dark');
        if (!s) { s = document.createElement('style'); s.id = 'yumark-dark'; document.head.appendChild(s); }
        s.textContent = ':root{' +
            '--ym-bg:$bg;--ym-fg:$fg;--ym-fg-muted:#A8A59B;--ym-border:#4A4A46;' +
            '--ym-code-bg:#2E2E2C;--ym-code-fg:#E0DED6;--ym-th-bg:#343432;--ym-link:#7FB2E5;' +
            '--ym-err-fg:#F0908A;--ym-err-bg:#3A2624;--ym-err-border:#6B3A36;' +
            '--ym-tok-comment:#7C8288;--ym-tok-punct:#A8A59B;--ym-tok-num:#D19A66;' +
            '--ym-tok-str:#98C379;--ym-tok-op:#E5C07B;--ym-tok-kw:#C678DD;' +
            '--ym-tok-fn:#61AFEF;--ym-tok-var:#E06C75;' +
            '}';
    })();
""".trimIndent()

/**
 * 文本选择监听脚本：在预览模式 WebView 中监听文本选择，
 * 选中文本后通过 AndroidSelection 接口回传给 Kotlin
 */
private fun textSelectionListenerJs(): String = """
    (function() {
        // 避免重复注入
        if (window.__yumarkSelectionListener) return;
        window.__yumarkSelectionListener = true;

        var lastSelection = '';

        function notifySelection() {
            var selection = window.getSelection();
            var text = selection ? selection.toString().trim() : '';

            // 通知选中文本或清空
            if (window.AndroidSelection && window.AndroidSelection.onTextSelected) {
                if (text) {
                    if (text !== lastSelection) {
                        lastSelection = text;
                        window.AndroidSelection.onTextSelected(text);
                    }
                } else {
                    // 文本清空时也通知
                    if (lastSelection !== '') {
                        lastSelection = '';
                        window.AndroidSelection.onTextSelected('');
                    }
                }
            }
        }

        // 监听选择变化
        document.addEventListener('selectionchange', function() {
            clearTimeout(window.__selectionTimer);
            window.__selectionTimer = setTimeout(notifySelection, 200);
        });

        // 触摸结束时检查
        document.addEventListener('touchend', function() {
            setTimeout(notifySelection, 100);
        });

        // 点击空白处取消选择
        document.addEventListener('click', function(e) {
            setTimeout(notifySelection, 100);
        });
    })();
""".trimIndent()

