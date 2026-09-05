package com.yumark.app.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * `ImageRepositoryImpl` 里 MIME → 落盘扩展名那张表的单测。
 *
 * 锁的是一条不变量：**落盘字节的真实格式必须与文件扩展名一致**。从前 webp/gif 源图会落成
 * `x.webp` / `x.gif` 而内容是 JPEG 字节，那时导出侧只按扩展名声明 MIME，于是 HTML/PDF 里出现
 * `data:image/webp;base64,<JPEG 字节>`。浏览器靠自己嗅探照样显示，所以这条不变量在运行时没有
 * 任何报错能提示它被破坏 —— 只能靠这里的断言拦住。
 *
 * 导出侧现在改成按字节判定（`sniffInlineImageMime` / `sniffImageInfo`），谎报 MIME 那个后果
 * 已经不成立；不变量本身照旧要守：扩展名是文件离开应用之后唯一还在的类型信息，而字节与名字
 * 一旦允许分家，下一处「按名字做事」的代码就又会踩空。
 *
 * 只测纯函数：`Bitmap.CompressFormat` 在 JVM 单测里是 android.jar 的空壳
 * （`unitTests.isReturnDefaultValues = true`，取到 null），格式那一半在 [ImageEncoding] 里
 * 刻意没有出现，改成用扩展名代表它。
 */
class ImageExtensionTest {

    @Test
    fun `png 源图重编码与原样复制都是 png`() {
        val e = imageEncodingForMime("image/png")
        assertThat(e.reencodeExtension).isEqualTo("png")
        assertThat(e.copyExtension).isEqualTo("png")
    }

    @Test
    fun `webp 源图重编码仍是 webp，不再退化成 jpg`() {
        // 旧实现：扩展名 webp + CompressFormat.JPEG。现在两条路都必须是 webp
        val e = imageEncodingForMime("image/webp")
        assertThat(e.reencodeExtension).isEqualTo("webp")
        assertThat(e.copyExtension).isEqualTo("webp")
    }

    @Test
    fun `gif 重编码降级成 png 而不是留着 gif 后缀`() {
        // Bitmap 编不出 GIF；降级到 JPEG 会把透明区填成黑块，所以是 PNG。
        // 动图要靠 copyExtension 那条路（原样复制源字节）才保得住多帧。
        val e = imageEncodingForMime("image/gif")
        assertThat(e.reencodeExtension).isEqualTo("png")
        assertThat(e.copyExtension).isEqualTo("gif")
    }

    @Test
    fun `jpeg 源图两条路都是 jpg`() {
        val e = imageEncodingForMime("image/jpeg")
        assertThat(e.reencodeExtension).isEqualTo("jpg")
        assertThat(e.copyExtension).isEqualTo("jpg")
    }

    @Test
    fun `MIME 为 null 时重编码成 jpg 且禁止原样复制`() {
        // getType 拿不到类型时说不出可信的扩展名，复制原字节只能瞎起名，
        // 那就是在换个入口重犯「扩展名与字节不一致」
        val e = imageEncodingForMime(null)
        assertThat(e.reencodeExtension).isEqualTo("jpg")
        assertThat(e.copyExtension).isNull()
    }

    @Test
    fun `认不出的 MIME 与 null 同一条路`() {
        for (mime in listOf("image/heic", "image/avif", "image/bmp", "application/pdf", "")) {
            val e = imageEncodingForMime(mime)
            assertThat(e.reencodeExtension).isEqualTo("jpg")
            assertThat(e.copyExtension).isNull()
        }
    }

    @Test
    fun `MIME 大小写不敏感`() {
        // 部分 provider 报 IMAGE/PNG；不归一化的话 png 会被当成认不出的类型转成 jpg，
        // 白丢一次无损和透明通道
        assertThat(imageEncodingForMime("IMAGE/PNG").reencodeExtension).isEqualTo("png")
        assertThat(imageEncodingForMime("Image/Gif").copyExtension).isEqualTo("gif")
    }

    @Test
    fun `每个方案的落盘格式都在导出侧认得的集合里`() {
        // 导出侧按字节判定：`sniffInlineImageMime` 认不出的字节一律**不内联**，图片保留原引用，
        // 到了导出的 HTML / PDF 里就是一张裂图（fail-closed，没有任何报错）。这里钉的是落盘
        // **格式**必须落在两个嗅探器都认得的范围内 —— 格式在 [ImageEncoding] 里没有出现，
        // 由扩展名代表它（见类 KDoc）。
        //
        // 集合刻意只列这三个而不是把嗅探器认得的全列上（PNG/JPEG/GIF/BMP/WebP +
        // HEIC/HEIF/AVIF/ICO/SVG）：`Bitmap.compress` 写得出的只有 PNG / JPEG / WebP，
        // 重编码那条路不可能产出别的。哪天有人往枚举里加一个 `reencodeExtension = "heic"`，
        // 落盘字节其实还是 JPEG（`compressFormatOf` 的 else 分支），这条断言就会响。
        val knownToExporter = setOf("png", "jpg", "webp")
        for (e in ImageEncoding.entries) {
            assertThat(knownToExporter).contains(e.reencodeExtension)
            e.copyExtension?.let { assertThat(knownToExporter + "gif").contains(it) }
        }
    }
}
