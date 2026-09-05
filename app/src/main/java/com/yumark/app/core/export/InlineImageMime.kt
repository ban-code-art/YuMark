package com.yumark.app.core.export

/**
 * 导出内联前的「这堆字节到底是不是图片」判定：纯 `ByteArray` 进，MIME 出，不碰 `android.*`。
 *
 * 与 [sniffImageInfo] 的分工：那个函数是给 DOCX 用的，除格式还要给出**像素尺寸**
 * （`<wp:extent>` 的 cx/cy 缺了 Word 会报整篇损坏），所以它只认能廉价读出宽高的五种格式
 * （PNG / JPEG / GIF / BMP / WebP）。内联成 data URI 不需要尺寸——浏览器自己会解——
 * 于是这里在它之上再补几种「读不出尺寸但确实是图片」的格式：HEIC/HEIF/AVIF（手机相册直出）、
 * ICO、SVG，正是从前按扩展名声明过的那几种。少了这一层，「改成按字节判定」会顺手把这些格式
 * 从「能内联」降成「导出件里一张裂图」——那不是修复，是换了个方式坏。
 *
 * 为什么必须按字节判、不能按扩展名判：见 [inlineOne] 的注释。一句话——正文里的 `![](…)`
 * 不全是应用自己写的，扩展名既会撒谎，也能被用来把非图片文件伪装成图片。
 *
 * **不抛异常是硬契约**，理由与 [sniffImageInfo] 相同：调用链最外层是导出的 `runCatching`，
 * 这里抛一次就把「这张图没内联」升级成「整次导出失败」。
 */
internal fun sniffInlineImageMime(bytes: ByteArray): String? = runCatching {
    // 先走尺寸嗅探那一套：它顺带把「宽高为 0 / 大得离谱」的坏图挡在外面，
    // 而坏图内联进去在浏览器里同样是一张裂图，不如保留原 URI 让文件本身还能点开。
    sniffImageInfo(bytes)?.mime
        ?: sniffIsoBmffImageMime(bytes)
        ?: sniffIcoMime(bytes)
        ?: sniffSvgMime(bytes)
}.getOrNull()

/**
 * ISO-BMFF 容器（HEIC / HEIF / AVIF）：`[box 大小 4B]["ftyp"][主 brand 4B][次版本 4B][兼容 brand…]`。
 *
 * 取值按「具体 → 通用」排序，不是撞上第一个就收工：相册直出文件的主 brand 常常是通用的
 * `mif1`，真正说明编码的 `heic` / `avif` 躺在兼容列表里。先看到 `mif1` 就返回会把 AVIF
 * 报成 `image/heif`——data URI 的类型一旦谎报，浏览器画出来就是一张裂图。
 */
private fun sniffIsoBmffImageMime(b: ByteArray): String? {
    if (!matches(b, FTYP_BOX, at = 4)) return null
    val brands = isoBmffBrands(b)
    return when {
        brands.any { it in AVIF_BRANDS } -> "image/avif"
        brands.any { it in HEIC_BRANDS } -> "image/heic"
        brands.any { it in HEIF_BRANDS } -> "image/heif"
        else -> null
    }
}

/**
 * `ftyp` 里的主 brand 与兼容 brand，全部小写；最多取 [MAX_BRANDS] 个。
 *
 * box 声明的大小只用来**收窄**扫描范围，绝不用来放宽：它是文件里的一个数字，可以是 0、
 * 1（表示真实大小在后面 8 字节里）或任意巨值，拿它当循环上界就是把越界读的决定权交给输入。
 * 主 brand 在 8..11，12..15 是次版本号（不是 brand，混进来会多出一堆假 brand），
 * 兼容 brand 从 16 起每 4 字节一个。
 */
private fun isoBmffBrands(b: ByteArray): List<String> {
    val out = mutableListOf<String>()
    asciiAt(b, 8, 4)?.let { out += it.lowercase() }
    val end = minOf(u32be(b, 0) ?: return out, b.size.toLong()).toInt()
    var at = 16
    while (at + 4 <= end && out.size < MAX_BRANDS) {
        asciiAt(b, at, 4)?.let { out += it.lowercase() }
        at += 4
    }
    return out
}

/**
 * ICO：`00 00 01 00` + 小端图像数目(2B) + 每 16 字节一条目录项。
 *
 * 只比头四个字节不够：任何以 `00 00 01 00` 开头的二进制（包括一整块零填充里恰好这样的位置）
 * 都会撞上，那等于把「按字节判定」退化成「碰巧前缀相同」，非图片文件又能混进导出件了。
 * 所以还要求数目 ≥ 1、且字节数装得下它自己声明的目录项——三条一起才说明这是个真 ICO 头。
 *
 * `00 00 02 00` 是 CUR（光标），刻意不认：它不是网页画得出来的图。
 */
private fun sniffIcoMime(b: ByteArray): String? {
    if (!matches(b, ICO_MAGIC)) return null
    val count = u16le(b, 4) ?: return null
    if (count < 1 || b.size < ICO_HEADER + count * ICO_ENTRY) return null
    return "image/x-icon"
}

/**
 * SVG：唯一一种文本格式，只能按「开头长什么样」判。
 *
 * 序言可以是 BOM、前导空白、`<?xml …?>`、注释、DOCTYPE 的任意组合，真实文件里这几样都常见，
 * 所以逐个跳过。但**必须**在序言之后紧接着就是 `<svg`：换成「前 1KB 里出现过 `<svg`」的松判定，
 * 一整个 HTML 页面（正文里完全可以内嵌 `<svg>`）就会被判成图片内联进导出件。
 * 带内部 DTD 子集的 DOCTYPE（`<!DOCTYPE svg [ … ]>`）会在 `]` 处判否——罕见，且落在
 * fail-closed 那一侧：图片保留原引用，文件本身仍然打得开。
 *
 * 内联 SVG 的脚本风险不成立：data URI 装进 `<img src>` 是图片语境，里面的 `<script>`
 * 与事件属性都不执行。要是哪天导出件改用 `<object>`/`<iframe>` 引图，这一条得重新算。
 */
private fun sniffSvgMime(b: ByteArray): String? {
    // 截断处可能切在多字节字符中间：String(bytes, UTF_8) 把坏字节替换成 U+FFFD，不抛异常
    val head = String(b, 0, minOf(b.size, SVG_PROBE_BYTES), Charsets.UTF_8).trimStart(BOM)
    var i = 0
    var node = 0
    while (node++ < SVG_MAX_PROLOG_NODES) {
        while (i < head.length && head[i].isWhitespace()) i++
        if (i >= head.length || head[i] != '<') return null
        if (head.startsWith("<svg", i, ignoreCase = true)) {
            // `<svgfoo` 不是 svg 元素：标签名后面必须是空白、`/` 或 `>`
            val next = head.getOrNull(i + 4) ?: return null
            return if (next.isWhitespace() || next == '>' || next == '/') "image/svg+xml" else null
        }
        val after = when {
            // `<!--` 必须排在 `<!` 前面：注释里的第一个 `>` 常常不是注释的结尾
            head.startsWith("<?", i) -> head.indexOf("?>", i).takeIf { it >= 0 }?.plus(2)
            head.startsWith("<!--", i) -> head.indexOf("-->", i).takeIf { it >= 0 }?.plus(3)
            head.startsWith("<!", i) -> head.indexOf('>', i).takeIf { it >= 0 }?.plus(1)
            else -> null
        } ?: return null
        i = after
    }
    return null
}

// ---- 定长读取：越界一律返回 null，绝不抛 ----
//
// 这几个助手与 [ImageBinaryInfo] 那边同名的私有函数刻意各写一份：它们是 file-private，
// 抽成 internal 共享会把「只在本文件用」的约定变成一个跨文件的公共契约，而这一份的语义
// 有意与那边不同（u32be 返回 Long，因为 ISO-BMFF 的 box 大小是无符号 32 位，
// 用 Int 接会把 3GB 以上的声明值读成负数，minOf 一比就变成「扫到 0」）。

private fun matches(b: ByteArray, magic: ByteArray, at: Int = 0): Boolean {
    if (at < 0 || b.size < at + magic.size) return false
    for (i in magic.indices) if (b[at + i] != magic[i]) return false
    return true
}

private fun asciiAt(b: ByteArray, at: Int, len: Int): String? =
    if (at < 0 || len < 0 || at + len > b.size) null else String(b, at, len, Charsets.US_ASCII)

private fun u16le(b: ByteArray, i: Int): Int? =
    if (i < 0 || i + 1 >= b.size) null
    else ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i].toInt() and 0xFF)

private fun u32be(b: ByteArray, i: Int): Long? {
    if (i < 0 || i + 3 >= b.size) return null
    var v = 0L
    for (k in 0..3) v = (v shl 8) or (b[i + k].toInt() and 0xFF).toLong()
    return v
}

private val FTYP_BOX = "ftyp".toByteArray(Charsets.US_ASCII)
private val ICO_MAGIC = byteArrayOf(0x00, 0x00, 0x01, 0x00)

/** AVIF：`avif` 静态图、`avis` 图像序列。 */
private val AVIF_BRANDS = setOf("avif", "avis")

/** HEVC 码流的 HEIF，也就是通称的 HEIC；iOS 与多数安卓相册直出的就是这一族。 */
private val HEIC_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs")

/** 通用 HEIF 容器 brand：只说明「是 HEIF」，不说明码流是什么，所以排在最后兜底。 */
private val HEIF_BRANDS = setOf("mif1", "mif2", "msf1")

/** 兼容 brand 的扫描上限。真实文件里超过 5 个就算多，取 16 是留余量而非放宽。 */
private const val MAX_BRANDS = 16

private const val ICO_HEADER = 6
private const val ICO_ENTRY = 16

/** SVG 只看开头这么多字节：序言再长也不会长过 1KB，读整个文件是白搭。 */
private const val SVG_PROBE_BYTES = 1024

/**
 * UTF-8 BOM 解码后的字符。写成 [Char] 构造而不是字符串字面量：BOM 在源码里是一个**看不见**的
 * 字符，谁都无法在 diff 里认出它，也很容易被编辑器顺手吃掉或替换成别的零宽字符。
 */
private val BOM = Char(0xFEFF)

/** 序言里最多跳过几个节点。有上界才不会被一串注释拖成线性扫描。 */
private const val SVG_MAX_PROLOG_NODES = 8
