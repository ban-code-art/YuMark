import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起 Kotlin 支持内置（built-in Kotlin），不再需要 org.jetbrains.kotlin.android。
    // kapt 与 built-in Kotlin 不兼容（应用即失败），注解处理一律走 KSP。
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.yumark.app"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "com.yumark.app"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = 21
        versionName = "0.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名配置从根目录 keystore.properties 读取（该文件不入库，见 .gitignore）。
    // 绝不在构建脚本中硬编码 keystore 密码。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystoreProps.load(keystorePropsFile.inputStream())
    }
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile", "../release.keystore"))
            storePassword = keystoreProps.getProperty("storePassword", "")
            keyAlias = keystoreProps.getProperty("keyAlias", "")
            keyPassword = keystoreProps.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
        // 没这一行的话，JVM 单测里凡是碰到 android.jar 的类（`android.util.Log.d` 首当其冲）
        // 都会抛 "Method d in android.util.Log not mocked"，本项目又不用 Robolectric，
        // 结果就是任何带一行调试日志的 ViewModel 都测不了。返回默认值即可。
        unitTests.isReturnDefaultValues = true
    }

    // Room 的 MigrationTestHelper 从**测试 APK 的 assets** 里按
    // `<AppDatabase 的 canonicalName>/<版本>.json` 找 schema，找不到就抛
    // "Cannot find the schema file in the assets folder"，而不是跳过校验。
    //
    // 这件事**不需要**在这里手写 sourceSets：只要配了下面的 room { schemaDirectory(...) }，
    // Room 的 Gradle 插件就会注册 copyRoomSchemasToAndroidTestAssets<Variant> 任务，
    // 把 schema 复制到 build/generated/assets/… 并登记为 androidTest 的 assets 来源，
    // 最终进 mergeDebugAndroidTestAssets 的输出（已验证：11 个 json 全部就位）。
    //
    // 曾经这里有一行 `sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")`。
    // 删掉它有三个理由：
    //   1. 与插件任务重复，同一批相对路径由两个来源提供，是 assets 合并冲突的隐患；
    //   2. srcDir(Any) 在 AGP 9 已弃用，留着就是一条永久编译警告；
    //   3. 手写那行没有任务依赖，读到的是磁盘上入库的旧文件；插件任务在任务图里，
    //      同一次构建里改了实体也能拿到刚导出的 schema。

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// AGP 9 移除了 android.kotlinOptions，编译器选项统一走 Kotlin 插件的 compilerOptions。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            // KT-73255：构造参数上的注解目前只落在 value parameter 上，未来会同时落到 field/property。
            // 提前对齐未来默认值，使语义在编译器切换默认行为前后保持一致；
            // 本项目该位置的注解只有 Hilt 限定符 @ApplicationContext，额外落到私有字段上无副作用。
            "-Xannotation-default-target=param-property"
        )
    }
}

dependencies {
    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.window)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Security (encrypted storage for AI API keys)
    implementation(libs.security.crypto)

    // Coil
    implementation(libs.coil.compose)

    // Commonmark (Markdown parser for HTML export)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.task.list.items)

    // Ktor (HTTP client for update checking)
    implementation(libs.ktor.client.android)
    // CIO 引擎用于 WebDAV：Android 引擎基于 HttpURLConnection，不支持 PROPFIND/MKCOL 等自定义方法
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Instrumented Testing（androidTest：编译进独立的测试 APK，跑在真机/模拟器上）
    //
    // 引擎是 JUnit4，不是 JUnit5。testInstrumentationRunner 已在 defaultConfig 里设为
    // AndroidJUnitRunner，而它是个 JUnit4 Runner —— 用例必须 @RunWith(AndroidJUnit4::class)
    // 并 import org.junit.Test。上面 unit test 那套 junit-bom/jupiter 不会流到这里：
    // androidTestImplementation 继承 implementation，但**不继承** testImplementation，
    // 这正是想要的隔离，别去加 configuration 继承关系「打通」它。
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // 断言库与 unit test 保持一致，避免一个仓库里两套断言 DSL
    androidTestImplementation(libs.truth)
    // MigrationTestHelper：唯一能在真实 SQLite 上执行迁移、并复用 Room 自己的
    // schema 校验逻辑的工具。纯 JVM 测试只能比对建表语句字符串，证明不了
    // SQLite 接受这条语句（FTS4 写错是运行期 vtable constructor failed）。
    androidTestImplementation(libs.room.testing)
    // Compose UI 测试依赖**刻意没有**在这里声明：目前没有任何 Compose UI 用例，
    // 而 ui-test-manifest 会往 debug 变体的 manifest 里注入 Activity、ui-test-junit4
    // 会把 espresso 拉进测试 APK —— 为零个用例引入这些变更不划算。
    // 依赖坐标已在 gradle/libs.versions.toml 里备好，写第一个 Compose UI 用例时补这三行：
    //     androidTestImplementation(platform(libs.compose.bom))
    //     androidTestImplementation(libs.compose.ui.test.junit4)
    //     debugImplementation(libs.compose.ui.test.manifest)
}

// Room schema 导出：kapt 时代走 defaultConfig.javaCompileOptions.annotationProcessorOptions，
// Room 2.7+ 走官方 Gradle 插件的 room {}（schema 目录被登记为任务 input/output，
// 优于 ksp.arg("room.schemaLocation") —— 后者对 Gradle 而言是不透明字符串，增量构建判定不准）。
// schemas/ 入库是写迁移测试的前提。
room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.incremental", "true")
}