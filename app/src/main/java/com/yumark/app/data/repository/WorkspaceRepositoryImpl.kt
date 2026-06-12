package com.yumark.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.yumark.app.data.local.file.ScanEntry
import com.yumark.app.data.local.file.WorkspaceScanner
import com.yumark.app.data.local.prefs.WorkspaceDataStore
import com.yumark.app.domain.model.Workspace
import com.yumark.app.domain.repository.WorkspaceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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

    override suspend fun openWorkspace(treeUri: String): Result<Workspace> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                    ?: error("无法访问所选文件夹")
                if (!rootDoc.canRead()) error("没有该文件夹的读取权限")
                val result = WorkspaceScanner.scan(DocumentFileEntry(rootDoc))
                val ws = Workspace(
                    name = rootDoc.name ?: "外部文件夹",
                    treeUri = treeUri,
                    root = result.root,
                    truncated = result.truncated
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
            ?: return Result.failure(IllegalStateException("没有打开的工作区"))
        return openWorkspace(current.treeUri)
    }

    override suspend fun restoreOnLaunch() {
        if (_workspace.value != null) return
        // 优先恢复用户在设置里指定的默认目录；失败时回退到上次会话打开的工作区
        val defaultDir = workspaceDataStore.defaultDirUriFlow.first()
        if (defaultDir != null) {
            if (hasReadPermission(defaultDir) && openWorkspace(defaultDir).isSuccess) return
            // 默认目录授权失效或打开失败：只清默认目录，继续尝试上次工作区
            workspaceDataStore.clearDefaultDirUri()
        }
        val lastSession = workspaceDataStore.treeUriFlow.first() ?: return
        if (!hasReadPermission(lastSession)) {
            workspaceDataStore.clearTreeUri()
            return
        }
        openWorkspace(lastSession).onFailure { workspaceDataStore.clearTreeUri() }
    }

    override val defaultDirUri: kotlinx.coroutines.flow.Flow<String?>
        get() = workspaceDataStore.defaultDirUriFlow

    override suspend fun setDefaultDir(treeUri: String): Result<Workspace> {
        // 设为默认目录的同时立即打开它（持久授权由 UI 在选择回调里完成）
        workspaceDataStore.saveDefaultDirUri(treeUri)
        return openWorkspace(treeUri)
    }

    override suspend fun clearDefaultDir() {
        workspaceDataStore.clearDefaultDirUri()
    }

    override suspend fun defaultDirName(): String? {
        val uri = workspaceDataStore.defaultDirUriFlow.first() ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name
    }

    private fun hasReadPermission(uri: String): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == uri && it.isReadPermission
        }

    override suspend fun readDocument(docUri: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(docUri))
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("无法打开文件")
            }
        }

    override suspend fun writeDocument(docUri: String, content: String): Result<Unit> =
        // NonCancellable：写盘一旦开始必须完成，避免协程取消时 "wt" 截断后留下半截文件
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            runCatching {
                // "wt" 截断模式：新内容比旧内容短时不残留旧字节
                context.contentResolver.openOutputStream(Uri.parse(docUri), "wt")
                    ?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                    ?: error("无法写入文件（可能为只读）")
            }
        }

    override fun documentName(docUri: String): String {
        val raw = DocumentFile.fromSingleUri(context, Uri.parse(docUri))?.name ?: "未命名"
        return raw.substringBeforeLast('.', raw)
    }
}

/** DocumentFile 到 ScanEntry 的适配 */
private class DocumentFileEntry(private val file: DocumentFile) : ScanEntry {
    override val name: String? get() = file.name
    override val isDirectory: Boolean get() = file.isDirectory
    override val uri: String get() = file.uri.toString()
    override val lastModified: Long get() = file.lastModified()
    override fun children(): List<ScanEntry> = file.listFiles().map { DocumentFileEntry(it) }
}
