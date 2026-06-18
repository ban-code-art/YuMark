package com.yumark.app.domain.usecase.ai.agent

import kotlinx.serialization.json.Json

internal val agentRuntimeJson = Json {
    ignoreUnknownKeys = true
}

internal fun extractJsonObject(raw: String): String {
    val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        .find(raw)
        ?.groupValues
        ?.get(1)
        ?.trim()
    if (!fenced.isNullOrBlank()) return fenced

    val start = raw.indexOf('{')
    require(start >= 0) { "JSON object not found" }
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until raw.length) {
        val char = raw[index]
        when {
            escaped -> escaped = false
            char == '\\' && inString -> escaped = true
            char == '"' -> inString = !inString
            !inString && char == '{' -> depth++
            !inString && char == '}' -> {
                depth--
                if (depth == 0) return raw.substring(start, index + 1)
            }
        }
    }
    error("JSON object is incomplete")
}
