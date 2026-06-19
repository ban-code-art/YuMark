package com.yumark.app.domain.usecase.sync

import com.yumark.app.domain.model.SyncOutcome
import com.yumark.app.domain.model.WebDavConfig
import com.yumark.app.domain.repository.SyncRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** 观察 WebDAV 同步配置。 */
class GetWebDavConfigUseCase @Inject constructor(
    private val repository: SyncRepository
) {
    operator fun invoke(): Flow<WebDavConfig> = repository.observeConfig()
}

/** 保存 WebDAV 同步配置。 */
class SaveWebDavConfigUseCase @Inject constructor(
    private val repository: SyncRepository
) {
    suspend operator fun invoke(config: WebDavConfig) = repository.saveConfig(config)
}

/** 测试 WebDAV 连接（不落盘）。 */
class TestWebDavConnectionUseCase @Inject constructor(
    private val repository: SyncRepository
) {
    suspend operator fun invoke(config: WebDavConfig): Result<Unit> =
        repository.testConnection(config)
}

/** 执行一次双向同步。 */
class SyncNowUseCase @Inject constructor(
    private val repository: SyncRepository
) {
    suspend operator fun invoke(): Result<SyncOutcome> = repository.syncNow()
}

/** 观察上次成功同步时间（epoch 毫秒）。 */
class ObserveLastSyncedAtUseCase @Inject constructor(
    private val repository: SyncRepository
) {
    operator fun invoke(): Flow<Long?> = repository.observeLastSyncedAt()
}
