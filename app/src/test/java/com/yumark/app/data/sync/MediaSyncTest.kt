package com.yumark.app.data.sync

import com.google.common.truth.Truth.assertThat
import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.db.dao.ImageDao
import com.yumark.app.data.local.db.entity.DocumentEntity
import com.yumark.app.data.local.db.entity.ImageEntity
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.data.remote.webdav.WebDavClient
import com.yumark.app.domain.model.RemoteEntry
import com.yumark.app.domain.model.WebDavConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [MediaSync] 的纯 JVM 测试：真实临时目录当 images/（写入路径的消毒逻辑用真 File 才有意义），
 * DAO 与 WebDAV 用 mockk。解码宽高在 JVM 上走 android.jar 桩（build.gradle 的
 * returnDefaultValues），正好覆盖 0×0 回退分支。
 */
class MediaSyncTest {

    @TempDir
    lateinit var tempDir: File

    private val documentDao: DocumentDao = mockk()
    private val imageDao: ImageDao = mockk()
    private val fileManager: FileManager = mockk()
    private val web: WebDavClient = mockk()
    private val config = WebDavConfig(enabled = true, baseUrl = "https://dav.example", username = "u", password = "p", remoteDir = "yumark")

    private lateinit var imagesDir: File
    private lateinit var media: MediaSync

    @BeforeEach
    fun setup() {
        imagesDir = File(tempDir, "images").apply { mkdirs() }
        every { fileManager.getImagesDir() } returns imagesDir
        // 默认桩：测试按需覆写。uploadBytes 的默认成功让「推送 N 张」类用例只关心计数；
        // verify(exactly = 0) 的用例不受桩存在影响。
        coEvery { web.uploadBytes(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { web.ensureSubDir(any(), any()) } returns Result.success(Unit)
        coEvery { imageDao.getByFileName(any()) } returns null
        coEvery { imageDao.insert(any()) } returns Unit
        media = MediaSync(documentDao, imageDao, fileManager)
    }

    private fun rootDoc(id: String) = DocumentEntity(id, "笔记", null, 0L, 0L, false, 0, 0)
    private fun imageRow(docId: String, name: String) =
        ImageEntity("img-$name", docId, name, File(imagesDir, name).path, 10, 10, 1L, 0L)

    // ---- 推送 ----

    @Test
    fun `推送只上传远端没有的图片`() = runTest {
        coEvery { documentDao.getByFolderIncludingRoot(null) } returns listOf(rootDoc("d1"))
        coEvery { imageDao.getByDocument("d1") } returns listOf(imageRow("d1", "a.jpg"), imageRow("d1", "b.png"))
        coEvery { web.listSubDir(config, MediaSync.MEDIA_DIR) } returns Result.success(
            listOf(RemoteEntry("a.jpg", null, null, isDirectory = false))
        )
        // a.jpg 远端已有不该上传；本地文件缺失的 b.png 跳过（读文件失败分支）
        File(imagesDir, "b.png").writeBytes(ByteArray(10))

        val stats = media.pushLocalImages(config, web)

        coVerify(exactly = 0) { web.uploadBytes(any(), any(), "a.jpg", any(), any()) }
        coVerify(exactly = 1) { web.uploadBytes(any(), any(), "b.png", any(), any()) }
        assertThat(stats.uploaded).isEqualTo(1)
    }

    @Test
    fun `推送范围只含根级文档且受单轮上限约束`() = runTest {
        coEvery { documentDao.getByFolderIncludingRoot(null) } returns listOf(rootDoc("d1"))
        // 同一篇文档挂 60 张图：上限 50，多余的下轮再推
        coEvery { imageDao.getByDocument("d1") } returns
            (1..60).map { imageRow("d1", "img$it.jpg") }
        coEvery { web.listSubDir(config, MediaSync.MEDIA_DIR) } returns Result.success(emptyList())
        (1..60).forEach { File(imagesDir, "img$it.jpg").writeBytes(ByteArray(4)) }

        val stats = media.pushLocalImages(config, web)

        assertThat(stats.uploaded).isEqualTo(MediaSync.MAX_UPLOADS_PER_CYCLE)
        // 整轮探测/建目录恰好一次：50 张图不应有 50 次多余的 PROPFIND
        coVerify(exactly = 1) { web.ensureSubDir(any(), any()) }
    }

    @Test
    fun `列远端目录失败时推送放弃且不报错`() = runTest {
        coEvery { documentDao.getByFolderIncludingRoot(null) } returns listOf(rootDoc("d1"))
        coEvery { imageDao.getByDocument("d1") } returns listOf(imageRow("d1", "a.jpg"))
        coEvery { web.listSubDir(config, MediaSync.MEDIA_DIR) } returns
            Result.failure(RuntimeException("boom"))

        val stats = media.pushLocalImages(config, web)

        // 推送是 best-effort：这轮拿不到「远端缺什么」的清单就整个放弃，绝不盲传
        assertThat(stats.uploaded).isEqualTo(0)
        coVerify(exactly = 0) { web.uploadBytes(any(), any(), any(), any(), any()) }
    }

    // ---- 拉取 ----

    @Test
    fun `正文引用的全新图片会被下载并登记库行`() = runTest {
        coEvery { imageDao.getByFileName("x.jpg") } returns null
        coEvery { web.downloadBytes(config, MediaSync.MEDIA_DIR, "x.jpg") } returns
            Result.success(ByteArray(16))
        coEvery { imageDao.insert(any()) } returns Unit

        val stats = media.pullImagesFor(config, web, "d1", "![](images/x.jpg)")

        assertThat(stats.downloaded).isEqualTo(1)
        assertThat(File(imagesDir, "x.jpg").exists()).isTrue()
        coVerify(exactly = 1) {
            imageDao.insert(match { it.documentId == "d1" && it.fileName == "x.jpg" })
        }
    }

    @Test
    fun `库行在文件丢时会补拉且不重复登记`() = runTest {
        coEvery { imageDao.getByFileName("y.jpg") } returns imageRow("d1", "y.jpg")
        coEvery { web.downloadBytes(config, MediaSync.MEDIA_DIR, "y.jpg") } returns
            Result.success(ByteArray(8))

        val stats = media.pullImagesFor(config, web, "d1", "images/y.jpg")

        assertThat(stats.restored).isEqualTo(1)
        assertThat(File(imagesDir, "y.jpg").exists()).isTrue()
        coVerify(exactly = 0) { imageDao.insert(any()) }
    }

    @Test
    fun `库行文件都在时不发请求`() = runTest {
        coEvery { imageDao.getByFileName("z.jpg") } returns imageRow("d1", "z.jpg")
        File(imagesDir, "z.jpg").writeBytes(ByteArray(4))

        val stats = media.pullImagesFor(config, web, "d1", "images/z.jpg")

        assertThat(stats.alreadyLocal).isEqualTo(1)
        coVerify(exactly = 0) { web.downloadBytes(any(), any(), any()) }
    }

    @Test
    fun `不安全的引用名直接跳过`() = runTest {
        // 内容里混了路径穿越（.. 与 ..%2F）、带空格（会碎成无害短名 a）与中文引用。
        // 断言口径是「不产生任何落盘与登记」：碎片名 a 即便被拉也 404（默认桩失败）
        coEvery { web.downloadBytes(any(), any(), "a") } returns Result.failure(RuntimeException("404"))
        val stats = media.pullImagesFor(
            config, web, "d1",
            "images/..%2Fdatabases/yumark.db images/../x.jpg images/a b.jpg images/正常.jpg"
        )

        assertThat(stats.downloaded).isEqualTo(0)
        assertThat(stats.restored).isEqualTo(0)
        assertThat(imagesDir.listFiles().isNullOrEmpty()).isTrue()
        coVerify(exactly = 0) { imageDao.insert(any()) }
    }

    @Test
    fun `正文没有图片引用时零网络请求`() = runTest {
        val stats = media.pullImagesFor(config, web, "d1", "# 纯文字\n没有图")

        assertThat(stats).isEqualTo(MediaSync.PullStats(0, 0, 0))
        coVerify(exactly = 0) { web.downloadBytes(any(), any(), any()) }
        coVerify(exactly = 0) { imageDao.insert(any()) }
    }
}
