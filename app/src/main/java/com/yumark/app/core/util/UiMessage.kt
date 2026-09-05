package com.yumark.app.core.util

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * 延迟解析的用户可见文案。
 *
 * 存在的理由：`core` / `data` / `domain` 三层都要产出给用户看的句子（错误原因、WebView 加载
 * 失败、AI 请求失败），但这三层**拿不到也不该拿 Context**。把资源 id 与实参装进这个类型，
 * 一路传到 Composable 侧再解析，界面代码不用感知文案来自哪一层。
 *
 * 为什么放在 `core.util` 而不是 `presentation.common`：[ErrorHandler]、[AiErrorMapper]、
 * `core.webview.WebViewFailure` 的返回类型都是它。若它住在 presentation，`core` 就得反向依赖
 * presentation——分层箭头指反了，且 `core` 的 JVM 单元测试会被拖进 Compose 依赖。
 * 解析函数 `UiMessage.resolve()` 留在 `presentation.common`，那边才需要 Compose。
 *
 * 只有 [androidx.annotation] 的注解依赖（纯 Java 注解 jar，不含 Android 运行时），
 * 所以持有本类型的 `core` 代码依然能在 JVM 单元测试里跑。
 */
sealed interface UiMessage {

    /**
     * `strings.xml` 里的一条文案。
     *
     * @param args 传给 `getString(id, *args)` 的占位符实参，顺序即 %1、%2…。
     *   元素本身可以是 [UiMessage]——解析时会先递归解析成 String 再填进去，
     *   「%1$s失败：%2$s」这类两段式文案因此不需要在产出侧提前拼字符串。
     */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiMessage

    /**
     * `<plurals>` 里的一条文案。
     *
     * @param count 选分支用的数量。
     * @param args 占位符实参；**留空时自动以 [count] 作为唯一实参**（对应最常见的 `%1$d` 写法），
     *   需要「3 个文件夹 / 12 个文档」这类多参数时才显式传。
     */
    data class Plural(
        @PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = emptyList()
    ) : UiMessage

    /**
     * 已成句的字符串，原样透出。
     *
     * 留着它不是妥协：抛出方在抛出那一刻就写好了文案的场合（[FriendlyIOException] 携带的
     * WebDAV 报错、[FriendlyValidationException] 的校验说明、模型返回的原文）确实只有字符串。
     * 这类文案不可翻译，见 backlog。
     */
    data class Raw(val text: String) : UiMessage

    companion object {
        /** `UiMessage.of(id, a, b)` 比 `UiMessage.Res(id, listOf(a, b))` 短一半。 */
        fun of(@StringRes id: Int, vararg args: Any): Res = Res(id, args.toList())

        /** 同上，plurals 版。 */
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): Plural =
            Plural(id, count, args.toList())
    }
}
