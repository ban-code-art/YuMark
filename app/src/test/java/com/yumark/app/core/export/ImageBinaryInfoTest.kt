package com.yumark.app.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [sniffImageInfo] 的字节级用例：所有素材都在这里手工拼出来，不读任何资源文件。
 *
 * 为什么值得这么细：这段解析的两种错法在产物里都不报错。尺寸算错 → Word 里图片变形；
 * 格式认错 → 写进 `word/media/` 的扩展名与真实字节不符，Word 静默不画那张图。
 * 而且它**不许抛异常**——[DocxExporter.export] 整体是一个 `runCatching`，
 * 这里抛一次就从"这张图没嵌上"升级成"整篇文档导出失败"，所以截断/畸形输入必须返回 null。
 */
class ImageBinaryInfoTest {

    // 字节夹具全部来自 ImageByteFixtures.kt。这里从前有一整套私有的 be16/be32/le16/le32/
    // png/gif/bmp/webp/jpeg，与 DocxExporterTest 的那套已经漂开；共享后请不要再在类内加私有副本。

    // ---- 各格式识别 ----

    @Test
    fun `PNG 读出大端宽高`() {
        val info = sniffImageInfo(pngBytes(800, 600))
        assertThat(info).isNotNull()
        assertThat(info!!.mime).isEqualTo("image/png")
        assertThat(info.ext).isEqualTo("png")
        assertThat(info.widthPx).isEqualTo(800)
        assertThat(info.heightPx).isEqualTo(600)
    }

    @Test
    fun `magic 对但第一个 chunk 不是 IHDR 时返回 null`() {
        val bad = pngBytes(800, 600).copyOf()
        // 12..15 改成 sRGB：IHDR 必须是第一个 chunk，不是就说明这不是能信的 PNG 头
        asciiBytes("sRGB").forEachIndexed { i, b -> bad[12 + i] = b }
        assertThat(sniffImageInfo(bad)).isNull()
    }

    @Test
    fun `GIF 读出小端宽高`() {
        val info = sniffImageInfo(gifBytes(320, 240))
        assertThat(info!!.mime).isEqualTo("image/gif")
        assertThat(info.ext).isEqualTo("gif")
        assertThat(info.widthPx).isEqualTo(320)
        assertThat(info.heightPx).isEqualTo(240)
    }

    @Test
    fun `BMP BITMAPINFOHEADER 按 32 位读宽高`() {
        val info = sniffImageInfo(bmpBytes(1024, 768))
        assertThat(info!!.mime).isEqualTo("image/bmp")
        assertThat(info.ext).isEqualTo("bmp")
        assertThat(info.widthPx).isEqualTo(1024)
        assertThat(info.heightPx).isEqualTo(768)
    }

    @Test
    fun `BMP BITMAPCOREHEADER 按 16 位读宽高`() {
        // headerSize == 12 时宽高各只有 16 位，按 40 那套读会把调色板数据算进去
        val info = sniffImageInfo(bmpCoreBytes(64, 48))
        assertThat(info!!.widthPx).isEqualTo(64)
        assertThat(info.heightPx).isEqualTo(48)
    }

    @Test
    fun `BMP 负高度取绝对值`() {
        // 负高度 = 行序自上而下，是合法 BMP，尺寸本身仍是 50
        val info = sniffImageInfo(bmpBytes(100, -50))
        assertThat(info!!.widthPx).isEqualTo(100)
        assertThat(info.heightPx).isEqualTo(50)
    }

    @Test
    fun `BMP 高度为 Int MIN_VALUE 时返回 null`() {
        // abs(Int.MIN_VALUE) 是它自己，不挡掉就会带着负数走到 cx／cy 上去
        assertThat(sniffImageInfo(bmpBytes(100, Int.MIN_VALUE))).isNull()
    }

    @Test
    fun `BMP 宽度为负时返回 null`() {
        assertThat(sniffImageInfo(bmpBytes(-10, 50))).isNull()
    }

    // ---- JPEG：必须走段，不能按固定偏移取 ----

    @Test
    fun `JPEG 跳过 APP0 与 DHT 找到 SOF0`() {
        // FF C4 编号夹在 C0..CF 中间但不是 SOF；当成 SOF 会把霍夫曼表头当宽高读成 32639
        val bytes = jpegSofBytes(1920, 1080, jpegSegmentBytes(0xE0, 14), jpegSegmentBytes(0xDB, 65), jpegSegmentBytes(0xC4, 20))
        val info = sniffImageInfo(bytes)
        assertThat(info!!.mime).isEqualTo("image/jpeg")
        assertThat(info.ext).isEqualTo("jpg")
        assertThat(info.widthPx).isEqualTo(1920)
        assertThat(info.heightPx).isEqualTo(1080)
    }

    @Test
    fun `JPEG 的 FF C8 与 FF CC 也不是 SOF`() {
        val info = sniffImageInfo(jpegSofBytes(640, 480, jpegSegmentBytes(0xC8, 8), jpegSegmentBytes(0xCC, 8)))
        assertThat(info!!.widthPx).isEqualTo(640)
        assertThat(info.heightPx).isEqualTo(480)
    }

    @Test
    fun `JPEG 的 FF FF 填充只前进一个字节`() {
        // 按"读两字节长度"处理 FF FF 会一步跳到文件外，再也找不到 SOF
        val info = sniffImageInfo(jpegSofBytes(200, 100, byteArrayOf(0xFF.toByte(), 0xFF.toByte())))
        assertThat(info!!.widthPx).isEqualTo(200)
        assertThat(info.heightPx).isEqualTo(100)
    }

    @Test
    fun `JPEG 的无长度字段标记按两字节跳过`() {
        // FF 01(TEM) 与 FF D0..D7(RSTn) 没有长度字段；读"长度"会拿正文数据当长度跳出文件
        val info = sniffImageInfo(
            jpegSofBytes(300, 150, byteArrayOf(0xFF.toByte(), 0x01), byteArrayOf(0xFF.toByte(), 0xD3.toByte()))
        )
        assertThat(info!!.widthPx).isEqualTo(300)
        assertThat(info.heightPx).isEqualTo(150)
    }

    @Test
    fun `JPEG 到 SOS 仍没有 SOF 就返回 null`() {
        // SOS 之后是熵编码数据，里面的 FF xx 不是段标记，继续扫只会读出垃圾尺寸
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte()) +
            ByteArray(64) { 0x7F }
        assertThat(sniffImageInfo(bytes)).isNull()
    }

    @Test
    fun `JPEG 段长度小于 2 时返回 null`() {
        // 长度字段含自身那两个字节，小于 2 必是坏数据；不挡会导致 p 不前进而空转
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x01)
        assertThat(sniffImageInfo(bytes)).isNull()
    }

    // ---- WebP：三种码流都得认 ----

    @Test
    fun `WebP VP8 有损`() {
        val info = sniffImageInfo(webpVp8Bytes(640, 360))
        assertThat(info!!.mime).isEqualTo("image/webp")
        assertThat(info.ext).isEqualTo("webp")
        assertThat(info.widthPx).isEqualTo(640)
        assertThat(info.heightPx).isEqualTo(360)
    }

    @Test
    fun `WebP VP8L 无损`() {
        // 只认 VP8 的话，相册里随手一张无损 WebP 会被当成"不是 WebP"
        val info = sniffImageInfo(webpVp8lBytes(1000, 800))
        assertThat(info!!.mime).isEqualTo("image/webp")
        assertThat(info.widthPx).isEqualTo(1000)
        assertThat(info.heightPx).isEqualTo(800)
    }

    @Test
    fun `WebP VP8X 扩展格式读画布尺寸`() {
        // VP8X 存的是"画布宽-1"，不减一会整体差 1 像素
        val info = sniffImageInfo(webpVp8xBytes(2000, 1500))
        assertThat(info!!.widthPx).isEqualTo(2000)
        assertThat(info.heightPx).isEqualTo(1500)
    }

    @Test
    fun `WebP 未知 fourcc 返回 null`() {
        assertThat(sniffImageInfo(riffBytes("XXXX", ByteArray(16)))).isNull()
    }

    @Test
    fun `RIFF 但不是 WEBP 返回 null`() {
        // WAV 也是 RIFF：第 8 字节起不是 WEBP 就不能往下读码流
        val wav = asciiBytes("RIFF") + b32le(36) + asciiBytes("WAVE") + asciiBytes("fmt ") + b32le(16) + ByteArray(16)
        assertThat(sniffImageInfo(wav)).isNull()
    }

    // ---- 不许抛：截断、畸形、超界一律 null ----

    @Test
    fun `截断的 PNG 返回 null 而不抛异常`() {
        // 逐字节截：任何一处越界读都必须被边界检查挡住
        for (len in 0..pngBytes(800, 600).size) {
            val info = sniffImageInfo(pngBytes(800, 600).copyOf(len))
            if (len < 24) assertThat(info).isNull() else assertThat(info).isNotNull()
        }
    }

    @Test
    fun `逐字节截断所有格式都不抛异常`() {
        val samples = listOf(
            gifBytes(320, 240), bmpBytes(100, 50), bmpCoreBytes(64, 48),
            jpegSofBytes(640, 480, jpegSegmentBytes(0xE0, 14)), webpVp8Bytes(640, 360),
            webpVp8lBytes(1000, 800), webpVp8xBytes(2000, 1500)
        )
        for (sample in samples) {
            for (len in 0..sample.size) sniffImageInfo(sample.copyOf(len))
        }
    }

    @Test
    fun `空数组与未知 magic 返回 null`() {
        assertThat(sniffImageInfo(ByteArray(0))).isNull()
        assertThat(sniffImageInfo(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))).isNull()
        // PDF 也是"文档里可能出现的字节流"，但它不是图片
        assertThat(sniffImageInfo(asciiBytes("%PDF-1.7") + ByteArray(32))).isNull()
    }

    @Test
    fun `宽或高为 0 返回 null`() {
        // 0 会让 wp_extent 的 cx／cy 变成 0，Word 对此的反应是整篇报损坏
        assertThat(sniffImageInfo(pngBytes(0, 600))).isNull()
        assertThat(sniffImageInfo(pngBytes(800, 0))).isNull()
        assertThat(sniffImageInfo(gifBytes(0, 0))).isNull()
    }

    @Test
    fun `尺寸超过上限返回 null`() {
        // 10 万像素的边长只可能是把别的字段读成了宽高
        assertThat(sniffImageInfo(pngBytes(200_000, 100))).isNull()
        assertThat(sniffImageInfo(pngBytes(100, 200_000))).isNull()
        assertThat(sniffImageInfo(bmpBytes(999_999, 10))).isNull()
    }

    @Test
    fun `mime 与 ext 一一对应`() {
        // 这层配对是 DocxExporter 的白名单判定（按 mime）和落盘名（按 ext）之间的唯一契约：
        // 一旦某个分支把 ext 写成与 mime 不符的值，Word 就会静默不画那张图。
        val pairs = listOf(
            pngBytes(10, 10) to ("image/png" to "png"),
            jpegSofBytes(10, 10) to ("image/jpeg" to "jpg"),
            gifBytes(10, 10) to ("image/gif" to "gif"),
            bmpBytes(10, 10) to ("image/bmp" to "bmp"),
            webpVp8Bytes(10, 10) to ("image/webp" to "webp")
        )
        for ((bytes, expected) in pairs) {
            val info = sniffImageInfo(bytes)
            assertThat(info).isNotNull()
            assertThat(info!!.mime to info.ext).isEqualTo(expected)
        }
    }

    @Test
    fun `扩展名一律由字节判定而非文件名`() {
        // 正文里的 a.jpg 完全可能装着 PNG 字节：按字节判就是 png，按名字判会写出坏部件
        val info = sniffImageInfo(pngBytes(16, 16))
        assertThat(info!!.ext).isEqualTo("png")
    }
}
