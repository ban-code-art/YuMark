package com.yumark.app.core.export

/**
 * 导出侧图片测试共用的字节夹具：手搓「最小的合法文件头」。
 *
 * 这里是**唯一**一份。曾经 `ImageBinaryInfoTest` 与 `DocxExporterTest` 各带一套私有的
 * `png`/`gif`/`be32`/`le16`，两份已经开始漂（前者多 bmpCore/vp8l/段扫描，后者只有 png/gif；
 * VP8 frame tag 一边写 `0x10` 一边写 `0x00`）。三份的后果不是「多写几行」，而是同一个格式在不同
 * 测试里长得不一样，改嗅探器时只有一部分用例会响。三个消费者（[sniffImageInfo] 的字节级用例、
 * DOCX 嵌图用例、内联 MIME 用例）现在都从这里取。
 *
 * 名字刻意都带 `Bytes` 后缀：调用方从前用的是 `png`/`gif` 这种私有成员名，而**成员会静悄悄遮蔽
 * 顶层函数**——留着同名就等于给下一个人埋一个「明明改了共享夹具却没生效」的坑。
 *
 * 「最小」是有意的：字节只多到 [sniffImageInfo] 能读出宽高为止，不追求真能被解码器打开。
 * 这些夹具的用途是钉「格式判定与边界检查」，不是钉解码。宽高一律可以传负数/极值，
 * 越界与畸形分支正是要靠它们钉住。
 */

// ---- 定长整数 ----

internal fun b16be(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

internal fun b32be(v: Int) = byteArrayOf(
    (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
)

internal fun b16le(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

internal fun b24le(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte())

internal fun b32le(v: Int) = byteArrayOf(
    v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()
)

internal fun asciiBytes(s: String) = s.toByteArray(Charsets.US_ASCII)

// ---- 五种「读得出尺寸」的格式（对应 sniffImageInfo） ----

/** PNG：8B 魔数 + IHDR chunk（长度/类型/宽/高必须紧挨着，嗅探器按固定偏移读 12/16/20）。 */
internal fun pngBytes(w: Int = 2, h: Int = 2): ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        b32be(13) + asciiBytes("IHDR") + b32be(w) + b32be(h) +
        byteArrayOf(8, 6, 0, 0, 0)

/** GIF89a：逻辑屏尺寸小端在 6..9，后面三字节是全局色表描述。 */
internal fun gifBytes(w: Int = 2, h: Int = 2): ByteArray =
    asciiBytes("GIF89a") + b16le(w) + b16le(h) + byteArrayOf(0x70, 0, 0)

/**
 * JPEG：SOI 之后直接给一个 SOF0。
 *
 * 段长 11 = 长度(2) + 精度(1) + 高(2) + 宽(2) + 分量数(1) + 单个分量(3)，与真实 SOF0 自洽；
 * 嗅探器在 SOF 处就 return，后面不需要 DQT/DHT/SOS。
 */
internal fun jpegBytes(w: Int = 2, h: Int = 2): ByteArray =
    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte()) +
        b16be(11) + byteArrayOf(8) + b16be(h) + b16be(w) + byteArrayOf(1, 1, 0x11, 0)

/** BMP + BITMAPINFOHEADER（头大小 40 → 宽高是 32 位有符号，在 18..25）。 */
internal fun bmpBytes(w: Int = 2, h: Int = 2): ByteArray =
    asciiBytes("BM") + b32le(0) + b32le(0) + b32le(54) +
        b32le(40) + b32le(w) + b32le(h) + b16le(1) + b16le(24)

/**
 * BMP + BITMAPCOREHEADER（头大小 12 → 宽高各只有 16 位，紧跟在头大小之后）。
 *
 * 古早格式，但仍是合法 BMP：按 40 那套偏移去读会把调色板数据当成宽高。
 */
internal fun bmpCoreBytes(w: Int = 2, h: Int = 2): ByteArray =
    asciiBytes("BM") + b32le(0) + b32le(0) + b32le(26) +
        b32le(12) + b16le(w) + b16le(h) + b16le(1) + b16le(24)

/** `RIFF[大小]WEBP[fourcc][块大小][载荷]`，载荷从偏移 20 起。 */
internal fun riffBytes(fourcc: String, payload: ByteArray): ByteArray =
    asciiBytes("RIFF") + b32le(4 + 8 + payload.size) + asciiBytes("WEBP") +
        asciiBytes(fourcc) + b32le(payload.size) + payload

/**
 * WebP 有损：3B frame tag、`9D 01 2A` sync code、14 位宽高（这里不塞满 14 位，直接小端写）。
 *
 * frame tag 首字节取 `0x10`（frame type = key frame、version 0、**show_frame = 1**）而不是 0：
 * 静态 WebP 的那一帧一定是要显示的，`show_frame = 0` 的样本现实中不存在。嗅探器只看 sync code
 * 与其后的宽高，对这个字节不敏感——但夹具本身自洽，才不会让人以为 0 有什么讲究。
 */
internal fun webpVp8Bytes(w: Int = 2, h: Int = 2): ByteArray = riffBytes(
    "VP8 ",
    byteArrayOf(0x10, 0, 0) + byteArrayOf(0x9D.toByte(), 0x01, 0x2A) + b16le(w) + b16le(h)
)

/**
 * WebP 无损：签名 `0x2F` + 一个 32 位小端里打包的 `width-1` / `height-1`（各 14 位）。
 *
 * 只认 VP8 不认 VP8L 的嗅探器会把相册里随手一张无损 WebP 判成「不是 WebP」。
 */
internal fun webpVp8lBytes(w: Int = 2, h: Int = 2): ByteArray = riffBytes(
    "VP8L",
    byteArrayOf(0x2F) + b32le((w - 1) or ((h - 1) shl 14))
)

/**
 * WebP 扩展：flags(1) + 保留(3) + 24 位「画布宽-1」「画布高-1」。
 *
 * flags 给 0：这个样本没有 ALPH / ICCP / EXIF 任何附属块，置上对应的位就是自相矛盾的字节流。
 */
internal fun webpVp8xBytes(w: Int = 2, h: Int = 2): ByteArray = riffBytes(
    "VP8X",
    byteArrayOf(0, 0, 0, 0) + b24le(w - 1) + b24le(h - 1)
)

// ---- JPEG 的段级夹具：这个格式必须走段扫描，不能按固定偏移取 ----

/**
 * 一个带长度字段的 JPEG 段，填充字节一律 `0x7F`。
 *
 * 选 `0x7F` 是为了让「把这一段误当成 SOF」这种错误暴露出来：那样读出来的会是 0x7F7F = 32639，
 * 与用例里的真实宽高相差极远，断言一定会红；填 0 则可能与「宽高为 0 返回 null」混在一起。
 */
internal fun jpegSegmentBytes(marker: Int, payloadSize: Int): ByteArray =
    byteArrayOf(0xFF.toByte(), marker.toByte()) + b16be(payloadSize + 2) +
        ByteArray(payloadSize) { 0x7F }

/**
 * `FF D8` + [before] 里的任意前置字节 + 一个三分量 SOF0（高在 +5、宽在 +7）+ SOS。
 *
 * 与 [jpegBytes] 的区别：那个是「SOI 后紧接 SOF0」的最小样本，只够钉格式判定；
 * 这个用来钉**段扫描**——APP0/DQT/DHT、`FF FF` 填充、无长度字段的 TEM/RSTn 都从 [before] 塞进去。
 */
internal fun jpegSofBytes(w: Int = 2, h: Int = 2, vararg before: ByteArray): ByteArray {
    var out = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    before.forEach { out += it }
    out += byteArrayOf(0xFF.toByte(), 0xC0.toByte()) + b16be(17) + byteArrayOf(8) +
        b16be(h) + b16be(w) + byteArrayOf(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
    out += byteArrayOf(0xFF.toByte(), 0xDA.toByte())
    return out
}

// ---- 只判格式、读不出尺寸的那几种（对应 sniffInlineImageMime 自己那一层） ----

/**
 * ISO-BMFF（HEIC / HEIF / AVIF）：`[box 大小 4B]["ftyp"][主 brand][次版本][兼容 brand…]`。
 *
 * box 大小写成真实长度，让 [isoBmffBytes] 造出来的样本落在「声明与实际一致」这条正路上；
 * 声明值撒谎的样本由用例自己拼，那是另一条要单独钉的边界。
 *
 * @param major 主 brand，必须 4 个 ASCII 字符
 * @param compatible 兼容 brand 列表，从偏移 16 起每 4 字节一个
 */
internal fun isoBmffBytes(major: String, vararg compatible: String): ByteArray =
    b32be(16 + 4 * compatible.size) + asciiBytes("ftyp") + asciiBytes(major) + b32be(0) +
        compatible.fold(ByteArray(0)) { acc, brand -> acc + asciiBytes(brand) }

/**
 * ICO：`00 00 01 00` + 小端图像数目(2B) + 每 16 字节一条目录项。
 *
 * [count] 与 [entries] 分开，正是为了造出「头里声明 3 张、实际一条目录项都装不下」这种
 * 撒谎样本 —— 嗅探器要靠字节数装得下声明值来排除「碰巧前四字节相同」的非图片文件。
 */
internal fun icoBytes(count: Int = 1, entries: Int = count): ByteArray =
    byteArrayOf(0, 0, 1, 0) + b16le(count) + ByteArray(16 * entries)

/** SVG：唯一的文本格式。[prolog] 用来在 `<svg` 之前插 BOM / XML 声明 / 注释 / DOCTYPE。 */
internal fun svgBytes(prolog: String = ""): ByteArray =
    (prolog + """<svg xmlns="http://www.w3.org/2000/svg" width="2" height="2"/>""")
        .toByteArray(Charsets.UTF_8)

// ---- 非图片：所有「必须被拒」的用例都用它 ----

/** PDF 头。选它当反例是因为它真会出现在正文引用里（用户把 `.pdf` 写进 `![](…)`）。 */
internal fun pdfBytes(): ByteArray = asciiBytes("%PDF-1.7\n%%EOF")
