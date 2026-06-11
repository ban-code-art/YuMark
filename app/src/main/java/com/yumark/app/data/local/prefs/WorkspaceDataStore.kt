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
}
