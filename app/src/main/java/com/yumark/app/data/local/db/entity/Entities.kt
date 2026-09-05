package com.yumark.app.data.local.db.entity

import androidx.room.*

@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["folder_id"]),
        Index(value = ["name"]),
        Index(value = ["updated_at"]),
        // 同一文件夹下不许两篇同名。代码里的 requireNoDocumentNameConflict 是「先查再写」，
        // 两个协程并发跑时各自都查到「没有重名」，然后各插一行——检查挡不住这种交错，
        // 唯一索引是最后一道闸（migration 11 → 12 一并给老库补上，并先消重历史重复行）。
        //
        // 已知局限：folder_id 可空（根目录是 NULL），而 SQLite 的唯一索引不认为两个 NULL 相等
        // （NULL != NULL），所以**根目录下的同名文档这条索引挡不住**，那一组仍然只靠
        // requireNoDocumentNameConflict 守着。表达式索引 `IFNULL(folder_id, '')` 能覆盖根目录，
        // 但 Room 的 schema 校验器不认表达式索引，迁移后启动即报 schema 不匹配；
        // 把根目录的 folder_id 从 NULL 换成空串是更大的重构，另案处理。
        //
        // 与代码检查的另一处差异：findNameConflict 比较前 trim，索引比的是原样字符串，
        // 所以「笔记」与「笔记 」在索引看来是两个名字。索引是兜底而非代码检查的镜像。
        Index(value = ["folder_id", "name"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    @ColumnInfo(name = "word_count") val wordCount: Int,
    @ColumnInfo(name = "character_count") val characterCount: Int
)

@Entity(
    tableName = "folders",
    indices = [
        Index(value = ["parent_id"]),
        // 同一父级下不许两个同名文件夹，理由与 [DocumentEntity] 上那条一致（TOCTOU 兜底）。
        // 这里还多一层：导入库镜像目录是按文件夹名逐段拼出来的，两个同名兄弟会指向同一个
        // 镜像目录，图片直接互相覆盖（见 FolderRepositoryImpl.importMirrorDir）。
        //
        // 同一处已知局限：parent_id 为 NULL 的根级文件夹不受这条索引保护（NULL != NULL），
        // 仍只靠 requireNoFolderNameConflict 守着。
        Index(value = ["parent_id", "name"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val order: Int
)

@Entity(
    tableName = "images",
    indices = [Index(value = ["document_id"])],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ImageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "document_versions",
    indices = [Index(value = ["document_id", "created_at"])],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class DocumentVersionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    val content: String,
    @ColumnInfo(name = "word_count") val wordCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * 文档与远端 WebDAV 文件的同步态（每篇一条）。
 * - [remotePath] 远端相对路径（remoteDir/文件名.md），用于改名检测。
 * - [remoteEtag] 上次同步时的远端版本标识，判断远端是否变化。
 * - [localHash] 上次同步时的本地正文哈希，判断本地是否变化。
 */
@Entity(
    tableName = "sync_state",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "remote_path") val remotePath: String,
    @ColumnInfo(name = "remote_etag") val remoteEtag: String?,
    @ColumnInfo(name = "local_hash") val localHash: String?,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long?
)

/**
 * 「这篇文档在本地被删掉了」的墓碑，供下次同步把删除推到远端。
 *
 * 为什么需要一张单独的表：删除是**唯一**一种在本地不留任何痕迹的改动。文档行一删，
 * [SyncStateEntity] 被外键 CASCADE 一起带走，于是下一次同步看到的是「远端有一个文件、
 * 本地没有对应文档」——与「另一台设备新建了一篇」完全无法区分，只能拉回来，
 * 被删掉的文档就这样复活了。墓碑把「删过」这件事显式记下来，让同步能区分二者。
 *
 * **刻意不加外键**：指向 `documents` 的外键会在文档行被删的那一刻把墓碑一起 CASCADE 掉，
 * 正好抹掉唯一需要留下的信息。这也是整张表存在的意义所在，任何时候都不要「补上」这个外键。
 *
 * 只给同步过的文档立碑：[remotePath] 来自删除前那一刻的 `sync_state`，没有同步记录就说明
 * 远端压根没有这个文件，没什么可删。
 *
 * 生命周期：远端文件删成功（或已不存在）后由同步流程清掉这一行，见 `SyncRepositoryImpl`。
 */
@Entity(tableName = "sync_tombstones")
data class SyncTombstoneEntity(
    @PrimaryKey @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "remote_path") val remotePath: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long
)
