package com.yumark.app.data.ai.adapters

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.AiTool
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test

class ProviderToolMessageFormattingTest {

    private val tools = listOf(
        AiTool(
            name = "read_document",
            description = "read",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf("document_id" to mapOf("type" to "string"))
            )
        )
    )

    @Test
    fun `claude tool definitions are included in request body`() {
        val body = buildClaudeBody(
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            config = com.yumark.app.domain.model.AiRequestConfig(model = "claude"),
            tools = tools
        )

        val arr = body["tools"]!!.jsonArray
        assertThat(arr).hasSize(1)
        val tool = arr[0].jsonObject
        assertThat(tool["name"]!!.jsonPrimitive.content).isEqualTo("read_document")
        assertThat(tool["input_schema"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("object")
    }

    @Test
    fun `claude formats assistant tool call and tool result messages`() {
        val assistant = claudeMessageContent(
            ChatMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(ToolCall("call-1", "read_document", """{"document_id":"doc-1"}"""))
            )
        )
        assertThat(assistant[0].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("tool_use")
        assertThat(assistant[0].jsonObject["id"]!!.jsonPrimitive.content).isEqualTo("call-1")

        val tool = claudeMessageContent(
            ChatMessage(role = "tool", content = "result", toolCallId = "call-1")
        )
        assertThat(tool[0].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("tool_result")
        assertThat(tool[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content).isEqualTo("call-1")
        assertThat(tool[0].jsonObject["content"]!!.jsonPrimitive.content).isEqualTo("result")
    }

    @Test
    fun `gemini tool declarations and tool messages are formatted`() {
        val body = buildGeminiBody(
            messages = listOf(
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(ToolCall("call-1", "read_document", """{"document_id":"doc-1"}"""))
                ),
                ChatMessage(
                    role = "tool",
                    content = "result",
                    toolCallId = "call-1",
                    toolName = "read_document"
                )
            ),
            config = com.yumark.app.domain.model.AiRequestConfig(model = "gemini"),
            tools = tools
        )

        val declarations = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertThat(declarations).hasSize(1)
        assertThat(declarations[0].jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("read_document")

        val contents = body["contents"]!!.jsonArray
        val assistantParts = contents[0].jsonObject["parts"]!!.jsonArray
        val functionCall = assistantParts[0].jsonObject["functionCall"]!!.jsonObject
        assertThat(functionCall["id"]!!.jsonPrimitive.content).isEqualTo("call-1")
        assertThat(functionCall["name"]!!.jsonPrimitive.content)
            .isEqualTo("read_document")

        val toolParts = contents[1].jsonObject["parts"]!!.jsonArray
        val response = toolParts[0].jsonObject["functionResponse"]!!.jsonObject
        assertThat(response["id"]!!.jsonPrimitive.content).isEqualTo("call-1")
        assertThat(response["name"]!!.jsonPrimitive.content).isEqualTo("read_document")
        assertThat(response["response"]!!.jsonObject["content"]!!.jsonPrimitive.content).isEqualTo("result")
    }

    @Test
    fun `gemini functionResponse 的 name 取 toolName 而不是从 id 反解`() {
        // Gemini 在并行调用时会真的下发 id。从前 name 是 `id.substringBefore('#')` 反解出来的，
        // 真 id 里没有 '#'，于是整串 id 被当成函数名发回去——Gemini 认不出这个函数。
        val parts = geminiMessageParts(
            ChatMessage(
                role = "tool",
                content = "result",
                toolCallId = "fc_9d3a1b",
                toolName = "search_in_project"
            )
        )

        val response = parts[0].jsonObject["functionResponse"]!!.jsonObject
        assertThat(response["name"]!!.jsonPrimitive.content).isEqualTo("search_in_project")
        assertThat(response["id"]!!.jsonPrimitive.content).isEqualTo("fc_9d3a1b")
    }

    @Test
    fun `gemini 不把适配器自己拼的占位 id 回传`() {
        // Gemini 没给 id 时适配器拼 `name#index` 供本地配对，那不是 Gemini 认识的 id。
        val toolParts = geminiMessageParts(
            ChatMessage(
                role = "tool",
                content = "result",
                toolCallId = "read_document#0",
                toolName = "read_document"
            )
        )
        val response = toolParts[0].jsonObject["functionResponse"]!!.jsonObject
        assertThat(response["id"]).isNull()
        assertThat(response["name"]!!.jsonPrimitive.content).isEqualTo("read_document")

        val callParts = geminiMessageParts(
            ChatMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(ToolCall("read_document#0", "read_document", "{}"))
            )
        )
        val functionCall = callParts[0].jsonObject["functionCall"]!!.jsonObject
        assertThat(functionCall["id"]).isNull()
        assertThat(functionCall["name"]!!.jsonPrimitive.content).isEqualTo("read_document")
    }

    @Test
    fun `gemini 老数据没有 toolName 时仍从占位 id 反解出函数名`() {
        val parts = geminiMessageParts(
            ChatMessage(role = "tool", content = "result", toolCallId = "list_documents#2")
        )
        val response = parts[0].jsonObject["functionResponse"]!!.jsonObject
        assertThat(response["name"]!!.jsonPrimitive.content).isEqualTo("list_documents")
        assertThat(response["id"]).isNull()
    }

    @Test
    fun `claude 把一轮里的多个 tool_result 折叠进同一条 user 消息`() {
        // Claude 要求一轮 assistant 的 N 个 tool_use 对应紧随其后**同一条** user 消息里的
        // N 个 tool_result。一个结果一条 user 消息会得到连续两条 user，
        // 兼容 Claude 协议的第三方中转会直接回 roles must alternate。
        val body = buildClaudeBody(
            messages = listOf(
                ChatMessage(role = "user", content = "读两篇"),
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCall("call-1", "read_document", """{"document_id":"a"}"""),
                        ToolCall("call-2", "read_document", """{"document_id":"b"}""")
                    )
                ),
                ChatMessage(role = "tool", content = "A", toolCallId = "call-1", toolName = "read_document"),
                ChatMessage(role = "tool", content = "B", toolCallId = "call-2", toolName = "read_document")
            ),
            config = com.yumark.app.domain.model.AiRequestConfig(model = "claude"),
            tools = tools
        )

        val msgs = body["messages"]!!.jsonArray
        // user / assistant / user —— 三条，而不是把两个结果摊成四条
        assertThat(msgs).hasSize(3)
        assertThat(msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            .containsExactly("user", "assistant", "user")
            .inOrder()

        val results = msgs[2].jsonObject["content"]!!.jsonArray
        assertThat(results).hasSize(2)
        assertThat(results.map { it.jsonObject["tool_use_id"]!!.jsonPrimitive.content })
            .containsExactly("call-1", "call-2")
            .inOrder()
        assertThat(results.map { it.jsonObject["type"]!!.jsonPrimitive.content })
            .containsExactly("tool_result", "tool_result")
        assertThat(results.map { it.jsonObject["content"]!!.jsonPrimitive.content })
            .containsExactly("A", "B")
            .inOrder()
    }

    @Test
    fun `claude 折叠只吃连续的 tool 消息不跨越 assistant 轮次`() {
        val body = buildClaudeBody(
            messages = listOf(
                ChatMessage(role = "assistant", content = null, toolCalls = listOf(ToolCall("c1", "read_document", "{}"))),
                ChatMessage(role = "tool", content = "1", toolCallId = "c1", toolName = "read_document"),
                ChatMessage(role = "assistant", content = null, toolCalls = listOf(ToolCall("c2", "read_document", "{}"))),
                ChatMessage(role = "tool", content = "2", toolCallId = "c2", toolName = "read_document")
            ),
            config = com.yumark.app.domain.model.AiRequestConfig(model = "claude"),
            tools = tools
        )

        val msgs = body["messages"]!!.jsonArray
        assertThat(msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            .containsExactly("assistant", "user", "assistant", "user")
            .inOrder()
        assertThat(msgs[1].jsonObject["content"]!!.jsonArray).hasSize(1)
        assertThat(msgs[3].jsonObject["content"]!!.jsonArray).hasSize(1)
    }

    @Test
    fun `claude accumulator keeps parallel tool calls until message completes`() {
        val accumulator = ClaudeToolCallAccumulator()

        accumulator.onBlockStart(
            buildJsonObject {
                put("index", 1)
                putJsonObject("content_block") {
                    put("type", "tool_use")
                    put("id", "call-2")
                    put("name", "search_in_project")
                }
            }
        )
        accumulator.onBlockDelta(
            buildJsonObject {
                put("index", 1)
                putJsonObject("delta") {
                    put("partial_json", """{"query":"agent"}""")
                }
            }
        )
        accumulator.onBlockStart(
            buildJsonObject {
                put("index", 0)
                putJsonObject("content_block") {
                    put("type", "tool_use")
                    put("id", "call-1")
                    put("name", "read_document")
                }
            }
        )
        accumulator.onBlockDelta(
            buildJsonObject {
                put("index", 0)
                putJsonObject("delta") {
                    put("partial_json", """{"document_id":"doc-1"}""")
                }
            }
        )

        val completed = accumulator.completeMessage()

        assertThat(completed).hasSize(2)
        assertThat(completed[0]).isEqualTo(ToolCall("call-1", "read_document", """{"document_id":"doc-1"}"""))
        assertThat(completed[1]).isEqualTo(ToolCall("call-2", "search_in_project", """{"query":"agent"}"""))
        assertThat(accumulator.completeMessage()).isEmpty()
    }

    // ==== 工具调用与正文共存 ====
    // 模型常在同一轮里先写一段说明再调工具，AgentUseCases 回填的助手消息也确实
    // 同时带着 content 和 toolCalls。OpenAI 侧一直两样都发；Claude / Gemini 从前只发
    // 工具调用，那段说明在下一轮上下文里凭空消失——模型看不见自己刚才的推理，
    // 于是把同一个工具再调一遍，或把解释过的事重新解释一次。

    @Test
    fun `claude keeps assistant text alongside tool calls`() {
        val content = claudeMessageContent(
            ChatMessage(
                role = "assistant",
                content = "我先读一下这篇文档。",
                toolCalls = listOf(ToolCall("call-1", "read_document", """{"document_id":"doc-1"}"""))
            )
        )

        assertThat(content).hasSize(2)
        // 顺序也钉住：说明在前、工具调用在后，和模型自己输出的顺序一致
        assertThat(content[0].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("text")
        assertThat(content[0].jsonObject["text"]!!.jsonPrimitive.content).isEqualTo("我先读一下这篇文档。")
        assertThat(content[1].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("tool_use")
        assertThat(content[1].jsonObject["id"]!!.jsonPrimitive.content).isEqualTo("call-1")
    }

    @Test
    fun `claude omits the text block when assistant text is blank`() {
        // 空 text 块会让 Claude 以 invalid_request_error 打回整个请求，
        // 而 content == null / 全空白正是「这一轮只有工具调用」的正常取值。
        listOf(null, "", "   \n  ").forEach { blank ->
            val content = claudeMessageContent(
                ChatMessage(
                    role = "assistant",
                    content = blank,
                    toolCalls = listOf(ToolCall("call-1", "read_document", "{}"))
                )
            )
            assertThat(content).hasSize(1)
            assertThat(content[0].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("tool_use")
        }
    }

    @Test
    fun `gemini keeps assistant text alongside function calls`() {
        val parts = geminiMessageParts(
            ChatMessage(
                role = "assistant",
                content = "我先读一下这篇文档。",
                toolCalls = listOf(ToolCall("call-1", "read_document", """{"document_id":"doc-1"}"""))
            )
        )

        assertThat(parts).hasSize(2)
        assertThat(parts[0].jsonObject["text"]!!.jsonPrimitive.content).isEqualTo("我先读一下这篇文档。")
        assertThat(parts[1].jsonObject["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            .isEqualTo("read_document")
    }

    @Test
    fun `gemini omits the text part when assistant text is blank`() {
        listOf(null, "", "   \n  ").forEach { blank ->
            val parts = geminiMessageParts(
                ChatMessage(
                    role = "assistant",
                    content = blank,
                    toolCalls = listOf(ToolCall("call-1", "read_document", "{}"))
                )
            )
            assertThat(parts).hasSize(1)
            assertThat(parts[0].jsonObject["functionCall"]).isNotNull()
        }
    }
}
