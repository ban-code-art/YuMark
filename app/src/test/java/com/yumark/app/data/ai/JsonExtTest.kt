package com.yumark.app.data.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class JsonExtTest {

    @Test
    fun `serializes a tool parameter schema with nested maps and lists`() {
        val params = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "document_id" to mapOf("type" to "string", "description" to "doc id")
            ),
            "required" to listOf("document_id")
        )
        // 序列化后再解析回来，验证结构无损
        val obj = Json.parseToJsonElement(params.toJsonElement().toString()).jsonObject
        assertThat(obj["type"]!!.jsonPrimitive.content).isEqualTo("object")
        assertThat(obj["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("document_id")
        val docIdSchema = obj["properties"]!!.jsonObject["document_id"]!!.jsonObject
        assertThat(docIdSchema["type"]!!.jsonPrimitive.content).isEqualTo("string")
    }

    @Test
    fun `handles primitives and null`() {
        assertThat("x".toJsonElement().jsonPrimitive.content).isEqualTo("x")
        assertThat((5).toJsonElement().jsonPrimitive.int).isEqualTo(5)
        assertThat(true.toJsonElement().jsonPrimitive.boolean).isTrue()
        assertThat(null.toJsonElement()).isEqualTo(JsonNull)
    }

    @Test
    fun `serializes a list of maps`() {
        val v = listOf(mapOf("a" to 1), mapOf("b" to 2))
        val arr = v.toJsonElement().jsonArray
        assertThat(arr).hasSize(2)
        assertThat(arr[0].jsonObject["a"]!!.jsonPrimitive.int).isEqualTo(1)
        assertThat(arr[1].jsonObject["b"]!!.jsonPrimitive.int).isEqualTo(2)
    }
}
