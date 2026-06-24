package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.AgentActionType
import org.junit.jupiter.api.Test

class ExtractImplicitWriteActionTest {

    @Test
    fun `extractDocumentBody unwraps fenced markdown block`() {
        val text = """
            我已综合资料为你创建一份文档。文档标题定为《主流AI》，你可预览并确认是否保存。

            ```markdown
            # 主流AI发展全景

            **编制**: YuMark
            正文内容。
            ```
        """.trimIndent()

        val body = extractDocumentBody(text)

        assertThat(body).startsWith("# 主流AI发展全景")
        assertThat(body).doesNotContain("你可预览并确认")
        assertThat(body).doesNotContain("```")
    }

    @Test
    fun `extractDocumentBody preserves inner code block when fenced doc contains one`() {
        // 模型把整篇文档用 ```markdown 围栏包裹，正文内部又含 ```kotlin 代码块。
        // 非贪婪正则会在内部首个 ``` 处误闭合，截断其后全部正文。
        val text = """
            我已为你创建文档，请预览确认。

            ```markdown
            # Kotlin 笔记

            示例代码：

            ```kotlin
            fun main() {
                println("hello")
            }
            ```

            更多正文内容，应当全部保留，不应被代码块截断。
            ```
        """.trimIndent()

        val body = extractDocumentBody(text)

        assertThat(body).startsWith("# Kotlin 笔记")
        assertThat(body).contains("```kotlin")
        assertThat(body).contains("println(\"hello\")")
        assertThat(body).contains("更多正文内容")
    }

    @Test
    fun `extractDocumentBody strips preamble before first heading`() {
        val text = """
            好的，我来帮你写。下面是文档：

            # 标题

            正文。
        """.trimIndent()

        val body = extractDocumentBody(text)

        assertThat(body).startsWith("# 标题")
        assertThat(body).doesNotContain("好的，我来帮你写")
    }

    @Test
    fun `extractDocumentBody returns text as-is when no fence or heading`() {
        val text = "纯文本，没有结构。"
        assertThat(extractDocumentBody(text)).isEqualTo(text)
    }

    @Test
    fun `conversationalPreamble returns text before fence`() {
        val text = "开场白一句话。\n\n```markdown\n# 标题\n```"
        assertThat(conversationalPreamble(text)).isEqualTo("开场白一句话。")
    }

    @Test
    fun `implicit create action uses clean body and heading title`() {
        val text = """
            我已综合资料为你创建一份专业文档，你可预览并确认是否保存为新文档。

            ```markdown
            # 主流AI发展全景（2026版）

            **编制**: YuMark
            这里是足够长的正文内容用于通过文档结构与长度判定，确保被识别为文档而非短回复。
            ```
        """.trimIndent()

        val action = extractImplicitWriteAction(
            text = text,
            userMessage = "帮我创建一份主流ai的文档",
            currentDocumentId = null,
            currentDocumentName = null
        )

        assertThat(action).isNotNull()
        assertThat(action!!.type).isEqualTo(AgentActionType.CREATE_DOCUMENT)
        // 文档正文不含开场白与围栏
        assertThat(action.content).startsWith("# 主流AI发展全景（2026版）")
        assertThat(action.content).doesNotContain("你可预览并确认")
        assertThat(action.content).doesNotContain("```")
        // 标题取自正文首个 Markdown 标题，而非开场白首行
        assertThat(action.description).isEqualTo("主流AI发展全景（2026版）")
    }

    @Test
    fun `returns null for ordinary question without write intent`() {
        val action = extractImplicitWriteAction(
            text = "人工智能是研究智能体的学科……（一段解释）",
            userMessage = "什么是人工智能？",
            currentDocumentId = null,
            currentDocumentName = null
        )
        assertThat(action).isNull()
    }

    private val docText = """
        好的，已为你优化：

        ```markdown
        # 标题

        这是更新后的完整文档正文，包含未改动部分与新增内容，长度足够通过文档判定阈值，确保识别为文档。
        ```
    """.trimIndent()

    @Test
    fun `add-content request on open document becomes EDIT action`() {
        val action = extractImplicitWriteAction(
            text = docText,
            userMessage = "给这部分增加一些内容",   // “增加”原先不在动词表内
            currentDocumentId = "doc-7",
            currentDocumentName = "我的笔记"
        )

        assertThat(action).isNotNull()
        assertThat(action!!.type).isEqualTo(AgentActionType.EDIT_DOCUMENT)
        assertThat(action.targetDocumentId).isEqualTo("doc-7")
        assertThat(action.content).startsWith("# 标题")
        assertThat(action.content).doesNotContain("好的，已为你优化")
    }

    @Test
    fun `optimize request ending with question mark still becomes EDIT`() {
        val action = extractImplicitWriteAction(
            text = docText,
            userMessage = "帮我优化一下好吗？",       // 句尾问号原先会被当成提问否决
            currentDocumentId = "doc-7",
            currentDocumentName = "我的笔记"
        )

        assertThat(action).isNotNull()
        assertThat(action!!.type).isEqualTo(AgentActionType.EDIT_DOCUMENT)
        assertThat(action.targetDocumentId).isEqualTo("doc-7")
    }

    @Test
    fun `explanation request on open document is not treated as edit`() {
        val action = extractImplicitWriteAction(
            text = "人工智能是……（解释）",
            userMessage = "解释一下这篇文档讲了什么",
            currentDocumentId = "doc-7",
            currentDocumentName = "我的笔记"
        )
        assertThat(action).isNull()
    }
}
