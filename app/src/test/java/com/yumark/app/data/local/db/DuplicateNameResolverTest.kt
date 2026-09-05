package com.yumark.app.data.local.db

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 迁移 11 → 12 消重时「新名字该叫什么」的判定。
 *
 * 为什么值得单独测：这段逻辑跑在 [AppDatabase.MIGRATION_11_12] 里，而迁移一旦算错名字，
 * 紧随其后的 CREATE UNIQUE INDEX 就会失败 → 迁移失败 → 老用户的数据库打不开 → 应用启动
 * 即崩，且**本地全新安装完全测不出来**（全新安装不走迁移）。所以把它抽成纯函数
 * （[resolveDuplicateSiblingNames]），在 JVM 上把每种边界都钉住。
 *
 * 测不到的部分诚实记在这里：SQL 本身（SELECT/UPDATE/CREATE INDEX 的执行）需要真实的
 * SQLite，本项目没有 Robolectric，只能靠 androidTest 里的 MigrationInstrumentedTest 覆盖。
 */
class DuplicateNameResolverTest {

    @Test
    fun `没有重名时不改任何名字`() {
        val rows = listOf(row("1", "f", "笔记"), row("2", "f", "清单"))

        assertThat(resolveDuplicateSiblingNames(rows)).isEmpty()
    }

    @Test
    fun `空列表返回空结果`() {
        assertThat(resolveDuplicateSiblingNames(emptyList())).isEmpty()
    }

    @Test
    fun `第一条保留原名`() {
        val rows = listOf(row("1", "f", "笔记"), row("2", "f", "笔记"))

        // 返回的只有「需要改名」的行：谁保留原名由调用侧的 ORDER BY 决定
        // （迁移里按 created_at、id 排，即最早创建的那条留原名）
        assertThat(resolveDuplicateSiblingNames(rows)).containsExactly("2" to "笔记 (2)")
    }

    @Test
    fun `三条同名分别拿到 2 和 3`() {
        val rows = listOf(row("1", "f", "笔记"), row("2", "f", "笔记"), row("3", "f", "笔记"))

        // 序号从 2 起：人读起来「笔记」/「笔记 (2)」才是「第二个笔记」
        assertThat(resolveDuplicateSiblingNames(rows))
            .containsExactly("2" to "笔记 (2)", "3" to "笔记 (3)")
            .inOrder()
    }

    @Test
    fun `新名字避开组内已存在的同名`() {
        // 这是最容易写错的一条：只避开「已定稿」的名字时，第二行会被改成「笔记 (2)」，
        // 正好撞上第三行——消重反而制造出一组新的重复，唯一索引照样建不起来。
        val rows = listOf(
            row("1", "f", "笔记"),
            row("2", "f", "笔记"),
            row("3", "f", "笔记 (2)")
        )

        assertThat(resolveDuplicateSiblingNames(rows)).containsExactly("2" to "笔记 (3)")
    }

    @Test
    fun `序号连续被占时继续往后找`() {
        val rows = listOf(
            row("1", "f", "笔记"),
            row("2", "f", "笔记"),
            row("3", "f", "笔记 (2)"),
            row("4", "f", "笔记 (3)")
        )

        assertThat(resolveDuplicateSiblingNames(rows)).containsExactly("2" to "笔记 (4)")
    }

    @Test
    fun `同名但不同父级的不算重复`() {
        // 唯一索引是 (folder_id, name) 两列：跨文件夹同名合法，动它就是无谓地改用户数据
        val rows = listOf(row("1", "f1", "笔记"), row("2", "f2", "笔记"))

        assertThat(resolveDuplicateSiblingNames(rows)).isEmpty()
    }

    @Test
    fun `多个分组各自独立编号`() {
        val rows = listOf(
            row("1", "f1", "笔记"),
            row("2", "f1", "笔记"),
            row("3", "f2", "笔记"),
            row("4", "f2", "笔记")
        )

        assertThat(resolveDuplicateSiblingNames(rows))
            .containsExactly("2" to "笔记 (2)", "4" to "笔记 (2)")
    }

    @Test
    fun `一个分组里多组不同的重名互不干扰`() {
        val rows = listOf(
            row("1", "f", "笔记"),
            row("2", "f", "笔记"),
            row("3", "f", "清单"),
            row("4", "f", "清单")
        )

        assertThat(resolveDuplicateSiblingNames(rows))
            .containsExactly("2" to "笔记 (2)", "4" to "清单 (2)")
    }

    @Test
    fun `首尾空格不同就是两个名字`() {
        // 唯一索引比的是原样字符串（不 trim），这里必须与它一致：
        // 多改一条就是无谓地动用户数据，少改一条才会让索引建不起来
        val rows = listOf(row("1", "f", "笔记"), row("2", "f", "笔记 "))

        assertThat(resolveDuplicateSiblingNames(rows)).isEmpty()
    }

    @Test
    fun `只差大小写就是两个名字`() {
        // 同上：SQLite 的 TEXT 列默认 BINARY 排序，`Notes` 与 `notes` 不冲突
        val rows = listOf(row("1", "f", "Notes"), row("2", "f", "notes"))

        assertThat(resolveDuplicateSiblingNames(rows)).isEmpty()
    }

    private fun row(id: String, groupKey: String, name: String) =
        SiblingNameRow(id = id, groupKey = groupKey, name = name)
}
