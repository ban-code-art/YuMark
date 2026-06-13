package com.yumark.app

import android.app.Application
import com.yumark.app.domain.repository.WorkspaceRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class YuMarkApplication : Application() {

    @Inject
    lateinit var workspaceRepository: WorkspaceRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 默认目录恢复提前到进程启动（而非等首页 ViewModel 创建）：
        // 目录扫描与首帧渲染并行，进入 App 时文件树通常已就绪
        appScope.launch { workspaceRepository.restoreOnLaunch() }
    }
}
