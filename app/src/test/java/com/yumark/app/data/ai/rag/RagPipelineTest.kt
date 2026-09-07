package com.yumark.app.data.ai.rag

import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.data.ai.adapters.EmbeddingAdapter
import com.yumark.app.data.local.db.entity.ChunkEntity
import com.yumark.app.data.local.db.dao.IndexedDocumentName
import com.yumark.app.data.local.db.dao.RagDao
import com.yumark.app.data.local.db.entity.EmbeddingEntity
import com.yumark.app.data.local.db.entity.EmbeddingJobEntity
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.repository.AiConfigRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [RagPipeline] 的索引原子性、脱敏与向量装载口径。
 *
 * 直接调 `internal indexDocument`：走 `enqueueIndex` 的话真正的索引发生在 `Dispatchers.IO` 上的
 * `launch` 里，`runTest` 的调度器等不到它，用例只能靠 sleep 碰运气。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RagPipelineTest {

    /** 按需返回向量或抛异常的假 embedding 适配器。 */
    private class FakeEmbeddingAdapter(
        private val handler: (List<String>) -> List<FloatArray>
    ) : EmbeddingAdapter {
        var callCount = 0
            private set

        override suspend fun embed(input: List<String>, model: String): List<FloatArray> {
            callCount++
            return handler(input)
        }
    }

    private val ragDao: RagDao = mockk(relaxed = true)
    private val vectorStore: VectorStore = mockk(relaxed = true)
    private val adapterFactory: com.yumark.app.data.ai.AiAdapterFactory = mockk()
    private val configRepository: AiConfigRepository = mockk()

    private val configFlow = MutableStateFlow(
        AiConfig(apiKey = "k", modelName = "m", embeddingModel = "text-embedding-3-small")
    )

    /** 足够长、能过分块「有效内容」门槛的正文。 */
    private val content = """
        # 知识库索引

        这一段是用来触发分块的正文，长度刻意超过有效内容阈值，避免被分块器当作噪声丢掉。

        ## 细节
        - 向量与分块按下标硬对齐
        - 数量对不上时旧索引必须一字不动
    """.trimIndent()

    private fun pipeline(adapter: EmbeddingAdapter): RagPipeline {
        every { configRepository.observeConfig() } returns configFlow
        every { adapterFactory.createEmbeddingAdapter(any()) } returns adapter
        return RagPipeline(ragDao, vectorStore, adapterFactory, configRepository)
    }

    /**
     * 用户选了「单独配置 embedding 端点」但地址没填：job 必须就地失败并说清原因，
     * 且**不该**碰到 embedding 适配器——放它走下去拼出来的是 "/embeddings" 这种相对路径，
     * 换回的网络异常里什么也读不出来。
     */
    @Test
    fun `单独配置模式下空地址就地失败且不发出 embedding 请求`() = runTest {
        configFlow.value = AiConfig(
            apiKey = "k",
            modelName = "m",
            embeddingModel = "text-embedding-3-small",
            ragUseMainEndpoint = false,
            ragBaseUrl = ""
        )
        val adapter = FakeEmbeddingAdapter { input -> List(input.size) { floatArrayOf(1f, 0f) } }

        pipeline(adapter).indexDocument("doc-1", "笔记", content)

        assertThat(adapter.callCount).isEqualTo(0)
        coVerify(exactly = 0) { ragDao.replaceDocumentIndex(any(), any(), any()) }
        coVerify(exactly = 1) { ragDao.updateJobStatus(any(), "failed", "未配置 embedding 端点地址", any()) }
        coVerify(exactly = 0) { ragDao.deleteChunksByDocument(any()) }
    }

    private fun expectedChunkCount(): Int = MarkdownChunker.chunkMarkdown(content, "doc-1").size

    @Test
    fun `embedding 少返回一条时旧索引一字未动`() = runTest {
        // chunks 与 vectors 按下标硬对齐。provider 少返回一条（部分返回、响应截断、对空白输入
        // 直接跳过）时若已经删了旧分块，库里只剩「有分块没向量」，而 failed 之后没有任何路径会
        // 自动重建 —— 旧索引就这么被一次失败的请求换成了半成品。
        val expected = expectedChunkCount()
        assertThat(expected).isAtLeast(1)
        val adapter = FakeEmbeddingAdapter { input -> List(input.size - 1) { floatArrayOf(1f, 0f) } }

        pipeline(adapter).indexDocument("doc-1", "笔记", content)

        coVerify(exactly = 0) { ragDao.replaceDocumentIndex(any(), any(), any()) }
        coVerify(exactly = 0) { ragDao.deleteChunksByDocument(any()) }
        coVerify(exactly = 0) { ragDao.insertChunks(any()) }
        coVerify(exactly = 0) { ragDao.insertEmbeddings(any()) }
        verify(exactly = 0) { vectorStore.upsertDocument(any(), any(), any()) }
        verify(exactly = 0) { vectorStore.setEmbedding(any(), any()) }

        val error = slot<String>()
        coVerify(exactly = 1) { ragDao.updateJobStatus(any(), "failed", capture(error), any()) }
        assertThat(error.captured).contains("数量与分块数不一致")
    }

    @Test
    fun `数量对齐时落库只走一次事务`() = runTest {
        // 删旧 → 插新 → 插向量三次独立写之间被杀进程，留下的同样是「有分块没向量」的库。
        val expected = expectedChunkCount()
        val adapter = FakeEmbeddingAdapter { input -> List(input.size) { floatArrayOf(0.5f, 0.5f) } }

        pipeline(adapter).indexDocument("doc-1", "笔记", content)

        val chunks = slot<List<ChunkEntity>>()
        val embeddings = slot<List<EmbeddingEntity>>()
        coVerify(exactly = 1) { ragDao.replaceDocumentIndex("doc-1", capture(chunks), capture(embeddings)) }
        assertThat(chunks.captured).hasSize(expected)
        assertThat(embeddings.captured).hasSize(expected)
        assertThat(embeddings.captured.map { it.model }.distinct()).containsExactly("text-embedding-3-small")
        assertThat(embeddings.captured.map { it.chunkId }).containsExactlyElementsIn(chunks.captured.map { it.id })
        // 事务外的裸写一个都不该有
        coVerify(exactly = 0) { ragDao.deleteChunksByDocument(any()) }
        coVerify(exactly = 0) { ragDao.insertChunks(any()) }
        coVerify(exactly = 0) { ragDao.insertEmbeddings(any()) }
        coVerify(exactly = 1) { ragDao.updateJobStatus(any(), "done", null, any()) }
    }

    @Test
    fun `落库的失败原因经过脱敏`() = runTest {
        // 这张表用户可导出、也会被贴进 issue。Ktor 的异常消息带完整请求 URL（Gemini 的密钥就在
        // `?key=` 里），OpenAI 兼容端点鉴权失败还会回显 Authorization 头。
        val rawKey = "sk-live-ABCDEF1234567890"
        val adapter = FakeEmbeddingAdapter {
            throw RuntimeException("provider rejected key $rawKey for model text-embedding-3-small")
        }

        pipeline(adapter).indexDocument("doc-1", "笔记", content)

        val error = slot<String>()
        coVerify(exactly = 1) { ragDao.updateJobStatus(any(), "failed", capture(error), any()) }
        assertThat(error.captured).contains("sk-***")
        assertThat(error.captured).doesNotContain(rawKey)
    }

    @Test
    fun `超长失败原因被截断而不是整段落库`() = runTest {
        // 把整个响应体塞进 message 的 provider 是存在的；这条记录只给排查看，没有理由存几 KB。
        val adapter = FakeEmbeddingAdapter { throw RuntimeException("X".repeat(2000)) }

        pipeline(adapter).indexDocument("doc-1", "笔记", content)

        val error = slot<String>()
        coVerify(exactly = 1) { ragDao.updateJobStatus(any(), "failed", capture(error), any()) }
        assertThat(error.captured.length).isLessThan(400)
        assertThat(error.captured).endsWith("…")
    }

    @Test
    fun `取消不把任务标成 failed`() = runTest {
        // 已取消的协程里再调 ragDao 只会立刻又抛（写不进去）；留在 running/pending 才对，
        // 下次启动 drainPendingJobs 会提示重新保存以重建索引。
        val adapter = FakeEmbeddingAdapter { throw CancellationException("stopped") }

        val thrown = runCatching { pipeline(adapter).indexDocument("doc-1", "笔记", content) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        coVerify(exactly = 0) { ragDao.updateJobStatus(any(), "failed", any(), any()) }
        coVerify(exactly = 0) { ragDao.updateJobStatus(any(), "done", any(), any()) }
        coVerify(exactly = 0) { ragDao.replaceDocumentIndex(any(), any(), any()) }
    }

    @Test
    fun `换掉 embedding 模型后旧向量不再进内存但分块照旧`() = runTest {
        // 旧向量属于另一个向量空间：维度不等时 cosine 直接返回 0f 沉底，维度碰巧相等时给出的
        // 名次毫无意义，而界面上写的是「知识库检索结果」。分块必须留着 —— 关键词检索还得用。
        coEvery { ragDao.getAllChunks() } returns listOf(chunkEntity("c1"), chunkEntity("c2"))
        coEvery { ragDao.getAllEmbeddings() } returns listOf(
            EmbeddingEntity("c1", "[1.0,0.0]", "m1", 0L),
            EmbeddingEntity("c2", "[0.0,1.0]", "m1", 0L)
        )
        configFlow.value = configFlow.value.copy(embeddingModel = "m1")
        val rag = pipeline(FakeEmbeddingAdapter { emptyList() })

        rag.hydrateIfEmpty()
        configFlow.value = configFlow.value.copy(embeddingModel = "m2")
        rag.hydrateIfEmpty()

        val chunkArgs = mutableListOf<List<Chunk>>()
        val embArgs = mutableListOf<Map<String, FloatArray>>()
        verify(exactly = 2) { vectorStore.hydrate(capture(chunkArgs), capture(embArgs), any()) }
        assertThat(embArgs[0].keys).containsExactly("c1", "c2")
        assertThat(embArgs[1]).isEmpty()
        assertThat(chunkArgs[1].map { it.id }).containsExactly("c1", "c2")
    }

    @Test
    fun `未配置 embedding 模型时一个向量都不读`() = runTest {
        coEvery { ragDao.getAllChunks() } returns listOf(chunkEntity("c1"))
        configFlow.value = configFlow.value.copy(embeddingModel = "")

        pipeline(FakeEmbeddingAdapter { emptyList() }).hydrateIfEmpty()

        val embArgs = slot<Map<String, FloatArray>>()
        verify(exactly = 1) { vectorStore.hydrate(any(), capture(embArgs), any()) }
        assertThat(embArgs.captured).isEmpty()
        coVerify(exactly = 0) { ragDao.getAllEmbeddings() }
    }

    @Test
    fun `模型未变时不重复 hydrate`() = runTest {
        configFlow.value = configFlow.value.copy(embeddingModel = "m1")
        val rag = pipeline(FakeEmbeddingAdapter { emptyList() })

        rag.hydrateIfEmpty()
        rag.hydrateIfEmpty()

        verify(exactly = 1) { vectorStore.hydrate(any(), any(), any()) }
    }

    @Test
    fun `knowledge_stats 的口径取自库而不是内存`() = runTest {
        // 内存那份是「当前模型能用的子集」，用它报总数会在换过模型之后凭空少掉一批分块，
        // 模型据此告诉用户「知识库是空的」—— 文档其实都还在。
        coEvery { ragDao.chunkCount() } returns 10
        coEvery { ragDao.documentCount() } returns 2
        coEvery { ragDao.embeddingCountByModel("m1") } returns 4
        configFlow.value = configFlow.value.copy(embeddingModel = "m1")

        val result = pipeline(FakeEmbeddingAdapter { emptyList() })
            .execute(ToolCall("t1", "knowledge_stats", "{}"))

        assertThat(result.isSuccess).isTrue()
        val text = result.getOrThrow()
        assertThat(text).contains("2 篇文档")
        assertThat(text).contains("共 10 个分块")
        // 「能向量检索的只有一部分」必须单独报：这两个数一旦不等，检索质量已经退化成关键词匹配，
        // 而这件事在结果列表里看不出来。
        assertThat(text).contains("4 个可做向量检索")
        assertThat(text).contains("余下 6 个")
    }

    @Test
    fun `没配 embedding 模型时 knowledge_stats 说明只能关键词检索`() = runTest {
        coEvery { ragDao.chunkCount() } returns 7
        coEvery { ragDao.documentCount() } returns 1
        configFlow.value = configFlow.value.copy(embeddingModel = "")

        val text = pipeline(FakeEmbeddingAdapter { emptyList() })
            .execute(ToolCall("t1", "knowledge_stats", "{}")).getOrThrow()

        assertThat(text).contains("共 7 个分块")
        assertThat(text).contains("0 个可做向量检索")
        assertThat(text).contains("未配置 embedding 模型")
        coVerify(exactly = 0) { ragDao.embeddingCountByModel(any()) }
    }

    @Test
    fun `向量全是旧模型产出时 knowledge_stats 提示重建`() = runTest {
        coEvery { ragDao.chunkCount() } returns 5
        coEvery { ragDao.documentCount() } returns 1
        coEvery { ragDao.embeddingCountByModel("m2") } returns 0
        configFlow.value = configFlow.value.copy(embeddingModel = "m2")

        val text = pipeline(FakeEmbeddingAdapter { emptyList() })
            .execute(ToolCall("t1", "knowledge_stats", "{}")).getOrThrow()

        assertThat(text).contains("0 个可做向量检索")
        assertThat(text).contains("不是当前 embedding 模型")
        assertThat(text).contains("m2")
    }

    @Test
    fun `内容哈希未变时跳过重复索引`() = runTest {
        // 幂等：重复索引等于重复付费。
        coEvery { ragDao.getLatestJob("doc-1") } returns EmbeddingJobEntity(
            "j1", "doc-1", "done", createContentHash(content), null, 0L, 0L
        )
        val adapter = FakeEmbeddingAdapter { List(it.size) { floatArrayOf(1f) } }

        pipeline(adapter).indexDocument("doc-1", "笔记", content)

        assertThat(adapter.callCount).isEqualTo(0)
        coVerify(exactly = 0) { ragDao.upsertJob(any()) }
        coVerify(exactly = 0) { ragDao.replaceDocumentIndex(any(), any(), any()) }
        coVerify(exactly = 0) { ragDao.deleteChunksByDocument(any()) }
    }

    @Test
    fun `没配 embedding 模型时索引失败但旧分块不动`() = runTest {
        configFlow.value = configFlow.value.copy(embeddingModel = "")
        val adapter = FakeEmbeddingAdapter { List(it.size) { floatArrayOf(1f) } }

        pipeline(adapter).indexDocument("doc-1", "笔记", content)

        assertThat(adapter.callCount).isEqualTo(0)
        coVerify(exactly = 1) { ragDao.updateJobStatus(any(), "failed", "未配置 embedding 模型", any()) }
        coVerify(exactly = 0) { ragDao.deleteChunksByDocument(any()) }
        coVerify(exactly = 0) { ragDao.replaceDocumentIndex(any(), any(), any()) }
    }

    @Test
    fun `新建的任务行不再抄旧任务的创建时间`() = runTest {
        // getLatestJob 靠 created_at 排序选出「最近一条」。旧写法把上一条的 created_at 抄进每条
        // 新任务，同文档所有行的排序键全等，SQLite 返回哪条纯看扫描顺序 —— 可能给出**老**任务：
        // 内容没变却比到老哈希就每次保存都重新 embedding（按量计费即烧钱）；把文档改回旧内容时
        // 反而命中旧哈希，跳过重建，库里留着新内容的分块，检索从此答错。
        val before = System.currentTimeMillis()
        coEvery { ragDao.getLatestJob("doc-1") } returns EmbeddingJobEntity(
            "j-old", "doc-1", "done", "别的哈希", null, 1000L, 1000L
        )
        val job = slot<EmbeddingJobEntity>()

        pipeline(FakeEmbeddingAdapter { List(it.size) { floatArrayOf(1f) } })
            .indexDocument("doc-1", "笔记", content)

        coVerify(exactly = 1) { ragDao.upsertJob(capture(job)) }
        assertThat(job.captured.id).isNotEqualTo("j-old")
        assertThat(job.captured.createdAt).isNotEqualTo(1000L)
        assertThat(job.captured.createdAt).isAtLeast(before)
    }

    @Test
    fun `未收敛的旧任务行被原地复用而不是新增一行`() = runTest {
        // running 只可能是上次被取消/被杀留下的僵尸（indexDocument 全程持 indexMutex，同一篇文档
        // 不存在第二个活着的任务）。复用同一行的 id 才不会让废行堆在表里；也只有这种原地复用
        // 才该保留原来的 created_at。
        coEvery { ragDao.getLatestJob("doc-1") } returns EmbeddingJobEntity(
            "j-zombie", "doc-1", "running", null, null, 1000L, 1000L
        )
        val job = slot<EmbeddingJobEntity>()

        pipeline(FakeEmbeddingAdapter { List(it.size) { floatArrayOf(1f) } })
            .indexDocument("doc-1", "笔记", content)

        coVerify(exactly = 1) { ragDao.upsertJob(capture(job)) }
        assertThat(job.captured.id).isEqualTo("j-zombie")
        assertThat(job.captured.createdAt).isEqualTo(1000L)
    }

    @Test
    fun `任务收敛后清掉同文档的历史任务行`() = runTest {
        // 只写终态不清行的话，这张表随保存次数与失败次数只涨不减（失败行还各带一段 error 文本），
        // 而真正被读的永远只是最新那一条。
        val job = slot<EmbeddingJobEntity>()

        pipeline(FakeEmbeddingAdapter { List(it.size) { floatArrayOf(1f) } })
            .indexDocument("doc-1", "笔记", content)

        coVerify(exactly = 1) { ragDao.upsertJob(capture(job)) }
        coVerify(exactly = 1) { ragDao.updateJobStatus(job.captured.id, "done", null, any()) }
        coVerify(exactly = 1) { ragDao.pruneJobsExcept("doc-1", job.captured.id) }
    }

    @Test
    fun `索引失败后同样清掉历史任务行`() = runTest {
        configFlow.value = configFlow.value.copy(embeddingModel = "")
        val job = slot<EmbeddingJobEntity>()

        pipeline(FakeEmbeddingAdapter { List(it.size) { floatArrayOf(1f) } })
            .indexDocument("doc-1", "笔记", content)

        coVerify(exactly = 1) { ragDao.upsertJob(capture(job)) }
        coVerify(exactly = 1) { ragDao.pruneJobsExcept("doc-1", job.captured.id) }
    }

    @Test
    fun `首次装载会把上次进程留下的僵尸任务标成失败`() = runTest {
        // 进程被杀时任务行停在 running。旧实现只查 status = 'pending'，而**没有任何代码写过**
        // 这个状态 —— 于是那段崩溃恢复从上线起一直在空转，僵尸行永远是这篇文档的「最近一条」，
        // 状态还是「跑着」。
        coEvery { ragDao.getUnfinishedJobs() } returns listOf(
            EmbeddingJobEntity("j-a", "doc-1", "running", "h1", null, 1L, 1L),
            EmbeddingJobEntity("j-b", "doc-2", "pending", "h2", null, 2L, 2L)
        )

        pipeline(FakeEmbeddingAdapter { emptyList() }).hydrateIfEmpty()

        val reason = slot<String>()
        coVerify(exactly = 1) { ragDao.updateJobStatus("j-a", "failed", capture(reason), any()) }
        coVerify(exactly = 1) { ragDao.updateJobStatus("j-b", "failed", any(), any()) }
        assertThat(reason.captured).contains("重新保存文档")
    }

    @Test
    fun `hydrate 用 documents 表里的真名而不是第一个分块的小标题`() = runTest {
        // 热路径（indexDocument → upsertDocument）存的是真名，冷启动 hydrate 若拿小标题顶替，
        // 同一篇文档就在重启前后改了名。两处后果：docName 的 1.4/1.6 关键词加权按错的名字算
        //（查真实标题一分不加，查第一节标题反而 heading + docName 连加两次），而 documentName
        // 会原样进模型读到的检索结果，模型据此把出处报成一个不存在的文档。
        coEvery { ragDao.getAllChunks() } returns listOf(chunkEntity("c1"), chunkEntity("c2", docId = "doc-2"))
        coEvery { ragDao.getIndexedDocumentNames() } returns listOf(
            IndexedDocumentName("doc-1", "季度复盘.md"),
            IndexedDocumentName("doc-2", "会议记录.md")
        )
        configFlow.value = configFlow.value.copy(embeddingModel = "")

        pipeline(FakeEmbeddingAdapter { emptyList() }).hydrateIfEmpty()

        val docArgs = slot<List<DocMeta>>()
        verify(exactly = 1) { vectorStore.hydrate(any(), any(), capture(docArgs)) }
        assertThat(docArgs.captured.map { it.id to it.name })
            .containsExactly("doc-1" to "季度复盘.md", "doc-2" to "会议记录.md")
        // chunkEntity 的 heading 是「标题」——顶替值一个都不该出现
        assertThat(docArgs.captured.map { it.name }).doesNotContain("标题")
    }

    @Test
    fun `JOIN 拿不到文档行时退回旧的小标题兜底`() = runTest {
        // 外键是 CASCADE，孤儿分块正常不存在；真出现时也不能让 DocMeta 缺席 ——
        // VectorStore.search 里 `documents[chunk.documentId] ?: continue` 会把这批分块整体
        // 从检索里剔掉，用户看到的是「知识库里没有这篇文档」。
        coEvery { ragDao.getAllChunks() } returns listOf(chunkEntity("c1", docId = "孤儿doc"))
        coEvery { ragDao.getIndexedDocumentNames() } returns emptyList()
        configFlow.value = configFlow.value.copy(embeddingModel = "")

        pipeline(FakeEmbeddingAdapter { emptyList() }).hydrateIfEmpty()

        val docArgs = slot<List<DocMeta>>()
        verify(exactly = 1) { vectorStore.hydrate(any(), any(), capture(docArgs)) }
        assertThat(docArgs.captured.map { it.id to it.name }).containsExactly("孤儿doc" to "标题")
    }

    private fun chunkEntity(id: String, docId: String = "doc-1") = ChunkEntity(
        id = id,
        documentId = docId,
        content = "分块正文 $id",
        contentHash = "h-$id",
        titlePath = "[]",
        heading = "标题",
        sourceType = "markdown",
        chunkIndex = 0,
        startLine = 1,
        endLine = 2,
        createdAt = 0L
    )
}
