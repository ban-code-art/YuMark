package com.yumark.app.di

import android.content.Context
import com.yumark.app.data.remote.UpdateChecker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton

/** 标记用于网络搜索的短超时 HttpClient。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WebSearchClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideUpdateChecker(
        @ApplicationContext context: Context
    ): UpdateChecker {
        return UpdateChecker(context)
    }

    /** AI 适配器共享的 HTTP 客户端：长超时以支持流式响应。 */
    @Provides
    @Singleton
    fun provideAiHttpClient(): HttpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = AiHttpTimeouts.CONNECT_TIMEOUT_MILLIS
            // requestTimeout 覆盖整个请求含流式 body 读取；流式输出可能持续很久，
            // 用 Ktor 的无限超时标记，改靠 socketTimeout 探测死连接，避免慢流被中途切断。
            requestTimeoutMillis = AiHttpTimeouts.REQUEST_TIMEOUT_MILLIS
            socketTimeoutMillis = AiHttpTimeouts.SOCKET_TIMEOUT_MILLIS
        }
    }

    /** 网络搜索专用客户端：短超时，避免单次搜索阻塞 Agent 循环。 */
    @Provides
    @Singleton
    @WebSearchClient
    fun provideWebSearchHttpClient(): HttpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = AiHttpTimeouts.CONNECT_TIMEOUT_MILLIS
            requestTimeoutMillis = WebSearchTimeouts.REQUEST_TIMEOUT_MILLIS
            socketTimeoutMillis = WebSearchTimeouts.SOCKET_TIMEOUT_MILLIS
        }
    }
}

internal object AiHttpTimeouts {
    const val CONNECT_TIMEOUT_MILLIS: Long = 10_000
    val REQUEST_TIMEOUT_MILLIS: Long = HttpTimeout.INFINITE_TIMEOUT_MS
    const val SOCKET_TIMEOUT_MILLIS: Long = 120_000
}

internal object WebSearchTimeouts {
    const val REQUEST_TIMEOUT_MILLIS: Long = 15_000
    const val SOCKET_TIMEOUT_MILLIS: Long = 15_000
}
