package com.yumark.app.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** AI API 提供商类型 */
enum class AiProvider {
    OPENAI,              // OpenAI 官方
    OPENAI_COMPATIBLE,   // OpenAI 兼容格式（Ollama、DeepSeek、本地 vLLM 等）
    CLAUDE,              // Anthropic Claude
    GEMINI               // Google Gemini
}

/** 各 Provider 的默认 Base URL（OPENAI_COMPATIBLE 由用户填写） */
val AiProvider.defaultBaseUrl: String
    get() = when (this) {
        AiProvider.OPENAI -> "https://api.openai.com/v1"
        AiProvider.OPENAI_COMPATIBLE -> ""
        AiProvider.CLAUDE -> "https://api.anthropic.com/v1"
        AiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
    }

/** AI 配置 */
data class AiConfig(
    val enabled: Boolean = false,
    val provider: AiProvider = AiProvider.OPENAI,
    val apiKey: String = "",
    val baseUrl: String = "",
    val modelName: String = "",
    val availableModels: List<String> = emptyList(),
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val streamEnabled: Boolean = true
)

/** 对话类型 */
enum class ConversationType {
    CHAT,    // 普通对话
    AGENT    // Agent 对话（可操作文档）
}

/** 对话状态 */
enum class ConversationStatus {
    IDLE,       // 空闲
    WORKING,    // Agent 正在工作
    COMPLETED   // Agent 已完成工作
}

/** 对话线程 */
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val type: ConversationType,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList(),
    val relatedDocumentId: String? = null,      // 关联的文档 ID
    val relatedDocumentName: String? = null,    // 关联的文档名称
    val status: ConversationStatus = ConversationStatus.IDLE  // 对话状态
)

/** 消息角色 */
enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

/** 单条消息 */
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val agentAction: AgentAction? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

/** Agent 操作类型 */
@Serializable
enum class AgentActionType {
    CREATE_DOCUMENT,    // 创建新文档
    EDIT_DOCUMENT       // 编辑当前文档
}

/** Agent 操作状态 */
@Serializable
enum class AgentActionStatus {
    PENDING,    // 等待用户确认
    APPROVED,   // 已批准
    REJECTED,   // 已拒绝
    EXECUTED    // 已执行
}

/**
 * Agent 操作。序列化后存入 [Message] 的 agentActionJson 列。
 * 权限：仅 CREATE / EDIT，不支持删除和移动。
 */
@Serializable
data class AgentAction(
    val type: AgentActionType,
    val description: String,
    val targetDocumentId: String? = null,
    val content: String,
    val status: AgentActionStatus = AgentActionStatus.PENDING
)
