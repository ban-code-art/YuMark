package com.yumark.app.domain.usecase.ai.agent

import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.usecase.LoadDocumentUseCase
import com.yumark.app.domain.usecase.ai.DocumentEditApplier
import com.yumark.app.domain.usecase.ai.EditException
import com.yumark.app.domain.usecase.ai.EditOp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * 把写工具调用（create_document / edit_document）转为待审批的 [AgentAction]。
 *
 * - create：直接以完整正文构造创建提议。
 * - edit：加载目标文档原文，用 [DocumentEditApplier] 顺序应用 `edits` 得到更新后全文，
 *   再构造编辑提议（content=新全文）→ 复用既有逐行 diff 审批门。
 *
 * 失败（缺目标、原文读不到、old_string 未命中/不唯一）返回 [Result.failure]，
 * 其 message 由 [SendAgentMessageUseCase] 回填给模型，引导自我修正。
 */
class BuildWriteProposalUseCase @Inject constructor(
    private val loadDocument: LoadDocumentUseCase
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend operator fun invoke(call: ToolCall, currentDocumentId: String?): Result<AgentAction> = runCatching {
        when (call.name) {
            "create_document" -> {
                val args = json.decodeFromString(CreateArgs.serializer(), call.arguments)
                val content = args.content.ifBlank { throw EditException("create_document 缺少 content。") }
                val title = args.title?.ifBlank { null } ?: "AI 生成文档"
                AgentAction(
                    type = AgentActionType.CREATE_DOCUMENT,
                    description = title,
                    content = content
                )
            }
            "edit_document" -> {
                val args = json.decodeFromString(EditArgs.serializer(), call.arguments)
                val docId = args.documentId?.ifBlank { null } ?: currentDocumentId
                    ?: throw EditException("缺少目标文档：请提供 document_id，或在文档内发起编辑。")
                if (args.edits.isEmpty()) throw EditException("edit_document 缺少 edits。")
                val base = loadDocument(docId)
                    .getOrElse { throw EditException("无法读取目标文档内容：${it.message}") }
                    .content
                val ops = args.edits.map { EditOp(it.oldString, it.newString, it.replaceAll) }
                val merged = DocumentEditApplier.applyEdits(base, ops).getOrThrow()
                AgentAction(
                    type = AgentActionType.EDIT_DOCUMENT,
                    description = "编辑文档",
                    targetDocumentId = docId,
                    content = merged
                )
            }
            else -> throw EditException("非写工具：${call.name}")
        }
    }
}

@Serializable
private data class CreateArgs(
    val title: String? = null,
    val content: String = ""
)

@Serializable
private data class EditArgs(
    @SerialName("document_id") val documentId: String? = null,
    val edits: List<EditOpPayload> = emptyList()
)

@Serializable
private data class EditOpPayload(
    @SerialName("old_string") val oldString: String = "",
    @SerialName("new_string") val newString: String = "",
    @SerialName("replace_all") val replaceAll: Boolean = false
)
