package com.yumark.app.data.ai.adapters

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.MessageContent
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** 守护三家 Provider 各自的多模态 content 格式（裸 Base64 → 各家协议）。 */
class MultimodalContentTest {

    private val parts = listOf(
        MessageContent.Text("看这张图"),
        MessageContent.Image(base64 = "QUJD", mimeType = "image/png")
    )

    @Test
    fun `openai formats image as data url`() {
        val arr = openAiContentParts(parts)
        val text = arr[0].jsonObject
        assertThat(text["type"]!!.jsonPrimitive.content).isEqualTo("text")
        assertThat(text["text"]!!.jsonPrimitive.content).isEqualTo("看这张图")
        val img = arr[1].jsonObject
        assertThat(img["type"]!!.jsonPrimitive.content).isEqualTo("image_url")
        assertThat(img["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            .isEqualTo("data:image/png;base64,QUJD")
    }

    @Test
    fun `claude formats image as base64 source`() {
        val img = claudeContentParts(parts)[1].jsonObject
        assertThat(img["type"]!!.jsonPrimitive.content).isEqualTo("image")
        val source = img["source"]!!.jsonObject
        assertThat(source["type"]!!.jsonPrimitive.content).isEqualTo("base64")
        assertThat(source["media_type"]!!.jsonPrimitive.content).isEqualTo("image/png")
        assertThat(source["data"]!!.jsonPrimitive.content).isEqualTo("QUJD")
    }

    @Test
    fun `gemini formats image as inline_data`() {
        val arr = geminiContentParts(parts)
        assertThat(arr[0].jsonObject["text"]!!.jsonPrimitive.content).isEqualTo("看这张图")
        val inline = arr[1].jsonObject["inline_data"]!!.jsonObject
        assertThat(inline["mime_type"]!!.jsonPrimitive.content).isEqualTo("image/png")
        assertThat(inline["data"]!!.jsonPrimitive.content).isEqualTo("QUJD")
    }
}
