package com.yumark.app.domain.repository

import com.yumark.app.domain.model.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface WorkspaceRepository {
    /** 当前工作区，null 表示未打开 */
    val workspace: StateFlow<Workspace?>

    /** 默认目录 URI（设置里显式指定，启动时优先恢复）；null 表示未设置 */
    val defaultDirUri: Flow<String?>

    /** 打开外部文件夹工作区并扫描（treeUri 为 SAF 树 URI 字符串） */
    suspend fun openWorkspace(treeUri: String): Result<Workspace>

    /** 关闭工作区（保留系统授权，便于下次快速重开） */
    suspend fun closeWorkspace()

    /** 重新扫描当前工作区文件树 */
    suspend fun rescan(): Result<Workspace>

    /** 设为默认目录（设置界面调用）；同时立即打开为当前工作区 */
    suspend fun setDefaultDir(treeUri: String): Result<Workspace>

    /** 清除默认目录（不影响当前已打开的工作区） */
    suspend fun clearDefaultDir()

    /** 默认目录的显示名（去 SAF 前缀），未设置返回 null */
    suspend fun defaultDirName(): String?

    /**
     * 应用启动时从持久化恢复工作区；优先恢复默认目录，失败回退到上次会话的工作区。
     * @return 用户可见的失败提示（如默认目录授权已失效）；一切正常或本就无可恢复项时返回 null
     */
    suspend fun restoreOnLaunch(): String?

    suspend fun readDocument(docUri: String): Result<String>
    suspend fun writeDocument(docUri: String, content: String): Result<Unit>

    /** 外部文档显示名（去扩展名） */
    fun documentName(docUri: String): String
}
