package com.yumark.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.yumark.app.R
import com.yumark.app.core.util.FriendlyIOException
import com.yumark.app.core.util.FriendlyValidationException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.data.local.file.ScanEntry
import com.yumark.app.data.local.file.WorkspaceScanner
import com.yumark.app.data.local.prefs.WorkspaceDataStore
import com.yumark.app.domain.model.Workspace
import com.yumark.app.domain.repository.WorkspaceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspaceDataStore: WorkspaceDataStore
) : WorkspaceRepository {

    private val _workspace = MutableStateFlow<Workspace?>(null)
    override val workspace: StateFlow<Workspace?> = _workspace.asStateFlow()

    // 启动恢复单飞：Application.onCreate 先行触发扫描（与首帧渲染并行），
    // 首页 ViewModel 稍后 await 同一结果取错误提示，不重复扫描也无竞态
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restoreDeferred: Deferred<UiMessage?> by lazy {
        restoreScope.async { doRestoreOnLaunch() }
    }

    /**
     * 打开一个 SAF 目录树作为工作区。
     *
     * 两处失败都用 [FriendlyIOException] 而不是 `error(...)`：`error` 抛的是裸
     * [IllegalStateException]，[com.yumark.app.core.util.ErrorHandler.classify] 把它归到
     * 「出现未知问题，请重试」，手写的中文提示一个字都到不了界面上。而这两种失败恰好都有
     * 明确的下一步动作（重新选目录、重新授权），是最不该被泛化掉的一类。
     */
    override suspend fun openWorkspace(treeUri: String): Result<Workspace> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = treeUri.toUri()
                val rootDoc = DocumentFile.fromTreeUri(context, uri)
                    ?: throw FriendlyIOException(
                        UiMessage.Res(R.string.workspace_error_open_failed)
                    )
                if (!rootDoc.canRead()) throw FriendlyIOException(
                    UiMessage.Res(R.string.workspace_error_no_read_permission)
                )
                val rootEntry = ContractTreeEntry(
                    resolver = context.contentResolver,
                    treeUri = uri,
                    documentId = DocumentsContract.getTreeDocumentId(uri),
                    name = rootDoc.name,
                    mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                    lastModified = 0L
                )
                val result = WorkspaceScanner.scan(rootEntry)
                val ws = Workspace(
                    name = rootDoc.name ?: "外部文件夹",
                    treeUri = treeUri,
                    root = result.root,
                    fileLimitHit = result.fileLimitHit,
                    depthLimitHit = result.depthLimitHit
                )
                workspaceDataStore.saveTreeUri(treeUri)
                _workspace.value = ws
                ws
            }
        }

    override suspend fun closeWorkspace() {
        workspaceDataStore.clearTreeUri()
        _workspace.value = null
    }

    override suspend fun rescan(): Result<Workspace> {
        val current = _workspace.value
            ?: return Result.failure(
                FriendlyValidationException(UiMessage.Res(R.string.workspace_error_none_open))
            )
        return openWorkspace(current.treeUri)
    }

    override suspend fun restoreOnLaunch(): UiMessage? = restoreDeferred.await()

    private suspend fun doRestoreOnLaunch(): UiMessage? {
        if (_workspace.value != null) return null
        // 优先恢复用户在设置里指定的默认目录；失败时回退到上次会话打开的工作区
        var error: UiMessage? = null
        val defaultDir = workspaceDataStore.defaultDirUriFlow.first()
        if (defaultDir != null) {
            if (hasReadPermission(defaultDir) && openWorkspace(defaultDir).isSuccess) return null
            // 默认目录授权失效或打开失败：清默认目录并提示用户，继续尝试上次工作区。
            // 带资源 id 而不是成句中文 —— 这句话会显示在侧栏错误条上，英文环境要出英文，
            // 且它指的菜单名在两种语言下不同（设置 → 默认目录 / Settings → Default folder）。
            workspaceDataStore.clearDefaultDirUri()
            error = UiMessage.Res(R.string.workspace_error_default_dir_invalid)
        }
        val lastSession = workspaceDataStore.treeUriFlow.first() ?: return error
        if (!hasReadPermission(lastSession)) {
            workspaceDataStore.clearTreeUri()
            return error
        }
        openWorkspace(lastSession).onFailure { workspaceDataStore.clearTreeUri() }
        return error
    }

    override val defaultDirUri: kotlinx.coroutines.flow.Flow<String?>
        get() = workspaceDataStore.defaultDirUriFlow

    override suspend fun setDefaultDir(treeUri: String): Result<Workspace> =
        // 先打开、成功了才落库。反过来写（先 saveDefaultDirUri 再 openWorkspace）的后果是：
        // 目录打不开时它照样成了「默认目录」——设置页立刻显示它的名字，下次冷启动还要先拿它
        // 试一遍才回退（见 doRestoreOnLaunch）；而调用方那时又把 Result 丢了，用户得不到任何提示。
        // 持久授权由 UI 在选择回调里先做完，所以这里不需要为了拿权限而提前写盘。
        openWorkspace(treeUri).onSuccess { workspaceDataStore.saveDefaultDirUri(treeUri) }

    override suspend fun clearDefaultDir() {
        workspaceDataStore.clearDefaultDirUri()
        // 默认目录打开时也被记成了「上次会话工作区」，必须一并清掉并关闭当前工作区，
        // 否则下次启动会从 treeUri 回退恢复出同一个目录
        workspaceDataStore.clearTreeUri()
        _workspace.value = null
    }

    override suspend fun defaultDirName(): String? {
        val uri = workspaceDataStore.defaultDirUriFlow.first() ?: return null
        return DocumentFile.fromTreeUri(context, uri.toUri())?.name
    }

    private fun hasReadPermission(uri: String): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == uri && it.isReadPermission
        }

    override suspend fun readDocument(docUri: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(docUri.toUri())
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw FriendlyIOException(
                        UiMessage.Res(R.string.workspace_error_file_unreadable)
                    )
            }
        }

    override suspend fun writeDocument(docUri: String, content: String): Result<Unit> =
        // NonCancellable：写盘一旦开始必须完成，避免协程取消时 "wt" 截断后留下半截文件
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            runCatching {
                // "wt" 截断模式：新内容比旧内容短时不残留旧字节
                context.contentResolver.openOutputStream(docUri.toUri(), "wt")
                    ?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                    ?: throw FriendlyIOException(
                        UiMessage.Res(R.string.workspace_error_file_not_writable)
                    )
            }
        }

    override fun documentName(docUri: String): String {
        val raw = DocumentFile.fromSingleUri(context, docUri.toUri())?.name ?: "未命名"
        return raw.substringBeforeLast('.', raw)
    }
}

/**
 * 基于 DocumentsContract 子文档批量查询的扫描条目：每个目录一次跨进程查询取回
 * 全部子项的 id/名称/类型/修改时间。DocumentFile.listFiles() 之后每访问一个属性
 * 都是单独一趟 ContentProvider 查询，大目录下启动恢复要慢一个数量级。
 * 子项 URI 用 buildDocumentUriUsingTree 生成，与 DocumentFile 路径下的格式一致。
 */
private class ContractTreeEntry(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val documentId: String,
    override val name: String?,
    private val mimeType: String?,
    override val lastModified: Long
) : ScanEntry {

    override val isDirectory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

    override val uri: String
        get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString()

    override fun children(): List<ScanEntry> {
        if (!isDirectory) return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val children = mutableListOf<ScanEntry>()
        // 单个目录枚举失败（权限/提供器异常）按空目录处理，与 DocumentFile.listFiles 行为一致
        runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    children += ContractTreeEntry(
                        resolver = resolver,
                        treeUri = treeUri,
                        documentId = cursor.getString(0),
                        name = cursor.getString(1),
                        mimeType = cursor.getString(2),
                        lastModified = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    )
                }
            }
        }
        return children
    }
}
