package com.yumark.app.presentation.trash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserAction
import com.yumark.app.core.util.onFailureReport
import com.yumark.app.domain.model.TrashedDocument
import com.yumark.app.domain.usecase.EmptyTrashUseCase
import com.yumark.app.domain.usecase.LoadTrashedDocumentsUseCase
import com.yumark.app.domain.usecase.PurgeDocumentUseCase
import com.yumark.app.domain.usecase.PurgeExpiredTrashUseCase
import com.yumark.app.domain.usecase.RestoreFromTrashUseCase
import com.yumark.app.presentation.common.SnackbarEffect
import com.yumark.app.presentation.common.formatElapsedTime
import com.yumark.app.presentation.common.resolveOrNull
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrashUiState(
    val items: List<TrashedDocument> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    // UiMessage 而不是 String：ViewModel 里拿不到 Context，成句的文案只能推到界面侧解析
    val message: UiMessage? = null,
    /** 待确认「彻底删除」的那一篇；null 表示无待确认项 */
    val pendingPurge: TrashedDocument? = null,
    /** 待确认「清空回收站」；null 表示无待确认项 */
    val showEmptyConfirm: Boolean = false
)

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val loadTrashed: LoadTrashedDocumentsUseCase,
    // 参数名避开成员函数 restore(id)：成员函数解析优先，撞名会让 restore(id) 静默变成
    // 递归调用自己（返回 Unit），调用点上的 .onSuccess 直接推不出类型
    private val restoreUseCase: RestoreFromTrashUseCase,
    private val purge: PurgeDocumentUseCase,
    private val emptyTrash: EmptyTrashUseCase,
    private val purgeExpired: PurgeExpiredTrashUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    init {
        refresh()
        // 进入回收站顺手做一次到期清理：30 天前移入的直接走彻底删除。
        // 失败静默——这条只是保洁，不该挡住用户看回收站；下一轮进入会重试。
        viewModelScope.launch {
            purgeExpired().onFailure { e ->
                if (e is CancellationException) throw e
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            loadTrashed()
                .onSuccess { list ->
                    _state.value = _state.value.copy(
                        items = list, loaded = true, loading = false
                    )
                }
                .onFailureReport(UserAction.TRASH_LOAD) { message -> _state.value = _state.value.copy(message = message) }
                .also { _state.value = _state.value.copy(loading = false) }
        }
    }

    fun restore(id: String) {
        viewModelScope.launch {
            val trashed = _state.value.items.firstOrNull { it.id == id }
            restoreUseCase(id)
                .onSuccess {
                    _state.value = _state.value.copy(
                        items = _state.value.items.filterNot { it.id == id },
                        message = trashed?.let {
                            UiMessage.of(R.string.trash_restored, it.name)
                        }
                    )
                }
                .onFailureReport(UserAction.RESTORE_DOCUMENT) { message -> _state.value = _state.value.copy(message = message) }
        }
    }

    fun requestPurge(item: TrashedDocument) {
        _state.value = _state.value.copy(pendingPurge = item)
    }

    /** 确认框里的「彻底删除」。 */
    fun confirmPurge() {
        val target = _state.value.pendingPurge ?: return
        _state.value = _state.value.copy(pendingPurge = null)
        viewModelScope.launch {
            purge(target.id)
                .onSuccess {
                    _state.value = _state.value.copy(
                        items = _state.value.items.filterNot { it.id == target.id },
                        message = UiMessage.Res(R.string.trash_purged)
                    )
                }
                .onFailureReport(UserAction.DELETE_DOCUMENT) { message -> _state.value = _state.value.copy(message = message) }
        }
    }

    fun requestEmpty() {
        _state.value = _state.value.copy(showEmptyConfirm = true)
    }

    /** 确认框里的「清空回收站」。 */
    fun confirmEmpty() {
        _state.value = _state.value.copy(showEmptyConfirm = false)
        viewModelScope.launch {
            emptyTrash()
                .onSuccess { refresh() }
                .onFailureReport(UserAction.DELETE_DOCUMENT) { message -> _state.value = _state.value.copy(message = message) }
        }
    }

    fun dismissPurge() {
        _state.value = _state.value.copy(pendingPurge = null)
    }

    fun dismissEmptyConfirm() {
        _state.value = _state.value.copy(showEmptyConfirm = false)
    }

    fun onMessageConsumed() {
        _state.value = _state.value.copy(message = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    navController: NavController,
    viewModel: TrashViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_trash_back))
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        TextButton(onClick = viewModel::requestEmpty) {
                            Text(stringResource(R.string.trash_empty_action))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded && state.loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                state.items.isEmpty() -> TrashEmptyContent()
                else -> TrashList(
                    items = state.items,
                    onRestore = viewModel::restore,
                    onPurge = viewModel::requestPurge
                )
            }
        }
    }

    SnackbarEffect(
        message = state.message.resolveOrNull(),
        hostState = snackbarHostState,
        onConsumed = viewModel::onMessageConsumed
    )

    state.pendingPurge?.let { item ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPurge,
            title = { Text(stringResource(R.string.trash_delete_forever)) },
            text = { Text(stringResource(R.string.trash_delete_forever_confirm, item.name)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPurge) {
                    Text(
                        stringResource(R.string.trash_delete_forever),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPurge) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (state.showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissEmptyConfirm,
            title = { Text(stringResource(R.string.trash_empty_action)) },
            text = {
                Text(stringResource(R.string.trash_empty_confirm, state.items.size))
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmEmpty) {
                    Text(
                        stringResource(R.string.trash_empty_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissEmptyConfirm) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
internal fun TrashEmptyContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.Screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 与文件列表空态同一手法：降饱和大图标先给出「这是正常的空状态」的信号，
        // 纯文字空屏读起来像出了问题
        Icon(
            Icons.Default.DeleteOutline,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(AppSpacing.Cozy))
        Text(
            stringResource(R.string.trash_empty),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(AppSpacing.Default))
        Text(
            stringResource(R.string.trash_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun TrashList(
    items: List<TrashedDocument>,
    onRestore: (String) -> Unit,
    onPurge: (TrashedDocument) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Default)
    ) {
        items(items, key = { it.id }) { item ->
            // animateItem 覆盖出现/移动/消失：恢复与彻底删除时卡片平滑退场，
            // 与主文档列表（FileListScreen）同一套动效
            Box(modifier = Modifier.animateItem()) {
                TrashItemRow(
                    item = item,
                    onRestore = { onRestore(item.id) },
                    onPurge = { onPurge(item) }
                )
            }
        }
    }
}

@Composable
internal fun TrashItemRow(
    item: TrashedDocument,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    // 与 DocumentCard 同一视觉语言：OutlinedCard + titleMedium 标题 + bodySmall 元信息行。
    // 回收站是文档库的镜像视图，行样式与主列表漂移会让两页看起来像两个应用。
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRestore)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Screen),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(AppSpacing.Tight))
                // 字数与相对时间和文档卡片同一口径（同一套 plurals、同一个 formatElapsedTime）；
                // 「移入于」前缀表明这是删除时间而不是修改时间
                val words = pluralStringResource(R.plurals.word_count, item.wordCount, item.wordCount)
                val deleted = formatElapsedTime(item.deletedAt)
                Text(
                    stringResource(R.string.trash_deleted_at, "$words • $deleted"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row {
                IconButton(onClick = onRestore, modifier = Modifier.testTag(TrashTags.RESTORE)) {
                    Icon(
                        Icons.Default.RestoreFromTrash,
                        stringResource(R.string.trash_restore),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(AppIconSize.Small)
                    )
                }
                IconButton(onClick = onPurge, modifier = Modifier.testTag(TrashTags.PURGE)) {
                    Icon(
                        Icons.Default.DeleteForever,
                        stringResource(R.string.trash_delete_forever),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(AppIconSize.Small)
                    )
                }
            }
        }
    }
}

/** UI 测试锚点（与 FileListTags 同一约定）。 */
object TrashTags {
    const val RESTORE = "trash_restore"
    const val PURGE = "trash_purge"
}
