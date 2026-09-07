package com.yumark.app.domain.usecase

/**
 * 重名判定规则（原 data/repository/FolderRepositoryImpl 内部件迁入 domain）：
 * 文档、文件夹、导入路径三处共用同一份比较规则，规则只留一份才不会分家。
 */

data class NamedEntry(val id: String, val name: String)

/**
 * 在同级条目 [siblings] 里找出与 [name] 重名的那一个，没有则返回 null。
 *
 * 两个细节是刻意的：
 * - [excludeId] 用来在改名时排掉自己，否则任何一次「名字没改」的保存都会被自己挡下来；
 * - 比较前 trim，但**不**忽略大小写。忽略大小写会让导入侧的「同名子文件夹复用」判定和这里
 *   打起来：源目录同时有 `Notes/` 和 `notes/` 时，复用找不到、创建又被拦，整次导入直接失败。
 *   只差大小写的两个条目留给导出侧去消重。
 *
 * 导入侧（`ImportFolderUseCase.resolveFolderPath`）复用的就是本函数，不再自己写一遍比较——
 * 它从前是裸 `it.name == segment`，少了 trim 这一步，于是「Notes 」这样的段落正好撞上上面
 * 描述的死局。规则只留一份，两侧才不会再分家。
 *
 * 返回的是**已存在**的那一条，好让提示语回显库里真实的名字，而不是用户刚敲的那个。
 */
fun findNameConflict(
    siblings: List<NamedEntry>,
    name: String,
    excludeId: String? = null
): NamedEntry? {
    val target = name.trim()
    return siblings.firstOrNull { it.id != excludeId && it.name.trim() == target }
}

/**
 * 从 [rootId] 出发，按 parentId 关系列出整棵子树的文件夹 id（含 [rootId] 本身，广度优先）。
 *
 * [parentById] 是「id → parentId」的全量快照（一次 `getAll()` 就够），因此这里是纯内存计算：
 * 删除前先把待删集合算完，才能在删库之后还知道该去磁盘上删哪些文件。
 *
 * 已访问集合同时兼作环检测：库里真出现 A→B→A 时只会各访问一次然后停下，
 * 不会像沿链递归那样栈溢出。[rootId] 不在 [parentById] 里（已被并发删掉）时只返回它自己。
 */
