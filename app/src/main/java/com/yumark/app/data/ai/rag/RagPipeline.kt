package com.yumark.app.data.ai.rag

import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.data.local.db.dao.RagDao
import com.yumark.app.data.local.db.entity.ChunkEntity
import com.yumark.app.data.local.db.entity.EmbeddingEntity
import com.yumark.app.data.local.db.entity.EmbeddingJobEntity
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.repository.AiConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val EMBED_BATCH_SIZE = 32
private const val SIMILARITY_THRESHOLD = 0.5f

/**
 * RAG 索引与检索管线 —— 移植自 guanmo `ragPipeline.ts`（Kotlin 重写）。
 *
 * - **索引**：文档保存后入队（[enqueueIndex]），后台协程 chunk → contentHash 去重 → 批量 embedding → 落库 + 更新内存。
 * - **检索**：[searchRelevant] 首次时从 DB hydrate，查询向量 + 关键词混合；embedding 不可用时退化为纯关键词。
 * - **工具执行**：[execute] 分发 search_knowledge / knowledge_stats，供 Agent 循环调用。
 *
 * 幂等：同一文档内容哈希未变则跳过重复索引（避免重复付费）。
 */
@Singleton
class RagPipeline @Inject constructor(
    private val ragDao: RagDao,
    private val vectorStore: VectorStore,
    private val adapterFactory: AiAdapterFactory,
    private val configRepository: AiConfigRepository
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val indexMutex = Mutex()
    @Volatile private var hydrated = false

    // ---------- 工具执行 ----------

    suspend fun execute(toolCall: ToolCall): Result<String> = runCatching {
        val args = json.decodeFromString<Map<String, JsonElement>>(toolCall.arguments)
        when (toolCall.name) {
            "search_knowledge" -> searchKnowledge(args)
            "knowledge_stats" -> stats()
            else -> error("未知知识库工具: ${toolCall.name}")
        }
    }

    private suspend fun searchKnowledge(args: Map<String, JsonElement>): String {
        val query = args["query"]?.jsonPrimitive?.content?.trim()
            ?: error("缺少参数: query")
        val topK = args["top_k"]?.jsonPrimitive?.intOrNull ?: 5

        hydrateIfEmpty()
        if (vectorStore.isEmpty()) {
            return "知识库为空。请在 AI 设置中配置 embedding 模型，并保存文档以建立索引。"
        }

        val queryEmbedding = runCatching { embedQuery(query) }.getOrNull()
        val results = vectorStore.hybridSearch(query, queryEmbedding, topK, SIMILARITY_THRESHOLD, preferCurrentDocId = null)

        return if (results.isEmpty()) {
            "未在知识库中找到与\"$query\"相关的内容。"
        } else {
            "知识库检索结果（\"$query\"，共 ${results.size} 条，按相关度排序）：\n\n" +
                results.joinToString("\n\n") { r ->
                    val title = buildString {
                        r.documentName.takeIf { it.isNotBlank() }?.let { append(it) }
                        if (r.chunk.titlePath.isNotEmpty()) {
                            if (isNotEmpty()) append(" › ")
                            append(r.chunk.titlePath.joinToString(" › "))
                        }
                        r.chunk.heading?.takeIf { it.isNotBlank() && it !in r.chunk.titlePath }?.let {
                            if (isNotEmpty()) append(" › ")
                            append(it)
                        }
                    }.ifBlank { r.documentName.ifBlank { "(无标题)" } }
                    "[$title | 相关度 ${"%.2f".format(r.score)} | ${r.retrievalMode}]\n${r.chunk.content}"
                }
        }
    }

    private suspend fun stats(): String {
        hydrateIfEmpty()
        val docs = vectorStore.documentCount()
        val chunks = vectorStore.chunkCount()
        return if (chunks == 0) {
            "知识库尚未建立索引（0 篇文档 / 0 个分块）。保存文档后会自动索引。"
        } else {
            "知识库统计：已索引 $docs 篇文档，共 $chunks 个分块。"
        }
    }

    // ---------- 检索 ----------

    /** 供需要原始结果的调用方使用：返回 topK 检索结果（已 hydrate）。 */
    suspend fun searchRelevant(query: String, topK: Int = 5, preferCurrentDocId: String? = null): List<SearchResult> {
        hydrateIfEmpty()
        if (vectorStore.isEmpty()) return emptyList()
        val queryEmbedding = runCatching { embedQuery(query) }.getOrNull()
        return vectorStore.hybridSearch(query, queryEmbedding, topK, SIMILARITY_THRESHOLD, preferCurrentDocId)
    }

    private suspend fun embedQuery(query: String): FloatArray {
        val config = configRepository.observeConfig().first()
        if (config.embeddingModel.isBlank()) throw IllegalStateException("未配置 embedding 模型")
        val adapter = adapterFactory.createEmbeddingAdapter(config)
        return adapter.embed(listOf(query), config.embeddingModel).first()
    }

    // ---------- 索引 ----------

    /**
     * 文档保存后入队索引（由 EditorViewModel 调用）。内容未变化则跳过；否则后台异步重建索引。
     * 内部协程执行，不阻塞调用方。
     */
    fun enqueueIndex(documentId: String, documentName: String, content: String) {
        scope.launch {
            indexMutex.withLock { indexDocument(documentId, documentName, content) }
            drainPendingJobsExcluding(documentId)  // 顺带处理其它积压任务
        }
    }

    /** 首次检索时若内存为空，从 DB 全量 hydrate；并消费崩溃前残留的 pending 任务。 */
    suspend fun hydrateIfEmpty() {
        if (hydrated) return
        indexMutex.withLock {
            if (hydrated) return@withLock
            val chunks = ragDao.getAllChunks().map { it.toDomain() }
            val embeddings = ragDao.getAllEmbeddings().associate { it.chunkId to decodeEmbedding(it.embedding) }
            val docs = chunks.groupBy { it.documentId }
                .map { (docId, list) -> DocMeta(docId, list.firstOrNull()?.heading ?: docId) }
            vectorStore.hydrate(chunks, embeddings, docs)
            hydrated = true
        }
        // 消费崩溃前残留的 pending/failed 任务（需重新读文档内容，交给 ViewModel 触发；此处仅清空残留失败任务标记以便下次重试）
        drainPendingJobs()
    }

    /** 索引一篇文档：chunk → 批量 embedding → 落库 + 更新内存。 */
    private suspend fun indexDocument(documentId: String, documentName: String, content: String) {
        val now = System.currentTimeMillis()
        val contentHash = createContentHash(content)

        // 幂等：若最近一次 done 任务的内容哈希相同，则跳过
        val latest = ragDao.getLatestJob(documentId)
        if (latest != null && latest.status == "done" && latest.contentHash == contentHash) return

        val jobId = latest?.takeIf { it.status == "pending" }?.id ?: UUID.randomUUID().toString()
        ragDao.upsertJob(
            EmbeddingJobEntity(jobId, documentId, "running", contentHash, null, latest?.createdAt ?: now, now)
        )

        try {
            val chunks = MarkdownChunker.chunkMarkdown(content, documentId)
            if (chunks.isEmpty()) {
                // 无有效内容：清旧分块，直接置 done
                ragDao.deleteChunksByDocument(documentId)
                vectorStore.removeDocument(documentId)
                ragDao.updateJobStatus(jobId, "done", null, now)
                return
            }

            val config = configRepository.observeConfig().first()
            if (config.embeddingModel.isBlank()) {
                ragDao.updateJobStatus(jobId, "failed", "未配置 embedding 模型", now)
                return
            }
            val adapter = adapterFactory.createEmbeddingAdapter(config)

            // 批量 embedding（分批，按 index 对齐）
            val vectors = mutableListOf<FloatArray>()
            for (batch in chunks.chunked(EMBED_BATCH_SIZE)) {
                vectors.addAll(adapter.embed(batch.map { it.content }, config.embeddingModel))
            }

            // 替换：先删旧分块（向量级联删除），再插新
            ragDao.deleteChunksByDocument(documentId)
            val chunkEntities = chunks.mapIndexed { i, c ->
                ChunkEntity(
                    id = c.id,
                    documentId = documentId,
                    content = c.content,
                    contentHash = c.contentHash,
                    titlePath = json.encodeToString(ListSerializer(String.serializer()), c.titlePath),
                    heading = c.heading,
                    sourceType = c.sourceType,
                    chunkIndex = i,
                    startLine = c.startLine,
                    endLine = c.endLine,
                    createdAt = now
                )
            }
            ragDao.insertChunks(chunkEntities)
            ragDao.insertEmbeddings(
                chunks.mapIndexed { i, c ->
                    EmbeddingEntity(c.id, encodeEmbedding(vectors[i]), config.embeddingModel, now)
                }
            )

            // 更新内存
            vectorStore.upsertDocument(documentId, documentName, chunks)
            chunks.forEachIndexed { i, c -> vectorStore.setEmbedding(c.id, vectors[i]) }
            ragDao.updateJobStatus(jobId, "done", null, System.currentTimeMillis())
        } catch (e: Exception) {
            ragDao.updateJobStatus(jobId, "failed", e.message?.take(300), System.currentTimeMillis())
        }
    }

    /** 消费所有 pending 任务（崩溃恢复）。因内存无文档正文，仅能跳过——真实重建由下次保存触发。 */
    private suspend fun drainPendingJobs() {
        val pending = ragDao.getPendingJobs()
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        pending.forEach { job ->
            // 无正文无法重建：标记 failed 提示需重新保存该文档以重建索引
            ragDao.updateJobStatus(job.id, "failed", "进程中断，请重新保存文档以重建索引", now)
        }
    }

    /** 入队路径处理完当前文档后，清空其它同文档积压任务（去重）。 */
    private suspend fun drainPendingJobsExcluding(documentId: String) {
        val pending = ragDao.getPendingJobs().filter { it.documentId == documentId }
        if (pending.size <= 1) return
        val now = System.currentTimeMillis()
        pending.drop(1).forEach { ragDao.updateJobStatus(it.id, "failed", "被后续保存覆盖", now) }
    }

    // ---------- 编解码 ----------

    private fun encodeEmbedding(vec: FloatArray): String =
        vec.joinToString(prefix = "[", postfix = "]") { it.toString() }

    private fun decodeEmbedding(str: String): FloatArray =
        json.parseToJsonElement(str).jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()

    private fun ChunkEntity.toDomain(): Chunk = Chunk(
        id = id,
        documentId = documentId,
        content = content,
        contentHash = contentHash,
        index = chunkIndex,
        startLine = startLine,
        endLine = endLine,
        titlePath = runCatching { json.decodeFromString(ListSerializer(String.serializer()), titlePath) }.getOrDefault(emptyList()),
        heading = heading,
        sourceType = sourceType
    )
}
