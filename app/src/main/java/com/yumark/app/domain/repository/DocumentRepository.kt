package com.yumark.app.domain.repository

import com.yumark.app.domain.model.Document
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    suspend fun getDocumentById(id: String): Result<Document>
    fun observeDocument(id: String): Flow<Document?>
    fun observeAllDocuments(): Flow<List<Document>>
    suspend fun getAllDocuments(): Result<List<Document>>

    /** 仅元数据（content 为空字符串），用于树/列表等不需要正文的场景，避免全量文件 IO */
    suspend fun getAllDocumentMetas(): Result<List<Document>>
    suspend fun getDocumentsByFolder(folderId: String?): Result<List<Document>>
    suspend fun searchDocuments(query: String): Result<List<Document>>
    suspend fun createDocument(name: String, folderId: String?): Result<Document>

    /**
     * 保存正文与统计。**刻意不改名**——名字只认库里那一行，改名走 [renameDocument]。
     *
     * 这是「自动保存把改名回滚掉」的防线：平板/折叠屏展开态下左窗格文件列表与右窗格编辑器
     * 同时活着（见 `AppShell`），在列表里改完名，编辑器内存里的 `name` 还是旧的，
     * 它下一次自动保存就会把旧名字写回库里，而界面上不会有任何提示。
     */
    suspend fun saveDocument(document: Document): Result<Unit>

    /**
     * 改名：只动库里那一行的 `name`，不读、不写、不重排正文文件。
     *
     * 独立于 [saveDocument] 的理由是数据丢失：曾经的改名是「读盘取正文 → copy(name=…) →
     * 整篇回写」，而磁盘上那份正文在编辑器有未保存修改时是**旧的**，改一次名就把用户
     * 正在写的东西按旧内容盖掉。名字与正文分开走，这条路径压根碰不到正文。
     *
     * 同名（`newName` 与现名相同）时直接成功返回，不查重、不写库。
     */
    suspend fun renameDocument(id: String, newName: String): Result<Unit>

    /** 把文档移动到目标文件夹(null 为根目录),仅更新归属,不重写正文文件。 */
    suspend fun moveDocument(id: String, targetFolderId: String?): Result<Unit>
    suspend fun deleteDocument(id: String): Result<Unit>
    suspend fun toggleFavorite(id: String): Result<Unit>
}
