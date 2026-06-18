package com.yumark.app.data.mapper

import com.google.common.truth.Truth.assertThat
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
}
