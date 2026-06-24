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

/** 网络搜索引擎 Provider（移植自 guanmo webSearch.ts） */
enum class WebSearchProvider {
    DUCKDUCKGO,  // 免 key，解析 lite 页面 HTML
    TAVILY,      // POST Bearer
    SERPER,      // POST X-API-KEY（Google）
    BRAVE,       // GET X-Subscription-Token
    CUSTOM       // 用户自定义 URL，自动探测常见响应格式
}

/**
 * 记忆分类（移植自 guanmo memoryService）。
 * 检索排序优先级：PROJECT > PROFILE > INSTRUCTION > PREFERENCE > LEARNING。
 */
enum class MemoryCategory(val priority: Int) {
    PROJECT(5),
    PROFILE(4),
    INSTRUCTION(3),
    PREFERENCE(2),
    LEARNING(1);

    companion object {
        fun fromString(raw: String?): MemoryCategory =
            runCatching { valueOf(raw?.uppercase()?.trim().orEmpty()) }.getOrDefault(PREFERENCE)
    }
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
    val streamEnabled: Boolean = true,
    val webSearchEnabled: Boolean = false,
    val webSearchProvider: WebSearchProvider = WebSearchProvider.DUCKDUCKGO,
    val webSearchApiKey: String = "",
    val webSearchCustomUrl: String = "",
    /** OpenAI 兼容 /embeddings 模型名（如 text-embedding-3-small）；为空则 RAG 不索引。复用 baseUrl/apiKey。 */
    val embeddingModel: String = ""
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
    val isStreaming: Boolean = false,
    val steps: List<AgentStep> = emptyList(),
    val attachments: List<MessageAttachment> = emptyList()
)

/**
 * 消息附件的持久化引用（存盘文件路径 + 元信息，**不含 Base64**）。
 * 序列化进 [Message] 的 attachmentsJson 列；显示时用 path 经 Coil 加载，
 * 发送时由 ImageProcessor 现读文件编码为 [MessageContent.Image]。
 */
@Serializable
data class MessageAttachment(
    val path: String,            // 应用私有目录相对路径，如 ai_attachments/<uuid>.jpg
    val mimeType: String,        // image/jpeg | image/png | image/gif | image/webp
    val width: Int? = null,
    val height: Int? = null
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

/**
 * AI工具定义（Function Calling）
 */
data class AiTool(
    val name: String,
    val description: String,
    val parameters: Map<String, Any> // JSON Schema
)

/**
 * AI发起的工具调用
 */
data class ToolCall(
    val id: String,           // 调用ID（用于关联响应）
    val name: String,         // 工具名称
    val arguments: String     // JSON字符串参数
)
