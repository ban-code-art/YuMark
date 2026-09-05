package com.yumark.app.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.agentUiPrefsDataStore: DataStore<androidx.datastore.preferences.core.Preferences> by
    preferencesDataStore(name = "agent_ui_prefs")

/**
 * Agent 界面偏好的轻量持久化（独立于全局 UserSettings，避免污染编辑器设置）。
 * 当前仅持久化「任务执行流程面板是否收起」——用户收起后跨会话/跨新 Agent 保持收起。
 */
@Singleton
class AgentUiPrefsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val TASK_PANEL_COLLAPSED = booleanPreferencesKey("task_panel_collapsed")
    }

    /**
     * 面板是否收起。默认 false（展开）。
     *
     * [catch] 不是可选项：这条流被 `AgentChatSheet` 用 `stateIn(viewModelScope, Eagerly, false)`
     * 立刻订阅，读盘出错（磁盘满、文件被外部改坏触发 `CorruptionException`）时异常会直接窜到
     * 那个 collect 里，结果是**每次打开 AI 对话都崩**，而且崩因跟界面毫无关系。
     * IOException 一律退回默认值——一个界面偏好读不到，不值得让整页打不开；
     * 非 IO 异常照旧抛出，那才是真该暴露的编程错误。
     */
    val taskPanelCollapsedFlow: Flow<Boolean> = context.agentUiPrefsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.TASK_PANEL_COLLAPSED] ?: false }

    suspend fun setTaskPanelCollapsed(collapsed: Boolean) {
        context.agentUiPrefsDataStore.edit { it[Keys.TASK_PANEL_COLLAPSED] = collapsed }
    }
}
