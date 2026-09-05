package com.yumark.app.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserFacingMessage
import com.yumark.app.data.local.prefs.WorkspaceDataStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [WorkspaceRepositoryImpl] 里唯一能在 JVM 单测里跑的分支：`rescan()` 在没有打开工作区时的
 * 早退。其余路径都要真的 SAF / DocumentFile / ContentResolver，留给设备侧。
 *
 * 值得单独钉一条：这五处从前写的是 `error("当前没有打开任何文件夹")`，抛裸
 * IllegalStateException，被 ErrorHandler.classify 的 else 分支归成「出现未知问题，请重试」——
 * 手写的那句话一个字都到不了界面上，而它恰好指向一件用户自己能做的事（先去选个文件夹）。
 */
class WorkspaceRepositoryRescanTest {

    private fun repo() = WorkspaceRepositoryImpl(
        context = mockk<Context>(),
        workspaceDataStore = mockk<WorkspaceDataStore>()
    )

    @Test
    fun `没有打开工作区时 rescan 失败且带资源化文案`() = runTest {
        val error = repo().rescan().exceptionOrNull()

        assertThat(error).isInstanceOf(UserFacingMessage::class.java)
        assertThat((error as UserFacingMessage).uiMessage)
            .isEqualTo(UiMessage.Res(R.string.workspace_error_none_open))
    }

    @Test
    fun `没有打开工作区时 rescan 不碰 Context 也不碰 DataStore`() = runTest {
        // 两个依赖都是非 relaxed 的 mock：真去调它们的任何方法都会因「no answer found」
        // 直接失败。这条把「早退发生在任何 IO 之前」钉住——从前的实现会先去 toUri()。
        assertThat(repo().rescan().isFailure).isTrue()
    }
}
