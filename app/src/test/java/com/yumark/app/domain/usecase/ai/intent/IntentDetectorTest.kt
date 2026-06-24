package com.yumark.app.domain.usecase.ai.intent

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class IntentDetectorTest {

    private val openDoc = AppContext(hasOpenDocument = true, hasRecentEdit = true)

    @Test
    fun `summarize open document flags read candidate and knowledge required`() {
        val r = IntentDetector.detect("总结这篇文档", openDoc)
        assertThat(r.candidates).contains(Capability.DOCUMENT_READ)
        assertThat(r.required).contains(Capability.KNOWLEDGE)
    }

    @Test
    fun `rewrite request flags write intent as required`() {
        val r = IntentDetector.detect("改写第二段为更正式的语气", openDoc)
        assertThat(r.candidates).contains(Capability.DOCUMENT_WRITE)
        assertThat(r.required).contains(Capability.DOCUMENT_WRITE)
    }

    @Test
    fun `time question flags time intent`() {
        val r = IntentDetector.detect("今天几号", AppContext())
        assertThat(r.candidates).contains(Capability.TIME)
    }

    @Test
    fun `pure chat with no context yields no candidates`() {
        val r = IntentDetector.detect("什么是递归", AppContext())
        assertThat(r.candidates).isEmpty()
    }

    @Test
    fun `web search phrasing flags web intent`() {
        val r = IntentDetector.detect("搜一下 Vue 3.4 的新特性", AppContext())
        assertThat(r.candidates).contains(Capability.WEB)
    }

    @Test
    fun `memory save phrasing flags memory intent`() {
        val r = IntentDetector.detect("记住我喜欢简洁风格", AppContext())
        assertThat(r.candidates).contains(Capability.MEMORY)
    }

    @Test
    fun `ToolSelector keeps update_plan when candidates exist`() {
        val r = IntentDetector.detect("改写第二段", openDoc)
        val available = listOf(
            com.yumark.app.domain.model.AiTool("update_plan", "plan", emptyMap()),
            com.yumark.app.domain.model.AiTool("edit_document", "edit", emptyMap()),
            com.yumark.app.domain.model.AiTool("web_search", "web", emptyMap())
        )
        val selected = ToolSelector.selectTools(r, available).map { it.name }
        assertThat(selected).contains("update_plan")
        assertThat(selected).contains("edit_document")
        assertThat(selected).doesNotContain("web_search")
    }

    @Test
    fun `ToolSelector sends no tools when no candidates`() {
        val r = IntentDetector.detect("什么是递归", AppContext())
        val available = listOf(
            com.yumark.app.domain.model.AiTool("update_plan", "plan", emptyMap()),
            com.yumark.app.domain.model.AiTool("read_document", "read", emptyMap())
        )
        val selected = ToolSelector.selectTools(r, available)
        assertThat(selected).isEmpty()
    }
}
