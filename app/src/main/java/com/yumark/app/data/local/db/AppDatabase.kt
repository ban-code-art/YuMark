package com.yumark.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.db.dao.FolderDao
import com.yumark.app.data.local.db.dao.ImageDao
import com.yumark.app.data.local.db.entity.DocumentEntity
import com.yumark.app.data.local.db.entity.FolderEntity
import com.yumark.app.data.local.db.entity.ImageEntity

@Database(
    entities = [DocumentEntity::class, FolderEntity::class, ImageEntity::class],
    version = 1,
    exportSchema = true  // 启用 schema 导出，支持数据库迁移
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun folderDao(): FolderDao
    abstract fun imageDao(): ImageDao

    companion object {
        /**
         * 数据库迁移示例
         * 当需要升级数据库版本时，添加相应的迁移策略
         *
         * 示例：从版本 1 升级到版本 2
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 示例：添加新列
                // db.execSQL("ALTER TABLE documents ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 示例：从版本 2 升级到版本 3
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 示例：创建新表
                // db.execSQL("""
                //     CREATE TABLE IF NOT EXISTS tags (
                //         id TEXT PRIMARY KEY NOT NULL,
                //         name TEXT NOT NULL,
                //         color TEXT NOT NULL
                //     )
                // """.trimIndent())
            }
        }

        /**
         * 获取所有已定义的迁移
         * 在 DatabaseModule 中使用：
         * Room.databaseBuilder(...).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
         */
        val ALL_MIGRATIONS = emptyArray<androidx.room.migration.Migration>()
    }
}
