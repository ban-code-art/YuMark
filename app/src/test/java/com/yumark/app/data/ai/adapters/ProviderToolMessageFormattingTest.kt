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
                ChatMessage(role = "tool", content = "result", toolCallId = "read_document#0")
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
        assertThat(response["id"]!!.jsonPrimitive.content).isEqualTo("read_document#0")
        assertThat(response["name"]!!.jsonPrimitive.content).isEqualTo("read_document")
        assertThat(response["response"]!!.jsonObject["content"]!!.jsonPrimitive.content).isEqualTo("result")
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
}
