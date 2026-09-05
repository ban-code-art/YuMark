package com.yumark.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.yumark.app.data.local.db.entity.DocumentSearchEntity

/**
 * 文档全文索引读写。
 *
 * 表是 FTS4 虚拟表，因此这里的每条 SQL 都有约束：
 * - `MATCH` 的左侧必须是表名（`document_search MATCH ?`），这样才会同时搜
 *   `name` 与 `content` 两列；写成 `content MATCH ?` 只搜正文。
 * - 绑定参数必须是 [com.yumark.app.core.search.FtsQueryBuilder] 产出的短语，
 *   直接塞用户输入会因 `"` / `NEAR/2` 抛 `malformed MATCH expression`。
 * - 虚拟表不能建索引，`WHERE doc_id = ?` 是全表扫描；单文档保存/删除的量级下
 *   可接受，但不要用它做批量循环删除。
 */
@Dao
interface DocumentSearchDao {

    /** 索引条目数，仅用于判断是否需要惰性回填（0 且文档非空 → 回填） */
    @Query("SELECT COUNT(*) FROM document_search")
    suspend fun count(): Int

    /**
     * 短语匹配，返回命中的文档 id。
     *
     * 返回顺序是 rowid 顺序（即入索引顺序），不是相关度顺序 —— 相关度排序由
     * `SearchDocumentsUseCase` 按匹配次数在内存里做。
     */
    @Query("SELECT doc_id FROM document_search WHERE document_search MATCH :match LIMIT :limit")
    suspend fun searchDocIds(match: String, limit: Int): List<String>

    @Insert
    suspend fun insert(entry: DocumentSearchEntity)

    @Query("DELETE FROM document_search WHERE doc_id = :docId")
    suspend fun deleteByDocId(docId: String)

    @Query("DELETE FROM document_search")
    suspend fun clear()

    /**
     * 按 doc_id 覆盖写。
     *
     * 不能用 `@Insert(onConflict = REPLACE)` 代替：冲突消解看的是主键 rowid，
     * 而这里的业务唯一键是 doc_id，REPLACE 只会不断追加重复行，
     * 让同一文档在搜索结果里出现多次。
     */
    @Transaction
    suspend fun upsert(entry: DocumentSearchEntity) {
        deleteByDocId(entry.docId)
        insert(entry)
    }
}
