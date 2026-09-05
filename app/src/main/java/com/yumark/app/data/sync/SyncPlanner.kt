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
 * - 删除双向传播：本地删除靠墓碑（[TombstoneInfo]）推到远端；远端删除在本地无未同步改动时
 *   落到本地。两侧都遵循「宁可复活一篇，也不误删一篇」。
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

    /**
     * 「这篇文档在本地被删过」的墓碑（来自 sync_tombstones）。
     *
     * [remotePath] 是删除前那一刻的远端路径——文档行已经不存在了，没有别的地方能算出它。
     */
    data class TombstoneInfo(val docId: String, val remotePath: String)

    sealed interface SyncAction {
        /** 上传本地内容；[deleteOldPath] 非空表示改名，上传后删旧远端文件。 */
        data class Upload(val docId: String, val fileName: String, val deleteOldPath: String?) : SyncAction

        /** 下载远端内容覆盖本地。 */
        data class DownloadOverwrite(val docId: String, val fileName: String, val remoteEtag: String?) : SyncAction

        /** 远端独有文件 → 新建本地文档。 */
        data class CreateLocal(val fileName: String, val remoteEtag: String?) : SyncAction

        /** 双边冲突 → 远端存冲突副本 + 本地上行；[deleteOldPath] 非空表示这同时是一次改名。 */
        data class Conflict(
            val docId: String,
            val fileName: String,
            val remoteEtag: String?,
            val deleteOldPath: String? = null
        ) : SyncAction

        /** 无变化 → 仅刷新基线。 */
        data class Skip(val docId: String, val fileName: String, val remoteEtag: String?, val localHash: String) : SyncAction

        /** 本地已删除、远端文件还在 → 删远端，成功后清掉墓碑。 */
        data class DeleteRemote(val docId: String, val fileName: String) : SyncAction

        /** 远端已删除、本地自基线以来没改过 → 删本地文档。 */
        data class DeleteLocal(val docId: String, val fileName: String) : SyncAction

        /**
         * 墓碑已无意义（远端文件本来就不在、或那个名字已被另一篇活着的文档接手）→ 只清墓碑。
         *
         * 单独立一个动作而不是在计划里静默丢掉：墓碑留在库里就是一颗延时炸弹，
         * 每轮同步都要重新判一次，而一旦某天远端出现同名文件就会被它删掉。
         */
        data class DropTombstone(val docId: String) : SyncAction
    }

    fun plan(
        locals: List<LocalDocInfo>,
        remotes: List<RemoteFileInfo>,
        records: List<SyncRecordInfo>,
        tombstones: List<TombstoneInfo> = emptyList()
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

            /**
             * 本地改过名（基线里的远端路径 ≠ 现在算出的文件名）。
             *
             * 这一支**必须**在下面比 etag 之前分出来：基线里的 `remoteEtag` 描述的是**旧**文件名
             * 那个文件，而 `remoteByName[local.fileName]` 取到的是**新**名字下的文件——两者根本不是
             * 同一份东西，拿它们比大小毫无意义。
             *
             * 旧实现只在「新名字在远端不存在」时才认改名，于是「改名撞上远端已有的同名文件」会一路
             * 落到下面的 etag 比较里：陌生文件的 etag 当然与旧文件的基线不等 → `remoteChanged`
             * 为真，而只改名没改正文时 `localChanged` 为假 → 判成 `DownloadOverwrite`，
             * **用一份毫不相干的文档内容覆盖掉本地正文**。更糟的是旧文件名没进 handledRemoteNames，
             * 它紧接着又被当成「远端独有」拉成一篇新文档——用户只是改了个名字，结果正文变成别人的，
             * 旁边还多出一篇重复文档。
             */
            val renamedFrom = record.remotePath.takeIf { it != local.fileName }
            if (renamedFrom != null) {
                // 旧路径由本次动作负责（上传后删掉），不能再被当成「远端独有」拉成重复文档
                handledRemoteNames += renamedFrom
                actions += if (remote == null) {
                    // 新名字在远端没被占：正常的改名推送
                    SyncAction.Upload(local.id, local.fileName, deleteOldPath = renamedFrom)
                } else {
                    // 新名字已被**另一份**远端文件占着（多半是另一台设备建的、本地还没拉下来）。
                    // 直接上传会把它冲掉，所以按冲突走：先把它的内容存成本地副本，再上传本地内容。
                    SyncAction.Conflict(local.id, local.fileName, remote.etag, deleteOldPath = renamedFrom)
                }
                continue
            }

            if (remote == null) {
                // 名字没变、远端文件却不见了 → 有基线在手，说明它**曾经**传上去过，
                // 现在没了只有一种解释：另一台设备把它删了。
                //
                // 但「删本地」是这份计划里唯一会销毁用户内容的动作，所以只在本地自基线以来
                // 一个字都没改时才做：本地有未同步的改动就改动优先，把它重新推上去（复活）。
                // 两个方向的误判代价完全不对称——误复活一篇，用户再删一次即可；误删一篇，
                // 那份内容在任何地方都不再存在。
                //
                // `localHash == null` 是历史空基线，判不出本地改没改，同样按复活处理。
                val unchangedSinceBaseline =
                    record.localHash != null && record.localHash == local.contentHash
                actions += if (unchangedSinceBaseline) {
                    SyncAction.DeleteLocal(local.id, local.fileName)
                } else {
                    SyncAction.Upload(local.id, local.fileName, deleteOldPath = null)
                }
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

        val localIds = locals.mapTo(mutableSetOf()) { it.id }

        // ===== 已同步、但本轮不在同步范围内的文档 =====
        // P1 只同步根级文档，所以一篇文档被移进文件夹之后就落到这里：它的 sync_state 还在
        // （那一行只在文档被删时才会被 CASCADE 带走），远端文件也还在，可本轮的 locals 里
        // 没有任何文档会认领那个文件名。不先把它登记下来，下面「远端独有」那一步就会把它
        // 重新拉成一篇根级新文档——用户只是把文档拖进了文件夹，手里却多出一份重复。
        //
        // 登记之后那个远端文件成为孤儿（不再被更新），这是当前的已知取舍：文件夹层级的
        // 同步是后续的事，而「多出一篇重复文档」是用户立刻就会看到的错误。
        for (record in records) {
            if (record.docId !in localIds) handledRemoteNames += record.remotePath
        }

        // ===== 本地删除 → 推到远端 =====
        // 必须排在下面「远端独有」之前：墓碑要先把自己那个远端文件名登记进 handledRemoteNames，
        // 否则同一轮里它立刻又被当成「远端独有」拉成一篇新文档——被删掉的文档就地复活，
        // 这正是墓碑表存在的原因。
        for (tomb in tombstones) {
            when {
                // 这个 id 又有活着的文档了（从历史版本恢复、或另一台设备把它同步回来）→ 墓碑作废
                tomb.docId in localIds -> actions += SyncAction.DropTombstone(tomb.docId)
                // 那个远端文件名已经归另一篇活着的文档（或某次改名的旧路径）处置 → 绝不能删，
                // 那已经不是墓碑主人的文件了。典型场景：删掉「随笔」后又新建了一篇「随笔」。
                tomb.remotePath in handledRemoteNames -> actions += SyncAction.DropTombstone(tomb.docId)
                else -> {
                    handledRemoteNames += tomb.remotePath
                    actions += if (remoteByName.containsKey(tomb.remotePath)) {
                        SyncAction.DeleteRemote(tomb.docId, tomb.remotePath)
                    } else {
                        // 远端本来就没有了（对方也删了，或当初压根没传成）→ 无事可做，清碑
                        SyncAction.DropTombstone(tomb.docId)
                    }
                }
            }
        }

        // 远端独有（未被任何本地文档、改名旧路径或墓碑覆盖）→ 拉取为本地新文档
        for (remote in remotes) {
            if (remote.fileName in handledRemoteNames) continue
            actions += SyncAction.CreateLocal(remote.fileName, remote.etag)
        }

        return actions
    }
}
