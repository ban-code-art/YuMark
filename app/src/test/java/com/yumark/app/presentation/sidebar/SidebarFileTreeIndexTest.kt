package com.yumark.app.presentation.sidebar

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.FolderTreeNode
import org.junit.jupiter.api.Test

/**
 * 侧栏「自动定位到当前文档」的索引换算。
 *
 * 侧栏的 LazyColumn 是 `items(tree)`：一个顶层节点连同它整棵展开的子树只占**一个** item。
 * 所以要的是顶层下标，不是把所有行摊平之后的行号——喂行号进去，animateScrollToItem 会把
 * 越界值夹到最后一项，于是「定位到当前文档」的实际效果是滚到列表末尾。
 * 下面多条用例顺带钉住返回值必须落在 tree 的下标范围内。
 */
class SidebarFileTreeIndexTest {

    private fun doc(id: String, folderId: String? = null) =
        Document.create(id = id, name = id, folderId = folderId)

    private fun node(
        folderId: String?,
        docs: List<String> = emptyList(),
        children: List<FolderTreeNode> = emptyList()
    ) = FolderTreeNode(
        folder = folderId?.let { Folder.create(id = it, name = it) },
        documents = docs.map { doc(it, folderId) },
        children = children
    )

    /** f1 → f1a → f1b → target，前面垫一个装了 50 篇文档的顶层文件夹。 */
    private fun deepTree() = listOf(
        node("f0", docs = List(50) { "d$it" }),
        node("f1", children = listOf(
            node("f1a", children = listOf(node("f1b", docs = listOf("target"))))
        )),
        node("f2", docs = listOf("tail"))
    )

    private val deepTreeExpanded = setOf("f0", "f1", "f1a", "f1b", "f2")

    @Test
    fun `文档在第二个顶层文件夹里时返回顶层下标 1`() {
        val tree = listOf(
            node("f0", docs = listOf("a", "b", "c")),
            node("f1", docs = listOf("target"))
        )
        // 摊平行号会是 5（两个文件夹行 + 三篇文档），而 tree 只有 2 项
        assertThat(findTopLevelIndexOfDocument(tree, "target", setOf("f0", "f1"))).isEqualTo(1)
    }

    @Test
    fun `深层嵌套的文档返回它所属的顶层下标`() {
        val tree = deepTree()
        assertThat(findTopLevelIndexOfDocument(tree, "target", deepTreeExpanded)).isEqualTo(1)
    }

    @Test
    fun `返回值永远落在 tree 的下标范围内`() {
        // 这条是整个改动的理由：旧实现在这棵树上返回 50 开外，而 LazyColumn 只有 3 项，
        // animateScrollToItem 一夹就夹到 f2，用户看到的是「定位」把自己扔到了列表末尾。
        val tree = deepTree()
        val index = findTopLevelIndexOfDocument(tree, "target", deepTreeExpanded)
        assertThat(index).isIn(tree.indices.toList())
    }

    @Test
    fun `根节点直属的文档也能定位`() {
        // folder == null 的节点在 FolderTreeItem 里视为始终展开，不看 expandedFolders
        val tree = listOf(node(null, docs = listOf("root-doc")), node("f0"))
        assertThat(findTopLevelIndexOfDocument(tree, "root-doc", emptySet())).isEqualTo(0)
    }

    @Test
    fun `顶层文件夹折叠时返回 -1`() {
        val tree = listOf(node("f0", docs = listOf("target")))
        // 折叠着的文件夹里那一行在屏上根本不存在，没有可滚过去的目标
        assertThat(findTopLevelIndexOfDocument(tree, "target", emptySet())).isEqualTo(-1)
    }

    @Test
    fun `中间层文件夹折叠时返回 -1`() {
        val tree = listOf(node("f0", children = listOf(node("f0a", docs = listOf("target")))))
        assertThat(findTopLevelIndexOfDocument(tree, "target", setOf("f0"))).isEqualTo(-1)
    }

    @Test
    fun `树里没有这篇文档时返回 -1`() {
        assertThat(findTopLevelIndexOfDocument(deepTree(), "不存在", deepTreeExpanded)).isEqualTo(-1)
    }

    @Test
    fun `祖先链是从根到直接父文件夹的有序列表`() {
        assertThat(ancestorFolderIdsOf(deepTree(), "target"))
            .containsExactly("f1", "f1a", "f1b").inOrder()
    }

    @Test
    fun `文档不在树里时祖先链为空`() {
        assertThat(ancestorFolderIdsOf(deepTree(), "不存在")).isEmpty()
    }

    @Test
    fun `根级文档的祖先链为空`() {
        val tree = listOf(node(null, docs = listOf("root-doc")))
        assertThat(ancestorFolderIdsOf(tree, "root-doc")).isEmpty()
    }

    @Test
    fun `展开祖先链之后一定定位得到`() {
        // 这正是 SidebarFileTree 里那个 LaunchedEffect 的两步流程：先 ancestorFolderIdsOf
        // 展开祖先，重组后再算索引。两个函数对「可见」的判定必须一致，否则会出现
        // 「祖先都展开了却依然定位不到」——effect 每次重启都只做展开、永远走不到滚动那一步。
        val tree = deepTree()
        val expanded = ancestorFolderIdsOf(tree, "target").toSet()
        assertThat(findTopLevelIndexOfDocument(tree, "target", expanded)).isEqualTo(1)
    }
}
