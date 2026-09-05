package com.yumark.app.core.text

import java.security.MessageDigest

/**
 * 文档正文的指纹（SHA-256 十六进制小写）。
 *
 * 两处需要「这份正文还是我当初读到的那一份吗」：
 * - WebDAV 同步比对本地/远端正文是否真的不同（`SyncRepositoryImpl`）；
 * - Agent 的编辑提议记下提议那一刻的原文指纹，批准时校验文档没在这期间被改过
 *   （见 [com.yumark.app.domain.model.AgentAction.baseContentHash]）。
 *
 * 存指纹而不是整篇原文：提议要序列化进 Room 的 `agentActionJson`，再塞一份全文等于
 * 每条提议翻倍占库；而校验只需要相等判定。
 *
 * 纯函数、不碰 Context，可在 JVM 单元测试里跑。
 *
 * **不要与 `data/ai/rag/RagContentHash.kt` 互换**：那一个 32 位 FNV-1a 且归一化抹掉空白
 * 大小写，用途是「这段正文值不值得重新付费算 embedding」；这一个 SHA-256 逐字节，用途是
 * 校验用户数据没被悄悄改掉——用错边会让「只改缩进」的改动被判成没变（rag 那个）或让用户
 * 白付一次重算（这边用在 rag 场景）。
 */
object ContentHash {

    /** 正文的 SHA-256 十六进制摘要。UTF-8 编码，与平台默认字符集无关。 */
    fun of(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
