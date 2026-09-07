package com.yumark.app.domain.usecase.ai

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.Document
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.model.ToolCall
import com.yumark.app.domain.repository.DocumentRepository
import com.yumark.app.domain.repository.FolderRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [ExecuteDocumentToolUseCase] 的工具语义测试：大纲/分页（大文档导航）与
 * 文件夹结构/名称解析（模型对库结构的感知）。
 */
class ExecuteDocumentToolUseCaseTest {

    private val documentRepository: DocumentRepository = mockk()
    private val folderRepository: FolderRepository = mockk()
    private lateinit var useCase: ExecuteDocumentToolUseCase

    @BeforeEach
    fun setup() {
        // 无文件夹的默认桩（「所在文件夹」解析用）；文件夹相关用例各自覆写
        coEvery { folderRepository.getAllFolders() } returns Result.success(emptyList())
        useCase = ExecuteDocumentToolUseCase(documentRepository, folderRepository)
    }

    private fun doc(id: String, name: String, folderId: String?, content: String) =
        Document.create(id, name, folderId).copy(content = content)

    private fun call(name: String, arguments: String) = ToolCall(
        id = "call-1", name = name, arguments = arguments
    )

    @Test
    fun `read_document 短文档返回表头加原文且无分页头`() = runTest {
        coEvery { documentRepository.getDocumentById("d1") } returns
            Result.success(doc("d1", "笔记", null, "# 标题\n正文"))

        val out = useCase(call("read_document", """{"document_id":"d1"}""")).getOrThrow()

        assertThat(out).contains("【文档名称】笔记")
        assertThat(out).contains("【所在文件夹】根目录")
        // 表头之后是逐字节原文（edit_document 的 old_string 依赖这个不变量）
        assertThat(out).contains("【正文片段】".let { "# 标题\n正文" })
        assertThat(out).doesNotContain("【正文片段")
        assertThat(out).doesNotContain("后续内容用 offset=")
    }

    @Test
    fun `read_document outline 模式返回结构与全文规模`() = runTest {
        val content = "# 一级\n\n正文一\n## 二级\n\n正文二"
        coEvery { documentRepository.getDocumentById("d1") } returns Result.success(doc("d1", "长文", null, content))

        val out = useCase(
            call("read_document", """{"document_id":"d1","mode":"outline"}""")
        ).getOrThrow()

        assertThat(out).contains("【大纲")
        assertThat(out).contains("共 ${content.length} 字符")
        assertThat(out).contains("# 一级")
        assertThat(out).contains("## 二级")
        // 大纲模式不回全文正文
        assertThat(out).doesNotContain("正文一\n正文二")
    }

    @Test
    fun `read_document 分页返回窗口与续读指引`() = runTest {
        val content = "字".repeat(500)   // 已知精确长度
        coEvery { documentRepository.getDocumentById("d1") } returns Result.success(doc("d1", "长文", null, content))

        val out = useCase(
            call(
                "read_document",
                """{"document_id":"d1","offset":400,"length":200}"""
            )
        ).getOrThrow()

        assertThat(out).contains("【正文片段：第 401–500 字符，共 500 字符】")
        assertThat(out).contains(content.substring(400))   // 窗口逐字节与磁盘一致
        assertThat(out).doesNotContain("后续内容用 offset=")   // 已到末尾，不再指引
        // 窗口内容逐字节与磁盘一致（外科式编辑的 old_string 依赖它）
        assertThat(out).contains(content.substring(400))
    }

    @Test
    fun `read_document 分页中段返回续读指引`() = runTest {
        val content = (1..100).joinToString("") { "字$it" }
        coEvery { documentRepository.getDocumentById("d1") } returns Result.success(doc("d1", "长文", null, content))

        val out = useCase(
            call("read_document", """{"document_id":"d1","offset":0,"length":200}""")
        ).getOrThrow()

        assertThat(out).contains("后续内容用 offset=200 继续读取")
        assertThat(out).contains(content.substring(0, 200))
    }

    @Test
    fun `outline 模式每条标题带字符偏移且可用作跳读起点`() = runTest {
        val content = "前言\n# 第一章\n第一章正文\n## 第二节\n第二节正文"
        coEvery { documentRepository.getDocumentById("d1") } returns Result.success(doc("d1", "长文", null, content))

        val out = useCase(
            call("read_document", """{"document_id":"d1","mode":"outline"}""")
        ).getOrThrow()

        // 偏移是真实可用的跳读起点："前言\n"=3 char；"# 第一章\n"=5+1；"第一章正文\n"=6+1
        assertThat(out).contains("3: # 第一章")
        assertThat(out).contains("15: ## 第二节")
        // 偏移可直接当 offset 用：从 15 跳读正好落在 "## 第二节" 上
        val jumped = useCase(
            call("read_document", """{"document_id":"d1","offset":15,"length":20}""")
        ).getOrThrow()
        assertThat(jumped).contains("## 第二节\n第二节正文")
    }

    @Test
    fun `分页起点落在 emoji 低代理上时吸附到完整代码点`() = runTest {
        // 5 个 BMP 字符 + 1 个 emoji（UTF-16 占 2 char：index 5 高代理、6 低代理）+ 尾随字符
        val content = "abcde😀结束"
        coEvery { documentRepository.getDocumentById("d1") } returns Result.success(doc("d1", "emoji", null, content))

        // offset=6 落在低代理上（防御式吸附必须有）：退到 5，窗口从完整的 😀 开始
        val out = useCase(
            call("read_document", """{"document_id":"d1","offset":6,"length":4}""")
        ).getOrThrow()

        assertThat(out).contains("😀")
        assertThat(out).contains("【正文片段：第 6–9 字符，共 9 字符】")
        assertThat(out).contains("😀结束")
    }

    @Test
    fun `list_documents 返回文件夹结构与名称路径`() = runTest {
        coEvery { folderRepository.getAllFolders() } returns Result.success(
            listOf(
                Folder.create("f1", "工作", null, 0),
                Folder.create("f2", "项目A", "f1", 1)
            )
        )
        coEvery { documentRepository.getAllDocuments() } returns Result.success(
            listOf(
                doc("d1", "周报", "f2", "内容"),
                doc("d2", "随笔", null, "内容")
            )
        )

        val out = useCase(call("list_documents", "{}")).getOrThrow()

        // 文件夹结构与层级路径（名称而非 UUID）
        assertThat(out).contains("文件夹结构：")
        assertThat(out).contains("【工作】")
        assertThat(out).contains("【项目A】")
        assertThat(out).contains("工作/项目A")
        // 文档的所在文件夹按名称显示，UUID 不再出现
        assertThat(out).contains("所在文件夹: 工作/项目A")
        assertThat(out).contains("所在文件夹: 根目录")
        // 文档列表的「所在文件夹」必须是名称路径；文件夹结构节里的 ID 是给
        // list_documents(folder_id=…) 筛选用的，保留是刻意设计
        assertThat(out).doesNotContain("所在文件夹: f1")
        assertThat(out).doesNotContain("所在文件夹: f2")
    }
}
