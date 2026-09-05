package com.yumark.app.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [sniffInlineImageMime] 的单测：**内联进导出件的字节到底算不算图片**。
 *
 * 这是导出件的内容闸门。判错的两个方向后果都在导出件里，而运行时一声不响：
 * - 该拒的没拒 → 一个改名成 `.png` 的数据库/PDF 被 base64 塞进 HTML，随 `ACTION_SEND` 发出去；
 * - 该收的拒了 → HEIC/AVIF/SVG 这些「读不出尺寸但确实是图片」的格式退回原 URI，
 *   别人打开导出的 .html 看到一张裂图。
 *
 * 「不抛异常」是这个函数的硬契约（外层是导出的 `runCatching`，抛一次就把「这张图没内联」
 * 升级成「整次导出失败」），所以每个格式都配一轮**逐字节截断**，把边界读全测一遍。
 *
 * 字节夹具见 `ImageByteFixtures.kt`。
 */
class InlineImageMimeTest {

    // ---- 委托给 sniffImageInfo 的五种 ----

    @Test
    fun `五种能读出尺寸的格式沿用尺寸嗅探的结论`() {
        assertThat(sniffInlineImageMime(pngBytes())).isEqualTo("image/png")
        assertThat(sniffInlineImageMime(jpegBytes())).isEqualTo("image/jpeg")
        assertThat(sniffInlineImageMime(gifBytes())).isEqualTo("image/gif")
        assertThat(sniffInlineImageMime(bmpBytes())).isEqualTo("image/bmp")
        assertThat(sniffInlineImageMime(webpVp8Bytes())).isEqualTo("image/webp")
        assertThat(sniffInlineImageMime(webpVp8xBytes())).isEqualTo("image/webp")
    }

    @Test
    fun `尺寸不合理的图当作认不出`() {
        // sniffImageInfo 的 1..MAX_DIM 闸门。坏图内联进去在浏览器里同样是裂图，
        // 不如保留原 URI 让文件本身还点得开
        assertThat(sniffInlineImageMime(pngBytes(w = 0, h = 8))).isNull()
        assertThat(sniffInlineImageMime(gifBytes(w = 8, h = 0))).isNull()
    }

    // ---- ISO-BMFF：HEIC / HEIF / AVIF ----

    @Test
    fun `主 brand 就说明编码时直接采信`() {
        assertThat(sniffInlineImageMime(isoBmffBytes("heic"))).isEqualTo("image/heic")
        assertThat(sniffInlineImageMime(isoBmffBytes("avif"))).isEqualTo("image/avif")
        // avis = AVIF 图像序列（动图），仍然是 AVIF
        assertThat(sniffInlineImageMime(isoBmffBytes("avis"))).isEqualTo("image/avif")
        assertThat(sniffInlineImageMime(isoBmffBytes("mif1"))).isEqualTo("image/heif")
    }

    @Test
    fun `主 brand 是通用 mif1 时要看兼容列表，不能撞上第一个就收工`() {
        // 相册直出的 AVIF 主 brand 常常是通用的 mif1，真正说明编码的 avif 躺在兼容列表里。
        // 先看到 mif1 就返回会把 AVIF 报成 image/heif —— data URI 的类型一旦谎报，
        // 浏览器画出来就是一张裂图。取值必须按「具体 → 通用」排序。
        assertThat(sniffInlineImageMime(isoBmffBytes("mif1", "avif"))).isEqualTo("image/avif")
        assertThat(sniffInlineImageMime(isoBmffBytes("msf1", "heic"))).isEqualTo("image/heic")
        // 顺序反过来同样成立：结论由集合归属决定，不由出现次序决定
        assertThat(sniffInlineImageMime(isoBmffBytes("mif1", "heic", "avif")))
            .isEqualTo("image/avif")
    }

    @Test
    fun `次版本号不算 brand`() {
        // 12..15 是次版本号。若扫描从 12 起，这个样本会被报成 image/avif；
        // 它其实是个 MP4 视频，一张图都没有
        val minorLooksLikeBrand = b32be(20) + asciiBytes("ftyp") + asciiBytes("mp42") +
            asciiBytes("avif") + asciiBytes("isom")
        assertThat(sniffInlineImageMime(minorLooksLikeBrand)).isNull()
    }

    @Test
    fun `不是图片的 ISO-BMFF 一律拒`() {
        // MP4 / MOV 与 HEIC 共用容器，只有 brand 分得开它们
        assertThat(sniffInlineImageMime(isoBmffBytes("mp42", "isom"))).isNull()
        assertThat(sniffInlineImageMime(isoBmffBytes("qt  "))).isNull()
    }

    @Test
    fun `box 声明的大小只用来收窄扫描范围`() {
        // 声明 0：兼容列表一个都不扫，结论退回主 brand（宁可少认，不能拿文件里的数字当循环上界）
        val sizeZero = b32be(0) + asciiBytes("ftyp") + asciiBytes("mif1") + b32be(0) +
            asciiBytes("avif")
        assertThat(sniffInlineImageMime(sizeZero)).isEqualTo("image/heif")

        // 声明 0xFFFFFFFF（当 Int 读是 -1）：必须被 b.size 夹住，不能越界读
        val sizeHuge = b32be(-1) + asciiBytes("ftyp") + asciiBytes("mif1") + b32be(0) +
            asciiBytes("avif")
        assertThat(sniffInlineImageMime(sizeHuge)).isEqualTo("image/avif")
    }

    // ---- ICO ----

    @Test
    fun `合法 ICO 头才算 ICO`() {
        assertThat(sniffInlineImageMime(icoBytes(count = 1))).isEqualTo("image/x-icon")
        assertThat(sniffInlineImageMime(icoBytes(count = 4))).isEqualTo("image/x-icon")
    }

    @Test
    fun `只比魔数不够，数目与长度都要自洽`() {
        // 任何以 00 00 01 00 开头的二进制都会撞上魔数（包括一整块零填充里恰好这样的位置）。
        // 那等于把「按字节判定」退化成「碰巧前缀相同」，非图片文件又能混进导出件
        assertThat(sniffInlineImageMime(icoBytes(count = 0, entries = 0))).isNull()
        assertThat(sniffInlineImageMime(icoBytes(count = 3, entries = 0))).isNull()
        assertThat(sniffInlineImageMime(byteArrayOf(0, 0, 1, 0))).isNull()
        assertThat(sniffInlineImageMime(ByteArray(64))).isNull()
    }

    @Test
    fun `CUR 光标刻意不认`() {
        // 00 00 02 00 是光标，不是网页画得出来的图
        val cur = byteArrayOf(0, 0, 2, 0) + b16le(1) + ByteArray(16)
        assertThat(sniffInlineImageMime(cur)).isNull()
    }

    // ---- SVG：唯一的文本格式 ----

    @Test
    fun `序言的各种写法都跳得过去`() {
        assertThat(sniffInlineImageMime(svgBytes())).isEqualTo("image/svg+xml")
        assertThat(sniffInlineImageMime(svgBytes("\n\t  "))).isEqualTo("image/svg+xml")
        // BOM 用 Char(0xFEFF) 拼，不写字面量：它在源码里看不见，diff 里认不出来
        assertThat(sniffInlineImageMime(svgBytes(Char(0xFEFF).toString())))
            .isEqualTo("image/svg+xml")
        assertThat(sniffInlineImageMime(svgBytes("""<?xml version="1.0" encoding="UTF-8"?>""")))
            .isEqualTo("image/svg+xml")
        assertThat(sniffInlineImageMime(svgBytes("<!-- 由 Figma 导出 -->")))
            .isEqualTo("image/svg+xml")
        assertThat(
            sniffInlineImageMime(
                svgBytes("""<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "svg11.dtd">""")
            )
        ).isEqualTo("image/svg+xml")
        // 四样叠在一起才是真实文件里最常见的样子
        assertThat(
            sniffInlineImageMime(
                svgBytes(Char(0xFEFF).toString() + "<?xml version=\"1.0\"?>\n<!-- x -->\n")
            )
        ).isEqualTo("image/svg+xml")
    }

    @Test
    fun `注释里的尖括号不当作序言结束`() {
        // `<!--` 必须排在 `<!` 前面判：注释里的第一个 `>` 常常不是注释的结尾
        assertThat(sniffInlineImageMime(svgBytes("<!-- a > b -->"))).isEqualTo("image/svg+xml")
    }

    @Test
    fun `含有 svg 的 HTML 页面不是图片`() {
        // 松成「前 1KB 里出现过 <svg>」的话，一整个 HTML 页面就会被判成图片内联进导出件。
        // 序言之后必须**紧接着**是 <svg>
        val page = """<!DOCTYPE html><html><body><svg width="1"></svg></body></html>"""
        assertThat(sniffInlineImageMime(page.toByteArray())).isNull()
    }

    @Test
    fun `标签名后面必须是分隔符`() {
        assertThat(sniffInlineImageMime("<svgfoo/>".toByteArray())).isNull()
        assertThat(sniffInlineImageMime("<svg>".toByteArray())).isEqualTo("image/svg+xml")
        assertThat(sniffInlineImageMime("<svg/>".toByteArray())).isEqualTo("image/svg+xml")
        // 大小写不敏感：手写与部分导出工具会给 <SVG
        assertThat(sniffInlineImageMime("<SVG xmlns=\"x\"/>".toByteArray()))
            .isEqualTo("image/svg+xml")
    }

    @Test
    fun `序言节点有上界，不会被一串注释拖着走`() {
        val comment = "<!-- x -->"
        assertThat(sniffInlineImageMime(svgBytes(comment.repeat(7))))
            .isEqualTo("image/svg+xml")
        assertThat(sniffInlineImageMime(svgBytes(comment.repeat(8)))).isNull()
    }

    @Test
    fun `未闭合的序言判否而不是死循环`() {
        assertThat(sniffInlineImageMime("<?xml version=".toByteArray())).isNull()
        assertThat(sniffInlineImageMime("<!-- 没收尾".toByteArray())).isNull()
        assertThat(sniffInlineImageMime("<!DOCTYPE svg".toByteArray())).isNull()
        assertThat(sniffInlineImageMime("<".toByteArray())).isNull()
    }

    // ---- 非图片 ----

    @Test
    fun `认不出的字节一律返回 null`() {
        assertThat(sniffInlineImageMime(pdfBytes())).isNull()
        assertThat(sniffInlineImageMime(ByteArray(0))).isNull()
        assertThat(sniffInlineImageMime("ABC".toByteArray())).isNull()
        assertThat(sniffInlineImageMime("# 标题\n正文".toByteArray())).isNull()
        // SQLite 库头：正是那条 `![](file:///…/yumark.db)` 要挡住的东西
        assertThat(sniffInlineImageMime(asciiBytes("SQLite format 3 "))).isNull()
    }

    // ---- 硬契约：绝不抛异常 ----

    @Test
    fun `逐字节截断都不抛异常`() {
        val samples = listOf(
            pngBytes(), jpegBytes(), gifBytes(), bmpBytes(), webpVp8Bytes(), webpVp8xBytes(),
            isoBmffBytes("mif1", "avif"), icoBytes(count = 2), svgBytes("<!-- x -->"),
            pdfBytes(), asciiBytes("SQLite format 3 ")
        )
        for (s in samples) {
            for (len in 0..s.size) {
                // 抛出去就是这条用例失败：外层是导出的 runCatching，
                // 在这里抛一次会把「这张图没内联」升级成「整次导出失败」
                sniffInlineImageMime(s.copyOf(len))
            }
        }
        // 循环真的跑过（避免样本列表被改空之后这条用例静悄悄地变成空转）
        assertThat(samples.sumOf { it.size + 1 }).isGreaterThan(200)
    }

    @Test
    fun `随机字节不抛也不误判`() {
        val rnd = java.util.Random(20260904)
        repeat(500) {
            val b = ByteArray(1 + rnd.nextInt(64))
            rnd.nextBytes(b)
            // 只要求不抛。随机字节撞上某个真魔数的概率不为零，所以不断言必须为 null
            sniffInlineImageMime(b)
        }
    }
}
