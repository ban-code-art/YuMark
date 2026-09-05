package com.yumark.app.core.export

import com.yumark.app.core.validation.FileNameValidator

/**
 * 把一批「文档 id + 文档名」映射成**唯一、稳定、与输入顺序无关**的远端文件名。
 *
 * 取代 `SyncRepositoryImpl.assignFileNames` 的三处缺陷（该文件属只读，需主进程改一行调用）：
 * 1. 谁拿到裸名取决于 `getAllDocuments()` 的返回顺序——顺序一变，两篇同名文档的远端文件名互换，
 *    每次同步都在远端反复删建，历史被搅乱；
 * 2. 加了短 id 后缀的名字**没有再查重**（`used.add` 的返回值被丢掉），两篇文档可以落到同一个远端
 *    文件名上，后上传的直接覆盖前一篇——这是真丢数据；
 * 3. `sanitize` 已把名字截到 200 字，再拼 `-abcdef` 会超出上限，部分服务端直接拒收。
 *
 * 稳定性做法：同名分组里**所有**成员都带 id 后缀（而不是让某一个"抢到"裸名），
 * 于是任何成员的名字都只由它自己的 id 与文档名决定，与别人排在它前面还是后面无关。
 */
object UniqueFileNames {

    /**
     * 文件名主体上限：直接取 [FileNameValidator.MAX_SANITIZED_LENGTH]。
     * 两处各写一个 200，改一边就会分叉——`sanitize` 先截到 200，这里再按另一个数拼后缀，
     * 结果是超长名字被服务端拒收，而两处代码看上去都"对"。
     */
    const val MAX_BASE_LENGTH = FileNameValidator.MAX_SANITIZED_LENGTH

    /** 冲突后缀取 id 前几位；与原实现一致，够短且够分辨。 */
    private const val SHORT_ID_LENGTH = 6

    /** Windows/部分 WebDAV 服务端的保留设备名，整段（忽略大小写）不可用作文件名。 */
    private val RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /** 一条待命名的记录。[id] 必须是稳定唯一键（文档 id）。 */
    data class Entry(val id: String, val name: String)

    /**
     * 返回 id → 文件名（含 [extension]）。
     *
     * 判重按小写比较：WebDAV 服务端与 Windows 文件系统多数大小写不敏感，
     * 按原样比较会生成一对"只差大小写"的名字，在那些服务端上互相覆盖。
     */
    fun assign(entries: List<Entry>, extension: String = ".md"): Map<String, String> {
        val bases = entries.associate { it.id to baseOf(it.name) }
        val groupSize = entries.groupingBy { bases.getValue(it.id).lowercase() }.eachCount()
        val taken = HashSet<String>(entries.size * 2)
        val result = LinkedHashMap<String, String>(entries.size * 2)
        // 排序遍历是"与输入顺序无关"的关键：先按 base 再按 id，任何入参顺序都走出同一条路径。
        for (entry in entries.sortedWith(compareBy({ bases.getValue(it.id).lowercase() }, { it.id }))) {
            val base = bases.getValue(entry.id)
            val collides = (groupSize[base.lowercase()] ?: 1) > 1
            val fileName = pickName(base, entry.id, collides, extension, taken)
            taken.add(fileName.lowercase())
            result[entry.id] = fileName
        }
        return result
    }

    /**
     * 逐级退让地取一个尚未被占用的名字。
     *
     * 分组内必带短 id 后缀；短 id 也撞（不同文档 id 前 6 位相同，或别的文档名字本身就长成
     * `xxx-abc123`）就退到完整 id，再撞就加计数。计数一定收敛：完整 id 唯一，最坏情况只多一位数字。
     */
    private fun pickName(
        base: String,
        id: String,
        collides: Boolean,
        extension: String,
        taken: Set<String>
    ): String {
        val shortId = id.take(SHORT_ID_LENGTH)
        var candidate = if (collides) suffixed(base, shortId) else base
        var attempt = 0
        while ((candidate + extension).lowercase() in taken) {
            attempt++
            candidate = when (attempt) {
                1 -> suffixed(base, shortId)
                2 -> suffixed(base, id)
                else -> suffixed(base, "$id-$attempt")
            }
        }
        return candidate + extension
    }

    /** 拼后缀并保证总长不超上限：截主体而不是截后缀，后缀被截掉就失去了区分作用。 */
    private fun suffixed(base: String, suffix: String): String {
        val room = (MAX_BASE_LENGTH - suffix.length - 1).coerceAtLeast(1)
        return truncateSafely(base, room) + "-" + suffix
    }

    /**
     * 规范化文件名主体：复用 [FileNameValidator.sanitize]（非法字符/`..`/空名/长度都在那里处理），
     * 再补三件它不管、而文件系统与 WebDAV 服务端一定会管的事：控制字符（文档名是用户自由输入的，
     * 粘贴多行文本就会带进换行，拼进 URL 会让 PUT 直接 400）、结尾的点和空格（Windows 静默丢掉，
     * 于是下一轮同步对不上账），以及保留设备名。
     */
    private fun baseOf(name: String): String {
        val printable = name.filter { it.code >= 0x20 && it.code != 0x7F }
        val sanitized = FileNameValidator.sanitize(printable).trimEnd('.', ' ')
        if (sanitized.isEmpty()) return "document"
        return if (sanitized.uppercase() in RESERVED_NAMES) "_$sanitized" else sanitized
    }

    /**
     * 按码点截断：`substring` 可能把一个 emoji 的代理对切成半个，
     * 留下的孤立代理字符在部分服务端上会让整个 PUT 400。
     */
    private fun truncateSafely(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        val cut = text.substring(0, maxLength)
        return if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }
}
