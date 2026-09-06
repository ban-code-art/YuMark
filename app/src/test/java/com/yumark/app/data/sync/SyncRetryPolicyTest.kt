package com.yumark.app.data.sync

import androidx.work.ListenableWorker
import com.google.common.truth.Truth.assertThat
import com.yumark.app.core.util.FriendlyValidationException
import com.yumark.app.core.util.UiMessage
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * [SyncWorker.SyncRetryPolicy] 的纯逻辑测试：一次同步结果 + 已重试次数 → WorkManager 决定。
 * 边界都在这里钉死：确定性失败不该烧重试预算，重试超限要让位给下个周期。
 */
class SyncRetryPolicyTest {

    private val disabled = FriendlyValidationException(UiMessage.Raw("sync disabled"))

    @Test
    fun `成功直接返回 success`() {
        val outcome = SyncWorker.SyncRetryPolicy.next(kotlin.Result.success(Unit), attempt = 0)
        assertThat(outcome).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `同步未启用不算失败 也不重试`() {
        val outcome = SyncWorker.SyncRetryPolicy.next(
            kotlin.Result.failure<Unit>(disabled), attempt = 0
        )
        // 周期任务常驻：用户没开同步时每次都 retry 只会白白消耗 WorkManager 的重试预算
        assertThat(outcome).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `瞬时失败在预算内 retry`() {
        val outcome = SyncWorker.SyncRetryPolicy.next(
            kotlin.Result.failure<Unit>(IOException("timeout")), attempt = 2
        )
        assertThat(outcome).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `重试超限转为 failure 等下个周期`() {
        val outcome = SyncWorker.SyncRetryPolicy.next(
            kotlin.Result.failure<Unit>(IOException("timeout")),
            attempt = SyncWorker.SyncRetryPolicy.MAX_RETRIES
        )
        assertThat(outcome).isEqualTo(ListenableWorker.Result.failure())
    }
}
