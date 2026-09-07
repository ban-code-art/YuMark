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
    /** OpenAI 兼容 /embeddings 模型名（如 text-embedding-3-small）；为空则 RAG 不索引。 */
    val embeddingModel: String = "",
    /**
     * embedding 端点是否复用上面 chat 的 [baseUrl] / [apiKey]。
     *
     * 默认 true 是为了兼容既有安装：这个字段从前不存在，那时 RAG 走的就是 chat 那一组，
     * 默认 false 会让升级后的用户知识库突然停止索引，而界面上只多出一个空输入框——
     * 没有任何提示告诉他刚才发生了什么。
     *
     * 之所以需要「分开配」：能跑 chat 的端点不一定提供 `/embeddings`（Claude、Gemini 都不提供
     * OpenAI 那套 embedding 协议），而 embedding 是按量计费里最便宜、最适合指向本地 Ollama /
     * 自建 vLLM 的一段。把两者绑死等于「想用 Claude 聊天就别想建向量索引」。
     */
    val ragUseMainEndpoint: Boolean = true,
    /** [ragUseMainEndpoint] 为 false 时生效的独立 embedding 端点，仍走 OpenAI 兼容 `/embeddings`。 */
    val ragBaseUrl: String = "",
    /** [ragUseMainEndpoint] 为 false 时生效的独立 embedding 密钥。 */
    val ragApiKey: String = "",
    /**
     * 从 embedding 端点 `/models` 拉回来的候选列表，只做输入提示。
     * 与 [availableModels] 分开存：两个端点可以是完全不同的服务，模型集合没有交集也正常。
     */
    val ragAvailableModels: List<String> = emptyList(),
    /**
     * 「笔记内容将发送到第三方 AI 端点」的知情确认（隐私合规）。开启 AI 开关时弹出一次，
     * 用户同意后置位。false 不阻止已启用的配置发起请求（那会把升级上来的老用户挡在门外），
     * 但 AI 设置页会在这种状态下再次弹出提示；置位随配置备份走，换机不必重新确认。
     */
    val consentAcknowledged: Boolean = false
)

/**
 * embedding 请求真正会打到的 baseUrl。
 *
 * 复用模式下与 chat 完全同源（含「留空则取 provider 默认值」这一层）；独立模式下只认
 * [AiConfig.ragBaseUrl]，**不**在它为空时悄悄回落到 chat 那一组——用户明确选了「单独配置」，
 * 静默换回另一个地址就是把请求连同密钥发去了一个他没指定的服务器。
 *
 * 空串表示「没配」，由调用方拦下来报错（见 `RagPipeline` 的 embedding 前置检查），
 * 而不是让它拼成 `/embeddings` 这种相对路径去撞一个语焉不详的网络异常。
 */
val AiConfig.ragBaseUrlResolved: String
    get() = if (ragUseMainEndpoint) baseUrl.ifBlank { provider.defaultBaseUrl } else ragBaseUrl.trim()

/**
 * 与 [ragBaseUrlResolved] 同一套判据的密钥。
 *
 * 独立模式下为空也照发：本地 Ollama、自建 vLLM 这类端点根本不校验 Authorization，
 * 在这里强制要求密钥会把最主要的使用场景挡在门外。
 */
val AiConfig.ragApiKeyResolved: String
    get() = if (ragUseMainEndpoint) apiKey else ragApiKey

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
    EDIT_DOCUMENT,      // 编辑当前文档
    MOVE_DOCUMENT,      // 移动文档到文件夹（可逆）
    RENAME_DOCUMENT,    // 重命名文档（可逆；重名自动改名落座）
    DELETE_DOCUMENT     // 删除文档（= 移入回收站；Agent 永远没有彻底删除权限）
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
    // ===== 动作空间扩展（move/rename/delete，v0.12）=====
    /** 批量动作的目标（多选移动/删除）；单目标动作用 [targetDocumentId]。 */
    val targetIds: List<String> = emptyList(),
    /** MOVE：目标文件夹 id；null = 根目录。 */
    val destinationFolderId: String? = null,
    /** RENAME：新名字（已按同级重名规则调整后的最终名）。 */
    val newName: String? = null,
    val status: AgentActionStatus = AgentActionStatus.PENDING,
    /**
     * EDIT_DOCUMENT：提议生成那一刻目标文档原文的指纹
     * （[com.yumark.app.core.text.ContentHash]）。
     *
     * [content] 是「原文 + 模型的编辑」合成出来的**新全文**，批准时会整篇覆盖目标文档。
     * 提议会随消息落库（agentActionJson），批准可以发生在几分钟甚至一次冷启动之后——
     * 这期间用户在编辑器里改了几行、WebDAV 拉回了远端版本，覆盖就把那些改动无声吃掉了
     * （历史版本里还能翻回来，但用户不会知道发生过）。
     * 记下这个指纹，[com.yumark.app.domain.usecase.ai.agent.ExecuteAgentActionUseCase]
     * 在写入前重新读文档比对：不一致就拒绝执行并提示重新生成。
     *
     * 可空且有默认值：本字段之前的提议已经在库里，缺这个 key 时解码成 null，
     * 那些老提议按「无基线可校验」放行（行为与从前一致），不会因为升级而失效。
     */
    val baseContentHash: String? = null
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
