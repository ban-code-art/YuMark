package com.yumark.app.domain.usecase.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SearchRankerTest {

    @Test
    fun `tokenize splits on whitespace and punctuation, lowercased`() {
        assertThat(SearchRanker.tokenize("Hello, World!")).containsExactly("hello", "world").inOrder()
        assertThat(SearchRanker.tokenize("预算 计划")).containsExactly("预算", "计划").inOrder()
        assertThat(SearchRanker.tokenize("   ")).isEmpty()
    }

    @Test
    fun `score is zero when no token matches`() {
        assertThat(SearchRanker.score("标题", "正文内容", listOf("xyz"))).isEqualTo(0)
    }

    @Test
    fun `score increases with more body matches`() {
        val tokens = listOf("预算")
        val low = SearchRanker.score("无关", "预算", tokens)
        val high = SearchRanker.score("无关", "预算 预算 预算", tokens)
        assertThat(high).isGreaterThan(low)
    }

    @Test
    fun `name match weighs more than body match`() {
        val tokens = listOf("预算")
        val nameHit = SearchRanker.score("预算计划", "无关正文", tokens)
        val bodyHit = SearchRanker.score("无关", "预算", tokens)
        assertThat(nameHit).isGreaterThan(bodyHit)
    }

    @Test
    fun `snippets return lines containing tokens with line index`() {
        val content = "第一行\n含预算的第二行\n第三行\n又一个预算行"
        val hits = SearchRanker.snippets(content, listOf("预算"), maxSnippets = 5)
        assertThat(hits.map { it.first }).containsExactly(1, 3).inOrder()
        assertThat(hits[0].second).contains("预算")
    }

    @Test
    fun `snippets respect max limit`() {
        val content = (1..10).joinToString("\n") { "预算行 $it" }
        assertThat(SearchRanker.snippets(content, listOf("预算"), maxSnippets = 3)).hasSize(3)
    }
}
