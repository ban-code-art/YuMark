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
    suspend fun toggleFavorite(id: String): Result<Unit>

    // ===== 回收站 =====
    //
    // 「删除」分两段：moveToTrash 只是把文档藏进回收站（可恢复，远端文件原样保留）；
    // purgeDocument 才是真正的删除（清理正文/图片/全文索引/RAG，同步过的文档立墓碑把
    // 删除推到远端）。在 purgeDocument 之前，用户的任何一次手滑都有回头路。

    /** 移入回收站。文档从所有库视图、搜索与同步清单中消失，但正文与历史全部保留。 */
    suspend fun moveToTrash(id: String): Result<Unit>

    /** 从回收站恢复。原文件夹已被删时落在根目录；原名被占用时自动改名落座。 */
    suspend fun restoreFromTrash(id: String): Result<Unit>

    /** 彻底删除：不可逆。清库行、图片文件、正文文件、全文索引与 RAG 索引，同步侧立墓碑。 */
    suspend fun purgeDocument(id: String): Result<Unit>

    /** 回收站全部内容（按移入时间倒序）。 */
    suspend fun getTrashedDocuments(): Result<List<com.yumark.app.domain.model.TrashedDocument>>

    /** 回收站实时计数：文件列表的入口角标据此提醒「里面有东西」。 */
    fun observeTrashCount(): Flow<Int>

    /**
     * 这篇文档是否在回收站。给「自动保存要不要继续喂索引」这类守护判断用：
     * 双窗格下文档从列表侧被移入回收站时，右侧编辑器还活着，自动保存链路会照常触发——
     * 索引侧（FTS/RAG）必须据此跳过，否则已删除的文档每轮保存都被重新写回索引。
     */
    suspend fun isTrashed(id: String): Boolean

    /** 清空回收站：逐篇彻底删除，遇到第一篇失败即停（已删的保持已删，重试幂等）。 */
    suspend fun emptyTrash(): Result<Unit>

    /** 清理移入时间早于 [nowMs] - [retentionMs] 的回收站文档，供到期自动清理调用。 */
    suspend fun purgeTrashExpired(nowMs: Long, retentionMs: Long): Result<Int>
}
