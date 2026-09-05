package com.yumark.app.core.coroutines

import javax.inject.Qualifier

/**
 * 标记「与进程同寿」的应用级 CoroutineScope。
 *
 * 存在的理由：ViewModel 被销毁时 `viewModelScope` **已经**被取消，`onCleared()` 里用它启动的
 * 协程会立刻死掉、一个字节都写不出去——未落盘的编辑内容就此永久丢失。跨组件生命周期
 * 必须做完的收尾（退出时保存、数据库预填充）只能挂在这个作用域上。
 *
 * 注意：它**永不取消**。因此只放「必须做完的短任务」；把长期订阅挂上来等于泄漏。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope
