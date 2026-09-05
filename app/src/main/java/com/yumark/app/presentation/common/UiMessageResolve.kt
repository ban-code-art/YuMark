package com.yumark.app.presentation.common

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.yumark.app.core.util.UiMessage

/**
 * [UiMessage] → String 的解析。**唯一**该把 UiMessage 变成字符串的地方。
 *
 * 类型本体在 `core.util`（那三层要产出它却拿不到 Context），解析留在这里：只有界面层
 * 有 Compose 与 `Resources`。
 *
 * 用 `LocalResources.current` 而不是 `stringResource(...)`：递归解析嵌套实参需要一个**普通函数**
 * （见 [resolve] 的私有重载），而 `stringResource` 是 @Composable，进不了普通函数体。
 * 也不能用 `LocalContext.current.getString(...)`——lint 的 `LocalContextGetResourceValueCall`
 * 是 error 级，且那条路径在系统语言切换后拿到的是旧配置的文案。
 */
@Composable
fun UiMessage.resolve(): String = LocalResources.current.resolve(this)

/** 可空版：状态流里的「当前无错误」用 null 表示，调用点因此不必先做判空再解析。 */
@Composable
fun UiMessage?.resolveOrNull(): String? = this?.resolve()

/**
 * 非组合期解析：给**回调里才拿到文案**的场景用（WebViewClient 的回调、Intent 失败分支……）。
 *
 * 那些地方进不了组合作用域，[resolve] 的 @Composable 版本用不了；调用点在组合期先
 * `val resources = LocalResources.current` 拿住实例，回调里再调这个函数。
 * 之所以不让调用点自己 `getString`：lint 的 `LocalContextGetResourceValueCall` 是 error 级，
 * 且嵌套实参的递归解析只存在于这里。
 */
fun Resources.resolveMessage(message: UiMessage): String = resolve(message)

/**
 * 递归解析。实参里嵌套的 [UiMessage] 先各自解析成 String 再填进外层占位符，
 * 「%1$s失败：%2$s」这类两段式文案的产出侧因此不需要提前拼字符串（也就不需要 Context）。
 */
private fun Resources.resolve(message: UiMessage): String = when (message) {
    is UiMessage.Raw -> message.text
    is UiMessage.Res ->
        if (message.args.isEmpty()) getString(message.id)
        else getString(message.id, *flattenArgs(message.args))
    is UiMessage.Plural -> {
        // args 留空时以 count 作为唯一实参：plurals 的文案几乎都是「每 %1$d 秒」这种单参数形态
        val args = if (message.args.isEmpty()) arrayOf<Any>(message.count)
        else flattenArgs(message.args)
        getQuantityString(message.id, message.count, *args)
    }
}

private fun Resources.flattenArgs(args: List<Any>): Array<Any> =
    Array(args.size) { i -> args[i].let { if (it is UiMessage) resolve(it) else it } }
