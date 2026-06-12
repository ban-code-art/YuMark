package com.yumark.app.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.workspaceDataStore: DataStore<Preferences> by preferencesDataStore(name = "workspace")

/**
 * 持久化当前工作区的 SAF 树 URI，应用重启后恢复
 */
@Singleton
class WorkspaceDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val TREE_URI = stringPreferencesKey("workspace_tree_uri")
        val DEFAULT_DIR_URI = stringPreferencesKey("default_dir_uri")
    }

    val treeUriFlow: Flow<String?> = context.workspaceDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.TREE_URI] }

    suspend fun saveTreeUri(uri: String) {
        context.workspaceDataStore.edit { it[Keys.TREE_URI] = uri }
    }

    suspend fun clearTreeUri() {
        context.workspaceDataStore.edit { it.remove(Keys.TREE_URI) }
    }

    /**
     * 默认目录 URI（用户在设置里显式指定，启动时优先恢复）。
     * 与 [treeUriFlow]（当前会话临时打开的工作区）分开：临时打开别的文件夹不改变默认目录。
     */
    val defaultDirUriFlow: Flow<String?> = context.workspaceDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.DEFAULT_DIR_URI] }

    suspend fun saveDefaultDirUri(uri: String) {
        context.workspaceDataStore.edit { it[Keys.DEFAULT_DIR_URI] = uri }
    }

    suspend fun clearDefaultDirUri() {
        context.workspaceDataStore.edit { it.remove(Keys.DEFAULT_DIR_URI) }
    }
}
