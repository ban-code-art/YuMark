package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PlanAgentTaskUseCaseTest {

    private val useCase = PlanAgentTaskUseCase()

    @Test
    fun `parses planner json from fenced output`() {
        val raw = """
            The plan is:
            ```json
            {
              "goal": "organize project notes",
              "success_criteria": ["source notes are located", "an edit proposal is ready"],
              "steps": [
                {
                  "title": "Find source notes",
                  "purpose": "Locate notes related to the user's goal",
                  "suggested_tools": ["search_in_project"],
                  "completion_criteria": "Relevant document ids are known",
                  "fallback": "Ask the user for a more specific document name"
                },
                {
                  "title": "Read target note",
                  "purpose": "Gather the exact current content before editing",
                  "suggested_tools": ["read_document"],
                  "completion_criteria": "Target document content is available",
                  "fallback": "Use search snippets if the document cannot be read"
                }
              ]
            }
            ```
        """.trimIndent()

        val result = useCase(raw)

        assertThat(result.isSuccess).isTrue()
        val plan = result.getOrThrow()
        assertThat(plan.goal).isEqualTo("organize project notes")
        assertThat(plan.successCriteria).containsExactly("source notes are located", "an edit proposal is ready").inOrder()
        assertThat(plan.steps.map { it.title }).containsExactly("Find source notes", "Read target note").inOrder()
        assertThat(plan.steps.first().suggestedTools).containsExactly("search_in_project")
    }

    @Test
    fun `rejects unsupported suggested tool`() {
        val raw = """
            {
              "goal": "summarize notes",
              "success_criteria": ["summary is grounded in existing notes"],
              "steps": [
                {
                  "title": "Search the web",
                  "purpose": "Use an unsupported external search",
                  "suggested_tools": ["web_search"],
                  "completion_criteria": "External results are found",
                  "fallback": "Ask the user"
                }
              ]
            }
        """.trimIndent()

        val result = useCase(raw)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("Unsupported tool")
    }

    @Test
    fun `rejects missing required step fields`() {
        val raw = """
            {
              "goal": "summarize notes",
              "success_criteria": ["summary is accurate"],
              "steps": [
                {
                  "title": "Read note",
                  "purpose": "Get source content",
                  "suggested_tools": ["read_document"],
                  "fallback": "Ask the user"
                }
              ]
            }
        """.trimIndent()

        val result = useCase(raw)

        assertThat(result.isFailure).isTrue()
    }
}
