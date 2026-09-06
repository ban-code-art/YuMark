package com.yumark.app.presentation.trash

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.domain.model.TrashedDocument
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 回收站页的 Compose UI 用例（首批 UI 自动化）。
 *
 * 刻意只测**纯组合函数层**（[TrashEmptyContent] / [TrashItemRow] / [TrashList]）而不拉起
 * 整个 [TrashScreen]：后者要 Hilt 容器 + ViewModel，测试 APK 就得引入整套 Hilt 测试管线；
 * 而回收站的交互风险集中在「按钮在不在、点了回调对不对」这一层，状态编排已由 JVM 单测
 * 与 ViewModel 契约覆盖。等第一个全屏 Hilt UI 用例的需求出现时再补测试基建。
 */
@RunWith(AndroidJUnit4::class)
class TrashScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    // createComposeRule() 的 rule.activity 在本版本不可靠，直接取 instrumentation 上下文取文案
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun trashed(name: String) = TrashedDocument(
        id = name,   // LazyColumn 的 key 取 id：必须逐条唯一（此前写死同一个 id 会让列表崩溃）
        name = name,
        deletedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        wordCount = 10
    )

    @Test
    fun emptyTrashShowsHint() {
        rule.setContent { MaterialTheme { Surface { TrashEmptyContent() } } }

        rule.onNodeWithText(context.getString(R.string.trash_empty))
            .assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.trash_empty_hint))
            .assertIsDisplayed()
    }

    @Test
    fun itemRowExposesRestoreAndPurgeActions() {
        var restored = false
        var purged: TrashedDocument? = null
        val item = trashed("笔记")

        rule.setContent {
            MaterialTheme {
                Surface {
                    // TrashItemRow 的 onPurge 是 () -> Unit（item 由外层闭包捕获），
                    // 这里如实按无参回调接，断言用外层的 item 副本
                    TrashItemRow(
                        item = item,
                        onRestore = { restored = true },
                        onPurge = { purged = item }
                    )
                }
            }
        }

        rule.onNodeWithText("笔记").assertIsDisplayed()
        // 动作按钮以 testTag 为锚（内容描述会随语区变化，tag 稳定）
        rule.onNodeWithTag(TrashTags.RESTORE).performClick()
        rule.onNodeWithTag(TrashTags.PURGE).performClick()

        assertThat(restored).isTrue()
        assertThat(purged?.id).isEqualTo("笔记")   // trashed() 助手以名字作 id（列表 key 逐条唯一）
        assertThat(purged?.name).isEqualTo("笔记")
    }

    @Test
    fun listRendersEveryItem() {
        val items = listOf(trashed("第一篇"), trashed("第二篇"))

        rule.setContent {
            MaterialTheme {
                Surface { TrashList(items = items, onRestore = {}, onPurge = {}) }
            }
        }

        rule.onNodeWithText("第一篇").assertIsDisplayed()
        rule.onNodeWithText("第二篇").assertIsDisplayed()
    }
}
