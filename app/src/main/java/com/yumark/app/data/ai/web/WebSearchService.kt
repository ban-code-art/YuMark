package com.yumark.app.data.ai.web

import com.yumark.app.di.WebSearchClient
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.model.WebSearchProvider
import com.yumark.app.domain.repository.AiConfigRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private data class SearchResult(val title: String, val url: String, val snippet: String)

/**
 * 网络搜索服务 —— 移植自 guanmo `webSearch.ts`。
 *
 * 支持 5 个 provider：DuckDuckGo（免 key，解析 lite 页面 HTML）、Tavily、Serper、Brave、Custom。
 * 由 [AiConfig] 的 webSearch* 字段驱动；webSearchApiKey 走加密存储。
 * [search] 解析工具调用参数，返回格式化后的上下文字符串（或失败）。
 */
@Singleton
class WebSearchService @Inject constructor(
    @WebSearchClient private val client: HttpClient,
    private val configRepository: AiConfigRepository
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 执行 web_search 工具调用，返回注入下轮上下文的字符串。 */
    suspend fun search(toolCall: ToolCall): Result<String> = runCatching {
        val args = json.decodeFromString<Map<String, JsonElement>>(toolCall.arguments)
        val query = args["query"]?.jsonPrimitive?.content ?: error("缺少参数: query")
        val maxResults = args["max_results"]?.jsonPrimitive?.intOrNull ?: 5
        if (query.isBlank()) return@runCatching "搜索词为空。"

        val config = configRepository.observeConfig().first()
        if (!config.webSearchEnabled) error("网络搜索未启用，请在设置中开启")

        val results: List<SearchResult> = when (config.webSearchProvider) {
            WebSearchProvider.TAVILY -> {
                requireKey(config.webSearchApiKey, "Tavily")
                searchTavily(query, maxResults, config.webSearchApiKey)
            }
            WebSearchProvider.SERPER -> {
                requireKey(config.webSearchApiKey, "Serper")
                searchSerper(query, maxResults, config.webSearchApiKey)
            }
            WebSearchProvider.BRAVE -> {
                requireKey(config.webSearchApiKey, "Brave")
                searchBrave(query, maxResults, config.webSearchApiKey)
            }
            WebSearchProvider.CUSTOM -> searchCustom(query, maxResults, config.webSearchApiKey, config.webSearchCustomUrl)
            WebSearchProvider.DUCKDUCKGO -> searchDuckDuckGo(query, maxResults)
        }

        buildSearchContext(query, results)
    }

    private fun requireKey(key: String, name: String) {
        if (key.isBlank()) error("$name API Key 未配置，请在设置中填写")
    }

    private suspend fun searchTavily(query: String, maxResults: Int, apiKey: String): List<SearchResult> {
        val resp = client.post("https://api.tavily.com/search") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(buildJsonObject {
                put("query", query)
                put("max_results", maxResults)
                put("include_answer", true)
                put("include_raw_content", false)
            })
        }
        if (!resp.status.isSuccess()) error("Tavily 搜索失败 (${resp.status.value})")
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        return data["results"]?.jsonArray?.map { it.jsonObject.toSearchResult("title", "url", "content") } ?: emptyList()
    }

    private suspend fun searchSerper(query: String, maxResults: Int, apiKey: String): List<SearchResult> {
        val resp = client.post("https://google.serper.dev/search") {
            contentType(ContentType.Application.Json)
            header("X-API-KEY", apiKey)
            setBody(buildJsonObject { put("q", query); put("num", maxResults) })
        }
        if (!resp.status.isSuccess()) error("Serper 搜索失败 (${resp.status.value})")
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        return data["organic"]?.jsonArray?.map { it.jsonObject.toSearchResult("title", "link", "snippet") } ?: emptyList()
    }

    private suspend fun searchBrave(query: String, maxResults: Int, apiKey: String): List<SearchResult> {
        val url = "https://api.search.brave.com/res/v1/web/search?q=${encode(query)}&count=$maxResults"
        val resp = client.get(url) {
            headers {
                append("Accept", "application/json")
                append("Accept-Encoding", "gzip")
                append("X-Subscription-Token", apiKey)
            }
        }
        if (!resp.status.isSuccess()) error("Brave 搜索失败 (${resp.status.value})")
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        return data["web"]?.jsonObject?.get("results")?.jsonArray
            ?.map { it.jsonObject.toSearchResult("title", "url", "description") } ?: emptyList()
    }

    private suspend fun searchCustom(query: String, maxResults: Int, apiKey: String, customUrl: String): List<SearchResult> {
        if (customUrl.isBlank()) error("自定义搜索引擎 URL 未配置，请在设置中填写")
        val base = if (customUrl.contains("?")) "$customUrl&" else "$customUrl?"
        val url = "${base}q=${encode(query)}&count=$maxResults"
        val resp = client.get(url) {
            header("Accept", "application/json")
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }
        if (!resp.status.isSuccess()) error("自定义搜索失败 (${resp.status.value})")
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject

        // 自动探测常见响应格式：results / organic / webPages.value / items / hits
        val raw: List<JsonElement> = data["results"]?.jsonArrayOrNull()
            ?: data["organic"]?.jsonArrayOrNull()
            ?: data["webPages"]?.jsonObjectOrNull()?.get("value")?.jsonArrayOrNull()
            ?: data["items"]?.jsonArrayOrNull()
            ?: data["hits"]?.jsonArrayOrNull()
            ?: emptyList()

        return raw.take(maxResults).map { el ->
            val obj = el.jsonObject
            SearchResult(
                title = obj.string("title", "name"),
                url = obj.string("url", "link", "href"),
                snippet = obj.string("snippet", "description", "content", "abstract")
            )
        }
    }

    private suspend fun searchDuckDuckGo(query: String, maxResults: Int): List<SearchResult> {
        val url = "https://lite.duckduckgo.com/lite/?q=${encode(query)}"
        val resp = client.get(url)
        if (!resp.status.isSuccess()) error("DuckDuckGo 搜索失败 (${resp.status.value})")
        val html = resp.bodyAsText()

        val linkRegex = Regex("""<a[^>]+href="(https?://[^"]+)"[^>]*class="result-link"[^>]*>([^<]+)</a>""")
        val snippetRegex = Regex("""<td class="result-snippet">([^<]+)</td>""")
        val snippets = snippetRegex.findAll(html).map { it.groupValues[1].trim() }.toList()

        val results = mutableListOf<SearchResult>()
        var i = 0
        for (m in linkRegex.findAll(html)) {
            if (results.size >= maxResults) break
            results.add(SearchResult(m.groupValues[2].trim(), m.groupValues[1], snippets.getOrNull(i) ?: ""))
            i++
        }
        return results
    }

    private fun buildSearchContext(query: String, results: List<SearchResult>): String {
        if (results.isEmpty()) return "未找到与\"$query\"相关的网络搜索结果。"
        val parts = results.withIndex().joinToString("\n\n") { (i, r) ->
            "[来源 ${i + 1}: ${r.title}]\n${r.snippet}\n链接: ${r.url}"
        }
        return "以下是网络搜索\"$query\"的结果：\n\n$parts"
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun JsonObject.toSearchResult(titleKey: String, urlKey: String, snippetKey: String): SearchResult =
        SearchResult(
            title = string(titleKey, "name"),
            url = string(urlKey, "link", "href", "url"),
            snippet = string(snippetKey, "snippet", "description", "content", "abstract")
        )

    /** 取首个命中的字符串字段（含备选 key），JSON null 或非原始值视为缺失。 */
    private fun JsonObject.string(vararg keys: String): String {
        for (key in keys) {
            val el = this[key] ?: continue
            val p = el as? JsonPrimitive ?: continue   // JsonNull 不是 JsonPrimitive，自动跳过
            return p.content
        }
        return ""
    }

    private fun JsonElement?.jsonObjectOrNull(): JsonObject? =
        runCatching { this?.jsonObject }.getOrNull()

    private fun JsonElement?.jsonArrayOrNull(): JsonArray? =
        runCatching { this?.jsonArray }.getOrNull()
}
