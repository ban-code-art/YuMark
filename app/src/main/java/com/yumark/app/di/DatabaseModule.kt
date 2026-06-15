package com.yumark.app.di

import android.content.Context
import androidx.room.Room
import com.yumark.app.data.local.db.AppDatabase
import com.yumark.app.data.local.db.dao.ConversationDao
import com.yumark.app.data.local.db.dao.DocumentDao
import com.yumark.app.data.local.db.dao.FolderDao
import com.yumark.app.data.local.db.dao.ImageDao
import com.yumark.app.data.local.db.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "yumark_database"
        )
            // 添加所有已定义的迁移策略
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            // 仅在开发环境允许破坏性迁移（生产环境应删除此行）
            // .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideDocumentDao(database: AppDatabase): DocumentDao {
        return database.documentDao()
    }

    @Provides
    @Singleton
    fun provideFolderDao(database: AppDatabase): FolderDao {
        return database.folderDao()
    }

    @Provides
    @Singleton
    fun provideImageDao(database: AppDatabase): ImageDao {
        return database.imageDao()
    }

    @Provides
    @Singleton
    fun provideConversationDao(database: AppDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: AppDatabase): MessageDao {
        return database.messageDao()
    }
}
