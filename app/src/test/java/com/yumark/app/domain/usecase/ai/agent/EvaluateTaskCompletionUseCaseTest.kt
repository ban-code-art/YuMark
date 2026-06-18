package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EvaluateTaskCompletionUseCaseTest {

    private val useCase = EvaluateTaskCompletionUseCase()

    @Test
    fun `parses completed outcome from fenced json`() {
        val raw = """
            ```json
            {
              "outcome": "COMPLETED",
              "summary": "The target document was read and an edit proposal is ready.",
              "blocking_reason": null
            }
            ```
        """.trimIndent()

        val result = useCase(raw)

        assertThat(result.isSuccess).isTrue()
        val decision = result.getOrThrow()
        assertThat(decision.outcome).isEqualTo(TaskCompletionOutcome.COMPLETED)
        assertThat(decision.summary).contains("edit proposal")
        assertThat(decision.blockingReason).isNull()
    }

    @Test
    fun `parses blocked outcome with blocking reason`() {
        val raw = """
            {
              "outcome": "BLOCKED",
              "summary": "The requested source note could not be found.",
              "blocking_reason": "No matching document was returned by search."
            }
        """.trimIndent()

        val result = useCase(raw)

        assertThat(result.isSuccess).isTrue()
        val decision = result.getOrThrow()
        assertThat(decision.outcome).isEqualTo(TaskCompletionOutcome.BLOCKED)
        assertThat(decision.blockingReason).contains("No matching document")
    }

    @Test
    fun `rejects unknown completion outcome`() {
        val raw = """
            {
              "outcome": "MAYBE_DONE",
              "summary": "Probably finished",
              "blocking_reason": null
            }
        """.trimIndent()

        val result = useCase(raw)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("Unknown completion outcome")
    }

    @Test
    fun `rejects missing summary`() {
        val raw = """
            {
              "outcome": "FAILED",
              "blocking_reason": "Tool budget exhausted"
            }
        """.trimIndent()

        val result = useCase(raw)

        assertThat(result.isFailure).isTrue()
    }
}
