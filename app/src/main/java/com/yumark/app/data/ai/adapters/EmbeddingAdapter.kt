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
 * Embedding 适配器 —— 把文本批量转向量。Phase 4 仅支持 OpenAI 兼容的 `/embeddings` 协议，
 * 复用 chat 的 baseUrl/apiKey（大多数 OpenAI 兼容端点同时提供 chat 与 embedding）。
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

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
        val data = runCatching {
            json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]?.jsonArray
        }.getOrNull() ?: throw IllegalStateException("Embedding 响应缺少 data 字段")

        // OpenAI 规范：每个元素带 index 指明对应输入序号。显式按 index 排序以对齐输入。
        data.sortedBy { it.jsonObject["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
            .map { el ->
                el.jsonObject["embedding"]?.jsonArray
                    ?.map { v -> v.jsonPrimitive.content.toFloatOrNull() ?: 0f }
                    ?.toFloatArray()
                    ?: FloatArray(0)
            }
    }
}
