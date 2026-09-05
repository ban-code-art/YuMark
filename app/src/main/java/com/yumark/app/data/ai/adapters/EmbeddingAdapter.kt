package com.yumark.app.data.ai.adapters

import com.yumark.app.data.ai.HttpResponseException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Embedding 适配器 —— 把文本批量转向量。仅支持 OpenAI 兼容的 `/embeddings` 协议。
 *
 * 端点可以复用 chat 那一组，也可以由用户单独配置（Claude / Gemini 都不提供 `/embeddings`，
 * 而 embedding 恰是最值得指向本地 Ollama 或自建 vLLM 的一段）。解析规则在
 * [com.yumark.app.domain.model.ragBaseUrlResolved]，本类只接受已解析好的一对参数。
 */
interface EmbeddingAdapter {
    /**
     * 对 [input] 批量生成向量。返回顺序与输入一致（显式按响应 index 对齐，兼容乱序端点）。
     * 单次请求过大时由调用方分批；本方法不做内部分批。
     */
    suspend fun embed(input: List<String>, model: String): List<FloatArray>
}

class OpenAiEmbeddingAdapter(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: HttpClient
) : EmbeddingAdapter {

    override suspend fun embed(input: List<String>, model: String): List<FloatArray> = withContext(Dispatchers.IO) {
        if (input.isEmpty()) return@withContext emptyList()
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("input") { input.forEach { add(it) } }
        }.toString()

        val resp = client.post("${baseUrl.trimEnd('/')}/embeddings") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!resp.status.isSuccess()) {
            throw HttpResponseException(resp.status.value, resp.bodyAsText().take(500))
        }
        parseEmbeddingsResponse(resp.bodyAsText())
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * 从 `/embeddings` 响应体解析出按输入序对齐的向量列表。抽成 internal 静态函数，是为了能脱离
         * [HttpClient] 直测解析与校验分支——本模块测试不引 Ktor MockEngine。
         *
         * 任何结构缺陷都抛出，绝不静默降级：
         * - 缺失/非数组的 `embedding` 若降级成空向量，会骗过 RagPipeline 只数条数的 check、以 "[]" 落库、
         *   在 cosine 里因维度不等永久判 0f（该分块从此向量检索不到），却仍被 knowledge_stats 计入
         *   「可向量检索」——一个毫无信号的幽灵分块。
         * - 非数值分量塞 0f 同理是被悄悄污染的错向量。
         * 抛出后：indexDocument 的 catch 会判 job 失败、旧索引一字不动，embedQuery 的
         * runCatching{}.getOrNull() 得 null 后诚实退化为关键词检索。
         */
        internal fun parseEmbeddingsResponse(bodyText: String): List<FloatArray> {
            val data = runCatching {
                json.parseToJsonElement(bodyText).jsonObject["data"]?.jsonArray
            }.getOrNull() ?: throw IllegalStateException("Embedding 响应缺少 data 字段")

            // OpenAI 规范：每个元素带 index 指明对应输入序号。显式按 index 排序以对齐输入。
            return data.sortedBy { it.jsonObject["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
                .mapIndexed { i, el ->
                    val arr = el.jsonObject["embedding"]?.jsonArray
                        ?: throw IllegalStateException("Embedding 响应第 ${i + 1} 个元素缺少 embedding 数组")
                    FloatArray(arr.size) { j ->
                        arr[j].jsonPrimitive.content.toFloatOrNull()
                            ?: throw IllegalStateException("Embedding 响应第 ${i + 1} 个元素含非数值分量")
                    }
                }
        }
    }
}
