package com.yumark.app.data.sync

import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.sync.SyncPlanner.LocalDocInfo
import com.yumark.app.data.sync.SyncPlanner.RemoteFileInfo
import com.yumark.app.data.sync.SyncPlanner.SyncAction
import com.yumark.app.data.sync.SyncPlanner.SyncRecordInfo
import org.junit.jupiter.api.Test

class SyncPlannerTest {

    private fun local(id: String, name: String = "$id.md", hash: String) =
        LocalDocInfo(id = id, fileName = name, contentHash = hash)

    @Test
    fun `new local doc with no remote uploads`() {
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", hash = "h1")),
            remotes = emptyList(),
            records = emptyList()
        )
        assertThat(plan).containsExactly(SyncAction.Upload("d1", "d1.md", null))
    }

    @Test
    fun `remote-only file creates local`() {
        val plan = SyncPlanner.plan(
            locals = emptyList(),
            remotes = listOf(RemoteFileInfo("Remote.md", "e1")),
            records = emptyList()
        )
        assertThat(plan).containsExactly(SyncAction.CreateLocal("Remote.md", "e1"))
    }

    @Test
    fun `local change uploads`() {
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", hash = "h2")),
            remotes = listOf(RemoteFileInfo("d1.md", "e0")),
            records = listOf(SyncRecordInfo("d1", "d1.md", remoteEtag = "e0", localHash = "h1"))
        )
        assertThat(plan).containsExactly(SyncAction.Upload("d1", "d1.md", null))
    }

    @Test
    fun `remote change downloads`() {
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", hash = "h1")),
            remotes = listOf(RemoteFileInfo("d1.md", "e1")),
            records = listOf(SyncRecordInfo("d1", "d1.md", remoteEtag = "e0", localHash = "h1"))
        )
        assertThat(plan).containsExactly(SyncAction.DownloadOverwrite("d1", "d1.md", "e1"))
    }

    @Test
    fun `both changed produces conflict`() {
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", hash = "h2")),
            remotes = listOf(RemoteFileInfo("d1.md", "e1")),
            records = listOf(SyncRecordInfo("d1", "d1.md", remoteEtag = "e0", localHash = "h1"))
        )
        assertThat(plan).containsExactly(SyncAction.Conflict("d1", "d1.md", "e1"))
    }

    @Test
    fun `no change skips`() {
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", hash = "h1")),
            remotes = listOf(RemoteFileInfo("d1.md", "e0")),
            records = listOf(SyncRecordInfo("d1", "d1.md", remoteEtag = "e0", localHash = "h1"))
        )
        assertThat(plan).containsExactly(SyncAction.Skip("d1", "d1.md", "e0", "h1"))
    }

    @Test
    fun `rename uploads new name and deletes old remote, no spurious create`() {
        // 本地文档改名：当前文件名 new.md，记录的远端路径仍是 old.md，远端只有 old.md
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", name = "new.md", hash = "h1")),
            remotes = listOf(RemoteFileInfo("old.md", "e0")),
            records = listOf(SyncRecordInfo("d1", "old.md", remoteEtag = "e0", localHash = "h1"))
        )
        assertThat(plan).containsExactly(SyncAction.Upload("d1", "new.md", "old.md"))
    }

    @Test
    fun `first sync name collision with unknown remote is a conflict`() {
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", name = "Shared.md", hash = "h1")),
            remotes = listOf(RemoteFileInfo("Shared.md", "e9")),
            records = emptyList()
        )
        assertThat(plan).containsExactly(SyncAction.Conflict("d1", "Shared.md", "e9"))
    }

    @Test
    fun `remote vanished re-uploads local`() {
        // 记录的远端路径就是当前文件名，但远端已不存在 → 推回本地
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", hash = "h1")),
            remotes = emptyList(),
            records = listOf(SyncRecordInfo("d1", "d1.md", remoteEtag = "e0", localHash = "h1"))
        )
        assertThat(plan).containsExactly(SyncAction.Upload("d1", "d1.md", null))
    }

    @Test
    fun `mixed set is fully covered`() {
        val plan = SyncPlanner.plan(
            locals = listOf(
                local("up", hash = "h2"),       // 本地变 → 上传
                local("keep", hash = "h1")      // 不变 → 跳过
            ),
            remotes = listOf(
                RemoteFileInfo("up.md", "e0"),
                RemoteFileInfo("keep.md", "e0"),
                RemoteFileInfo("New.md", "e5")  // 远端独有 → 新建本地
            ),
            records = listOf(
                SyncRecordInfo("up", "up.md", "e0", "h1"),
                SyncRecordInfo("keep", "keep.md", "e0", "h1")
            )
        )
        assertThat(plan).containsExactly(
            SyncAction.Upload("up", "up.md", null),
            SyncAction.Skip("keep", "keep.md", "e0", "h1"),
            SyncAction.CreateLocal("New.md", "e5")
        )
    }
}
