package com.yumark.app.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [AiConfig.ragBaseUrlResolved] / [AiConfig.ragApiKeyResolved] 的解析规则。
 *
 * 这是「embedding 用哪一组端点和密钥」的唯一实现点，RAG 管线和模型列表按钮都从这取值。
 * 钉住四条规则：复用模式与 chat 完全同源（含 provider 默认值那一层）、独立模式只认
 * rag 那一组、独立模式**不**静默回落到 chat（那是把密钥发去用户没指定的服务器）、
 * 独立密钥允许为空（本地 Ollama / vLLM 不校验 Authorization）。
 */
class RagEndpointResolutionTest {

    @Test
    fun `复用模式沿用 chat 的 baseUrl 与 apiKey`() {
        val config = AiConfig(provider = AiProvider.OPENAI, apiKey = "sk-main", baseUrl = "")

        assertThat(config.ragBaseUrlResolved).isEqualTo("https://api.openai.com/v1")
        assertThat(config.ragApiKeyResolved).isEqualTo("sk-main")
    }

    @Test
    fun `复用模式下 OPENAI_COMPATIBLE 的空 baseUrl 解析为空串`() {
        val config = AiConfig(provider = AiProvider.OPENAI_COMPATIBLE, apiKey = "sk-main", baseUrl = "")

        assertThat(config.ragBaseUrlResolved).isEmpty()
    }

    @Test
    fun `独立模式只认 ragBaseUrl 与 ragApiKey`() {
        val config = AiConfig(
            provider = AiProvider.CLAUDE,
            apiKey = "sk-main",
            baseUrl = "https://api.anthropic.com/v1",
            ragUseMainEndpoint = false,
            ragBaseUrl = "http://127.0.0.1:11434/v1",
            ragApiKey = "sk-embed"
        )

        assertThat(config.ragBaseUrlResolved).isEqualTo("http://127.0.0.1:11434/v1")
        assertThat(config.ragApiKeyResolved).isEqualTo("sk-embed")
    }

    @Test
    fun `独立模式的空 baseUrl 不回落到 chat 的地址`() {
        val config = AiConfig(
            provider = AiProvider.OPENAI,
            apiKey = "sk-main",
            baseUrl = "https://api.openai.com/v1",
            ragUseMainEndpoint = false,
            ragBaseUrl = ""
        )

        assertThat(config.ragBaseUrlResolved).isEmpty()
    }

    @Test
    fun `独立模式的 ragBaseUrl 两端空白被剪掉`() {
        val config = AiConfig(
            ragUseMainEndpoint = false,
            ragBaseUrl = "  http://127.0.0.1:11434/v1/  "
        )

        assertThat(config.ragBaseUrlResolved).isEqualTo("http://127.0.0.1:11434/v1/")
    }

    @Test
    fun `独立模式的空密钥照常放行`() {
        val config = AiConfig(
            apiKey = "sk-main",
            ragUseMainEndpoint = false,
            ragApiKey = ""
        )

        assertThat(config.ragApiKeyResolved).isEmpty()
    }

    @Test
    fun `默认值是复用模式以兼容既有安装`() {
        val config = AiConfig()

        assertThat(config.ragUseMainEndpoint).isTrue()
        assertThat(config.ragBaseUrlResolved)
            .isEqualTo(AiProvider.OPENAI.defaultBaseUrl)
    }
}
