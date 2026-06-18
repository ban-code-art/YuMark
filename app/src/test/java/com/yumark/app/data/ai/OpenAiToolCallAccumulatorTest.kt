package com.yumark.app.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OpenAiToolCallAccumulatorTest {

    @Test
    fun `empty accumulator has no calls`() {
        val acc = OpenAiToolCallAccumulator()
        assertThat(acc.isEmpty()).isTrue()
        assertThat(acc.build()).isEmpty()
    }

    @Test
    fun `single delta yields one complete call`() {
        val acc = OpenAiToolCallAccumulator()
        acc.accept(0, "call_1", "read_document", """{"document_id":"x"}""")
        val calls = acc.build()
        assertThat(calls).hasSize(1)
        assertThat(calls[0].id).isEqualTo("call_1")
        assertThat(calls[0].name).isEqualTo("read_document")
        assertThat(calls[0].arguments).isEqualTo("""{"document_id":"x"}""")
    }

    @Test
    fun `arguments accumulate across chunks`() {
        val acc = OpenAiToolCallAccumulator()
        acc.accept(0, "call_1", "read_document", """{"doc""")
        acc.accept(0, null, null, """ument_id":"x"}""")
        assertThat(acc.build()[0].arguments).isEqualTo("""{"document_id":"x"}""")
    }

    @Test
    fun `id and name only present in first chunk are retained`() {
        val acc = OpenAiToolCallAccumulator()
        acc.accept(0, "call_1", "list_documents", "{")
        acc.accept(0, null, null, "}")
        val c = acc.build()[0]
        assertThat(c.id).isEqualTo("call_1")
        assertThat(c.name).isEqualTo("list_documents")
        assertThat(c.arguments).isEqualTo("{}")
    }

    @Test
    fun `parallel tool calls tracked by index`() {
        val acc = OpenAiToolCallAccumulator()
        acc.accept(0, "c0", "read_document", """{"document_id":"a"}""")
        acc.accept(1, "c1", "search_in_project", """{"query":"b"}""")
        val calls = acc.build()
        assertThat(calls).hasSize(2)
        assertThat(calls.map { it.name }).containsExactly("read_document", "search_in_project").inOrder()
    }

    @Test
    fun `interleaved chunks across indices accumulate independently`() {
        val acc = OpenAiToolCallAccumulator()
        acc.accept(0, "c0", "read_document", """{"id":""")
        acc.accept(1, "c1", "list_documents", "{")
        acc.accept(0, null, null, """"a"}""")
        acc.accept(1, null, null, "}")
        val calls = acc.build()
        assertThat(calls).hasSize(2)
        assertThat(calls[0].arguments).isEqualTo("""{"id":"a"}""")
        assertThat(calls[1].arguments).isEqualTo("{}")
    }
}
