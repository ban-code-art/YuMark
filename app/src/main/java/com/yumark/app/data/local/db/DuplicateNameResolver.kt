package com.yumark.app.data.local.db

/**
 * 一行参与「同级重名消重」的记录：只需要 id、同级分组键、当前名字三样。
 *
 * 刻意不用 Room 实体：消重逻辑因此能在 JVM 单测里直接跑（迁移里的 SQL 跑不了单测，
 * 但「新名字该叫什么」这件最容易写错的事必须能测）。
 *
 * [groupKey] 是「同级」的判定依据——documents 用 folder_id，folders 用 parent_id。
 * 只有非空的分组键才会进来：SQLite 的唯一索引不认为两个 NULL 相等，
 * 根目录（NULL）那一组根本不会让 CREATE UNIQUE INDEX 失败，也就不需要动用户的数据。
 */
internal data class SiblingNameRow(
    val id: String,
    val groupKey: String,
    val name: String
)

/**
 * 算出为了让 `(groupKey, name)` 唯一，需要把哪几行改成什么名字。
 *
 * 返回 `id to 新名字` 的列表，只包含**需要改名**的行；没有重复时返回空列表。
 * 调用方按这个列表逐条 UPDATE 即可（见 [AppDatabase] 的迁移 11 → 12）。
 *
 * 规则：
 * - 每个分组内，同名的第一行**保留原名**，其余的追加 ` (2)`、` (3)`…。
 *   「第一行」由调用方的 ORDER BY 决定（迁移里按 `name, created_at, id` 排，
 *   即同名之中最早创建的那条留原名）；本函数只按传入顺序处理，不自己排序，
 *   这样「保留哪一条」的规则留在 SQL 里一处可见。
 * - 候选名字要避开**整组已出现过的所有名字**，不只是已经定稿的那些。少了这一层，
 *   组内是 `A`、`A`、`A (2)` 时，第二行会被改成 `A (2)` 而撞上第三行——
 *   消重的结果反而制造出一组新的重复，索引照样建不起来。
 * - 序号从 2 起：第一条保留原名，人读起来 `A` / `A (2)` 才是「第二个 A」。
 *
 * 循环一定会停：每转一圈都会消耗掉 `occupied` 里一个已存在的名字，而它是有限集
 * （组内原有名字数 + 已分配的新名字数），所以最多转 `occupied.size + 1` 圈。
 */
internal fun resolveDuplicateSiblingNames(rows: List<SiblingNameRow>): List<Pair<String, String>> {
    val renames = mutableListOf<Pair<String, String>>()
    rows.groupBy { it.groupKey }.forEach { (_, group) ->
        // 已定稿（保留原名或已分配新名）的名字
        val claimed = mutableSetOf<String>()
        // 组内出现过的所有名字：含还没处理到的行，加上已经分配出去的新名字
        val occupied = group.mapTo(mutableSetOf<String>()) { it.name }
        for (row in group) {
            // 这个名字在组内第一次出现 → 它就是留原名的那一条
            if (claimed.add(row.name)) continue
            var suffix = FIRST_SUFFIX
            var candidate = "${row.name} ($suffix)"
            // add 返回 false 说明这个名字已经被占（原有的或刚分配的），换下一个序号
            while (!occupied.add(candidate)) {
                suffix++
                candidate = "${row.name} ($suffix)"
            }
            claimed.add(candidate)
            renames += row.id to candidate
        }
    }
    return renames
}

/** 第一个重复项拿到的序号；见 [resolveDuplicateSiblingNames] 里「序号从 2 起」的理由。 */
private const val FIRST_SUFFIX = 2
