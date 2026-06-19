package com.yumark.app.data.local.db.entity

import androidx.room.*

@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["folder_id"]),
        Index(value = ["name"]),
        Index(value = ["updated_at"])
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
    indices = [Index(value = ["parent_id"])],
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
