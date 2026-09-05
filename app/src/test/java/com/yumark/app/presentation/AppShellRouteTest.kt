package com.yumark.app.presentation

import com.google.common.truth.Truth.assertThat
import com.yumark.app.presentation.navigation.Screen
import org.junit.jupiter.api.Test

/**
 * [editorRouteFor] 守的是「转屏之后还在同一篇文档里」。
 *
 * `AppShell` 按宽度类在单窗格与双窗格之间二选一，跨过 Expanded 边界（平板转屏、折叠屏展开/合拢）
 * 时整支分支连着 NavHost 一起离开组合，新分支从自己的 startDestination 从头开始。补回来的那一次
 * `navigate` 用的就是这个函数重建出的路由——它返回 null 或者编错一个字符，用户正在编辑的文档
 * 就在转屏后从屏幕上消失了。
 */
class AppShellRouteTest {

    @Test
    fun `internal document rebuilds a documentId route`() {
        assertThat(editorRouteFor("doc-1", null)).isEqualTo("editor?documentId=doc-1")
    }

    @Test
    fun `external document rebuilds a docUri route`() {
        assertThat(editorRouteFor(null, "file:///sdcard/a.md"))
            .isEqualTo("editor?docUri=file%3A%2F%2F%2Fsdcard%2Fa.md")
    }

    @Test
    fun `neither argument means the editor is not open`() {
        assertThat(editorRouteFor(null, null)).isNull()
    }

    /**
     * 两个都在时以内部文档为准。路由是字符串拼出来的，谁都可能拼出一条两个参数都带的；
     * 真正打开的那一篇是内部文档，按 docUri 走会去 SAF 读一个根本不存在的 URI，
     * 界面上是「文件不存在」。
     */
    @Test
    fun `documentId wins when both arguments are present`() {
        assertThat(editorRouteFor("doc-1", "file:///sdcard/a.md"))
            .isEqualTo("editor?documentId=doc-1")
    }

    /**
     * 裸空格必须编成 `%20` 而不是 `+`。
     *
     * Navigation 取参数走的是百分号解码，`+` 会被原样留下：`my note.md` 变成 `my+note.md`，
     * 转屏恢复后编辑器去读一个多了个加号的路径，表现为「转个屏就说文件不存在」。
     * 这一位是 `Screen.encodeRouteArg` 补的，这里从调用方这一侧把它钉住。
     */
    @Test
    fun `a bare space in an external uri becomes percent-20, never plus`() {
        val route = editorRouteFor(null, "file:///sdcard/my note.md")!!
        assertThat(route).contains("my%20note.md")
        assertThat(route).doesNotContain("+")
    }

    /**
     * 已经百分号编码过的值（SAF 给出的 content URI 都是）要再编一层，Navigation 解码一次
     * 正好还原。少编这一层才会丢字符。
     */
    @Test
    fun `an already percent-encoded uri is encoded once more`() {
        val route = editorRouteFor(null, "content://authority/doc/a%2Fb")!!
        assertThat(route).contains("a%252Fb")
    }

    /** CJK 文件名走 UTF-8 百分号编码，不能原样出现在路由里。 */
    @Test
    fun `cjk document ids are percent-encoded`() {
        val route = editorRouteFor("中文", null)!!
        assertThat(route).isEqualTo("editor?documentId=%E4%B8%AD%E6%96%87")
        assertThat(route).doesNotContain("中文")
    }

    /**
     * 重建出来的路由必须与 [Screen.Editor] 声明的路由模板同一个前缀，否则 `navigate` 直接抛
     * IllegalArgumentException（destination 找不到），转屏当场崩在恢复那一步。
     */
    @Test
    fun `rebuilt routes share the declared editor prefix`() {
        assertThat(Screen.Editor.route).startsWith("editor?")
        assertThat(editorRouteFor("d", null)).startsWith("editor?")
        assertThat(editorRouteFor(null, "u")).startsWith("editor?")
    }
}
