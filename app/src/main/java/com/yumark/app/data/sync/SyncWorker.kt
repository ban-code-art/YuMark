package com.yumark.app.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yumark.app.core.util.FriendlyValidationException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * 后台定时同步：每 [SyncWorkScheduler.INTERVAL_HOURS] 小时在联网条件下跑一次 [doSync]。
 *
 * 依赖注入走 [SyncWorkerEntryPoint]（`EntryPointAccessors`）而不是 `@HiltWorker`：
 * 后者要求 Application 实现 `Configuration.Provider`、自定义 `WorkerFactory` 并移除
 * manifest 里的默认 initializer——为一个低频 Worker 拆掉 WorkManager 的自动启动链，
 * 换来的只是注入写法的体面，风险收益不成比例。EntryPoint 是 Google 为手动 DI 场景
 * 提供的官方出路，Hilt 的容器照常构建，worker 内部自行取依赖。
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncWorkerEntryPoint {
        fun syncRepository(): com.yumark.app.domain.repository.SyncRepository
    }

    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(
            applicationContext, SyncWorkerEntryPoint::class.java
        ).syncRepository()
        return doSync(repository)
    }

    /** 与 Android API 分离便于单测：输入一次同步结果与已重试次数，输出 WorkManager 的决定。 */
    internal suspend fun doSync(
        repository: com.yumark.app.domain.repository.SyncRepository
    ): Result = SyncRetryPolicy.next(runCatching { repository.syncNow() }, runAttemptCount)

    /**
     * 结果 → WorkManager 决定的映射（纯逻辑，JVM 可测）：
     * - 成功 → success；
     * - 配置未启用/不完整这类确定性失败 → success（别为「用户没开同步」浪费重试预算；
     *   周期任务本来就常驻，等用户下次保存配置时调度器会把任务换掉）；
     * - 其余（网络抖动、5xx）→ 剩余预算内 retry，超限 failure（下个周期照常再来）。
     */
    internal object SyncRetryPolicy {
        fun next(result: kotlin.Result<*>, attempt: Int): Result {
            val exception = result.exceptionOrNull()
                ?: return Result.success()
            return when {
                exception is CancellationException -> throw exception
                exception is FriendlyValidationException -> Result.success()
                attempt < MAX_RETRIES -> Result.retry()
                else -> Result.failure()
            }
        }

        const val MAX_RETRIES = 3
    }
}

/**
 * 周期同步的注册/注销。用 `KEEP` 策略：已有的周期任务不因每次启动重新排期而被顺延。
 *
 * 触发点：Application 启动（覆盖「开机后/改完设置后启动」两条路径）与
 * `SyncRepositoryImpl.saveConfig`（覆盖「用户在设置页开关同步」）。
 * 配置导入（ConfigBackupCodec）改了 enabled 的场景留到下次启动收编——无损，只是慢一轮。
 */
object SyncWorkScheduler {
    private const val UNIQUE_NAME = "yumark_periodic_sync"
    const val INTERVAL_HOURS = 6L

    fun updateSchedule(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) {
            manager.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            INTERVAL_HOURS, TimeUnit.HOURS, 1, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        manager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
