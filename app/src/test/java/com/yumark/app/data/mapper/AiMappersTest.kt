package com.yumark.app.data.mapper

import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.local.db.entity.MessageEntity
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.domain.model.AgentStep
import com.yumark.app.domain.model.Message
import com.yumark.app.domain.model.MessageAttachment
import com.yumark.app.domain.model.MessageRole
import org.junit.jupiter.api.Test

class AiMappersTest {

    @Test
    fun `message steps survive entity round trip`() {
        val msg = Message(
            id = "m1",
            conversationId = "c1",
            role = MessageRole.ASSISTANT,
            content = "答案",
            steps = listOf(
                AgentStep.ToolCalling("search_in_project", "预算"),
                AgentStep.ToolDone("read_document", true, "1.2k 字")
            )
        )
        val back = msg.toEntity().toDomain()
        assertThat(back.steps).isEqualTo(msg.steps)
    }

    @Test
    fun `empty steps map to null json and back to empty list`() {
        val msg = Message(id = "m2", conversationId = "c1", role = MessageRole.USER, content = "hi")
        val entity = msg.toEntity()
        assertThat(entity.stepsJson).isNull()
        assertThat(entity.toDomain().steps).isEmpty()
    }

    @Test
    fun `message attachments survive entity round trip`() {
        val msg = Message(
            id = "m3",
            conversationId = "c1",
            role = MessageRole.USER,
            content = "看图",
            attachments = listOf(
                MessageAttachment(path = "ai_attachments/x.jpg", mimeType = "image/jpeg", width = 800, height = 600),
                MessageAttachment(path = "ai_attachments/y.png", mimeType = "image/png")
            )
        )
        val back = msg.toEntity().toDomain()
        assertThat(back.attachments).isEqualTo(msg.attachments)
    }

    @Test
    fun `empty attachments map to null json`() {
        val msg = Message(id = "m4", conversationId = "c1", role = MessageRole.USER, content = "hi")
        assertThat(msg.toEntity().attachmentsJson).isNull()
    }

    @Test
    fun `agent action base fingerprint survives entity round trip`() {
        val msg = Message(
            id = "m5",
            conversationId = "c1",
            role = MessageRole.ASSISTANT,
            content = "我改好了",
            agentAction = AgentAction(
                type = AgentActionType.EDIT_DOCUMENT,
                description = "编辑文档",
                targetDocumentId = "doc-1",
                content = "新全文",
                baseContentHash = "a".repeat(64)
            )
        )
        val back = msg.toEntity().toDomain()
        assertThat(back.agentAction).isEqualTo(msg.agentAction)
    }

    @Test
    fun `legacy agent action json without the fingerprint key decodes to null`() {
        // baseContentHash 落地之前存进 agentActionJson 的提议：解码必须成功且指纹为 null，
        // 而不是整条 agentAction 解不出来（runCatching 会把它吞成 null，那张卡片就凭空消失）。
        val legacyJson = """
            {"type":"EDIT_DOCUMENT","description":"编辑文档","targetDocumentId":"doc-1",
             "content":"新全文","status":"PENDING"}
        """.trimIndent()
        val entity = MessageEntity(
            id = "m6",
            conversationId = "c1",
            role = MessageRole.ASSISTANT.name,
            content = "我改好了",
            agentActionJson = legacyJson,
            timestamp = 0L
        )

        val action = entity.toDomain().agentAction

        assertThat(action).isNotNull()
        assertThat(action!!.baseContentHash).isNull()
        assertThat(action.content).isEqualTo("新全文")
        assertThat(action.targetDocumentId).isEqualTo("doc-1")
    }
}
