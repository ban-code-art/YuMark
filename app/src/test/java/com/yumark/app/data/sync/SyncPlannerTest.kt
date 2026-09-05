package com.yumark.app.data.sync

import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.sync.SyncPlanner.LocalDocInfo
import com.yumark.app.data.sync.SyncPlanner.RemoteFileInfo
import com.yumark.app.data.sync.SyncPlanner.SyncAction
import com.yumark.app.data.sync.SyncPlanner.SyncRecordInfo
import com.yumark.app.data.sync.SyncPlanner.TombstoneInfo
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
    fun `远端文件消失且本地自基线以来一个字没改：删本地（远端删除下行）`() {
        // 有基线在手说明这篇**曾经**传上去过，现在远端没了只有一种解释：另一台设备删了它。
        // 本地内容与基线逐字节相同 → 没有任何本地改动会被这次删除带走。
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", hash = "h1")),
            remotes = emptyList(),
            records = listOf(SyncRecordInfo("d1", "d1.md", remoteEtag = "e0", localHash = "h1"))
        )
        assertThat(plan).containsExactly(SyncAction.DeleteLocal("d1", "d1.md"))
    }

    @Test
    fun `远端文件消失但本地有未同步改动：复活上传而不是删本地`() {
        // 两个方向的误判代价完全不对称：误复活一篇，用户再删一次即可；误删一篇，
        // 那份内容在任何地方都不再存在。所以改动优先。
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", hash = "h2")),
            remotes = emptyList(),
            records = listOf(SyncRecordInfo("d1", "d1.md", remoteEtag = "e0", localHash = "h1"))
        )
        assertThat(plan).containsExactly(SyncAction.Upload("d1", "d1.md", null))
        assertThat(plan.filterIsInstance<SyncAction.DeleteLocal>()).isEmpty()
    }

    @Test
    fun `远端文件消失且基线没有 localHash：判不出改没改，按复活处理`() {
        // 历史空基线（早期版本写入的行）判不出本地改没改，同样不允许销毁内容
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", hash = "h1")),
            remotes = emptyList(),
            records = listOf(SyncRecordInfo("d1", "d1.md", remoteEtag = "e0", localHash = null))
        )
        assertThat(plan).containsExactly(SyncAction.Upload("d1", "d1.md", null))
        assertThat(plan.filterIsInstance<SyncAction.DeleteLocal>()).isEmpty()
    }

    @Test
    fun `改名撞上远端已有的同名文件：判冲突而不是下载覆盖`() {
        // d1 原来同步为 old.md，本地改名成 new.md；而远端的 new.md 是**另一份**文档
        // （多半是另一台设备建的、本地还没拉下来）。
        //
        // 旧实现只在「新名字在远端不存在」时才认改名，于是这一例一路落到 etag 比较：
        // 陌生文件的 e9 与基线 e0 不等 → remoteChanged 为真，而只改名没改正文 → localChanged 为假
        // → 判成 DownloadOverwrite，用一份毫不相干的文档内容盖掉本地正文。
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", name = "new.md", hash = "h1")),
            remotes = listOf(RemoteFileInfo("old.md", "e0"), RemoteFileInfo("new.md", "e9")),
            records = listOf(SyncRecordInfo("d1", "old.md", remoteEtag = "e0", localHash = "h1"))
        )
        // 冲突：先把 new.md 的内容存成本地副本，再上传本地内容，顺带删掉旧路径
        assertThat(plan).containsExactly(
            SyncAction.Conflict("d1", "new.md", "e9", deleteOldPath = "old.md")
        )
        // 关键否定断言：绝不能是下载覆盖
        assertThat(plan.filterIsInstance<SyncAction.DownloadOverwrite>()).isEmpty()
    }

    @Test
    fun `改名撞名时旧路径不会被当成远端独有拉成重复文档`() {
        // 与上一例同一场景，这里钉的是另一半后果：旧实现里 old.md 没进 handledRemoteNames，
        // 于是它又被当成「远端独有」拉成一篇新文档——用户只改了个名字，正文变成别人的，
        // 旁边还多出一篇重复的。
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", name = "new.md", hash = "h1")),
            remotes = listOf(RemoteFileInfo("old.md", "e0"), RemoteFileInfo("new.md", "e9")),
            records = listOf(SyncRecordInfo("d1", "old.md", remoteEtag = "e0", localHash = "h1"))
        )
        assertThat(plan.filterIsInstance<SyncAction.CreateLocal>()).isEmpty()
    }

    @Test
    fun `改名且正文也改过，仍然只按改名冲突处理一次`() {
        // localChanged 为真也不该改变结论：基线描述的是 old.md，与 new.md 下那份文件没有可比性
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", name = "new.md", hash = "h2")),
            remotes = listOf(RemoteFileInfo("new.md", "e9")),
            records = listOf(SyncRecordInfo("d1", "old.md", remoteEtag = "e0", localHash = "h1"))
        )
        assertThat(plan).containsExactly(
            SyncAction.Conflict("d1", "new.md", "e9", deleteOldPath = "old.md")
        )
    }

    @Test
    fun `基线缺 etag 时不会把陌生文件误判成远端改动`() {
        // 历史空基线（PUT 没回 ETag 的服务器）+ 改名：remoteChanged 恒为假，旧实现会判成
        // Skip 并把陌生文件的 etag 当成自己的基线记下来，那份陌生文件从此再也不会被拉取。
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", name = "new.md", hash = "h1")),
            remotes = listOf(RemoteFileInfo("new.md", "e9")),
            records = listOf(SyncRecordInfo("d1", "old.md", remoteEtag = null, localHash = "h1"))
        )
        assertThat(plan).containsExactly(
            SyncAction.Conflict("d1", "new.md", "e9", deleteOldPath = "old.md")
        )
        assertThat(plan.filterIsInstance<SyncAction.Skip>()).isEmpty()
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

    // ===== 墓碑：本地删除 → 推到远端 =====

    @Test
    fun `墓碑对应的远端文件还在：删远端`() {
        val plan = SyncPlanner.plan(
            locals = emptyList(),
            remotes = listOf(RemoteFileInfo("gone.md", "e0")),
            records = emptyList(),
            tombstones = listOf(TombstoneInfo("d1", "gone.md"))
        )
        assertThat(plan).containsExactly(SyncAction.DeleteRemote("d1", "gone.md"))
    }

    @Test
    fun `被删掉的文档不会被自己的远端文件复活`() {
        // 这是整张墓碑表存在的原因。没有墓碑时，「远端有文件、本地没有对应文档」与
        // 「另一台设备新建了一篇」完全无法区分，只能拉回来——用户删掉的文档就地复活。
        val plan = SyncPlanner.plan(
            locals = emptyList(),
            remotes = listOf(RemoteFileInfo("gone.md", "e0")),
            records = emptyList(),
            tombstones = listOf(TombstoneInfo("d1", "gone.md"))
        )
        assertThat(plan.filterIsInstance<SyncAction.CreateLocal>()).isEmpty()
    }

    @Test
    fun `墓碑对应的远端文件本来就不在：只清墓碑`() {
        // 对方也删了、或者当初压根没传成 → 无事可做。留着墓碑就是一颗延时炸弹：
        // 每轮都要重判一次，而某天远端出现同名文件就会被它删掉。
        val plan = SyncPlanner.plan(
            locals = emptyList(),
            remotes = emptyList(),
            records = emptyList(),
            tombstones = listOf(TombstoneInfo("d1", "gone.md"))
        )
        assertThat(plan).containsExactly(SyncAction.DropTombstone("d1"))
    }
    @Test
    fun `墓碑的远端路径已归另一篇活着的文档：绝不删远端`() {
        // 典型场景：删掉「随笔」之后又新建了一篇「随笔」。那个远端文件名已经是新文档的了，
        // 按墓碑删掉等于把用户刚写的东西删了。
        val plan = SyncPlanner.plan(
            locals = listOf(local("d2", name = "随笔.md", hash = "h1")),
            remotes = listOf(RemoteFileInfo("随笔.md", "e0")),
            records = emptyList(),
            tombstones = listOf(TombstoneInfo("d1", "随笔.md"))
        )
        assertThat(plan).containsExactly(
            // 新文档没有基线、远端已有同名文件 → 按首次同步撞名走冲突
            SyncAction.Conflict("d2", "随笔.md", "e0"),
            SyncAction.DropTombstone("d1")
        )
        assertThat(plan.filterIsInstance<SyncAction.DeleteRemote>()).isEmpty()
    }

    @Test
    fun `墓碑的 docId 又有活着的文档了：墓碑作废`() {
        // 从历史版本恢复，或另一台设备把它同步回来了
        val plan = SyncPlanner.plan(
            locals = listOf(local("d1", hash = "h1")),
            remotes = listOf(RemoteFileInfo("d1.md", "e0")),
            records = listOf(SyncRecordInfo("d1", "d1.md", remoteEtag = "e0", localHash = "h1")),
            tombstones = listOf(TombstoneInfo("d1", "d1.md"))
        )
        assertThat(plan).containsExactly(
            SyncAction.Skip("d1", "d1.md", "e0", "h1"),
            SyncAction.DropTombstone("d1")
        )
        assertThat(plan.filterIsInstance<SyncAction.DeleteRemote>()).isEmpty()
    }

    @Test
    fun `墓碑的远端路径正是某次改名的旧路径：让改名处置，不重复删`() {
        // d2 从 gone.md 改名成 new.md（改名分支会把 gone.md 登记进 handledRemoteNames 并在
        // 上传后删掉它）；恰好还有一块指向 gone.md 的墓碑 → 只清墓碑，别发第二次 DELETE。
        val plan = SyncPlanner.plan(
            locals = listOf(local("d2", name = "new.md", hash = "h1")),
            remotes = listOf(RemoteFileInfo("gone.md", "e0")),
            records = listOf(SyncRecordInfo("d2", "gone.md", remoteEtag = "e0", localHash = "h1")),
            tombstones = listOf(TombstoneInfo("d1", "gone.md"))
        )
        assertThat(plan).containsExactly(
            SyncAction.Upload("d2", "new.md", deleteOldPath = "gone.md"),
            SyncAction.DropTombstone("d1")
        )
    }
    @Test
    fun `已同步的文档被移进文件夹后不会被重新拉成一篇根级重复文档`() {
        // P1 只同步根级文档。一篇文档被拖进文件夹之后，它的 sync_state 行还在
        // （那一行只在文档被删时才被 CASCADE 带走），远端文件也还在，可本轮的 locals 里
        // 没有任何文档会认领那个文件名 → 旧实现把它当成「远端独有」拉成一篇根级新文档，
        // 用户只是拖了一下，手里却多出一份重复。
        val plan = SyncPlanner.plan(
            locals = emptyList(),
            remotes = listOf(RemoteFileInfo("moved.md", "e0")),
            records = listOf(SyncRecordInfo("d1", "moved.md", remoteEtag = "e0", localHash = "h1"))
        )
        // 那个远端文件成为孤儿（不再更新），是当前的已知取舍；但绝不能变成重复文档
        assertThat(plan).isEmpty()
    }

    @Test
    fun `墓碑与其它动作共存时互不干扰`() {
        val plan = SyncPlanner.plan(
            locals = listOf(local("keep", hash = "h1")),
            remotes = listOf(
                RemoteFileInfo("keep.md", "e0"),
                RemoteFileInfo("gone.md", "e0"),
                RemoteFileInfo("New.md", "e5")
            ),
            records = listOf(SyncRecordInfo("keep", "keep.md", "e0", "h1")),
            tombstones = listOf(TombstoneInfo("d1", "gone.md"))
        )
        assertThat(plan).containsExactly(
            SyncAction.Skip("keep", "keep.md", "e0", "h1"),
            SyncAction.DeleteRemote("d1", "gone.md"),
            SyncAction.CreateLocal("New.md", "e5")
        )
    }
}
