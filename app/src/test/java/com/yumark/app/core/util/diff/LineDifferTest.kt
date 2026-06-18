package com.yumark.app.core.util.diff

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LineDifferTest {

    private fun lines(s: String) = if (s.isEmpty()) emptyList() else s.split("\n")

    @Test
    fun `identical text has no changes`() {
        val r = LineDiffer.diff("# 标题\n正文", "# 标题\n正文")
        assertThat(r.hasChanges).isFalse()
        assertThat(r.hunks).isEmpty()
        assertThat(r.lines.map { it.op }.all { it == DiffOp.UNCHANGED }).isTrue()
    }

    @Test
    fun `dropping added lines reconstructs the old text`() {
        val old = "a\nb\nc\nd"
        val new = "a\nB\nc\nd\ne"
        val r = LineDiffer.diff(old, new)
        val reconstructedOld = r.lines.filter { it.op != DiffOp.ADDED }.map { it.text }
        assertThat(reconstructedOld).isEqualTo(lines(old))
    }

    @Test
    fun `dropping removed lines reconstructs the new text`() {
        val old = "a\nb\nc\nd"
        val new = "a\nB\nc\nd\ne"
        val r = LineDiffer.diff(old, new)
        val reconstructedNew = r.lines.filter { it.op != DiffOp.REMOVED }.map { it.text }
        assertThat(reconstructedNew).isEqualTo(lines(new))
    }

    @Test
    fun `empty old yields a single added hunk`() {
        val r = LineDiffer.diff("", "x\ny")
        assertThat(r.hunks).hasSize(1)
        assertThat(r.hunks[0].removed).isEmpty()
        assertThat(r.hunks[0].added).isEqualTo(listOf("x", "y"))
    }

    @Test
    fun `empty new yields a single removed hunk`() {
        val r = LineDiffer.diff("x\ny", "")
        assertThat(r.hunks).hasSize(1)
        assertThat(r.hunks[0].removed).isEqualTo(listOf("x", "y"))
        assertThat(r.hunks[0].added).isEmpty()
    }

    @Test
    fun `inserting a line creates one added hunk between unchanged lines`() {
        val r = LineDiffer.diff("a\nc", "a\nb\nc")
        assertThat(r.hunks).hasSize(1)
        assertThat(r.hunks[0].removed).isEmpty()
        assertThat(r.hunks[0].added).isEqualTo(listOf("b"))
    }

    @Test
    fun `replacing a line creates one hunk with removed and added`() {
        val r = LineDiffer.diff("a\nb\nc", "a\nB\nc")
        assertThat(r.hunks).hasSize(1)
        assertThat(r.hunks[0].removed).isEqualTo(listOf("b"))
        assertThat(r.hunks[0].added).isEqualTo(listOf("B"))
    }

    @Test
    fun `unchanged lines carry no hunk id`() {
        val r = LineDiffer.diff("a\nb\nc", "a\nB\nc")
        val unchanged = r.lines.filter { it.op == DiffOp.UNCHANGED }
        assertThat(unchanged.map { it.text }).containsExactly("a", "c").inOrder()
        assertThat(unchanged.all { it.hunkId == DiffLine.NO_HUNK }).isTrue()
    }

    @Test
    fun `two separate edits create two hunks`() {
        val r = LineDiffer.diff("a\nb\nc\nd\ne", "A\nb\nc\nD\ne")
        assertThat(r.hunks).hasSize(2)
    }

    @Test
    fun `large document degrades to a single whole-document hunk`() {
        val old = (1..2001).joinToString("\n") { "line $it" }
        val new = (1..2001).joinToString("\n") { "LINE $it" }
        val r = LineDiffer.diff(old, new)
        assertThat(r.degraded).isTrue()
        assertThat(r.hunks).hasSize(1)
        assertThat(r.hunks[0].removed).isEqualTo(lines(old))
        assertThat(r.hunks[0].added).isEqualTo(lines(new))
    }
}
