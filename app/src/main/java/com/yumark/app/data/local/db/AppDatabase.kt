package com.yumark.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yumark.app.data.local.db.dao.ConversationDao
import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.db.dao.FolderDao
import com.yumark.app.data.local.db.dao.ImageDao
import com.yumark.app.data.local.db.dao.MessageDao
import com.yumark.app.data.local.db.entity.ConversationEntity
import com.yumark.app.data.local.db.entity.DocumentEntity
import com.yumark.app.data.local.db.entity.FolderEntity
import com.yumark.app.data.local.db.entity.ImageEntity
import com.yumark.app.data.local.db.entity.MessageEntity

@Database(
    entities = [
        DocumentEntity::class,
        FolderEntity::class,
        ImageEntity::class,
        ConversationEntity::class,
        MessageEntity::class
    ],
    version = 4,
    exportSchema = true  // 启用 schema 导出，支持数据库迁移
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun folderDao(): FolderDao
    abstract fun imageDao(): ImageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

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
         * 获取所有已定义的迁移
         * 在 DatabaseModule 中使用：
         * Room.databaseBuilder(...).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
         */
        val ALL_MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
