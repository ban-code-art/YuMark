package com.yumark.app.data.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把 [Map] / [Iterable] / 基本类型递归转 [JsonElement]。
 * 用于把 [com.yumark.app.domain.model.AiTool.parameters]（`Map<String, Any>` 的 JSON Schema）
 * 序列化进请求体。未知类型回退为字符串。
 */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> buildJsonObject { forEach { (k, v) -> put(k.toString(), v.toJsonElement()) } }
    is Iterable<*> -> buildJsonArray { forEach { add(it.toJsonElement()) } }
    else -> JsonPrimitive(toString())
}
