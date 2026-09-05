package com.yumark.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * 文档全文索引（FTS4 虚拟表）。
 *
 * 与 [DocumentEntity] 是**独立表**而非 `contentEntity` 关联表：正文不在数据库里
 * （存于应用私有目录的文件），Room 的 external content 机制帮不上忙，索引内容必须由
 * 仓库层显式写入。
 *
 * [name] 与 [content] 存的都是 `FtsTextNormalizer.normalize()` 之后的文本
 * （CJK 已逐字空格化、标点已剥离），不是原文；取回原文一律走文件系统。
 *
 * 三个约束是 Room 对 FTS 实体的硬性要求，改动前先看 `FtsTableEntityProcessor`：
 * - 不能有 `indices` / `foreignKeys`（虚拟表不支持）；
 * - 最多一个主键，且列名必须是 `rowid`、亲和性必须是 INTEGER；
 * - 列名不能叫 `docid`（任意大小写）—— FTS4 把它保留为 rowid 别名，
 *   建表会直接失败（`vtable constructor failed`），所以这里叫 `doc_id`。
 */
@Entity(tableName = "document_search")
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class DocumentSearchEntity(
    /** 传 null 由 SQLite 自动分配 rowid；Room 不会把该列写进 CREATE VIRTUAL TABLE 语句 */
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int? = null,
    @ColumnInfo(name = "doc_id") val docId: String,
    val name: String,
    val content: String
)
