package com.yumark.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yumark.app.data.local.db.dao.AgentTaskDao
import com.yumark.app.data.local.db.dao.ConversationDao
import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.db.dao.DocumentVersionDao
import com.yumark.app.data.local.db.dao.FolderDao
import com.yumark.app.data.local.db.dao.ImageDao
import com.yumark.app.data.local.db.dao.MessageDao
import com.yumark.app.data.local.db.dao.SyncStateDao
import com.yumark.app.data.local.db.entity.AgentEvidenceEntity
import com.yumark.app.data.local.db.entity.AgentTaskEntity
import com.yumark.app.data.local.db.entity.AgentTaskStepEntity
import com.yumark.app.data.local.db.entity.ConversationEntity
import com.yumark.app.data.local.db.entity.DocumentEntity
import com.yumark.app.data.local.db.entity.DocumentVersionEntity
import com.yumark.app.data.local.db.entity.FolderEntity
import com.yumark.app.data.local.db.entity.ImageEntity
import com.yumark.app.data.local.db.entity.MessageEntity
import com.yumark.app.data.local.db.entity.SyncStateEntity

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
        SyncStateEntity::class
    ],
    version = 8,
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
            MIGRATION_7_8
        )
    }
}
