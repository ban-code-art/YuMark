package com.yumark.app.di

import com.yumark.app.core.coroutines.AppScope
import com.yumark.app.core.crash.CrashReporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppScopeModule {

    /**
     * 应用级协程作用域：不随任何 ViewModel / Activity 销毁而取消。
     *
     * 三个组成部分都不是可选的：
     * - [SupervisorJob]：一个任务失败不能连坐取消整个作用域，否则第一次退出保存出错之后，
     *   后面所有的退出保存都静默失效——而这条路径本来就是「最后一次机会」；
     * - [Dispatchers.IO]：挂上来的都是写盘 / 数据库任务；
     * - [CoroutineExceptionHandler]：`launch` 里逸出的异常默认交给线程的
     *   UncaughtExceptionHandler，也就是**直接把进程杀掉**。收尾任务失败记一笔非致命就够了，
     *   不该让用户看到崩溃弹窗。
     *
     * `YuMarkApplication` 里另有一个同构的私有 appScope（启动期恢复默认目录用），
     * 但它没有通过 Hilt 暴露，注入不到。两者应当合并成这一个（改 Application 即可），
     * 在那之前进程里会存在两个应用级作用域——它们互不干扰，只是重复。
     */
    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(crashReporter: CrashReporter): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            crashReporter.recordNonFatal(throwable, note = "应用级后台任务")
        }
    )
}
