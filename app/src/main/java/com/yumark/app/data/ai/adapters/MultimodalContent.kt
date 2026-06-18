package com.yumark.app.data.ai.adapters

import com.yumark.app.domain.model.MessageContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 把 Provider 中立的 [MessageContent] 列表格式化为各家的 content 数组。
 * 三家共享同一份裸 Base64 图片，差异只在这里收敛（设计 §5）。
 */

/** OpenAI：text → {type:text}；image → {type:image_url, image_url:{url:"data:<mime>;base64,<b64>"}}。 */
internal fun openAiContentParts(parts: List<MessageContent>): JsonArray = buildJsonArray {
    parts.forEach { part ->
        when (part) {
            is MessageContent.Text -> addJsonObject {
                put("type", "text"); put("text", part.text)
            }
            is MessageContent.Image -> addJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") {
                    put("url", "data:${part.mimeType};base64,${part.base64}")
                }
            }
        }
    }
}

/** Claude：text → {type:text}；image → {type:image, source:{type:base64, media_type, data}}（裸 Base64）。 */
internal fun claudeContentParts(parts: List<MessageContent>): JsonArray = buildJsonArray {
    parts.forEach { part ->
        when (part) {
            is MessageContent.Text -> addJsonObject {
                put("type", "text"); put("text", part.text)
            }
            is MessageContent.Image -> addJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", part.mimeType)
                    put("data", part.base64)
                }
            }
        }
    }
}

/** Gemini：text → {text}；image → {inline_data:{mime_type, data}}（裸 Base64）。 */
internal fun geminiContentParts(parts: List<MessageContent>): JsonArray = buildJsonArray {
    parts.forEach { part ->
        when (part) {
            is MessageContent.Text -> addJsonObject { put("text", part.text) }
            is MessageContent.Image -> addJsonObject {
                putJsonObject("inline_data") {
                    put("mime_type", part.mimeType)
                    put("data", part.base64)
                }
            }
        }
    }
}
