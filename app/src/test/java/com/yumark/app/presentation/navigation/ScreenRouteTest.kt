package com.yumark.app.presentation.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 路由参数编码的回归锁。
 *
 * Navigation 取参数走的是百分号解码（`Uri.decode`），而 `java.net.URLEncoder` 按
 * form-urlencoded 把空格编成 `+` —— 两侧不配对时，含裸空格的外部 URI 会静默变成
 * `my+note.md`，编辑器报「文件不存在」，而编码侧和解码侧单看都没错。
 *
 * 这里不调 `Uri.decode` 验往返：单测环境的 android.jar 是桩，静态方法返回 null
 * （`app/build.gradle.kts` 的 `unitTests.isReturnDefaultValues = true`），
 * 所以直接锁编码结果的字面量。
 */
class ScreenRouteTest {

    @Test
    fun `空格编成百分号形式而不是加号`() {
        val route = Screen.Editor.createExternalRoute("file:///sdcard/my note.md")
        assertThat(route).isEqualTo("editor?docUri=file%3A%2F%2F%2Fsdcard%2Fmy%20note.md")
        assertThat(route).doesNotContain("+")
    }

    @Test
    fun `值里本来的加号编码后不受空格替换影响`() {
        // URLEncoder 先把字面 + 编成 %2B，此时串里已没有 + 可被替换；只有空格留下的那个 + 会变
        assertThat(Screen.Editor.createExternalRoute("a+b c"))
            .isEqualTo("editor?docUri=a%2Bb%20c")
    }

    @Test
    fun `已经百分号编码的 content URI 会再编一层`() {
        // SAF 给出的 content URI 自身就是百分号编码的。少编这一层，Navigation 解码一次后
        // documentId 里的 %3A 会被还原成 :，路径直接对不上——所以 %25 是对的。
        val route = Screen.Editor.createExternalRoute(
            "content://com.android.externalstorage.documents/document/primary%3ADocs%2Fa.md"
        )
        assertThat(route).contains("primary%253ADocs%252Fa.md")
    }

    @Test
    fun `与号和等号被编码 不会伪造出第二个参数`() {
        // 文件名里带 &docUri= 时若不编码，Navigation 会解析出两个同名参数，取到的是哪个不确定
        val route = Screen.Editor.createExternalRoute("file:///a&docUri=evil")
        assertThat(route).isEqualTo("editor?docUri=file%3A%2F%2F%2Fa%26docUri%3Devil")
        assertThat(route.split("docUri=").size - 1).isEqualTo(1)
    }

    @Test
    fun `createRoute 只带 documentId`() {
        val route = Screen.Editor.createRoute("abc-123")
        assertThat(route).isEqualTo("editor?documentId=abc-123")
        assertThat(route).doesNotContain("docUri")
    }

    @Test
    fun `中文按 UTF-8 百分号编码`() {
        assertThat(Screen.Editor.createRoute("笔记"))
            .isEqualTo("editor?documentId=%E7%AC%94%E8%AE%B0")
    }

    /**
     * 同一个 URI 的第二次外部打开必须是一个「新值」。
     *
     * `MainActivity` 把请求放在 `mutableStateOf` 里，Compose 用结构相等判定变更。若把
     * [ExternalOpenRequest] 退回成裸 String（或把 seq 从 equals 里去掉），第二次从文件管理器
     * 打开同一个文件时写回去的值与旧值相等，不重组、`LaunchedEffect` 不重跑，用户看到的是
     * 「点了没反应」，而日志里一切正常。
     */
    @Test
    fun `同一个 URI 的两次请求不相等`() {
        val first = ExternalOpenRequest("content://x/1", 1)
        assertThat(ExternalOpenRequest("content://x/1", 2)).isNotEqualTo(first)
        assertThat(ExternalOpenRequest("content://x/1", 1)).isEqualTo(first)
    }
}
