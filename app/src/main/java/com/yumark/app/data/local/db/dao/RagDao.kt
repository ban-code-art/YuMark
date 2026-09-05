package com.yumark.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yumark.app.data.local.db.entity.ChunkEntity
import com.yumark.app.data.local.db.entity.EmbeddingEntity
import com.yumark.app.data.local.db.entity.EmbeddingJobEntity

/**
 * [RagDao.getIndexedDocumentNames] 的投影行：一篇有分块的文档，及它在 `documents` 表里的真名。
 *
 * 单独一个投影而不是把名字塞进 [ChunkEntity]：名字属于文档而不是分块，跟着分块存会在
 * 重命名之后留下一堆过期副本（rag_chunks 只在重新索引时才重写）。
 */
data class IndexedDocumentName(val documentId: String, val name: String)

@Dao
interface RagDao {
    // ---- chunks ----
    @Query("SELECT * FROM rag_chunks")
    suspend fun getAllChunks(): List<ChunkEntity>

    @Query("SELECT * FROM rag_chunks WHERE document_id = :documentId")
    suspend fun getChunksByDocument(documentId: String): List<ChunkEntity>

    /**
     * hydrate 用：每篇**有分块**的文档的真名。
     *
     * INNER JOIN 而不是 LEFT JOIN：拿不到文档行的分块在这里就该缺席，让调用方走它自己的兜底，
     * 而不是收到一个 name 为 null 的行再判空。外键是 CASCADE，正常情况下不存在这种孤儿分块。
     */
    @Query(
        """
        SELECT c.document_id AS documentId, d.name AS name
        FROM rag_chunks c JOIN documents d ON d.id = c.document_id
        GROUP BY c.document_id
        """
    )
    suspend fun getIndexedDocumentNames(): List<IndexedDocumentName>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<ChunkEntity>)

    @Query("DELETE FROM rag_chunks WHERE document_id = :documentId")
    suspend fun deleteChunksByDocument(documentId: String)   // embeddings 经外键级联删除

    // ---- embeddings ----
    @Query("SELECT * FROM rag_embeddings")
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>

    @Query("SELECT * FROM rag_embeddings WHERE chunk_id IN (:chunkIds)")
    suspend fun getEmbeddings(chunkIds: List<String>): List<EmbeddingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>)

    // ---- 索引替换（跨两张表，必须同事务）----

    /**
     * 原子替换一篇文档的索引：删旧分块（向量随外键级联删）→ 插新分块 → 插新向量。
     *
     * 三步分开提交过一次就够出事：任一步之后进程被杀（或抛异常），库里会留下**有分块、没向量**
     * 的半成品。这种库不会报错，只会让检索静默退化成纯关键词；而那条 job 早已被标成 failed，
     * 之后再没有任何路径会自动重建它——用户永远等不到向量回来，也看不出哪里坏了。
     *
     * 事务同时把「先删后插」的中间态挡在库外：并发的 hydrate 不会读到一篇文档只剩一半分块的瞬间。
     */
    @Transaction
    suspend fun replaceDocumentIndex(
        documentId: String,
        chunks: List<ChunkEntity>,
        embeddings: List<EmbeddingEntity>
    ) {
        deleteChunksByDocument(documentId)
        insertChunks(chunks)
        insertEmbeddings(embeddings)
    }

    // ---- jobs ----

    /**
     * 这篇文档最近一次索引任务。`RagPipeline` 拿它做幂等判断（上一条 done 的 `content_hash`
     * 与本次正文相同就整篇跳过），所以「哪条算最近」必须是**唯一确定**的答案。
     *
     * 三段排序不是凑数：`created_at` 逐条递增（每条新任务都用当时的 now，只有原地复用同一行时
     * 才保留原值，见 `RagPipeline.indexDocument`），正常情况第一段就分出胜负；同毫秒建的两条
     * 落到第二段比谁更晚被改；主键兜最后一段，保证并列时也只有一个答案。
     *
     * 旧版本只写 `ORDER BY created_at DESC LIMIT 1`，而那时 `indexDocument` 又把上一条的
     * `created_at` 抄进每条新任务——同文档所有行的排序键全等，SQLite 返回哪条纯看扫描顺序，
     * 很可能给出同文档的**老**任务。后果是两种都不报错的故障：内容没变却比到老哈希 →
     * 每次保存重新 embedding（按量计费的接口就是在烧钱）；把文档改回旧内容时反而命中旧哈希 →
     * 跳过重建，库里留着新内容的分块，检索从此答错且没有任何路径会自愈。
     */
    @Query("SELECT * FROM rag_embedding_jobs WHERE document_id = :documentId ORDER BY created_at DESC, updated_at DESC, id DESC LIMIT 1")
    suspend fun getLatestJob(documentId: String): EmbeddingJobEntity?

    /**
     * 未收敛的任务行。查 `running` 才是重点：进程被杀（划掉后台、系统回收、崩溃）时任务就停在
     * `running`，只查 `'pending'` 的旧版本一条也捞不到——而 `pending` 这个状态**没有任何代码写过**，
     * 于是 `RagPipeline` 那段崩溃恢复从上线起一直在空转。`pending` 仍留在条件里是为了认出早期
     * 版本可能写进库的行：这张表不迁移，行还在。
     */
    @Query("SELECT * FROM rag_embedding_jobs WHERE status IN ('pending', 'running') ORDER BY created_at ASC")
    suspend fun getUnfinishedJobs(): List<EmbeddingJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: EmbeddingJobEntity)

    @Query("UPDATE rag_embedding_jobs SET status = :status, error = :error, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateJobStatus(id: String, status: String, error: String?, updatedAt: Long)

    /**
     * 同文档只留 [keepId] 这一行，其余历史任务行删掉。
     *
     * 没有这一步，表就只涨不减：每次保存成功堆一行、每次索引失败也堆一行（失败行还带一段
     * error 文本），而 [getLatestJob] 从头到尾只看最新那条，旧行一点用都没有。顺带把
     * 上次进程留下的僵尸行一并带走——它们跟被留下的这行是同一篇文档。
     *
     * 不需要 `deleteJobsByDocument(documentId)`：`EmbeddingJobEntity` 对 `documents.id` 声明了
     * `onDelete = CASCADE`（见 RagEntities.kt），删文档时任务行由数据库自己清掉。原来那个零调用方的
     * 方法已删，别再加回来——它只会让人以为级联不存在。
     */
    @Query("DELETE FROM rag_embedding_jobs WHERE document_id = :documentId AND id != :keepId")
    suspend fun pruneJobsExcept(documentId: String, keepId: String)

    // ---- stats ----
    @Query("SELECT COUNT(*) FROM rag_chunks")
    suspend fun chunkCount(): Int

    @Query("SELECT COUNT(DISTINCT document_id) FROM rag_chunks")
    suspend fun documentCount(): Int

    /**
     * 属于某个 embedding 模型的向量条数 = 「还能做向量检索的分块数」。
     *
     * `chunk_id` 是 rag_embeddings 的主键（一块最多一条向量），且分块删除会级联删向量，
     * 所以这个计数不会算上已消失的分块，直接与 [chunkCount] 相减就是只能靠关键词命中的部分。
     * 用 COUNT 而不是把 [getAllEmbeddings] 拉进内存过滤：向量是几 KB 的 JSON 字符串，
     * 只为报一个数字就全表反序列化一遍不值当。
     */
    @Query("SELECT COUNT(*) FROM rag_embeddings WHERE model = :model")
    suspend fun embeddingCountByModel(model: String): Int
}
