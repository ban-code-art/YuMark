package com.yumark.app.presentation.theme

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AppThemesTest {

    @Test
    fun `byId 返回对应主题`() {
        assertThat(AppThemes.byId("claude").id).isEqualTo("claude")
    }

    @Test
    fun `byId 未知 id 回退默认主题`() {
        assertThat(AppThemes.byId("deleted-theme").id).isEqualTo(AppThemes.DEFAULT_ID)
    }

    @Test
    fun `byId null 回退默认主题`() {
        assertThat(AppThemes.byId(null).id).isEqualTo(AppThemes.DEFAULT_ID)
    }

    @Test
    fun `注册表按序包含灰白与 claude`() {
        assertThat(AppThemes.all.map { it.id }).containsExactly("default", "claude").inOrder()
    }
}
