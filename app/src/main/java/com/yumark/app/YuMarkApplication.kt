package com.yumark.app

import android.app.Application
import com.yumark.app.core.coroutines.AppScope
import com.yumark.app.core.crash.CrashReporter
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.data.sync.SyncWorkScheduler
import com.yumark.app.domain.repository.SyncRepository
import com.yumark.app.domain.repository.WorkspaceRepository
import com.yumark.app.domain.usecase.ai.agent.ReconcileInterruptedAgentRunsUseCase
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class YuMarkApplication : Application() {

    @Inject
    lateinit var crashReporter: CrashReporter

    @Inject
    lateinit var workspaceRepository: WorkspaceRepository

    @Inject
    lateinit var syncRepository: SyncRepository

    @Inject
    lateinit var imageRepository: com.yumark.app.domain.repository.ImageRepository

    @Inject
    lateinit var reconcileInterruptedAgentRuns: ReconcileInterruptedAgentRunsUseCase

    /**
     * 启动期后台任务的作用域，与进程同寿。
     *
     * 由 `di/AppScopeModule` 提供而不在这里自己 new：`EditorViewModel.onCleared()` 也需要一个
     * 不随组件销毁而取消的作用域来做退出前保存，两处若各建一个，进程里就有两个同构却互不知情
     * 的应用级作用域——异常处理策略会各写一份并慢慢漂移。
     *
     * 限定符不加 `@field:` 前缀：Dagger 的 KSP 处理器会连属性一起读，加了前缀功能上同样能用，
     * 但 Dagger 自带的 lint 检查（FieldSiteTargetOnQualifierAnnotation）会报「Redundant
     * 'field:'」。构造参数注入（见 `EditorViewModel` 的 `appScope` 参数）同样直接写限定符。
     */
    @Inject
    @AppScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        // 第一件事就装崩溃处理器：装之前的崩溃抓不到，而后面这句 restoreOnLaunch 恰恰是
        // 启动期最容易出问题的一段（扫用户选定目录，路径失效/权限丢失都在这儿暴露）。
        // @Inject 字段在 super.onCreate() 里由 Hilt 注入完成，此处可直接用。
        crashReporter.install()

        // 界面层捕获的失败（ErrorHandler.report）也落进同一份崩溃日志。
        // ErrorHandler 是纯 Kotlin 单例，靠这里装上出口而不是注入 CrashReporter——
        // 否则每个用到它的 ViewModel 都要多一个构造参数。
        ErrorHandler.install { throwable, note -> crashReporter.recordNonFatal(throwable, note) }

        // 默认目录恢复提前到进程启动（而非等首页 ViewModel 创建）：
        // 目录扫描与首帧渲染并行，进入 App 时文件树通常已就绪
        appScope.launch { workspaceRepository.restoreOnLaunch() }

        // 上一次进程若死在 Agent 运行途中，落库的运行态没人清理（收尾代码在协程里，进程被杀时
        // 一行都不执行），重启后对话会永远显示「正在工作」且用户无从取消。放在启动期做一次，
        // 早于任何 ViewModel 订阅这两张表，用户不会先看到一帧残留状态。
        appScope.launch {
            reconcileInterruptedAgentRuns()
                .onFailure { ErrorHandler.report(it) }  // 失败只记一笔：清不掉残留不该拦住启动
        }

        // 后台定时同步的注册/注销：读一次 WebDAV 配置，开了就注册周期任务（KEEP，
        // 不打断已有排期），关了就注销。任务本身在进程死亡后由 WorkManager 持久化，
        // 每次启动都到这里对一次表，保证「设置页开关 + 配置导入」改完 enabled 后
        // 最迟下一次启动生效。
        appScope.launch {
            runCatching {
                val enabled = syncRepository.observeConfig().first().enabled
                SyncWorkScheduler.updateSchedule(this@YuMarkApplication, enabled)
            }.onFailure { ErrorHandler.report(it) }
        }

        // 启动静默增量同步：开启同步的用户打开应用即拉平两端，不用等 6 小时的周期任务，
        // 也不用在同步设置页手动点。同步本体是哈希比对的增量（未变化的文档零传输），
        // 静默失败只吞不掉启动流程——任何失败都被 runCatching 收下，不打扰用户。
        appScope.launch {
            runCatching { syncRepository.syncNow() }
        }

        // 孤儿图片清理（images 行的 document 已不存在的残留行 + 文件）：
        // 起因是「文件已落盘、库行未插入」一类崩溃窗口。此前 cleanOrphanedImages
        // 是零调用点的死代码；挂在启动期后台跑一次，幂等且廉价（一个 LEFT JOIN）。
        appScope.launch {
            runCatching { imageRepository.cleanOrphanedImages() }
                .onFailure { ErrorHandler.report(it) }
        }
    }
}
