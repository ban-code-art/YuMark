package com.yumark.app.data.ai.rag

/** 内容哈希 —— FNV-1a 32-bit（移植自 guanmo `contentHash.ts`），用于分块去重。 */
internal fun createContentHash(content: String): String {
    val normalized = normalizeForHash(content)
    var hash = -0x7ee3623b  // 0x811c9dc5 的 Int 表示
    for (ch in normalized) {
        hash = hash xor ch.code
        hash *= 0x01000193
    }
    return (hash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
}

private fun normalizeForHash(content: String): String =
    content.replace("\r\n", "\n")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        .lowercase()
