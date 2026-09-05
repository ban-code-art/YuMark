package com.yumark.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.FriendlyValidationException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.db.dao.DocumentSearchDao
import com.yumark.app.data.local.db.dao.ImageDao
import com.yumark.app.data.local.db.entity.DocumentEntity
import com.yumark.app.data.local.db.entity.DocumentSearchEntity
import com.yumark.app.data.local.db.entity.ImageEntity
import com.yumark.app.data.local.file.FileManager
import com.yumark.app.data.mapper.DocumentMapper
import com.yumark.app.domain.model.Document
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DocumentRepositoryImplTest {

    private lateinit var repository: DocumentRepositoryImpl
    private val dao: DocumentDao = mockk()
    private val fileManager: FileManager = mockk()
    private val mapper = DocumentMapper()
    private val searchDao: DocumentSearchDao = mockk()
    private val imageDao: ImageDao = mockk()

    @BeforeEach
    fun setup() {
        repository = DocumentRepositoryImpl(dao, fileManager, mapper, searchDao, imageDao)
        // 默认：索引已有内容（跳过惰性回填），写索引成功。各测试按需覆盖。
        coEvery { searchDao.count() } returns 1
        coEvery { searchDao.upsert(any()) } just Runs
        coEvery { searchDao.deleteByDocId(any()) } just Runs
        // 默认：文档没有配图，图片文件删除是空操作。图片清理相关的用例各自覆盖。
        coEvery { imageDao.getByDocument(any()) } returns emptyList()
        coEvery { fileManager.deleteImageFiles(any()) } returns 0
        // 重名判定的默认答案：同级目录里没有别的条目。查重相关的用例各自覆盖。
        coEvery { dao.getByFolderIncludingRoot(any()) } returns emptyList()
        // saveDocument 只在「名字变了」时才查重，读不到旧行就直接跳过；
        // 需要走改名分支的用例自己 stub 出那一行。
        coEvery { dao.getById(any()) } returns null
    }

    @AfterEach
    fun tearDown() = clearAllMocks()

    @Test
    fun `getDocumentById returns document when exists`() = runTest {
        val id = "test-id"
        val entity = DocumentEntity(id, "Test", null, 0L, 0L, false, 10, 50)
        coEvery { dao.getById(id) } returns entity
        coEvery { fileManager.loadDocumentContent(id) } returns Result.success("# Hello")

        val result = repository.getDocumentById(id)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.id).isEqualTo(id)
        assertThat(result.getOrNull()?.content).isEqualTo("# Hello")
    }

    // ---- 全文搜索：FTS 命中路径 ----

    @Test
    fun `searchDocuments 命中 FTS 时只回捞命中文档`() = runTest {
        val hit = DocumentEntity("2", "随笔", null, 0L, 0L, false, 0, 0)
        coEvery { searchDao.searchDocIds("\"kotlin\"", any()) } returns listOf("2")
        coEvery { dao.getByIds(listOf("2")) } returns listOf(hit)
        coEvery { fileManager.loadDocumentContent("2") } returns Result.success("今天学了 Kotlin 协程")

        val result = repository.searchDocuments("kotlin")

        assertThat(result.getOrNull()?.map { it.id }).containsExactly("2")
        // 换 FTS 的全部收益就在这一行：不再逐个读全库正文
        coVerify(exactly = 0) { dao.getAll() }
    }

    @Test
    fun `searchDocuments 保持 FTS 的命中顺序`() = runTest {
        val a = DocumentEntity("1", "A", null, 0L, 0L, false, 0, 0)
        val b = DocumentEntity("2", "B", null, 0L, 0L, false, 0, 0)
        coEvery { searchDao.searchDocIds(any(), any()) } returns listOf("2", "1")
        // getByIds 的返回顺序由 SQLite 决定，故意与命中顺序相反
        coEvery { dao.getByIds(any()) } returns listOf(a, b)
        coEvery { fileManager.loadDocumentContent(any()) } returns Result.success("x")

        val result = repository.searchDocuments("kotlin")

        assertThat(result.getOrNull()?.map { it.id }).containsExactly("2", "1").inOrder()
    }

    @Test
    fun `searchDocuments 跳过索引里已删除的幽灵条目`() = runTest {
        val alive = DocumentEntity("2", "B", null, 0L, 0L, false, 0, 0)
        coEvery { searchDao.searchDocIds(any(), any()) } returns listOf("2", "ghost")
        coEvery { dao.getByIds(any()) } returns listOf(alive)
        coEvery { fileManager.loadDocumentContent("2") } returns Result.success("x")

        val result = repository.searchDocuments("kotlin")

        assertThat(result.getOrNull()?.map { it.id }).containsExactly("2")
    }

    @Test
    fun `searchDocuments 用规范化后的短语查询`() = runTest {
        coEvery { searchDao.searchDocIds(any(), any()) } returns emptyList()
        coEvery { dao.getAll() } returns emptyList()

        repository.searchDocuments("我爱Kotlin")

        // 中文必须逐字拆开，且整体是一条双引号短语，否则要么搜不到要么语法报错
        coVerify { searchDao.searchDocIds("\"我 爱 kotlin\"", any()) }
    }

    // ---- 全文搜索：降级路径 ----

    @Test
    fun `searchDocuments 按文件名或正文匹配且忽略大小写`() = runTest {
        val byName = DocumentEntity("1", "Kotlin 笔记", null, 0L, 0L, false, 0, 0)
        val byContent = DocumentEntity("2", "随笔", null, 0L, 0L, false, 0, 0)
        val noMatch = DocumentEntity("3", "购物清单", null, 0L, 0L, false, 0, 0)
        // FTS 无命中 → 退回旧的内存子串搜索，行为必须与改造前逐字一致
        coEvery { searchDao.searchDocIds(any(), any()) } returns emptyList()
        coEvery { dao.getAll() } returns listOf(byName, byContent, noMatch)
        coEvery { fileManager.loadDocumentContent("1") } returns Result.success("无关内容")
        coEvery { fileManager.loadDocumentContent("2") } returns Result.success("今天学了 KOTLIN 协程")
        coEvery { fileManager.loadDocumentContent("3") } returns Result.success("牛奶 鸡蛋")

        val result = repository.searchDocuments("kotlin")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.map { it.id }).containsExactly("1", "2")
    }

    @Test
    fun `searchDocuments 在 MATCH 抛异常时降级而不是失败`() = runTest {
        // 索引表缺失/损坏/表达式被拒都走这里；搜索功能不能因此整体不可用
        coEvery { searchDao.searchDocIds(any(), any()) } throws RuntimeException("no such table")
        coEvery { dao.getAll() } returns
            listOf(DocumentEntity("1", "Kotlin 笔记", null, 0L, 0L, false, 0, 0))
        coEvery { fileManager.loadDocumentContent("1") } returns Result.success("")

        val result = repository.searchDocuments("kotlin")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.map { it.id }).containsExactly("1")
    }

    @Test
    fun `searchDocuments 遇到无法索引的查询直接走子串搜索`() = runTest {
        // 纯 emoji 规范化后没有任何 token，FtsQueryBuilder 返回 null
        coEvery { dao.getAll() } returns
            listOf(DocumentEntity("1", "笑脸 😀", null, 0L, 0L, false, 0, 0))
        coEvery { fileManager.loadDocumentContent("1") } returns Result.success("")

        val result = repository.searchDocuments("😀")

        assertThat(result.getOrNull()?.map { it.id }).containsExactly("1")
        coVerify(exactly = 0) { searchDao.searchDocIds(any(), any()) }
    }

    // ---- 惰性回填 ----

    @Test
    fun `首次搜索时把历史文档补进索引`() = runTest {
        // 迁移只建了空表，历史文档的正文在文件系统里，只能在这里补
        val entries = mutableListOf<DocumentSearchEntity>()
        coEvery { searchDao.count() } returns 0
        coEvery { searchDao.upsert(capture(entries)) } just Runs
        coEvery { searchDao.searchDocIds(any(), any()) } returns emptyList()
        coEvery { dao.getAll() } returns
            listOf(DocumentEntity("1", "旧笔记", null, 0L, 0L, false, 0, 0))
        coEvery { fileManager.loadDocumentContent("1") } returns Result.success("我爱北京")

        repository.searchDocuments("kotlin")

        assertThat(entries).hasSize(1)
        assertThat(entries[0].docId).isEqualTo("1")
        // 入索引的必须是规范化文本，否则中文整句成为一个 token
        assertThat(entries[0].content).isEqualTo("我 爱 北 京")
        assertThat(entries[0].name).isEqualTo("旧 笔 记")
    }

    @Test
    fun `回填只做一次`() = runTest {
        coEvery { searchDao.count() } returns 0
        coEvery { searchDao.searchDocIds(any(), any()) } returns emptyList()
        coEvery { dao.getAll() } returns emptyList()

        repository.searchDocuments("a")
        repository.searchDocuments("b")

        coVerify(exactly = 1) { searchDao.count() }
    }

    @Test
    fun `回填失败不影响搜索结果`() = runTest {
        coEvery { searchDao.count() } throws RuntimeException("db locked")
        coEvery { searchDao.searchDocIds(any(), any()) } returns emptyList()
        coEvery { dao.getAll() } returns
            listOf(DocumentEntity("1", "Kotlin", null, 0L, 0L, false, 0, 0))
        coEvery { fileManager.loadDocumentContent("1") } returns Result.success("")

        val result = repository.searchDocuments("kotlin")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.map { it.id }).containsExactly("1")
    }

    // ---- 索引维护 ----

    @Test
    fun `保存文档时同步更新索引`() = runTest {
        val entries = mutableListOf<DocumentSearchEntity>()
        coEvery { searchDao.upsert(capture(entries)) } just Runs
        val doc = Document.create("1", "我的笔记").copy(content = "Hello 世界")
        coEvery { fileManager.saveDocumentContent("1", "Hello 世界") } returns Result.success(Unit)
        coEvery { dao.updateContentMeta("1", any(), any(), any()) } just Runs
        // 索引标题取库里的现值，所以这一行必须存在，否则整段索引写入会被跳过
        coEvery { dao.getById("1") } returns row("1", "我的笔记")

        val result = repository.saveDocument(doc)

        assertThat(result.isSuccess).isTrue()
        assertThat(entries).hasSize(1)
        assertThat(entries[0].name).isEqualTo("我 的 笔 记")
        assertThat(entries[0].content).isEqualTo("hello 世 界")
    }

    @Test
    fun `索引写入失败不会让保存失败`() = runTest {
        // 索引是搜索的加速结构，坏了只该让这篇文档暂时搜不到，绝不能吃掉用户的保存
        coEvery { searchDao.upsert(any()) } throws RuntimeException("fts corrupted")
        val doc = Document.create("1", "笔记")
        coEvery { fileManager.saveDocumentContent(any(), any()) } returns Result.success(Unit)
        coEvery { dao.updateContentMeta(any(), any(), any(), any()) } just Runs
        coEvery { dao.getById("1") } returns row("1", "笔记")

        val result = repository.saveDocument(doc)

        assertThat(result.isSuccess).isTrue()
        coVerify { dao.updateContentMeta("1", any(), any(), any()) }
    }

    @Test
    fun `删除文档时清掉索引条目`() = runTest {
        coEvery { dao.deleteWithTombstone("1", any()) } just Runs
        coEvery { fileManager.deleteDocumentFile("1") } returns Result.success(Unit)

        val result = repository.deleteDocument("1")

        assertThat(result.isSuccess).isTrue()
        // 留着条目就是幽灵命中：搜到一篇点进去发现不存在
        coVerify { searchDao.deleteByDocId("1") }
    }

    @Test
    fun `删除索引失败不会让删除文档失败`() = runTest {
        coEvery { searchDao.deleteByDocId(any()) } throws RuntimeException("fts corrupted")
        coEvery { dao.deleteWithTombstone("1", any()) } just Runs
        coEvery { fileManager.deleteDocumentFile("1") } returns Result.success(Unit)

        val result = repository.deleteDocument("1")

        assertThat(result.isSuccess).isTrue()
        coVerify { fileManager.deleteDocumentFile("1") }
    }

    @Test
    fun `新建文档时把标题写进索引`() = runTest {
        val entries = mutableListOf<DocumentSearchEntity>()
        coEvery { searchDao.upsert(capture(entries)) } just Runs
        coEvery { dao.insert(any()) } just Runs
        coEvery { fileManager.saveDocumentContent(any(), "") } returns Result.success(Unit)

        val result = repository.createDocument("新建文档", null)

        assertThat(result.isSuccess).isTrue()
        assertThat(entries).hasSize(1)
        // 正文为空，但用户新建后马上按标题搜也该能命中
        assertThat(entries[0].name).isEqualTo("新 建 文 档")
        assertThat(entries[0].content).isEmpty()
    }

    // ---- 新建：磁盘与库不能各说一套 ----

    @Test
    fun `写正文文件失败时不插库`() = runTest {
        // 旧实现忽略了 saveDocumentContent 的 Result：磁盘写失败照样插行，
        // 列表里于是多出一篇点进去空白、一保存就把别处覆盖掉的幽灵文档
        coEvery { fileManager.saveDocumentContent(any(), "") } returns
            Result.failure(java.io.IOException("no space left"))

        val result = repository.createDocument("新建文档", null)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `插库失败时把已经写出的空文件收回去`() = runTest {
        coEvery { fileManager.saveDocumentContent(any(), "") } returns Result.success(Unit)
        coEvery { dao.insert(any()) } throws RuntimeException("db locked")
        coEvery { fileManager.deleteDocumentFile(any()) } returns Result.success(Unit)

        val result = repository.createDocument("新建文档", null)

        assertThat(result.isFailure).isTrue()
        // 不收回就是 documents/ 里留一个没有主人的 .md，谁也不会再去看它
        coVerify(exactly = 1) { fileManager.deleteDocumentFile(any()) }
    }

    // ---- 重名：同一文件夹下不许两篇同名 ----

    @Test
    fun `新建时撞上同名文档就拒绝`() = runTest {
        coEvery { dao.getByFolderIncludingRoot(null) } returns
            listOf(DocumentEntity("1", "笔记", null, 0L, 0L, false, 0, 0))

        val result = repository.createDocument("笔记", null)

        val error = result.exceptionOrNull()
        // 必须是 FriendlyValidationException：只有它的文案会原样进 Snackbar
        assertThat(error).isInstanceOf(FriendlyValidationException::class.java)
        val message = (error as FriendlyValidationException).uiMessage
        // 文案是资源 id + 实参，随语区翻译；实参里回显库里真实的名字，
        // 用户才知道跟哪一篇撞了（JVM 上没有资源表，取不到成句的结果，只能核实参）
        assertThat(message).isEqualTo(
            UiMessage.of(R.string.document_error_duplicate_name, "笔记")
        )
        // 拦在最前面：既没写文件也没插库，不留任何半成品
        coVerify(exactly = 0) { fileManager.saveDocumentContent(any(), any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `重名只在同一文件夹内判定`() = runTest {
        coEvery { dao.getByFolderIncludingRoot("folder-a") } returns
            listOf(DocumentEntity("1", "笔记", "folder-a", 0L, 0L, false, 0, 0))
        coEvery { dao.getByFolderIncludingRoot("folder-b") } returns emptyList()
        coEvery { fileManager.saveDocumentContent(any(), "") } returns Result.success(Unit)
        coEvery { dao.insert(any()) } just Runs

        val result = repository.createDocument("笔记", "folder-b")

        assertThat(result.isSuccess).isTrue()
        // 查的必须是目标文件夹：拿错 folderId 会把限制扩成全库唯一
        coVerify { dao.getByFolderIncludingRoot("folder-b") }
    }

    // ---- 改名：只动库里那一行的 name ----

    @Test
    fun `改名撞上同名文档时不写库`() = runTest {
        coEvery { dao.getById("1") } returns DocumentEntity("1", "旧名", null, 0L, 0L, false, 0, 0)
        coEvery { dao.getByFolderIncludingRoot(null) } returns listOf(
            DocumentEntity("1", "旧名", null, 0L, 0L, false, 0, 0),
            DocumentEntity("2", "新名", null, 0L, 0L, false, 0, 0)
        )

        val result = repository.renameDocument("1", "新名")

        assertThat(result.exceptionOrNull()).isInstanceOf(FriendlyValidationException::class.java)
        coVerify(exactly = 0) { dao.rename(any(), any(), any()) }
        coVerify(exactly = 0) { fileManager.saveDocumentContent(any(), any()) }
    }

    @Test
    fun `保存正文从不扫同级目录`() = runTest {
        // saveDocument 是自动保存的路径，每隔几秒跑一次。它已经改不动名字（名字归
        // renameDocument），所以连查重都不必——哪怕入参带的是个跟别人重名的过期名字。
        coEvery { dao.getById("1") } returns row("1", "笔记")
        coEvery { fileManager.saveDocumentContent(any(), any()) } returns Result.success(Unit)
        coEvery { dao.updateContentMeta(any(), any(), any(), any()) } just Runs

        val result = repository.saveDocument(Document.create("1", "别的名字").copy(content = "x"))

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { dao.getByFolderIncludingRoot(any()) }
    }

    @Test
    fun `改名时不把自己算成重名`() = runTest {
        // 同级列表里那一行就是自己（并发读到的新名字）；不排掉的话任何改名都会被自己挡下来
        coEvery { dao.getById("1") } returns DocumentEntity("1", "旧名", null, 0L, 0L, false, 0, 0)
        coEvery { dao.getByFolderIncludingRoot(null) } returns
            listOf(DocumentEntity("1", "新名", null, 0L, 0L, false, 0, 0))
        coEvery { dao.rename("1", "新名", any()) } just Runs
        coEvery { fileManager.loadDocumentContent("1") } returns Result.success("正文")

        val result = repository.renameDocument("1", "新名")

        assertThat(result.isSuccess).isTrue()
        coVerify { dao.rename("1", "新名", any()) }
    }

    @Test
    fun `改名不回写正文文件`() = runTest {
        // 这是「改名弄丢用户正在写的东西」的回归测试：旧实现是读盘取正文 → copy(name=…) →
        // saveDocument 整篇回写，而磁盘那份落后于编辑框，回写一次就把未保存的段落钉成旧内容。
        coEvery { dao.getById("1") } returns row("1", "旧名")
        coEvery { dao.rename("1", "新名", any()) } just Runs
        coEvery { fileManager.loadDocumentContent("1") } returns Result.success("落盘的正文")

        val result = repository.renameDocument("1", "新名")

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { fileManager.saveDocumentContent(any(), any()) }
        coVerify(exactly = 1) { dao.rename("1", "新名", any()) }
    }

    @Test
    fun `名字没变的改名什么都不做`() = runTest {
        // 「点了改名但没改字」既不该扫同级目录，也不该刷 updated_at 把文档顶到列表最前面
        coEvery { dao.getById("1") } returns row("1", "笔记")

        val result = repository.renameDocument("1", "笔记")

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { dao.rename(any(), any(), any()) }
        coVerify(exactly = 0) { dao.getByFolderIncludingRoot(any()) }
        coVerify(exactly = 0) { searchDao.upsert(any()) }
        coVerify(exactly = 0) { fileManager.loadDocumentContent(any()) }
    }

    @Test
    fun `保存正文时索引标题取库里的现值而不是入参`() = runTest {
        // 展开态双窗格（左列表 + 右编辑器）：左边刚改完名，右边编辑器内存里还是旧名字，
        // 几秒后它的自动保存跑到这里。拿入参那份写索引，等于把改名在搜索里也回滚掉。
        val entries = mutableListOf<DocumentSearchEntity>()
        coEvery { searchDao.upsert(capture(entries)) } just Runs
        coEvery { dao.getById("1") } returns row("1", "新名")
        coEvery { fileManager.saveDocumentContent(any(), any()) } returns Result.success(Unit)
        coEvery { dao.updateContentMeta(any(), any(), any(), any()) } just Runs

        repository.saveDocument(Document.create("1", "旧名").copy(content = "x"))

        assertThat(entries.single().name).isEqualTo("新 名")
    }

    @Test
    fun `保存正文只回写时间戳与两个计数`() = runTest {
        // 整行 @Update 会让自动保存拿 ViewModel 内存里那份可能过期的元数据盖掉库里的
        // name / folder_id / is_favorite：在左窗格改名、移动、收藏，几秒后被右窗格静默改回去。
        val doc = Document.create("1", "笔记").copy(content = "一二三")
        coEvery { dao.getById("1") } returns row("1", "笔记")
        coEvery { fileManager.saveDocumentContent(any(), any()) } returns Result.success(Unit)
        coEvery { dao.updateContentMeta(any(), any(), any(), any()) } just Runs

        val result = repository.saveDocument(doc)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) {
            dao.updateContentMeta("1", any(), doc.wordCount, doc.characterCount)
        }
    }

    @Test
    fun `读不到待改名的文档时报文档不存在`() = runTest {
        val result = repository.renameDocument("missing", "新名")

        val error = result.exceptionOrNull()
        // 并发删除是预期内的竞态：文案得说「文档不存在」，而不是被归到「出现未知问题，请重试」，
        // 也不该占掉一格崩溃日志配额（AppError.Friendly 不记账）
        assertThat(error).isInstanceOf(FriendlyValidationException::class.java)
        assertThat((error as FriendlyValidationException).uiMessage)
            .isEqualTo(UiMessage.Res(R.string.document_error_not_found))
        coVerify(exactly = 0) { dao.rename(any(), any(), any()) }
    }

    @Test
    fun `读不到文档时报文档不存在`() = runTest {
        val error = repository.getDocumentById("missing").exceptionOrNull()

        assertThat(error).isInstanceOf(FriendlyValidationException::class.java)
        assertThat((error as FriendlyValidationException).uiMessage)
            .isEqualTo(UiMessage.Res(R.string.document_error_not_found))
    }

    // ---- 删除：配图文件不能留成孤儿 ----

    @Test
    fun `删除文档时把配图文件一起删掉`() = runTest {
        val names = mutableListOf<Collection<String>>()
        coEvery { imageDao.getByDocument("1") } returns listOf(
            imageRow("img-1", "1", "a.png"),
            imageRow("img-2", "1", "b.jpg")
        )
        coEvery { fileManager.deleteImageFiles(capture(names)) } returns 2
        coEvery { dao.deleteWithTombstone("1", any()) } just Runs
        coEvery { fileManager.deleteDocumentFile("1") } returns Result.success(Unit)

        val result = repository.deleteDocument("1")

        assertThat(result.isSuccess).isTrue()
        // 旧实现只删了 .md：图片全留在 images/ 目录里，谁也再引用不到
        assertThat(names.single()).containsExactly("a.png", "b.jpg")
    }

    @Test
    fun `图片名单在删库之前取`() = runTest {
        coEvery { dao.deleteWithTombstone("1", any()) } just Runs
        coEvery { fileManager.deleteDocumentFile("1") } returns Result.success(Unit)

        repository.deleteDocument("1")

        // images 行挂着 documents 的 CASCADE：删库之后再查只剩空表，
        // 磁盘上那些图片就成了连孤儿清理都扫不到的永久垃圾
        coVerifyOrder {
            imageDao.getByDocument("1")
            dao.deleteWithTombstone("1", any())
        }
    }

    @Test
    fun `删除文档时留下同步墓碑`() = runTest {
        coEvery { dao.deleteWithTombstone("1", any()) } just Runs
        coEvery { fileManager.deleteDocumentFile("1") } returns Result.success(Unit)

        repository.deleteDocument("1")

        // 走裸 deleteById 就不会立碑：同步过的文档删掉后，远端那个文件下次同步会被当成
        // 「另一台设备新建的」拉回来，删掉的文档就地复活
        coVerify { dao.deleteWithTombstone("1", any()) }
    }

    @Test
    fun `正文文件删不掉时图片已经清过`() = runTest {
        // 图片清理排在正文删除之前，正是为了不被那句 getOrThrow 整段跳过
        coEvery { imageDao.getByDocument("1") } returns listOf(imageRow("img-1", "1", "a.png"))
        coEvery { dao.deleteWithTombstone("1", any()) } just Runs
        coEvery { fileManager.deleteDocumentFile("1") } returns
            Result.failure(java.io.IOException("busy"))

        val result = repository.deleteDocument("1")

        assertThat(result.isFailure).isTrue()
        coVerify { fileManager.deleteImageFiles(listOf("a.png")) }
    }

    // ---- 移动：目标文件夹里不许撞名 ----

    @Test
    fun `移动到已有同名文档的文件夹时拒绝`() = runTest {
        coEvery { dao.getById("1") } returns DocumentEntity("1", "笔记", null, 0L, 0L, false, 0, 0)
        coEvery { dao.getByFolderIncludingRoot("folder-a") } returns
            listOf(DocumentEntity("2", "笔记", "folder-a", 0L, 0L, false, 0, 0))

        val result = repository.moveDocument("1", "folder-a")

        val error = result.exceptionOrNull()
        // 与新建/改名同一种错误：文案要能原样进 Snackbar
        assertThat(error).isInstanceOf(FriendlyValidationException::class.java)
        assertThat((error as FriendlyValidationException).uiMessage).isEqualTo(
            UiMessage.of(R.string.document_error_duplicate_name, "笔记")
        )
        // 移动这条路径以前没查重，等于给「同一文件夹不许同名」开了个后门
        coVerify(exactly = 0) { dao.moveToFolder(any(), any(), any()) }
    }

    @Test
    fun `目标文件夹没有同名时正常移动`() = runTest {
        coEvery { dao.getById("1") } returns DocumentEntity("1", "笔记", null, 0L, 0L, false, 0, 0)
        coEvery { dao.moveToFolder("1", "folder-a", any()) } just Runs

        val result = repository.moveDocument("1", "folder-a")

        assertThat(result.isSuccess).isTrue()
        // 查的必须是**目标**文件夹，拿错 folderId 会把限制扩成全库唯一
        coVerify { dao.getByFolderIncludingRoot("folder-a") }
    }

    @Test
    fun `移动时不把自己算成重名`() = runTest {
        // 重复点两次移动：第二次读到的同级列表里已经有自己那一行
        coEvery { dao.getById("1") } returns
            DocumentEntity("1", "笔记", "folder-a", 0L, 0L, false, 0, 0)
        coEvery { dao.getByFolderIncludingRoot("folder-a") } returns
            listOf(DocumentEntity("1", "笔记", "folder-a", 0L, 0L, false, 0, 0))
        coEvery { dao.moveToFolder("1", "folder-a", any()) } just Runs

        val result = repository.moveDocument("1", "folder-a")

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `读不到待移动的文档时不写库`() = runTest {
        val result = repository.moveDocument("missing", "folder-a")

        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(FriendlyValidationException::class.java)
        assertThat((error as FriendlyValidationException).uiMessage)
            .isEqualTo(UiMessage.Res(R.string.document_error_not_found))
        coVerify(exactly = 0) { dao.moveToFolder(any(), any(), any()) }
    }

    /** documents 行的最小构造：本类的用例只关心 id / name / folder_id 三列。 */
    private fun row(id: String, name: String, folderId: String? = null) =
        DocumentEntity(id, name, folderId, 0L, 0L, false, 0, 0)

    /** images 行的最小构造：本类只关心 file_name 这一列。 */
    private fun imageRow(id: String, docId: String, fileName: String) =
        ImageEntity(id, docId, fileName, "/data/images/$fileName", 1, 1, 1L, 0L)
}
