package com.yumark.app.domain.usecase.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DocumentEditApplierTest {

    @Test
    fun `exact single replacement`() {
        val base = "# 标题\n\n第一段。\n第二段。"
        val r = DocumentEditApplier.applyEdits(base, listOf(EditOp("第一段。", "第一段（已改）。")))
        assertThat(r.isSuccess).isTrue()
        assertThat(r.getOrThrow()).isEqualTo("# 标题\n\n第一段（已改）。\n第二段。")
    }

    @Test
    fun `sequential multi-edit applies in order`() {
        val base = "a\nb\nc"
        val r = DocumentEditApplier.applyEdits(base, listOf(
            EditOp("a", "A"),
            EditOp("c", "C")
        ))
        assertThat(r.getOrThrow()).isEqualTo("A\nb\nC")
    }

    @Test
    fun `ambiguous match without replaceAll fails`() {
        val base = "x\nx\nx"
        val r = DocumentEditApplier.applyEdits(base, listOf(EditOp("x", "y")))
        assertThat(r.isFailure).isTrue()
        assertThat(r.exceptionOrNull()).isInstanceOf(EditException::class.java)
        assertThat(r.exceptionOrNull()!!.message).contains("不唯一")
    }

    @Test
    fun `replaceAll replaces every occurrence`() {
        val base = "x\nx\nx"
        val r = DocumentEditApplier.applyEdits(base, listOf(EditOp("x", "y", replaceAll = true)))
        assertThat(r.getOrThrow()).isEqualTo("y\ny\ny")
    }

    @Test
    fun `not found fails with guidance`() {
        val base = "hello world"
        val r = DocumentEditApplier.applyEdits(base, listOf(EditOp("nonexistent", "z")))
        assertThat(r.isFailure).isTrue()
        assertThat(r.exceptionOrNull()!!.message).contains("read_document")
    }

    @Test
    fun `empty old string fails`() {
        val r = DocumentEditApplier.applyEdits("abc", listOf(EditOp("", "z")))
        assertThat(r.isFailure).isTrue()
        assertThat(r.exceptionOrNull()!!.message).contains("为空")
    }

    @Test
    fun `tolerant match absorbs trailing whitespace differences`() {
        // 原文行尾有多余空格，old_string 没有 —— 精确匹配失败，行级 trim 容差命中
        val base = "## 小节   \n正文一行。"
        val r = DocumentEditApplier.applyEdits(base, listOf(EditOp("## 小节", "## 小节（改）")))
        assertThat(r.isSuccess).isTrue()
        assertThat(r.getOrThrow()).contains("## 小节（改）")
        assertThat(r.getOrThrow()).contains("正文一行。")
    }

    @Test
    fun `multiline block replacement`() {
        val base = "intro\n## A\nold body\nmore\n## B\ntail"
        val r = DocumentEditApplier.applyEdits(base, listOf(
            EditOp("## A\nold body\nmore", "## A\nnew body")
        ))
        assertThat(r.getOrThrow()).isEqualTo("intro\n## A\nnew body\n## B\ntail")
    }

    @Test
    fun `empty edits list fails`() {
        val r = DocumentEditApplier.applyEdits("abc", emptyList())
        assertThat(r.isFailure).isTrue()
    }
}
