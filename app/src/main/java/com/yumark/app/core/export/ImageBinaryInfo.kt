package com.yumark.app.core.export

/**
 * 图片字节的格式与像素尺寸嗅探：纯 `ByteArray` 进，数据类出，签名与返回值都不碰 `android.*`。
 *
 * **为什么不用 `BitmapFactory.decodeByteArray(bytes, 0, size, Options(inJustDecodeBounds = true))`：**
 * 本项目单元测试开着 `unitTests.isReturnDefaultValues = true`，android.jar 的桩方法一律返回默认值，
 * 对象型静态方法返回 **null**，紧跟着的成员访问就是 NPE。于是这条最不能写错的解析
 * （尺寸算错 → Word 里图片变形；格式认错 → 写进 .docx 的扩展名与真实字节不符，Word 拒绘）
 * 在 JVM 上一条用例都跑不了。所以这里自己读文件头。
 *
 * **格式一律由字节判定，绝不由文件名/扩展名推断**——正文里的 `.jpg` 完全可能装着 PNG 字节，
 * 这与 `ImageRepositoryImpl` 那边 `ImageEncoding` 要解决的是同一类问题。
 *
 * **不抛异常是硬契约：** [DocxExporter.export] 整体裹在一个 `runCatching` 里，这里抛一次的后果
 * 不是"这张图没嵌上"，而是"整篇文档导出失败"。每处读取都先查边界，最外层再兜一层
 * `runCatching` —— 那是兜底，不是机制。
 */
internal data class ImageBinaryInfo(
    /** 标准 MIME，用于 `[Content_Types].xml` 的 `<Default>` 声明与格式白名单判定。 */
    val mime: String,
    /** `word/media/imageN.<ext>` 用的扩展名，与 [mime] 一一对应。 */
    val ext: String,
    val widthPx: Int,
    val heightPx: Int
)

/**
 * 认出 PNG / JPEG / GIF / BMP / WebP 并读出像素尺寸；认不出、读不全、尺寸不合理都返回 null。
 *
 * 尺寸合理性在这里统一闸一次（`1..MAX_DIM`），各分支便不必各查一遍：宽或高为 0 的图
 * 会让 `<wp:extent cx="0"/>` 进文档，Word 对此的反应是整篇报损坏。
 */
internal fun sniffImageInfo(bytes: ByteArray): ImageBinaryInfo? = runCatching {
    when {
        startsWith(bytes, PNG_MAGIC) -> sniffPng(bytes)
        startsWith(bytes, JPEG_MAGIC) -> sniffJpeg(bytes)
        startsWith(bytes, GIF_MAGIC) -> sniffGif(bytes)
        startsWith(bytes, BMP_MAGIC) -> sniffBmp(bytes)
        startsWith(bytes, RIFF_MAGIC) && startsWith(bytes, WEBP_MAGIC, at = 8) -> sniffWebp(bytes)
        else -> null
    }?.takeIf { it.widthPx in 1..MAX_DIM && it.heightPx in 1..MAX_DIM }
}.getOrNull()

/** PNG：IHDR 必须是第一个 chunk，长度在 8..11、类型在 12..15、宽高大端紧随其后。 */
private fun sniffPng(b: ByteArray): ImageBinaryInfo? {
    if (!startsWith(b, IHDR_TYPE, at = 12)) return null
    val w = beI32(b, 16) ?: return null
    val h = beI32(b, 20) ?: return null
    return ImageBinaryInfo("image/png", "png", w, h)
}

/** GIF：`GIF87a`/`GIF89a` 共用的逻辑屏尺寸，小端，在 6..9。 */
private fun sniffGif(b: ByteArray): ImageBinaryInfo? {
    val w = leU16(b, 6) ?: return null
    val h = leU16(b, 8) ?: return null
    return ImageBinaryInfo("image/gif", "gif", w, h)
}

/**
 * BMP：DIB 头大小在 14..17，它决定宽高字段有多宽。
 *
 * `headerSize == 12` 是古早的 `BITMAPCOREHEADER`，宽高各 16 位；其余（40 的
 * `BITMAPINFOHEADER` 及各代扩展）都是 32 位有符号。按 40 一把梭会把 CORE 版的宽高读成
 * 一个混了调色板数据的巨大数字。
 */
private fun sniffBmp(b: ByteArray): ImageBinaryInfo? {
    val headerSize = leI32(b, 14) ?: return null
    val w: Int
    val h: Int
    if (headerSize == 12) {
        w = leU16(b, 18) ?: return null
        h = leU16(b, 20) ?: return null
    } else {
        w = leI32(b, 18) ?: return null
        h = leI32(b, 22) ?: return null
    }
    // 高度为负 = 行序自上而下（顶行在前），尺寸本身取绝对值；宽度为负则是坏文件。
    // Int.MIN_VALUE 单独挡掉：abs 对它是恒等的，会带着负数往下走。
    if (h == Int.MIN_VALUE) return null
    return ImageBinaryInfo("image/bmp", "bmp", w, if (h < 0) -h else h)
}

/**
 * JPEG：尺寸只在 SOF 段里，而 SOF 前面可以有任意多段 APPn/DQT/DHT/COM，所以必须**走段**，
 * 不能按固定偏移取。
 *
 * 三类容易写错的段：
 * - **无长度字段**的 `FF 01`（TEM）与 `FF D0..D7`（RSTn）—— 按"读两字节长度"处理会拿正文
 *   数据当长度，一步跳到文件外；
 * - `FF FF` 是**填充**，只能前进一个字节（下一个 `FF` 可能就是真标记的引导）；
 * - `FF C4`（DHT）/`FF C8`（JPG）/`FF CC`（DAC）**不是 SOF**，虽然编号夹在 C0..CF 中间。
 *   把它们当 SOF 会把霍夫曼表的头几个字节读成宽高。
 *
 * 到 SOS(`FF DA`) 或 EOI(`FF D9`) 就停：之后是熵编码数据，里面的 `FF xx` 不是段标记。
 */
private fun sniffJpeg(b: ByteArray): ImageBinaryInfo? {
    var p = 2
    while (p + 1 < b.size) {
        if (b.u(p) != 0xFF) { p++; continue }
        val marker = b.u(p + 1)
        when {
            marker == 0xFF -> p++
            marker == 0xDA || marker == 0xD9 -> return null
            marker == 0x01 || marker == 0xD8 || marker in 0xD0..0xD7 -> p += 2
            marker in SOF_MARKERS -> {
                // 段内布局：长度(2) 精度(1) 高(2) 宽(2)，即相对标记起点 +5 是高、+7 是宽。
                val h = beU16(b, p + 5) ?: return null
                val w = beU16(b, p + 7) ?: return null
                return ImageBinaryInfo("image/jpeg", "jpg", w, h)
            }
            else -> {
                val len = beU16(b, p + 2) ?: return null
                if (len < 2) return null   // 长度含自身那两个字节，< 2 必是坏数据
                p += 2 + len
            }
        }
    }
    return null
}

/**
 * WebP：`RIFF....WEBP` 之后第 12 字节起的 fourcc 决定按哪种码流读尺寸。
 *
 * 三种都得认。只认 `VP8 ` 的话，相册里随手一张 VP8L（无损）图会被当成"不是 WebP"，
 * 落到 `else -> null` 上，最终连"这是 WebP，换文字占位"这个正确结论都得不出。
 *
 * 认出来之后 [DocxExporter] 仍会把 WebP 送去文字占位：Word 2016 及更早没有 WebP 解码器，
 * 一个红叉框比一行文字更糟。嗅探它的意义在于**确定地**知道这是 WebP，而不是信文件名。
 */
private fun sniffWebp(b: ByteArray): ImageBinaryInfo? {
    val w: Int
    val h: Int
    when {
        startsWith(b, VP8_LOSSY, at = 12) -> {
            // VP8 关键帧：3 字节 frame tag、3 字节 sync code 9D 01 2A，然后 14 位宽、14 位高
            if (!startsWith(b, VP8_SYNC, at = 23)) return null
            w = (leU16(b, 26) ?: return null) and 0x3FFF
            h = (leU16(b, 28) ?: return null) and 0x3FFF
        }
        startsWith(b, VP8_LOSSLESS, at = 12) -> {
            // VP8L：签名字节 0x2F，随后 32 位小端里低 14 位是 width-1、次 14 位是 height-1
            if (b.size <= 20 || b.u(20) != 0x2F) return null
            val bits = leI32(b, 21) ?: return null
            w = (bits and 0x3FFF) + 1
            h = ((bits ushr 14) and 0x3FFF) + 1
        }
        startsWith(b, VP8_EXTENDED, at = 12) -> {
            // VP8X：24 位画布宽-1 在 24..26，高-1 在 27..29
            w = (leU24(b, 24) ?: return null) + 1
            h = (leU24(b, 27) ?: return null) + 1
        }
        else -> return null
    }
    return ImageBinaryInfo("image/webp", "webp", w, h)
}

// ---- 定长读取：越界一律返回 null，绝不抛 ----

private fun startsWith(bytes: ByteArray, magic: ByteArray, at: Int = 0): Boolean {
    if (at < 0 || bytes.size < at + magic.size) return false
    for (i in magic.indices) if (bytes[at + i] != magic[i]) return false
    return true
}

private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

private fun beU16(b: ByteArray, i: Int): Int? =
    if (i < 0 || i + 1 >= b.size) null else (b.u(i) shl 8) or b.u(i + 1)

private fun beI32(b: ByteArray, i: Int): Int? =
    if (i < 0 || i + 3 >= b.size) null
    else (b.u(i) shl 24) or (b.u(i + 1) shl 16) or (b.u(i + 2) shl 8) or b.u(i + 3)

private fun leU16(b: ByteArray, i: Int): Int? =
    if (i < 0 || i + 1 >= b.size) null else (b.u(i + 1) shl 8) or b.u(i)

private fun leU24(b: ByteArray, i: Int): Int? =
    if (i < 0 || i + 2 >= b.size) null else (b.u(i + 2) shl 16) or (b.u(i + 1) shl 8) or b.u(i)

private fun leI32(b: ByteArray, i: Int): Int? =
    if (i < 0 || i + 3 >= b.size) null
    else (b.u(i + 3) shl 24) or (b.u(i + 2) shl 16) or (b.u(i + 1) shl 8) or b.u(i)

private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val IHDR_TYPE = "IHDR".toByteArray(Charsets.US_ASCII)
private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
private val GIF_MAGIC = "GIF8".toByteArray(Charsets.US_ASCII)
private val BMP_MAGIC = "BM".toByteArray(Charsets.US_ASCII)
private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
private val WEBP_MAGIC = "WEBP".toByteArray(Charsets.US_ASCII)
private val VP8_LOSSY = "VP8 ".toByteArray(Charsets.US_ASCII)
private val VP8_LOSSLESS = "VP8L".toByteArray(Charsets.US_ASCII)
private val VP8_EXTENDED = "VP8X".toByteArray(Charsets.US_ASCII)
private val VP8_SYNC = byteArrayOf(0x9D.toByte(), 0x01, 0x2A)

/** SOF0/1/2/3/5/6/7/9/10/11/13/14/15。刻意不含 C4(DHT)、C8(JPG)、CC(DAC)。 */
private val SOF_MARKERS = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)

/** 尺寸上限：再大都是把别的字段读成了宽高。10 万像素的边长本身已远超任何真实图片。 */
private const val MAX_DIM = 100_000



