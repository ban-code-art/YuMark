package com.yumark.app.presentation.editor

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [QuickEditHeuristics] 的判定表。
 *
 * 这几条判定决定「划词 AI 回复下面有没有那张能一键替换选中正文的卡片」，两个方向都会伤人：
 * 漏判 → 模型改好了但用户只能手抄；误判 → 一段总结被当成改写，点「应用」就把原文换成了总结。
 * 从前它们是 `AiQuickViewModel` 的 private 方法，一条都没测过。
 */
class QuickEditHeuristicsTest {

    // ---- [[EDIT]] 协议 ----

    @Test
    fun `无 EDIT 标记时返回 null 表示这是问答`() {
        assertThat(QuickEditHeuristics.parseEditContent("这段话的意思是……")).isNull()
    }

    @Test
    fun `EDIT 标记内为空表示删除，返回空串而不是 null`() {
        // 空串必须与 null 区分：null = 不挂卡片，空串 = 挂卡片且应用后删除选中正文
        assertThat(QuickEditHeuristics.parseEditContent("[[EDIT]][[/EDIT]]")).isEqualTo("")
    }

    @Test
    fun `缺少结束标记时取到结尾，流式被截断也不丢改写`() {
        // 这里刻意宽松，与「被 max tokens 砍断的回复不许挂卡片」不矛盾，两件事分两层做：
        // 本函数只认标记、不知道这段文本是怎么结束的（模型漏写收尾标记、用户点停止，都该留住改写）；
        // 「服务端说了 finish_reason=length」这一位只有适配层拿得到，由 AiQuickViewModel 在
        // StreamEvent.Done 里按 event.truncated 把 edit 压成 null（见 AiQuickDialog.kt 的截断保护）。
        assertThat(QuickEditHeuristics.parseEditContent("[[EDIT]]改写后的文本"))
            .isEqualTo("改写后的文本")
    }

    @Test
    fun `stripEditMarkers 抹掉两端标记`() {
        assertThat(QuickEditHeuristics.stripEditMarkers("[[EDIT]] 正文 [[/EDIT]]"))
            .isEqualTo("正文")
    }

    // ---- apply_edit 工具参数 ----

    @Test
    fun `apply_edit 的空 new_text 是删除，不是解析失败`() {
        assertThat(QuickEditHeuristics.parseApplyEditArgs("""{"new_text":""}""")).isEqualTo("")
    }

    @Test
    fun `apply_edit 缺字段或非法 JSON 一律 null`() {
        assertThat(QuickEditHeuristics.parseApplyEditArgs("""{"other":1}""")).isNull()
        assertThat(QuickEditHeuristics.parseApplyEditArgs("not json")).isNull()
        assertThat(QuickEditHeuristics.parseApplyEditArgs("")).isNull()
    }

    // ---- 删除意图 ----

    @Test
    fun `删除动词加指向选区才算删除意图`() {
        assertThat(QuickEditHeuristics.isDeletionIntent("把这段删掉")).isTrue()
        assertThat(QuickEditHeuristics.isDeletionIntent("delete this")).isTrue()
        // 有动词但没指向选区：可能是"删除文档里所有的感叹号"这类改写要求
        assertThat(QuickEditHeuristics.isDeletionIntent("删除多余的空行")).isFalse()
        // 指向选区但不是删除
        assertThat(QuickEditHeuristics.isDeletionIntent("把这段改得更简洁")).isFalse()
    }

    // ---- 兜底启发式 ----

    @Test
    fun `以 Markdown 标题开头视为解释而非改写`() {
        assertThat(QuickEditHeuristics.hasExplanationStructure("# 分析\n这段话有三个问题")).isTrue()
        assertThat(QuickEditHeuristics.looksLikeRewrite("# 分析", selected = "原文")).isFalse()
    }

    @Test
    fun `解释性引导语视为解释而非改写`() {
        for (reply in listOf("以下是修改后的版本", "总结一下：这段在讲缓存", "我建议改成主动语态")) {
            assertThat(QuickEditHeuristics.hasExplanationStructure(reply)).isTrue()
        }
    }

    @Test
    fun `平铺直叙的改写照样认得出来`() {
        assertThat(QuickEditHeuristics.looksLikeRewrite("缓存命中率决定了首屏耗时。", selected = "缓存决定速度。"))
            .isTrue()
    }

    @Test
    fun `空回复不算改写`() {
        assertThat(QuickEditHeuristics.looksLikeRewrite("   ", selected = "原文")).isFalse()
    }

    @Test
    fun `长度超过选区 2 点 5 倍视为扩写型解释`() {
        val selected = "缓存命中率决定首屏耗时。"           // 12 字，> 16 的闸门要更长的选区
        val long = "缓".repeat(selected.length * 3)
        // 选区 <= 16 字时长度闸不生效：短选区改写后变长很正常（"缩写"→"这是一段缩写说明"）
        assertThat(QuickEditHeuristics.looksLikeRewrite(long, selected)).isTrue()

        val longSelected = "缓存命中率决定了首屏耗时，也决定了服务端压力。"  // > 16 字
        assertThat(QuickEditHeuristics.looksLikeRewrite("缓".repeat(longSelected.length * 3), longSelected))
            .isFalse()
        // 同一个选区，长度相当的回复仍是改写
        assertThat(QuickEditHeuristics.looksLikeRewrite("缓".repeat(longSelected.length), longSelected))
            .isTrue()
    }

    // ---- resolveEdit 的优先级 ----

    @Test
    fun `EDIT 标记优先于删除意图`() {
        // 用户说"删掉这段"、模型却给了 [[EDIT]] 新文本：以模型给的文本为准，不能当删除
        val r = QuickEditHeuristics.resolveEdit(
            text = "[[EDIT]]精简后的文本[[/EDIT]]",
            selected = "原来那段冗长的文本",
            userMessage = "把这段删掉"
        )
        assertThat(r).isEqualTo("精简后的文本")
    }

    @Test
    fun `删除意图在没有 EDIT 标记时给出空替换`() {
        val r = QuickEditHeuristics.resolveEdit(
            text = "好的，已删除。",
            selected = "要删掉的那段",
            userMessage = "把这一段删掉"
        )
        assertThat(r).isEqualTo("")
    }

    @Test
    fun `问答式回复不产生改写`() {
        val r = QuickEditHeuristics.resolveEdit(
            text = "以下是这段话的三个问题：语序、用词、标点。",
            selected = "原文",
            userMessage = "这段有什么问题"
        )
        assertThat(r).isNull()
    }

    @Test
    fun `纯空白回复不产生改写`() {
        assertThat(QuickEditHeuristics.resolveEdit("   ", "原文", "改写这段")).isNull()
    }
}
