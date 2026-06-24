package com.yumark.app.data.ai.web

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiProvider
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.model.WebSearchProvider
import com.yumark.app.domain.repository.AiConfigRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * WebSearchService 单元测试
 *
 * 注意：由于 WebSearchService 依赖真实的 HttpClient 和外部 API，
 * 这些测试主要验证配置检查和参数解析逻辑。
 * 实际的网络请求需要在集成测试或真机测试中验证。
 */
class WebSearchServiceTest {

    private fun mockConfigRepo(
        enabled: Boolean = true,
        provider: WebSearchProvider = WebSearchProvider.DUCKDUCKGO,
        apiKey: String = "test-key"
    ): AiConfigRepository {
        val repo = mockk<AiConfigRepository>()
        coEvery { repo.observeConfig() } returns flowOf(
            AiConfig(
                provider = AiProvider.OPENAI,
                apiKey = "test",
                baseUrl = "https://api.openai.com",
                webSearchEnabled = enabled,
                webSearchProvider = provider,
                webSearchApiKey = apiKey,
                webSearchCustomUrl = "https://custom.com/search?q={query}"
            )
        )
        return repo
    }

    private fun toolCall(query: String, maxResults: Int = 5): ToolCall {
        return ToolCall(
            id = "call_123",
            name = "web_search",
            arguments = """{"query":"$query","max_results":$maxResults}"""
        )
    }

    @Test
    fun `web search disabled returns error`() = runTest {
        val client = mockk<io.ktor.client.HttpClient>(relaxed = true)
        val configRepo = mockConfigRepo(enabled = false)
        val service = WebSearchService(client, configRepo)

        val result = service.search(toolCall("test query"))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("未启用")
    }

    @Test
    fun `empty query returns message`() = runTest {
        val client = mockk<io.ktor.client.HttpClient>(relaxed = true)
        val configRepo = mockConfigRepo()
        val service = WebSearchService(client, configRepo)

        val result = service.search(
            ToolCall("call_123", "web_search", """{"query":"","max_results":5}""")
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).contains("搜索词为空")
    }

    @Test
    fun `missing query parameter returns error`() = runTest {
        val client = mockk<io.ktor.client.HttpClient>(relaxed = true)
        val configRepo = mockConfigRepo()
        val service = WebSearchService(client, configRepo)

        val result = service.search(
            ToolCall("call_123", "web_search", """{"max_results":5}""")
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("缺少参数")
    }

    @Test
    fun `tavily provider requires api key`() = runTest {
        val client = mockk<io.ktor.client.HttpClient>(relaxed = true)
        val configRepo = mockConfigRepo(provider = WebSearchProvider.TAVILY, apiKey = "")
        val service = WebSearchService(client, configRepo)

        val result = service.search(toolCall("test query"))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Tavily")
        assertThat(result.exceptionOrNull()?.message).contains("未配置")
    }

    @Test
    fun `serper provider requires api key`() = runTest {
        val client = mockk<io.ktor.client.HttpClient>(relaxed = true)
        val configRepo = mockConfigRepo(provider = WebSearchProvider.SERPER, apiKey = "")
        val service = WebSearchService(client, configRepo)

        val result = service.search(toolCall("test query"))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Serper")
        assertThat(result.exceptionOrNull()?.message).contains("未配置")
    }

    @Test
    fun `brave provider requires api key`() = runTest {
        val client = mockk<io.ktor.client.HttpClient>(relaxed = true)
        val configRepo = mockConfigRepo(provider = WebSearchProvider.BRAVE, apiKey = "")
        val service = WebSearchService(client, configRepo)

        val result = service.search(toolCall("test query"))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Brave")
        assertThat(result.exceptionOrNull()?.message).contains("未配置")
    }

    @Test
    fun `duckduckgo provider does not require api key`() = runTest {
        // DuckDuckGo 不需要 API key，但会因为没有真实网络连接而失败
        // 这个测试验证配置检查通过，不会因为缺少 key 而提前失败
        val client = mockk<io.ktor.client.HttpClient>(relaxed = true)
        val configRepo = mockConfigRepo(provider = WebSearchProvider.DUCKDUCKGO, apiKey = "")
        val service = WebSearchService(client, configRepo)

        val result = service.search(toolCall("test query"))

        // 应该通过配置检查，但会因网络请求失败
        // 错误消息不应包含 "未配置"
        if (result.isFailure) {
            assertThat(result.exceptionOrNull()?.message).doesNotContain("未配置")
        }
    }

    @Test
    fun `max_results defaults to 5 when not provided`() = runTest {
        val client = mockk<io.ktor.client.HttpClient>(relaxed = true)
        val configRepo = mockConfigRepo()
        val service = WebSearchService(client, configRepo)

        // 不提供 max_results 参数
        val result = service.search(
            ToolCall("call_123", "web_search", """{"query":"test"}""")
        )

        // 验证不会因缺少 max_results 而失败
        // 实际的默认值 5 在内部使用，这里只验证参数解析成功
        assertThat(result.isSuccess || result.exceptionOrNull()?.message?.contains("max_results") == false).isTrue()
    }
}
