package com.yumark.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yumark.app.data.local.db.dao.AgentTaskDao
import com.yumark.app.data.local.db.dao.ConversationDao
import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.db.dao.DocumentSearchDao
import com.yumark.app.data.local.db.dao.DocumentVersionDao
import com.yumark.app.data.local.db.dao.FolderDao
import com.yumark.app.data.local.db.dao.ImageDao
import com.yumark.app.data.local.db.dao.MemoryDao
import com.yumark.app.data.local.db.dao.MessageDao
import com.yumark.app.data.local.db.dao.RagDao
import com.yumark.app.data.local.db.dao.SyncStateDao
import com.yumark.app.data.local.db.dao.SyncTombstoneDao
import com.yumark.app.data.local.db.entity.AgentEvidenceEntity
import com.yumark.app.data.local.db.entity.AgentTaskEntity
import com.yumark.app.data.local.db.entity.AgentTaskStepEntity
import com.yumark.app.data.local.db.entity.ChunkEntity
import com.yumark.app.data.local.db.entity.ConversationEntity
import com.yumark.app.data.local.db.entity.DocumentEntity
import com.yumark.app.data.local.db.entity.DocumentSearchEntity
import com.yumark.app.data.local.db.entity.DocumentVersionEntity
import com.yumark.app.data.local.db.entity.EmbeddingEntity
import com.yumark.app.data.local.db.entity.EmbeddingJobEntity
import com.yumark.app.data.local.db.entity.FolderEntity
import com.yumark.app.data.local.db.entity.ImageEntity
import com.yumark.app.data.local.db.entity.MemoryEntity
import com.yumark.app.data.local.db.entity.MessageEntity
import com.yumark.app.data.local.db.entity.SyncStateEntity
import com.yumark.app.data.local.db.entity.SyncTombstoneEntity

@Database(
    entities = [
        DocumentEntity::class,
        FolderEntity::class,
        ImageEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        AgentTaskEntity::class,
        AgentTaskStepEntity::class,
        AgentEvidenceEntity::class,
        DocumentVersionEntity::class,
        SyncStateEntity::class,
        SyncTombstoneEntity::class,
        MemoryEntity::class,
        ChunkEntity::class,
        EmbeddingEntity::class,
        EmbeddingJobEntity::class,
        DocumentSearchEntity::class
    ],
    version = 14,
    exportSchema = true  // 启用 schema 导出，支持数据库迁移
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun folderDao(): FolderDao
    abstract fun imageDao(): ImageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun documentVersionDao(): DocumentVersionDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun syncTombstoneDao(): SyncTombstoneDao
    abstract fun memoryDao(): MemoryDao
    abstract fun ragDao(): RagDao
    abstract fun documentSearchDao(): DocumentSearchDao

    companion object {
        /**
         * 版本 1 → 2：新增 AI 对话功能的 conversations / messages 表。
         * 不改动既有 documents/folders/images 表，确保用户数据保留。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversations (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        type TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS messages (
                        id TEXT PRIMARY KEY NOT NULL,
                        conversationId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        agentActionJson TEXT,
                        timestamp INTEGER NOT NULL,
                        FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_conversationId ON messages(conversationId)"
                )
            }
        }

        /**
         * 版本 2 → 3：为 conversations 表添加关联文档信息字段
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 添加新字段（默认值为 NULL）
                db.execSQL("ALTER TABLE conversations ADD COLUMN relatedDocumentId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE conversations ADD COLUMN relatedDocumentName TEXT DEFAULT NULL")
            }
        }

        /**
         * 版本 3 → 4：为 conversations 表添加状态字段
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 添加状态字段（默认为 IDLE）
                db.execSQL("ALTER TABLE conversations ADD COLUMN status TEXT NOT NULL DEFAULT 'IDLE'")
            }
        }

        /**
         * 版本 4 → 5：messages 表加 agent 步骤(stepsJson) 与附件(attachmentsJson) 列。
         * attachmentsJson 为 attachment Phase 2 预留（D2：两项 schema 变更合并到同一迁移版本）。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN stepsJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT DEFAULT NULL")
            }
        }

        /**
         * 版本 5 -> 6：新增 Agent 任务、步骤和证据表。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        conversation_id TEXT NOT NULL,
                        goal TEXT NOT NULL,
                        status TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        current_step_id TEXT,
                        plan_version INTEGER NOT NULL,
                        final_summary TEXT,
                        blocking_reason TEXT,
                        FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_agent_tasks_conversation_id ON agent_tasks(conversation_id)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_task_steps (
                        id TEXT NOT NULL PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        status TEXT NOT NULL,
                        step_order INTEGER NOT NULL,
                        depends_on_step_ids_json TEXT NOT NULL,
                        completion_criteria TEXT NOT NULL,
                        result_summary TEXT,
                        tool_hints_json TEXT NOT NULL,
                        FOREIGN KEY(task_id) REFERENCES agent_tasks(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_task_steps_task_id ON agent_task_steps(task_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_task_steps_task_id_step_order ON agent_task_steps(task_id, step_order)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_evidence (
                        id TEXT NOT NULL PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        step_id TEXT,
                        type TEXT NOT NULL,
                        content TEXT NOT NULL,
                        source_tool TEXT,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(task_id) REFERENCES agent_tasks(id) ON DELETE CASCADE,
                        FOREIGN KEY(step_id) REFERENCES agent_task_steps(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_evidence_task_id ON agent_evidence(task_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_evidence_step_id ON agent_evidence(step_id)")
            }
        }

        /**
         * 版本 6 → 7：新增文档历史版本表 document_versions（本地内容快照）。
         * 不改动既有表，确保用户数据保留。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS document_versions (
                        id TEXT NOT NULL PRIMARY KEY,
                        document_id TEXT NOT NULL,
                        content TEXT NOT NULL,
                        word_count INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_document_versions_document_id_created_at ON document_versions(document_id, created_at)"
                )
            }
        }

        /**
         * 版本 7 → 8：新增 WebDAV 同步态表 sync_state（每文档一条，记录远端路径/etag/本地哈希/上次同步时间）。
         * 不改动既有表，确保用户数据保留。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_state (
                        document_id TEXT NOT NULL PRIMARY KEY,
                        remote_path TEXT NOT NULL,
                        remote_etag TEXT,
                        local_hash TEXT,
                        last_synced_at INTEGER,
                        FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * 版本 8 → 9：新增长期记忆表 memories（移植自 guanmo memoryService）。
         * 与 RAG 知识库分开，无 chunk、无 document 关联。
         *
         * 表结构必须与 [MemoryEntity] 逐字一致（无 DB 默认值、无索引）——否则 Room 在
         * 迁移后做 schema 校验会抛 "Migration didn't properly handle: memories" 导致启动闪退。
         * 列默认值由 MemoryEntity 构造器在插入时提供（locked=false / status="active"）。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memories (
                        id TEXT NOT NULL PRIMARY KEY,
                        content TEXT NOT NULL,
                        category TEXT NOT NULL,
                        source TEXT NOT NULL,
                        locked INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * 版本 9 → 10：新增 RAG 知识库三表——分块、向量、索引任务（移植自 guanmo vectorStore/chunker）。
         * 均外键关联 documents，删除文档时级联清除其分块、向量与任务。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rag_chunks (
                        id TEXT NOT NULL PRIMARY KEY,
                        document_id TEXT NOT NULL,
                        content TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        title_path TEXT NOT NULL,
                        heading TEXT,
                        source_type TEXT NOT NULL,
                        chunk_index INTEGER NOT NULL,
                        start_line INTEGER NOT NULL,
                        end_line INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rag_chunks_document_id ON rag_chunks(document_id)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rag_embeddings (
                        chunk_id TEXT NOT NULL PRIMARY KEY,
                        embedding TEXT NOT NULL,
                        model TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(chunk_id) REFERENCES rag_chunks(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rag_embedding_jobs (
                        id TEXT NOT NULL PRIMARY KEY,
                        document_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        content_hash TEXT,
                        error TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rag_embedding_jobs_document_id ON rag_embedding_jobs(document_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rag_embedding_jobs_status ON rag_embedding_jobs(status)")
            }
        }

        /**
         * 版本 10 → 11：新增文档全文索引虚拟表 document_search（FTS4 + unicode61）。
         *
         * 建表语句必须与 Room 为 [DocumentSearchEntity] 生成的语句一致，否则启动时 schema
         * 校验抛 "Migration didn't properly handle: document_search"（与 [MIGRATION_8_9]
         * 同一个坑）。只有**升级**路径会执行这里；全新安装走 Room 自己生成的建表语句，
         * 所以这条语句写错只炸老用户，本地全新安装测不出来。
         *
         * 正确写法的来源是导出的 schema（`app/schemas/…/11.json` 的 createSql），不是读
         * Room 源码推导——见 migrate() 里的注释。
         *
         * 另外：列名不能写成 docId —— FTS4 保留 docid（不分大小写）作为 rowid 别名，
         * 建表会直接 "vtable constructor failed"。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 这条语句是从 `app/schemas/com.yumark.app.data.local.db.AppDatabase/11.json`
                // 里 document_search 实体的 createSql 原样抄来的（把 ${TABLE_NAME} 换成表名），
                // 不是照 Room 源码推导出来的——推导过一次，得到的是列间「逗号不带空格」，
                // 与真实产物差一个空格。导出的 schema 才是唯一可核对的事实来源，
                // 以后改这张表：先跑一次构建让 Room 重新导出 11.json，再对着它改这里。
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `document_search` USING FTS4(" +
                        "`doc_id` TEXT NOT NULL, `name` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "tokenize=unicode61)"
                )
                // 不在迁移里回填索引：正文在文件系统而非数据库，SQL 拿不到；
                // 由 DocumentRepositoryImpl 首次搜索时惰性回填（见 ensureSearchIndex）。
            }
        }

        /**
         * 版本 11 → 12：给 `documents(folder_id, name)` 与 `folders(parent_id, name)` 建**唯一索引**。
         *
         * 为什么需要：`requireNoDocumentNameConflict` / `requireNoFolderNameConflict` 都是
         * 「先查再写」，两个协程并发时各自都查到「没有重名」，然后各插一行——同名于是照样出现，
         * 而导出与 WebDAV 同步都拿名字当文件名，两篇同名会互相覆盖。唯一索引是最后一道闸。
         *
         * 两件必须处理的事，写错任何一件都是老用户升级后启动即崩：
         * 1. **老库里可能已经有重复行**（旧版本没有那两个检查）。直接 CREATE UNIQUE INDEX 会在
         *    这些设备上抛 SQLiteConstraintException，迁移失败 → 数据库打不开 → 应用起不来。
         *    所以先 [dedupeSiblingNames] 消重再建索引。
         * 2. **索引名必须与 Room 为实体生成的一致**（`index_<表>_<列>_<列>`），否则迁移跑完
         *    Room 的 schema 校验会报 "Migration didn't properly handle"。这两条语句的格式照
         *    导出 schema 里索引的 createSql 抄（反引号、`, ` 分隔、IF NOT EXISTS 都要对得上），
         *    由 MigrationContractTest 逐字比对钉住。
         *
         * 已知局限（刻意接受）：folder_id / parent_id 可空，而 SQLite 的唯一索引不认为两个 NULL
         * 相等，所以**根目录那一组不受这两条索引保护**，仍只靠代码里的 requireNo*NameConflict。
         * 表达式索引 `IFNULL(folder_id, '')` 能覆盖根目录，但 Room 的 schema 校验器不认表达式
         * 索引；把根目录的 NULL 换成空串是更大的重构，另案处理。消重同样只做非 NULL 组——
         * 唯一索引不会因 NULL 组失败，没必要为此改用户的数据。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                dedupeSiblingNames(db, table = "documents", parentColumn = "folder_id")
                dedupeSiblingNames(db, table = "folders", parentColumn = "parent_id")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_documents_folder_id_name` " +
                        "ON `documents` (`folder_id`, `name`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_parent_id_name` " +
                        "ON `folders` (`parent_id`, `name`)"
                )
            }
        }

        /**
         * 版本 12 → 13：新增 `sync_tombstones`，让「本地删掉的文档」能把删除推到远端。
         *
         * 在此之前删除是**唯一**一种在本地不留痕迹的改动：文档行一删，`sync_state` 被
         * CASCADE 带走，下一次同步看到「远端有文件、本地无文档」，只能当成另一台设备的新建
         * 拉回来——删掉的文档就地复活。墓碑表把「删过」显式记下来（见 [SyncTombstoneEntity]）。
         *
         * 建表语句必须与 Room 为实体生成的那一条**逐字相同**（反引号、`, ` 分隔、
         * PRIMARY KEY 写成表级约束），否则升级用户在迁移后的 schema 校验里撞
         * "Migration didn't properly handle: sync_tombstones" 闪退，而全新安装一切正常、
         * 本地永远测不出来。唯一可核对的事实来源是导出的 `app/schemas/…/13.json` 里的
         * `createSql`，由 `MigrationContractTest` 逐字比对。
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_tombstones` " +
                        "(`document_id` TEXT NOT NULL, `remote_path` TEXT NOT NULL, " +
                        "`deleted_at` INTEGER NOT NULL, PRIMARY KEY(`document_id`))"
                )
            }
        }

        /**
         * 版本 13 → 14：`documents` 新增回收站两列（`deleted_at` / `original_name`）。
         *
         * 在此之前「删除文档」是一步到位的硬删除：版本史被 CASCADE 带走、远端文件被墓碑
         * 推着删掉，单设备用户手滑一删，那份内容在任何地方都不再存在。从这一版起删除分成
         * 两段：UI 的删除先软删除进回收站（可恢复），只有回收站里的彻底删除才走原来的
         * deleteWithTombstone + 磁盘清理路径。列语义与同步侧的配合方式写在
         * [DocumentEntity] 上，DAO 写入路径见 [DocumentDao.trashById]。
         *
         * ADD COLUMN 带默认 NULL：历史行两列即为 null（活跃态），升级前后数据零变化。
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `documents` ADD COLUMN `deleted_at` INTEGER")
                db.execSQL("ALTER TABLE `documents` ADD COLUMN `original_name` TEXT")
            }
        }

        /**
         * 把 [table] 里同一 [parentColumn] 下的重名行改名，直到 `(parentColumn, name)` 无重复。
         *
         * 为什么在 Kotlin 里算而不是纯 SQL：「改完之后不能撞上第三个已存在的同名」需要
         * `WHILE EXISTS` 式的循环，SQLite 的 UPDATE 里写不出来。所以读出全部候选行、
         * 在 [resolveDuplicateSiblingNames] 里算好新名字、再逐条 UPDATE。
         *
         * 只取 `parentColumn IS NOT NULL` 的行：NULL 组不会让唯一索引失败（NULL != NULL），
         * 不该顺手改用户数据。ORDER BY 决定「同名之中谁保留原名」——按 name 聚在一起、
         * 组内按 created_at 再按 id，即最早创建的那条留原名，且结果与行的物理顺序无关。
         *
         * 表名/列名是本函数的两个字面量实参，不是外部输入，直接拼进 SQL；名字这类用户数据
         * 一律走 bindArgs（用 `arrayOf<Any>` 而非 `arrayOf<Any?>`：两个值都非空，
         * 这样在 androidx.sqlite 的 Java 版与 Kotlin 版签名下都编得过）。
         */
        private fun dedupeSiblingNames(
            db: SupportSQLiteDatabase,
            table: String,
            parentColumn: String
        ) {
            val rows = mutableListOf<SiblingNameRow>()
            db.query(
                "SELECT `id`, `$parentColumn`, `name` FROM `$table` " +
                    "WHERE `$parentColumn` IS NOT NULL " +
                    "ORDER BY `$parentColumn`, `name`, `created_at`, `id`"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += SiblingNameRow(
                        id = cursor.getString(0),
                        groupKey = cursor.getString(1),
                        name = cursor.getString(2)
                    )
                }
            }
            resolveDuplicateSiblingNames(rows).forEach { (id, newName) ->
                db.execSQL(
                    "UPDATE `$table` SET `name` = ? WHERE `id` = ?",
                    arrayOf<Any>(newName, id)
                )
            }
        }

        /**
         * 获取所有已定义的迁移
         * 在 DatabaseModule 中使用：
         * Room.databaseBuilder(...).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
         */
        val ALL_MIGRATIONS = arrayOf<Migration>(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14
        )
    }
}
