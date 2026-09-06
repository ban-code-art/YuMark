plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    // 静态分析门禁（detekt 默认规则集 + 基线）：
    //   ./gradlew detektBaseline 一次性把存量问题固化进 config/detekt-baseline.xml，
    //   之后 detekt 只拦**新增**违规——存量代码不必为过门禁而大规模翻改。
    //   CI（android.yml）与本地跑的是同一条任务。
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
