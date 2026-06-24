package com.yumark.app.data.ai.memory

import com.yumark.app.data.local.db.dao.MemoryDao
import com.yumark.app.data.local.db.entity.MemoryEntity
import com.yumark.app.domain.model.MemoryCategory
import com.yumark.app.domain.model.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

private data class Memory(val id: String, val content: String, val category: MemoryCategory, val updatedAt: Long)

/**
 * 记忆系统服务 —— 移植自 guanmo `memoryService.ts`。
 *
 * 独立于 RAG 知识库：无 chunk、无 document 关联。Phase 3 用词法相似度检索
 * （token 集合余弦 + 子串包含加成），save 时按 ≥0.68 相似度去重更新。
 * Phase 4 embedding 基建就绪后可在此之上叠加向量精排。
 *
 * 分类检索优先级（[MemoryCategory.priority]）：PROJECT > PROFILE > INSTRUCTION > PREFERENCE > LEARNING。
 */
@Singleton
class MemoryService @Inject constructor(
    private val memoryDao: MemoryDao
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 执行记忆类工具调用，返回注入下轮上下文的状态/结果字符串。 */
    suspend fun execute(toolCall: ToolCall): Result<String> = runCatching {
        val args = json.decodeFromString<Map<String, JsonElement>>(toolCall.arguments)
        when (toolCall.name) {
            "save_memory" -> saveMemory(args)
            "search_memory" -> searchMemory(args)
            "list_memories" -> listMemories(args)
            else -> error("未知记忆工具: ${toolCall.name}")
        }
    }

    private suspend fun saveMemory(args: Map<String, JsonElement>): String {
        val content = args["content"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() } ?: error("缺少参数: content")
        val category = MemoryCategory.fromString(args["category"]?.jsonPrimitive?.content)
        val now = System.currentTimeMillis()

        val active = memoryDao.getActive()
        val similar = active
            .map { Memory(it.id, it.content, MemoryCategory.fromString(it.category), it.updatedAt) }
            .maxByOrNull { lexicalSimilarity(content, it.content) }
        val bestSim = similar?.let { lexicalSimilarity(content, it.content) } ?: 0.0

        return if (similar != null && bestSim >= SIMILARITY_DEDUP_THRESHOLD) {
            memoryDao.updateContent(similar.id, content, category.name, now)
            "已更新已有记忆（相似度 ${"%.2f".format(bestSim)}，分类: ${category.name}）：\n$content"
        } else {
            memoryDao.upsert(
                MemoryEntity(
                    id = UUID.randomUUID().toString(),
                    content = content,
                    category = category.name,
                    source = "user_explicit",
                    locked = false,
                    status = "active",
                    createdAt = now,
                    updatedAt = now
                )
            )
            "已保存新记忆（分类: ${category.name}）：\n$content"
        }
    }

    private suspend fun searchMemory(args: Map<String, JsonElement>): String {
        val query = args["query"]?.jsonPrimitive?.content?.trim()
            ?: error("缺少参数: query")
        val topK = args["top_k"]?.jsonPrimitive?.intOrNull ?: 5

        val ranked = memoryDao.getActive()
            .map { Memory(it.id, it.content, MemoryCategory.fromString(it.category), it.updatedAt) }
            .map { it to lexicalSimilarity(query, it.content) }
            .filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<Memory, Double>> { it.first.category.priority }.thenByDescending { it.second })
            .take(topK)

        return if (ranked.isEmpty()) {
            "未找到与\"$query\"相关的记忆。"
        } else {
            "记忆检索结果（\"$query\"，共 ${ranked.size} 条，按分类优先级与相关度排序）：\n\n" +
                ranked.joinToString("\n\n") { (m, sim) ->
                    "[${m.category.name} | 相关度 ${"%.2f".format(sim)}]\n${m.content}"
                }
        }
    }

    private suspend fun listMemories(args: Map<String, JsonElement>): String {
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
        val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
        val memories = memoryDao.list(limit, offset)
        val total = memoryDao.count()
        return if (memories.isEmpty()) {
            "记忆库为空（共 0 条）。"
        } else {
            "记忆列表（显示 ${memories.size}/$total 条）：\n" + memories.joinToString("\n") { m ->
                "- [${MemoryCategory.fromString(m.category).name}] ${m.content.take(80)}"
            }
        }
    }

    /**
     * 词法相似度（移植 guanmo lexicalSimilarity）：token 集合余弦 + 子串包含 +0.2。
     * token 化对 ASCII 取词、对中文取单字，兼顾中英文短记忆条目。
     */
    internal fun lexicalSimilarity(a: String, b: String): Double {
        val ta = tokenize(a)
        val tb = tokenize(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size.toDouble()
        val cosine = inter / sqrt(ta.size.toDouble() * tb.size.toDouble())
        val substr = if (a.length >= b.length) {
            if (b.isNotBlank() && a.contains(b)) 0.2 else 0.0
        } else {
            if (a.isNotBlank() && b.contains(a)) 0.2 else 0.0
        }
        return minOf(1.0, cosine + substr)
    }

    private fun tokenize(s: String): Set<String> {
        val lower = s.lowercase().trim()
        if (lower.isEmpty()) return emptySet()
        val tokens = mutableSetOf<String>()
        for (m in TOKEN_REGEX.findAll(lower)) tokens.add(m.value)
        return tokens
    }

    private companion object {
        const val SIMILARITY_DEDUP_THRESHOLD = 0.68
        val TOKEN_REGEX = Regex("""[a-z0-9]+|[一-鿿]""")
    }
}
