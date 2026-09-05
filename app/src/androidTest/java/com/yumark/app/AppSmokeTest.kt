package com.yumark.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * androidTest 流水线自检。
 *
 * 存在的理由不是「测业务」，而是把「instrumented 测试这条链路本身通了」变成一个会红的信号。
 * 这条链路有一串只在设备上才暴露的失败点：测试 APK 没装上、AndroidJUnitRunner 没被
 * manifest 采纳、dex 里方法名非法、被测应用装不上……全都表现为「一个用例都没跑」，
 * 而 CI 上「零用例」和「全部通过」的退出码都是 0。所以必须有一个必然存在的用例占位，
 * 否则 connectedDebugAndroidTest 会在什么都没跑的情况下报绿。
 *
 * 刻意不引入 Hilt 测试运行器（HiltTestApplication + 自定义 Runner）：那会让这个自检用例
 * 本身依赖 Hilt 图能否构建，把「链路通不通」和「依赖注入对不对」两件事耦在一起，
 * 自检就失去意义了。这里只用 targetContext，不启动 Application，
 * 不触发 [YuMarkApplication] 上 @HiltAndroidApp 的初始化。
 *
 * 方法名不带空格是硬约束：dex 的 SimpleName 只有 040 及以上版本允许空格字符，
 * 而 D8 按 minSdk 决定 dex 版本（本项目 minSdk 26 → 无法用 040），带空格的反引号方法名
 * 会让 assembleDebugAndroidTest 直接失败：
 * "Space characters in SimpleName '…' are not allowed prior to DEX version 040"。
 * 汉字落在 U+2030..U+D7FF，所有 dex 版本都允许，所以中文名可以用、空格不行。
 * （app/src/test 下的用例只在 JVM 跑、不过 dex，那边方法名带空格没事，别照抄过来。）
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @Test
    fun `被测应用的包名是预期的applicationId`() {
        val targetPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName

        // 两个值都接受：debug 变体带 applicationIdSuffix = ".debug"，
        // connectedDebugAndroidTest 装上去的就是 com.yumark.app.debug；
        // 而不带 suffix 的变体是 com.yumark.app。
        //
        // 不用 BuildConfig.APPLICATION_ID 做断言：在 androidTest 源集里它会解析到
        // **测试 APK 自己**的 BuildConfig（com.yumark.app.debug.test），
        // 断言就变成自己跟自己比，永远成立，测不出任何东西。
        assertThat(targetPackage).isIn(listOf("com.yumark.app", "com.yumark.app.debug"))
    }

    @Test
    fun `测试APK与被测应用是两个不同的包`() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // context 是测试 APK 自己，targetContext 是被测应用，两者必须是不同的包。
        // 相等意味着 instrumentation 的 targetPackage 指错了地方，此时
        // MigrationTestHelper 读的 assets、以及数据库文件落地的目录都会是错的 ——
        // 那类失败的表象是「schema 文件找不到」，根因却在这里，值得单独钉一条。
        val testPackage = instrumentation.context.packageName
        val targetPackage = instrumentation.targetContext.packageName

        assertThat(testPackage).isNotEqualTo(targetPackage)
        // AGP 默认把 androidTest 的 applicationId 定为「被测 applicationId + .test」
        assertThat(testPackage).endsWith(".test")
    }
}
