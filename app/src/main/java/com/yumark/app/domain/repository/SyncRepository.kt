package com.yumark.app.domain.repository

import com.yumark.app.domain.model.SyncOutcome
import com.yumark.app.domain.model.WebDavConfig
import kotlinx.coroutines.flow.Flow

/**
 * WebDAV 云端同步仓库。
 *
 * P1：手动触发的双向同步——把本地 Markdown 库的根级文档正文与远端某 WebDAV 目录对齐。
 * 文件夹层级、图片附件、删除传播、后台定时同步留作后续阶段。
 */
interface SyncRepository {
    /** 观察当前 WebDAV 配置。 */
    fun observeConfig(): Flow<WebDavConfig>

    /** 保存配置（password 加密落盘）。 */
    suspend fun saveConfig(config: WebDavConfig)

    /** 观察上次成功同步时间（epoch 毫秒，从未同步为 null）。 */
    fun observeLastSyncedAt(): Flow<Long?>

    /** 用给定配置测试连接（不落盘），便于保存前验证。 */
    suspend fun testConnection(config: WebDavConfig): Result<Unit>

    /** 用已保存配置执行一次双向同步，返回结果计数。 */
    suspend fun syncNow(): Result<SyncOutcome>
}
