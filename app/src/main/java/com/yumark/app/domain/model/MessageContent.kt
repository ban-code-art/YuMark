package com.yumark.app.domain.model

/**
 * 多模态消息内容（API 线格式，发送时即时构造，瞬态、不持久化）。
 *
 * Provider 中立：图片只存**裸 Base64 + mimeType**，不预拼 data URL，
 * 由各适配器按自家协议格式化（OpenAI: image_url data URL；Claude: source.base64；
 * Gemini: inline_data）。持久化用的引用见 [MessageAttachment]。
 */
sealed class MessageContent {
    /** 文本片段 */
    data class Text(val text: String) : MessageContent()

    /** 图片片段 */
    data class Image(
        val base64: String,   // 纯 Base64，无 "data:..." 前缀
        val mimeType: String  // image/jpeg | image/png | image/gif | image/webp
    ) : MessageContent()
}
