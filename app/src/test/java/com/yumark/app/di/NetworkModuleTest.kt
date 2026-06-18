package com.yumark.app.di

import com.google.common.truth.Truth.assertThat
import io.ktor.client.plugins.HttpTimeout
import org.junit.jupiter.api.Test

class NetworkModuleTest {

    @Test
    fun `AI request timeout uses Ktor infinite marker instead of zero`() {
        assertThat(AiHttpTimeouts.REQUEST_TIMEOUT_MILLIS)
            .isEqualTo(HttpTimeout.INFINITE_TIMEOUT_MS)
        assertThat(AiHttpTimeouts.REQUEST_TIMEOUT_MILLIS)
            .isNotEqualTo(0L)
    }
}
