package com.yumark.app.domain.usecase.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 守护 写工具 schema 与 parseWriteToolCall 读取的字段名一致——
 * 二者漂移会导致模型填了参数却解析不出 action。
 */
class DocumentContextToolsTest {

    private fun tool(name: String) =
        DocumentContextTools.getAllTools().first { it.name == name }

    @Suppress("UNCHECKED_CAST")
    private fun properties(name: String): Map<String, Any?> =
        tool(name).parameters["properties"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun required(name: String): List<String> =
        (tool(name).parameters["required"] as? List<String>).orEmpty()

    @Test
    fun `getAllTools includes write tools`() {
        val names = DocumentContextTools.getAllTools().map { it.name }
        assertThat(names).containsAtLeast("create_document", "edit_document")
    }

    @Test
    fun `create_document schema matches parser fields`() {
        assertThat(properties("create_document").keys).containsAtLeast("title", "content")
        assertThat(required("create_document")).contains("content")
    }

    @Test
    fun `edit_document schema is surgical edits array`() {
        val props = properties("edit_document")
        assertThat(props.keys).containsAtLeast("document_id", "edits")
        assertThat(required("edit_document")).contains("edits")

        @Suppress("UNCHECKED_CAST")
        val edits = props["edits"] as Map<String, Any?>
        assertThat(edits["type"]).isEqualTo("array")
        @Suppress("UNCHECKED_CAST")
        val itemProps = (edits["items"] as Map<String, Any?>)["properties"] as Map<String, Any?>
        assertThat(itemProps.keys).containsAtLeast("old_string", "new_string", "replace_all")
    }

    @Test
    fun `update_plan tool is exposed with steps schema`() {
        val names = DocumentContextTools.getAllTools().map { it.name }
        assertThat(names).contains("update_plan")
        assertThat(required("update_plan")).contains("steps")
    }
}
