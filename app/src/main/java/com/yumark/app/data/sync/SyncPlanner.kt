package com.yumark.app.data.sync

/**
 * WebDAV 同步的**纯决策逻辑**（无 IO，便于单测）。
 *
 * 按文件名把本地文档与远端文件配对，结合上次同步记录（[SyncRecordInfo]）判定每篇该做什么：
 * 上传 / 下载覆盖 / 新建本地 / 冲突 / 跳过。真正的网络与落库由 `SyncRepositoryImpl` 执行。
 *
 * P1 策略（last-write-wins + 冲突安全兜底）：
 * - 单边变化 → 推送/拉取。
 * - 双边自上次同步都变 → [SyncAction.Conflict]：保留本地、把远端存为冲突副本、本地内容上行。
 * - 缺基线（首次/历史空 etag/hash）→ 谨慎处理，避免误判与误覆盖。
 */
object SyncPlanner {

    /** 本地文档（仅同步所需字段）。[fileName] 已含 .md 后缀且全局唯一。 */
    data class LocalDocInfo(val id: String, val fileName: String, val contentHash: String)

    /** 远端文件。 */
    data class RemoteFileInfo(val fileName: String, val etag: String?)

    /** 上次同步记录（来自 sync_state）。 */
    data class SyncRecordInfo(
        val docId: String,
        val remotePath: String,
        val remoteEtag: String?,
        val localHash: String?
    )

    sealed interface SyncAction {
        /** 上传本地内容；[deleteOldPath] 非空表示改名，上传后删旧远端文件。 */
        data class Upload(val docId: String, val fileName: String, val deleteOldPath: String?) : SyncAction

        /** 下载远端内容覆盖本地。 */
        data class DownloadOverwrite(val docId: String, val fileName: String, val remoteEtag: String?) : SyncAction

        /** 远端独有文件 → 新建本地文档。 */
        data class CreateLocal(val fileName: String, val remoteEtag: String?) : SyncAction

        /** 双边冲突 → 远端存冲突副本 + 本地上行。 */
        data class Conflict(val docId: String, val fileName: String, val remoteEtag: String?) : SyncAction

        /** 无变化 → 仅刷新基线。 */
        data class Skip(val docId: String, val fileName: String, val remoteEtag: String?, val localHash: String) : SyncAction
    }

    fun plan(
        locals: List<LocalDocInfo>,
        remotes: List<RemoteFileInfo>,
        records: List<SyncRecordInfo>
    ): List<SyncAction> {
        val remoteByName = remotes.associateBy { it.fileName }
        val recordByDoc = records.associateBy { it.docId }
        val actions = mutableListOf<SyncAction>()
        val handledRemoteNames = mutableSetOf<String>()

        for (local in locals) {
            handledRemoteNames += local.fileName
            val record = recordByDoc[local.id]
            val remote = remoteByName[local.fileName]

            if (record == null) {
                // 从未同步过
                if (remote == null) {
                    actions += SyncAction.Upload(local.id, local.fileName, deleteOldPath = null)
                } else {
                    // 本地新文档与某远端文件同名：保留双方，远端存副本、本地上行
                    actions += SyncAction.Conflict(local.id, local.fileName, remote.etag)
                }
                continue
            }

            if (remote == null) {
                // 远端没有当前文件名：要么本地改名（旧路径仍在远端），要么远端被删
                val oldPath = record.remotePath.takeIf { it != local.fileName }
                if (oldPath != null) handledRemoteNames += oldPath
                actions += SyncAction.Upload(local.id, local.fileName, deleteOldPath = oldPath)
                continue
            }

            val localChanged = record.localHash != null && record.localHash != local.contentHash
            val remoteChanged = record.remoteEtag != null && remote.etag != null && remote.etag != record.remoteEtag
            actions += when {
                localChanged && remoteChanged ->
                    SyncAction.Conflict(local.id, local.fileName, remote.etag)
                localChanged ->
                    SyncAction.Upload(local.id, local.fileName, deleteOldPath = null)
                remoteChanged ->
                    SyncAction.DownloadOverwrite(local.id, local.fileName, remote.etag)
                else ->
                    SyncAction.Skip(local.id, local.fileName, remote.etag, local.contentHash)
            }
        }

        // 远端独有（未被任何本地文档或改名旧路径覆盖）→ 拉取为本地新文档
        for (remote in remotes) {
            if (remote.fileName in handledRemoteNames) continue
            actions += SyncAction.CreateLocal(remote.fileName, remote.etag)
        }

        return actions
    }
}
