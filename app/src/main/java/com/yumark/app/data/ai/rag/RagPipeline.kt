package com.yumark.app.data.ai.rag

import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.data.local.db.dao.RagDao
import com.yumark.app.data.local.db.entity.ChunkEntity
import com.yumark.app.data.local.db.entity.EmbeddingEntity
import com.yumark.app.data.local.db.entity.EmbeddingJobEntity
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.model.ragBaseUrlResolved
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
 * 写进 `rag_embedding_jobs.error` 的技术细节字符上限。
 *
 * 比 [ErrorHandler.MAX_DETAIL_CHARS]（120，那个数字是按 Snackbar 一两行的容量定的）宽，沿用本文件
 * 原来的 300：这条记录只落库、只给排查看，多留点原文能省一次复现。
 */
private const val JOB_ERROR_MAX_CHARS = 300

/**
 * RAG 索引与检索管线 —— 移植自 guanmo `ragPipeline.ts`（Kotlin 重写）。
 *
 * - **索引**：文档保存后入队（[enqueueIndex]），后台协程 chunk → contentHash 去重 → 批量 embedding → 落库 + 更新内存。
 * - **检索**：[searchRelevant] 首次时从 DB hydrate（只装载当前 embedding 模型的向量，见
 *   [hydrateIfEmpty]），查询向量 + 关键词混合；embedding 不可用时退化为纯关键词。
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

    /**
     * 上一次 hydrate 生效时的 embedding 模型（null = 还没 hydrate 过；`""` = 当时没配模型）。
     *
     * 记模型名而不是一个 `hydrated` 布尔：内存里的向量属于某一个向量空间，用户换掉 embedding 模型
     * 之后（维度不同、语义也不同），旧向量再参与余弦计算只是在给一个随机排序背书——维度不等时
     * [VectorStore] 里的 `cosine` 返回 0f 直接沉底，维度碰巧相等时给出的名次毫无意义，而界面上写的
     * 是「知识库检索结果」。一次性布尔挡不住这件事：换了模型内存里还是旧的那一份。
     */
    @Volatile private var hydratedEmbeddingModel: String? = null

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

    /**
     * 知识库状态。给模型看的工具返回值，所以字面量直接写在这里（不是 UI 资源）。
     *
     * 口径全部取自 DB 而不是内存 [vectorStore]：内存那份是「当前模型能用的子集」，用它报总数会
     * 在换过模型之后凭空少掉一批分块，模型据此告诉用户「知识库是空的」——文档其实都还在。
     * 「有多少能做向量检索」必须单独报：这两个数一旦不等，检索质量就已经退化成关键词匹配了，
     * 而这件事在结果列表里看不出来（[SearchResult.retrievalMode] 只进日志级别的细节）。
     */
    private suspend fun stats(): String {
        hydrateIfEmpty()
        val chunks = ragDao.chunkCount()
        if (chunks == 0) {
            return "知识库尚未建立索引（0 篇文档 / 0 个分块）。保存文档后会自动索引。"
        }
        val docs = ragDao.documentCount()
        val model = currentEmbeddingModel()
        val vectorReady = if (model.isBlank()) 0 else ragDao.embeddingCountByModel(model)
        val head = "知识库统计：已索引 ${docs} 篇文档、共 ${chunks} 个分块"
        return when {
            model.isBlank() ->
                "${head}，其中 0 个可做向量检索。当前未配置 embedding 模型，只能做关键词检索" +
                    "——在 AI 设置里填上 embedding 模型并重新保存文档，才会建立向量索引。"
            vectorReady == 0 ->
                "${head}，其中 0 个可做向量检索。已有向量都不是当前 embedding 模型（${model}）产出的，" +
                    "只能做关键词检索——重新保存这些文档以按新模型重建索引。"
            vectorReady < chunks ->
                "${head}，其中 ${vectorReady} 个可做向量检索（模型 ${model}）。" +
                    "余下 ${chunks - vectorReady} 个分块只能被关键词命中，重新保存对应文档即可补齐。"
            else -> "${head}，全部可做向量检索（模型 ${model}）。"
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
        // 用户选了「单独配置 embedding 端点」却没填地址时，resolved 是空串。不在这里回落到 chat 的
        // baseUrl（那是把密钥发去他没指定的服务器），也不放它拼成 "/embeddings" 去撞一个看不懂的
        // 网络异常。两个调用点都是 runCatching{}.getOrNull()，抛出去就退化成纯关键词检索。
        if (config.ragBaseUrlResolved.isBlank()) throw IllegalStateException("未配置 embedding 端点地址")
        val adapter = adapterFactory.createEmbeddingAdapter(config)
        return adapter.embed(listOf(query), config.embeddingModel).first()
    }

    /** 当前配置的 embedding 模型；未配置时为空串（不抛异常——纯关键词检索是合法状态）。 */
    private suspend fun currentEmbeddingModel(): String =
        configRepository.observeConfig().first().embeddingModel

    // ---------- 索引 ----------

    /**
     * 文档保存后入队索引（由 EditorViewModel 调用）。内容未变化则跳过；否则后台异步重建索引。
     * 内部协程执行，不阻塞调用方。
     */
    fun enqueueIndex(documentId: String, documentName: String, content: String) {
        // 原来这里还跟了一句 drainPendingJobsExcluding(documentId)「顺带清同文档积压任务」，已删：
        // 同一篇文档不可能有第二个活着的任务（[indexDocument] 全程持 [indexMutex]），它能看见的行
        // 只有两种——本进程刚排到的**后继**任务（标 failed 就是误杀），或上次进程留下的僵尸行
        // （归 [reclaimUnfinishedJobs] 与 `pruneJobsExcept` 管）。而它排序取的是 `created_at ASC`
        // 再 `drop(1)`，留最老、杀最新，方向本身就是反的。之所以一直没出事：它查的 `'pending'`
        // 从来没有代码写过，这段等于没执行。
        scope.launch { indexMutex.withLock { indexDocument(documentId, documentName, content) } }
    }

    /**
     * 把一篇文档从索引里整个摘掉：DB 分块（向量随外键级联）+ 内存 [VectorStore]。
     *
     * 回收站的「移入」与「彻底删除」都要调（彻底删除时文档行没了，分块行会随外键级联消失，
     * 但内存里的向量不会自己消失，不摘的话本轮会话内 knowledge 检索继续命中幽灵分块）；
     * 文档恢复后由调用方重新 [enqueueIndex] 补回索引。
     *
     * 挂 [indexMutex] 与 [indexDocument] 串行：并发的重建/摘除交错时，后执行者胜，
     * 与「同一文档不可能有第二个活着的任务」的前提一致。
     */
    suspend fun removeFromIndex(documentId: String) {
        indexMutex.withLock {
            ragDao.deleteChunksByDocument(documentId)
            ragDao.failUnfinishedJobs(
                documentId = documentId,
                error = "document removed from index",
                updatedAt = System.currentTimeMillis()
            )
            vectorStore.removeDocument(documentId)
        }
    }

    /**
     * 内存未装载、**或装载时用的 embedding 模型已经不是当前配置的那个**时，从 DB 全量 hydrate；
     * 并回收上次进程留下的未收敛任务。名字沿用「IfEmpty」，判据见 [hydratedEmbeddingModel]。
     *
     * 只装载与当前模型一致的向量。不一致的那批**不是**丢掉分块——分块照旧进内存，
     * [VectorStore.keywordSearch] / [VectorStore.hybridSearch] 对没有向量的分块本来就成立
     * （`search` 里 `embeddings[chunk.id] ?: continue`），所以它们仍然能被关键词命中；
     * 丢的只是「拿旧向量空间的坐标去和新查询向量比距离」这个动作。
     *
     * 模型为空（用户没配 embedding）时一个向量都不装，直接进纯关键词模式——这是合法状态，不报错。
     *
     * 配置读取刻意放在 `withLock` **外面**：[indexDocument] 也持这把锁，而它同样要读配置，
     * 在锁内做任何可能挂起等待外部数据源的事都是在给死锁攒条件。锁内只做「读库 + 换内存」。
     */
    suspend fun hydrateIfEmpty() {
        val model = currentEmbeddingModel()
        if (hydratedEmbeddingModel == model) return
        indexMutex.withLock {
            if (hydratedEmbeddingModel == model) return@withLock
            val chunks = ragDao.getAllChunks().map { it.toDomain() }
            val embeddings = if (model.isBlank()) {
                emptyMap<String, FloatArray>()
            } else {
                ragDao.getAllEmbeddings()
                    .filter { it.model == model }
                    .associate { it.chunkId to decodeEmbedding(it.embedding) }
            }
            // 文档真名只有 documents 表有。从前这里拿「第一个分块的小标题」顶替，连标题都没有时
            // 干脆是一串 UUID —— 而热路径（indexDocument → upsertDocument）用的是真名，于是
            // 冷启动 hydrate 之后同一篇文档悄悄改了名。后果有两处：docName 那 1.4/1.6 的关键词
            // 加权（VectorStore.kt:158,163）从此按错的名字算（查真实标题一分不加，查第一节标题
            // 反而连加两次 heading 1.8 + docName 1.4，UUID 还能贡献莫名的子串命中）；而
            // documentName 会原样拼进模型读到的检索结果（见 search_knowledge 那段），
            // 模型据此把出处报成一个根本不存在的文档。
            val names = ragDao.getIndexedDocumentNames().associate { it.documentId to it.name }
            val docs = chunks.groupBy { it.documentId }
                // JOIN miss 才退回旧表达式：文档行被删而分块还在（外键是 CASCADE，正常不会发生）。
                .map { (docId, list) -> DocMeta(docId, names[docId] ?: list.firstOrNull()?.heading ?: docId) }
            vectorStore.hydrate(chunks, embeddings, docs)
            hydratedEmbeddingModel = model
            // 回收僵尸任务必须在**锁内**：它把 running 行标 failed，而本进程正在跑的那条也是 running。
            // 放在锁外（原来的写法）就有机会把活着的任务判死，等它跑完再写 done，状态一来一回自相矛盾。
            // 不额外阻塞谁：上面的 hydrate 本来就持这把锁，检索路径该等的已经等了。
            reclaimUnfinishedJobs()
        }
    }

    /**
     * 索引一篇文档：chunk → 批量 embedding → 原子落库 + 更新内存。
     *
     * `internal` 而不是 `private`：单元测试直接调它。走 [enqueueIndex] 的话索引发生在
     * `Dispatchers.IO` 上的 `launch` 里，`runTest` 的调度器等不到它，用例只能靠 sleep 碰运气。
     * 生产代码仍然只从 [enqueueIndex] 进来（进来时已持 [indexMutex]）。
     */
    internal suspend fun indexDocument(documentId: String, documentName: String, content: String) {
        val now = System.currentTimeMillis()
        val contentHash = createContentHash(content)

        // 幂等：若最近一次 done 任务的内容哈希相同，则跳过
        val latest = ragDao.getLatestJob(documentId)
        if (latest != null && latest.status == "done" && latest.contentHash == contentHash) return

        // 复用上一条未收敛的行：`pending` 是历史状态（没有代码写过），`running` 只可能是上次被取消
        // 或被杀留下的僵尸——[indexDocument] 全程持 [indexMutex]，同一篇文档不存在第二个**活着**的任务。
        // 复用是为了不让废行堆在表里；真正的清理靠下面的 `settle` 与启动时的 [reclaimUnfinishedJobs]。
        val reusable = latest?.takeIf { it.status == "pending" || it.status == "running" }
        val jobId = reusable?.id ?: UUID.randomUUID().toString()
        ragDao.upsertJob(
            // createdAt 只在原地复用同一行时保留原值。无条件抄 `latest.createdAt`（原来的写法）会让
            // 这篇文档所有任务行的排序键全等，[RagDao.getLatestJob] 当场退化成「随便给一条」——
            // 那条查询的注释里写了这么做会烧钱、还会让改回旧内容的文档留着错的索引。
            EmbeddingJobEntity(jobId, documentId, "running", contentHash, null, reusable?.createdAt ?: now, now)
        )

        // 收尾：写终态 + 删掉这篇文档其它历史任务行。两件事必须一起做——只写终态的话，
        // 表会随着保存次数（和失败次数）只涨不减，而真正被读的永远只是最新那一条。
        suspend fun settle(status: String, error: String?) {
            ragDao.updateJobStatus(jobId, status, error, System.currentTimeMillis())
            ragDao.pruneJobsExcept(documentId, jobId)
        }

        try {
            val chunks = MarkdownChunker.chunkMarkdown(content, documentId)
            if (chunks.isEmpty()) {
                // 无有效内容：清旧分块，直接置 done
                ragDao.deleteChunksByDocument(documentId)
                vectorStore.removeDocument(documentId)
                settle("done", null)
                return
            }

            val config = configRepository.observeConfig().first()
            if (config.embeddingModel.isBlank()) {
                settle("failed", "未配置 embedding 模型")
                return
            }
            // 与 [embedQuery] 同一道门：单独配置了 embedding 端点但地址为空时就地失败，
            // 报的是「地址没填」而不是让请求发出去换回一条网络异常。
            if (config.ragBaseUrlResolved.isBlank()) {
                settle("failed", "未配置 embedding 端点地址")
                return
            }
            val adapter = adapterFactory.createEmbeddingAdapter(config)

            // 批量 embedding（分批，按 index 对齐）
            val vectors = mutableListOf<FloatArray>()
            for (batch in chunks.chunked(EMBED_BATCH_SIZE)) {
                vectors.addAll(adapter.embed(batch.map { it.content }, config.embeddingModel))
            }

            // 数量对不上就地失败，**必须在动库之前**。
            // 后面 chunks 与 vectors 是按下标硬对齐的（`vectors[i]`）：provider 少返回一条
            // （部分返回、响应被截断、某些兼容实现对空白输入直接跳过）就会越界抛异常。等到那时旧分块
            // 已经删了、新分块已经插了，库里只剩「有分块没向量」，而 job 被标 failed 之后没有任何
            // 路径会自动重建——旧索引就这么被一次失败的请求换成了半成品。在这里抛，旧索引一字未动，
            // 检索继续用得上，下次保存还能重试。
            check(vectors.size == chunks.size) {
                "embedding 数量与分块数不一致：期望 ${chunks.size} 条，实际收到 ${vectors.size} 条，" +
                    "本次不改动已有索引"
            }

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
            val embeddingEntities = chunks.mapIndexed { i, c ->
                EmbeddingEntity(c.id, encodeEmbedding(vectors[i]), config.embeddingModel, now)
            }
            // 替换走单个事务（删旧分块 → 插新分块 → 插向量，见 RagDao.replaceDocumentIndex）：
            // 三次独立写之间被杀进程，留下的同样是「有分块没向量」的库。
            ragDao.replaceDocumentIndex(documentId, chunkEntities, embeddingEntities)

            // 更新内存
            vectorStore.upsertDocument(documentId, documentName, chunks)
            chunks.forEachIndexed { i, c -> vectorStore.setEmbedding(c.id, vectors[i]) }
            settle("done", null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消时不改 job 状态：已取消的协程里再调 ragDao 只会立刻又抛（写不进去），
            // 而把它留在 running 才是对的——这条行下次会被复用或被启动时的回收标成 failed，
            // 两条路都指向「这篇文档得重建索引」。
            throw e
        } catch (e: Exception) {
            // 原始 message 不能直接落库：Ktor 的异常消息带完整请求 URL（Gemini 的密钥就在 `?key=` 里），
            // OpenAI 兼容端点鉴权失败时还会把 `Authorization: Bearer …` 回显出来。这张表用户可导出，
            // 也会被贴进 issue，所以走 safeDetail（脱敏 → 压平 → 截断）而不是 message?.take(n)。
            settle("failed", ErrorHandler.safeDetail(e, JOB_ERROR_MAX_CHARS))
        }
    }

    /**
     * 回收上次进程留下的未收敛任务（[RagDao.getUnfinishedJobs]：`running` / 历史 `pending`）。
     *
     * 内存里没有文档正文，重建不了，只能标 failed 让状态不再是「跑着」——下次保存这篇文档时
     * [indexDocument] 会因为「最近一条不是 done」而重新索引，索引成功再把这些行清掉。
     *
     * 调用点在 [hydrateIfEmpty] 的锁内，且那里对同一个 embedding 模型只会走一次，
     * 所以这段实际就是「每次冷启动后的第一次检索顺手做一遍清理」。
     */
    private suspend fun reclaimUnfinishedJobs() {
        val unfinished = ragDao.getUnfinishedJobs()
        if (unfinished.isEmpty()) return
        val now = System.currentTimeMillis()
        unfinished.forEach { job ->
            ragDao.updateJobStatus(job.id, "failed", "进程中断，请重新保存文档以重建索引", now)
        }
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
