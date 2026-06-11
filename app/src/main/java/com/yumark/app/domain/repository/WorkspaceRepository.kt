package com.yumark.app.domain.repository

import com.yumark.app.domain.model.Workspace
import kotlinx.coroutines.flow.StateFlow

interface WorkspaceRepository {
    /** 当前工作区，null 表示未打开 */
    val workspace: StateFlow<Workspace?>

    /** 打开外部文件夹工作区并扫描（treeUri 为 SAF 树 URI 字符串） */
    suspend fun openWorkspace(treeUri: String): Result<Workspace>

    /** 关闭工作区（保留系统授权，便于下次快速重开） */
    suspend fun closeWorkspace()

    /** 重新扫描当前工作区文件树 */
    suspend fun rescan(): Result<Workspace>

    /** 应用启动时从持久化恢复工作区；授权失效则静默清除 */
    suspend fun restoreOnLaunch()

    suspend fun readDocument(docUri: String): Result<String>
    suspend fun writeDocument(docUri: String, content: String): Result<Unit>

    /** 外部文档显示名（去扩展名） */
    fun documentName(docUri: String): String
}
