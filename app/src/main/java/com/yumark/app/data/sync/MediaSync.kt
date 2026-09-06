package com.yumark.app.data.sync

import android.graphics.BitmapFactory
import android.util.Log
import com.yumark.app.core.util.PathSafety
import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.db.dao.ImageDao
import com.yumark.app.data.local.db.entity.ImageEntity
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.data.remote.webdav.WebDavClient
import com.yumark.app.domain.model.RemoteEntry
import com.yumark.app.domain.model.WebDavConfig
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 图片/附件的 WebDAV 同步（`_media/` 子目录通道）。
 *
 * **为什么是独立通道而不是并进文档同步**：文档同步的决策核心（[SyncPlanner]）按文件名
 * 配对、以 ETag/哈希记账，一条通道只伺候一种不变量才立得住。图片与文档的不变量完全
 * 不同——文件名是不可变的内容寻址（`<uuid>.<ext>`）、没有「远端被改」这种状态、
 * 不需要冲突语义——硬塞进同一条通道等于让规划器同时伺候两套不变量。
 * 分开后文档同步的过滤器（只认 `.md`）天然忽略 `_media/` 条目，零改动互不干扰。
 *
 * 两个方向：
 * - **推送**（[pushLocalImages]）：把本地库里有、远端没有的图片传上去。与文档同步的
 *   P1 范围对齐——只推**根级文档**的图；文件夹文档的图等文件夹同步立项时一起收编。
 * - **拉取**（[pullImagesFor]）：一篇文档从远端落成本地（新建/覆盖/冲突副本）时，按
 *   正文里的 `images/<fileName>` 引用逐张拉取并登记 `images` 表。不做全量预下载：
   * 引用不到的远端图不拉（多设备场景下那是别人文档的图，先拉回来只会变成孤儿文件）。
 *
 * 刻意**不做远端清理**：本机把文档彻底删除后，远端 `_media/` 里的图会留成孤儿。
 * 原因是多设备安全——「本机 images 表里没有」推不出「其他设备也不要」，贸然按本地
 * 名单清理会删掉别的设备刚上传的图。孤儿只占服务器空间不占本地，等文件夹/媒体
 * 同步进入 P2 再引入带 tombstone 的清理。
 */
@Singleton
class MediaSync @Inject constructor(
    private val documentDao: DocumentDao,
    private val imageDao: ImageDao,
    private val fileManager: FileManager
) {

    /** 远端媒体子目录名。单段、无用户输入，直接拼进 URL（[WebDavClient] 负责编码）。 */
    companion object {
        private const val TAG = "MediaSync"
        const val MEDIA_DIR = "_media"

        /**
         * 单轮推送上限。首次同步一个图片很多的库时，一口气推几百张会把同步拖成分钟级，
         * 还可能撞上服务器限速；留一半给下一轮（周期任务 6 小时一轮，手动同步随时可催）。
         */
        const val MAX_UPLOADS_PER_CYCLE = 50

        /** 正文里的图片引用形态固定为 `images/<uuid>.<ext>`（见 EditorViewModel 的解析配置）。 */
        private val IMAGE_REF = Regex("images/([A-Za-z0-9._\\-]+)")

        /**
         * 引用名的白名单形态：只许字母数字点横线下划线。字符集过关还不够——
         * `..` 本身完全符合字符集，却是父目录名，必须单独点名拒绝
         * （PathSafety.requireInside 是第二道闸，这里是把它挡在 DAO 查询之前）。
         */
        private val SAFE_NAME = Regex("^[A-Za-z0-9._\\-]+$")
        fun isSafeRefName(name: String): Boolean =
            SAFE_NAME.matches(name) && name != "." && name != ".."
    }

    data class PushStats(val uploaded: Int, val skipped: Int)
    data class PullStats(val downloaded: Int, val restored: Int, val alreadyLocal: Int)

    /**
     * 推送本地图片到远端 `_media/`。整体 best-effort：调用方已用 runCatching 包住，
     * 逐张失败只记日志不中断本轮（下一轮按同一份「远端缺什么」清单继续）。
     */
    suspend fun pushLocalImages(config: WebDavConfig, web: WebDavClient): PushStats {
        // 只推根级活跃文档的图：与文档同步的 P1 范围对齐（folderId IS null 的语义由 DAO 把关）
        val rootDocs = documentDao.getByFolderIncludingRoot(null).map { it.id }
        if (rootDocs.isEmpty()) return PushStats(0, 0)
        val wanted = LinkedHashSet<String>()
        for (docId in rootDocs) {
            imageDao.getByDocument(docId).forEach { wanted.add(it.fileName) }
        }
        if (wanted.isEmpty()) return PushStats(0, 0)

        val remoteNames = web.listSubDir(config, MEDIA_DIR).getOrElse { return PushStats(0, 0) }
            .filter { !it.isDirectory }
            .mapTo(HashSet()) { it.name }

        // 整轮探测/建目录一次（目录已存在时探测即空操作），之后逐张上传不再各自探测——
        // 50 张图 50 次 PROPFIND 是纯浪费
        web.ensureSubDir(config, MEDIA_DIR).getOrElse { return PushStats(0, 0) }

        val imagesDir = fileManager.getImagesDir()
        var uploaded = 0
        var skipped = 0
        for (name in wanted) {
            if (name in remoteNames) {
                skipped++
                continue
            }
            if (uploaded >= MAX_UPLOADS_PER_CYCLE) break
            val file = localImageFile(imagesDir, name) ?: continue
            val bytes = runCatching { file.readBytes() }
                .onFailure { Log.w(TAG, "读取本地图片失败：$name", it) }
                .getOrNull() ?: continue
            web.uploadBytes(config, MEDIA_DIR, name, bytes, ensureDir = false)
                .onFailure { Log.w(TAG, "上传图片失败：$name", it) }
                .onSuccess { uploaded++ }
        }
        return PushStats(uploaded, skipped)
    }

    /**
     * 为一篇刚从远端落地的文档补齐正文引用的图片（见类注释的拉取策略）。
     * 返回前**不抛**业务异常：拉不到图只让正文暂时裂图，绝不能让文档同步本身失败。
     */
    suspend fun pullImagesFor(config: WebDavConfig, web: WebDavClient, documentId: String, content: String): PullStats {
        val names = IMAGE_REF.findAll(content).map { it.groupValues[1] }
            .filter { isSafeRefName(it) }
            .distinct()
            .toList()
        if (names.isEmpty()) return PullStats(0, 0, 0)

        val imagesDir = fileManager.getImagesDir()
        var downloaded = 0
        var restored = 0
        var alreadyLocal = 0
        for (name in names) {
            val row = imageDao.getByFileName(name)
            // **file.exists() 是必须的**：localImageFile 只负责构造并消毒路径，
            // File 对象在磁盘上没有对应实体时照样返回非 null。拿「对象非空」当
            // 「文件在」的判据，文件丢失的场景就永远走不进补拉分支。
            val file = localImageFile(imagesDir, name)?.takeIf { it.exists() }
            when {
                // 库行与文件都在：什么都不做
                row != null && file != null -> alreadyLocal++
                // 库行在、文件丢（用户清数据残留/外部删除）：重新拉字节写回，行复用
                row != null -> {
                    if (writeRemoteImage(config, web, imagesDir, name)) restored++
                }
                // 全新引用：下载、落盘、登记库行（宽高按字节解码边界，失败退 0×0）。
                // 注意这里要用未做存在性过滤的路径对象：目标文件本来就该不存在
                else -> {
                    val bytes = web.downloadBytes(config, MEDIA_DIR, name)
                        .onFailure { Log.w(TAG, "下载远端图片失败：$name", it) }
                        .getOrNull() ?: continue
                    val target = localImageFile(imagesDir, name) ?: continue
                    runCatching { target.writeBytes(bytes) }
                        .onFailure { Log.w(TAG, "写入图片文件失败：${target.path}", it) }
                        .getOrNull() ?: continue
                    val (w, h) = dimensionsOf(bytes)
                    imageDao.insert(
                        ImageEntity(
                            id = UUID.randomUUID().toString(),
                            documentId = documentId,
                            fileName = name,
                            filePath = target.path,
                            width = w,
                            height = h,
                            fileSize = bytes.size.toLong(),
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    downloaded++
                }
            }
        }
        return PullStats(downloaded, restored, alreadyLocal)
    }

    /** 校验并解析出图片文件的安全落点；名字或路径不合规一律返回 null（拒绝服务而不是报错）。 */
    private fun localImageFile(imagesDir: File, name: String): File? {
        if (!isSafeRefName(name)) return null
        val file = File(imagesDir, name)
        return runCatching {
            PathSafety.requireInside(file, imagesDir, label = "Media sync image")
            file
        }.getOrNull()
    }

    private suspend fun writeRemoteImage(config: WebDavConfig, web: WebDavClient, imagesDir: File, name: String): Boolean {
        val target = localImageFile(imagesDir, name) ?: return false
        val bytes = web.downloadBytes(config, MEDIA_DIR, name)
            .onFailure { Log.w(TAG, "重新下载图片失败：$name", it) }
            .getOrNull() ?: return false
        return runCatching { target.writeBytes(bytes) }
            .onFailure { Log.w(TAG, "恢复图片文件失败：${target.path}", it) }
            .isSuccess
    }

    /** 解码图片边界；字节不是图（或 JVM 测试桩返回 null）时退 0×0——该字段只做展示参考。 */
    private fun dimensionsOf(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return (opts.outWidth.takeIf { it > 0 } ?: 0) to (opts.outHeight.takeIf { it > 0 } ?: 0)
    }
}
