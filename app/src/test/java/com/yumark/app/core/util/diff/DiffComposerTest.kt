package com.yumark.app.core.util.diff

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DiffComposerTest {

    private fun accept(r: DiffResult, v: Boolean) = List(r.hunks.size) { v }

    @Test
    fun `accepting all hunks yields the new text`() {
        val old = "a\nb\nc"
        val new = "a\nB\nc\nd"
        val r = LineDiffer.diff(old, new)
        assertThat(DiffComposer.applyHunks(r, accept(r, true))).isEqualTo(new)
    }

    @Test
    fun `rejecting all hunks yields the old text`() {
        val old = "a\nb\nc"
        val new = "a\nB\nc\nd"
        val r = LineDiffer.diff(old, new)
        assertThat(DiffComposer.applyHunks(r, accept(r, false))).isEqualTo(old)
    }

    @Test
    fun `no changes yields identical text`() {
        val text = "x\ny"
        val r = LineDiffer.diff(text, text)
        assertThat(DiffComposer.applyHunks(r, accept(r, true))).isEqualTo(text)
    }

    @Test
    fun `partial acceptance applies only the chosen hunk`() {
        val old = "a\nb\nc\nd\ne"
        val new = "A\nb\nc\nD\ne"
        val r = LineDiffer.diff(old, new)
        assertThat(r.hunks).hasSize(2)
        // 接受第一处改动(a→A)，拒绝第二处(d→D)
        val composed = DiffComposer.applyHunks(r, listOf(true, false))
        assertThat(composed).isEqualTo("A\nb\nc\nd\ne")
    }

    @Test
    fun `invariants hold across many old-new pairs`() {
        val cases = listOf(
            "" to "hello",
            "hello" to "",
            "a\nb\nc" to "a\nb\nc",
            "line1\nline2" to "line1\nCHANGED\nline2\nline3",
            "# 标题\n\n正文" to "# 新标题\n\n正文\n\n补充",
        )
        for ((old, new) in cases) {
            val r = LineDiffer.diff(old, new)
            assertThat(DiffComposer.applyHunks(r, accept(r, true))).isEqualTo(new)
            assertThat(DiffComposer.applyHunks(r, accept(r, false))).isEqualTo(old)
        }
    }

    @Test
    fun `invariants hold on degraded result`() {
        val old = (1..2001).joinToString("\n") { "x$it" }
        val new = (1..2001).joinToString("\n") { "y$it" }
        val r = LineDiffer.diff(old, new)
        assertThat(r.degraded).isTrue()
        assertThat(DiffComposer.applyHunks(r, listOf(true))).isEqualTo(new)
        assertThat(DiffComposer.applyHunks(r, listOf(false))).isEqualTo(old)
    }
}
