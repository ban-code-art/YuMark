package com.yumark.app.core.export

import android.content.Context
import com.yumark.app.core.util.PathSafety
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 读取本地图片字节，供导出侧内联成 base64 data URI。
 *
 * 单独抽一个类而不是让每个导出器各写一份：这个函数同时是**大小闸门**、**scheme 白名单**和
 * **目录白名单**。三样里只要有一份实现漏了一样，就会有一条导出路径能拿一张 20MB 的原图把
 * HTML 撑到 27MB、把 `http://` 当本地文件去读、或者把应用私有目录里的任意文件读出来。
 * 安全与资源约束有两份实现，实际强度就等于更弱的那一份。
 *
 * 格式判定**不在这里**：它只需要字节、不需要 Context，放在纯逻辑侧的
 * [sniffInlineImageMime]（由 [inlineOne] 调用）才能进 JVM 单测。这里只管「能不能读这个位置」，
 * 那边只管「读出来的是不是图片」，两件事各一份实现。
 *
 * 现在的两个使用方：[WebViewDocumentRenderer]（PDF / 长图 / 富 HTML）与 [HtmlExporter]（纯 HTML）。
 */
@Singleton
class LocalImageBytes @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 读取 `file://` / `content://` / 裸绝对路径指向的图片字节，失败、超限或位置不允许返回 null。
     *
     * 单张上限 [MAX_INLINE_IMAGE_BYTES]：data URI 会整张塞进 HTML 文本，一张 20MB 的原图
     * base64 之后接近 27MB，既撑爆分享通道也让浏览器打不开——超限就返回 null 让调用方保留
     * 原 URI（裂图但文件可用），而不是把整次导出拖垮。
     *
     * `content://` 不预检大小：SAF 的 size 列不是所有提供器都给，只能边读边数（[readCapped]），
     * 到线就停手；`file://` 能先看 [File.length]，省掉把超大文件整个读进内存这一步。
     */
    fun read(url: String): ByteArray? = runCatching {
        val uri = url.toUri()
        when (uri.scheme?.lowercase()) {
            "content" -> context.contentResolver.openInputStream(uri)?.use { readCapped(it) }
            "file", null -> readContainedFile(uri.path)
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * 读裸路径 / `file://` 指向的文件，且**只允许**应用自己那两个图片目录。
     *
     * ### 为什么必须有这道目录白名单
     * 正文里的图片引用不全是应用自己写进去的：导入的 `.md`、WebDAV 同步下来的正文、
     * AI Agent 改写出的正文都能带任意 `![](…)`。少了这道校验，一句
     * `![](file:///data/user/0/com.yumark.app/databases/yumark.db)` 就会被读出来 base64 内联进
     * 导出的 HTML，再随 `ACTION_SEND` 发出去——那个库和 `datastore` 目录下的 `.preferences_pb`
     * 都是明文。
     * （API Key 与 WebDAV 密码在 `EncryptedSharedPreferences` 里是密文，但文档标题、正文路径、
     * 同步地址这些一样不该跟着一张「图片」走出去。）
     *
     * 收窄到这两个目录不损失任何**能读到的**引用：应用只声明了 `INTERNET` 与
     * `REQUEST_INSTALL_PACKAGES`（见 `AndroidManifest.xml:4-5`），没有任何存储权限，所以
     * `/sdcard/x.png` 这类共享存储路径本来就读不到（EACCES → runCatching → null）；
     * 外部工作区文档的图片走的是 `content://` 分支（SAF 授权），不经过这里。真正能读到的位置
     * 全部落在应用私有目录内，而其中只有 `images/` 与 `import_assets/` 装的是图片。
     *
     * 用 [PathSafety.requireInside] 而不是字符串前缀比较：段级比较才挡得住
     * `…/files/images-backup/` 这种同级目录，canonical 化才挡得住符号链接与 `..`。
     * 任一条根校验失败（含 canonicalFile 抛 IOException）都当「不允许」——校验器出错时
     * 沉默放行是最坏的选择。
     */
    private fun readContainedFile(path: String?): ByteArray? {
        if (path == null) return null
        val f = File(path)
        if (!f.isFile || f.length() > MAX_INLINE_IMAGE_BYTES) return null
        val allowed = allowedImageRoots().any { root ->
            runCatching { PathSafety.requireInside(f, root, "Inline image") }.isSuccess
        }
        return if (allowed) f.readBytes() else null
    }

    /**
     * 允许内联的两个目录：工具栏「从相册选择」落盘的 `images/`，与导入库镜像 `import_assets/`。
     *
     * 目录名在这里再写一份、不引用 `FileManager`：`core` 层不依赖 `data` 层（反过来是允许的，
     * `FileManager` 自己就 import 了 `core.export.ExportRetention`）。这份重复是可控的——
     * 名字一旦漂了，后果是「少认一个目录」，图片保持原引用（裂图，文件仍可用），
     * 属于 fail-closed 的那一侧；而 [PathSafety] 当初被抽成单一实现，是因为那份重复
     * 漂了会 fail-open。改 `FileManager.kt:27` / `:31` 的目录名时要一并改这里。
     *
     * 另外三个私有目录刻意不在名单里：`documents/` 装的是 `.md` 正文，`exports/` 装的是上一次
     * 的导出件，`ai_attachments/` 装的是喂给模型的附件——三者都没有任何解析器会把正文里的
     * 相对引用指过去（见 `EditorViewModel.appImagesPrefix` 与 `importLibraryImageResolver`），
     * 放进来只会扩大可读面。
     */
    private fun allowedImageRoots(): List<File> = listOf(
        File(context.filesDir, "images"),
        File(context.filesDir, "import_assets")
    )

    /**
     * 边读边数，超过 [MAX_INLINE_IMAGE_BYTES] 立刻停手并返回 null。
     *
     * 从前 `content://` 分支是 `it.readBytes()` 后再比长度，那个顺序让上限**根本不起作用**：
     * 一个 SAF 提供器给出的 500MB 流会先被整个读进内存，OOM 抛在 `readBytes()` 里，被外层
     * `runCatching` 吞成一个静默的 null——用户看到的是「导出成功但图是裂的」，或者进程直接没了。
     * 现在最多多读一个缓冲区就返回。
     */
    private fun readCapped(input: InputStream): ByteArray? {
        val limit = MAX_INLINE_IMAGE_BYTES
        val out = java.io.ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        /** 单张内联上限。6MB 原图 base64 后约 8MB，仍在「能发出去、能打开」的范围内。 */
        const val MAX_INLINE_IMAGE_BYTES = 6L * 1024 * 1024
    }
}
