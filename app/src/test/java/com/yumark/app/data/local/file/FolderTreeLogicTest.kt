package com.yumark.app.data.local.file

import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.repository.NamedEntry
import com.yumark.app.data.repository.findNameConflict
import com.yumark.app.data.repository.folderSubtreeIds
import org.junit.jupiter.api.Test

/**
 * 文件夹子树计算与同级重名判定。
 *
 * 两个被测函数都是 FolderRepositoryImpl.kt 里的顶层 internal 函数：抽出来的唯一理由
 * 就是让这两段最容易出错的逻辑能在 JVM 上真跑，不必为此拖起 Room 与 Context。
 */
class FolderTreeLogicTest {

    // ---- 待删子树：先算集合，再删库，最后删盘 ----

    @Test
    fun `子树包含根本身`() {
        assertThat(folderSubtreeIds("a", mapOf("a" to null))).containsExactly("a")
    }

    @Test
    fun `多层子树全部收进来`() {
        val parentById = mapOf(
            "root" to null,
            "a" to "root",
            "b" to "root",
            "a1" to "a",
            "a1x" to "a1"
        )

        // 少算一层就等于「文件夹删了，它下面的文档正文与图片永远回收不了」
        assertThat(folderSubtreeIds("a", parentById)).containsExactly("a", "a1", "a1x")
    }

    @Test
    fun `不把兄弟分支和根级文件夹拉进来`() {
        // parent_id 为 NULL 的那些最危险：按 parentId 分组时它们全挤在 null 这个键下，
        // 查错键就会把整个根目录算成待删集合——那是一次「删除文件夹」清空全库。
        val parentById = mapOf("x" to null, "y" to null, "x1" to "x")

        assertThat(folderSubtreeIds("x", parentById)).containsExactly("x", "x1")
    }

    @Test
    fun `父指针成环时不死循环`() {
        // 库里真出现 a→b→a 时，沿 parentId 递归会栈溢出；这里每个 id 只访问一次然后停下
        val parentById = mapOf("a" to "b", "b" to "a")

        assertThat(folderSubtreeIds("a", parentById)).containsExactly("a", "b")
    }

    @Test
    fun `根已被并发删掉时只返回它自己`() {
        // 返回空列表会让后续的删库语句一条都不执行，那一行文件夹就永远留在库里
        assertThat(folderSubtreeIds("gone", mapOf("a" to null))).containsExactly("gone")
    }

    @Test
    fun `广度优先且根排在最前`() {
        val parentById = mapOf("r" to null, "c" to "r", "g" to "c")

        assertThat(folderSubtreeIds("r", parentById)).containsExactly("r", "c", "g").inOrder()
    }

    // ---- 同级重名 ----

    @Test
    fun `同名时返回库里已存在的那一条`() {
        val siblings = listOf(NamedEntry("1", "笔记"), NamedEntry("2", "清单"))

        // 返回已存在的条目而不是布尔值：提示语要回显库里真实的名字
        assertThat(findNameConflict(siblings, "笔记")?.id).isEqualTo("1")
    }

    @Test
    fun `没有同名时返回 null`() {
        assertThat(findNameConflict(listOf(NamedEntry("1", "笔记")), "清单")).isNull()
    }

    @Test
    fun `改名时把自己排掉`() {
        // 不排掉的话，「名字没改也点了保存」会被自己挡下来
        assertThat(findNameConflict(listOf(NamedEntry("1", "笔记")), "笔记", excludeId = "1"))
            .isNull()
    }

    @Test
    fun `排掉自己之后仍然撞得上别人`() {
        val siblings = listOf(NamedEntry("1", "旧名"), NamedEntry("2", "新名"))

        assertThat(findNameConflict(siblings, "新名", excludeId = "1")?.id).isEqualTo("2")
    }

    @Test
    fun `首尾空格不算区别`() {
        // FileNameValidator 会拦掉首尾带空格的新输入，但库里可能躺着老数据
        val siblings = listOf(NamedEntry("1", " 笔记 "))

        assertThat(findNameConflict(siblings, "笔记")?.name).isEqualTo(" 笔记 ")
    }

    @Test
    fun `只差大小写不算重名`() {
        // 刻意如此：导入侧「复用同名子文件夹」的判定是大小写敏感的（ImportFolderUseCase），
        // 这里若忽略大小写，源目录同时有 Notes/ 和 notes/ 就会「复用找不到、创建又被拦」，
        // 整次导入直接失败。只差大小写的两条留给导出侧去消重。
        assertThat(findNameConflict(listOf(NamedEntry("1", "Notes")), "notes")).isNull()
    }
}
