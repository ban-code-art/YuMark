package com.yumark.app.di

import android.content.Context
import com.yumark.app.data.remote.UpdateChecker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideUpdateChecker(
        @ApplicationContext context: Context
    ): UpdateChecker {
        return UpdateChecker(context)
    }
}
