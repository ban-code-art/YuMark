package com.yumark.app.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** [TextSnapshot] 单测。 */
class TextSnapshotTest {

    @Test
    fun `选区首尾相同即为折叠光标`() {
        assertThat(TextSnapshot("abc", 1, 1).isCollapsed).isTrue()
        assertThat(TextSnapshot("abc", 1, 3).isCollapsed).isFalse()
    }

    @Test
    fun `EMPTY 是空文本加零位光标`() {
        assertThat(TextSnapshot.EMPTY).isEqualTo(TextSnapshot("", 0, 0))
        assertThat(TextSnapshot.EMPTY.isCollapsed).isTrue()
    }

    @Test
    fun `atEnd 把光标放在文末`() {
        assertThat(TextSnapshot.atEnd("hello")).isEqualTo(TextSnapshot("hello", 5, 5))
        assertThat(TextSnapshot.atEnd("")).isEqualTo(TextSnapshot.EMPTY)
    }
}
