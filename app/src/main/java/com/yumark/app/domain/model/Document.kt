package com.yumark.app.domain.model

import com.yumark.app.core.text.WordCount
import kotlinx.datetime.Instant

data class Document(
    val id: String,
    val name: String,
    val content: String,
    val folderId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isFavorite: Boolean,
    val wordCount: Int,
    val characterCount: Int
) {
    companion object {
        fun create(id: String, name: String, folderId: String? = null): Document {
            val now = kotlinx.datetime.Clock.System.now()
            return Document(
                id = id,
                name = name,
                content = "",
                folderId = folderId,
                createdAt = now,
                updatedAt = now,
                isFavorite = false,
                wordCount = 0,
                characterCount = 0
            )
        }
    }
}

/**
 * 按当前 [Document.content] 重算字数/字符数，并把 `updatedAt` 推到现在。
 *
 * 这一步必须在**保存前**做，且结果要写回内存里的文档，不能只写进数据库：
 * 编辑器保存成功后紧接着用同一个 `Document` 去落历史版本快照，字数取的是这个字段。
 * 从前重算只发生在 `SaveDocumentUseCase` 内部的一个临时副本上，内存里的 `wordCount`
 * 一直停在打开文档那一刻，于是历史列表每一行显示的字数都属于另一个版本。
 */
fun Document.withFreshCounts(): Document = copy(
    updatedAt = kotlinx.datetime.Clock.System.now(),
    wordCount = WordCount.of(content),
    characterCount = WordCount.charactersOf(content)
)

/**
 * 回收站里的一篇文档（元数据视图，不带正文）。
 *
 * 与 [Document] 分开建而不是给它挂 `deletedAt` 可空字段：库视图、搜索、同步、Agent 工具
 * 拿到的 [Document] 永远是活跃文档，类型上就排除了「列表代码还要处处判回收站态」的负担；
 * 只有回收站页面消费本模型。
 */
data class TrashedDocument(
    val id: String,
    val name: String,
    val deletedAt: Instant,
    val updatedAt: Instant,
    val wordCount: Int
)
