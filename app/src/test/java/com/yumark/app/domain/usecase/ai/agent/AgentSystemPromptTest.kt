package com.yumark.app.domain.usecase.ai.agent

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.AgentTask
import com.yumark.app.domain.model.AgentTaskAggregate
import com.yumark.app.domain.model.AgentTaskStatus
import com.yumark.app.domain.model.AgentTaskStep
import com.yumark.app.domain.model.AgentTaskStepStatus
import com.yumark.app.domain.model.AiTool
import org.junit.jupiter.api.Test

/**
 * 系统提示的构建语义：计划注入（长任务方向感）与文档上下文预算。
 * 上下文裁剪可能丢掉早期的 update_plan 调用，注入块是模型维持计划方向感的唯一来源。
 */
class AgentSystemPromptTest {

    private fun aggregate(
        id: String = "task-1",
        goal: String = "整理三篇笔记",
        steps: List<Pair<String, AgentTaskStepStatus>>
    ) = AgentTaskAggregate(
        task = AgentTask(
            id = id, conversationId = "c1", goal = goal,
            status = AgentTaskStatus.EXECUTING,
            createdAt = 0L, updatedAt = 0L
        ),
        evidence = emptyList(),
        steps = steps.mapIndexed { index, (title, status) ->
            AgentTaskStep(
                id = "s$index", taskId = id, title = title,
                description = "", status = status, order = index,
                dependsOnStepIds = emptyList(), completionCriteria = "", resultSummary = "",
                toolHints = emptyList()
            )
        }
    )

    @Test
    fun `有进行中任务时计划块注入系统提示`() {
        val agg = aggregate(
            steps = listOf(
                "读取三篇文档" to AgentTaskStepStatus.DONE,
                "合并要点" to AgentTaskStepStatus.RUNNING,
                "写回文档" to AgentTaskStepStatus.PENDING
            )
        )
        val prompt = buildAgentSystemPrompt(null, null, emptyList(), planContext = currentPlanContext(agg, "task-1"))

        assertThat(prompt).contains("# 当前任务计划")
        assertThat(prompt).contains("目标：整理三篇笔记")
        assertThat(prompt).contains("[x] 读取三篇文档")
        assertThat(prompt).contains("[~] 合并要点")
        assertThat(prompt).contains("[ ] 写回文档")
    }

    @Test
    fun `无任务或任务不匹配时不注入计划块`() {
        assertThat(currentPlanContext(null, "task-1")).isNull()
        // id 不匹配（换了会话的任务）也不注入
        val agg = aggregate(id = "other", steps = listOf("x" to AgentTaskStepStatus.PENDING))
        assertThat(currentPlanContext(agg, "task-1")).isNull()
        // 无步骤的任务同样不注入（空块没有信息量）
        val empty = aggregate(steps = emptyList())
        assertThat(currentPlanContext(empty, "task-1")).isNull()

        val prompt = buildAgentSystemPrompt(null, null, emptyList(), planContext = null)
        assertThat(prompt).doesNotContain("# 当前任务计划")
    }

    @Test
    fun `文档上下文超预算时退回大纲模式`() {
        val bigDoc = "# 标题\n" + "正文".repeat(4000)   // 12001 字符，超 FULL_DOC_CONTEXT_BUDGET
        val prompt = buildAgentSystemPrompt("长文", bigDoc, emptyList())

        assertThat(prompt).contains("仅给出大纲")
        assertThat(prompt).contains("read_document 获取")
        assertThat(prompt).doesNotContain("完整内容如下")
    }

    @Test
    fun `预算内的文档注入完整内容`() {
        val smallDoc = "# 标题\n正文"
        val prompt = buildAgentSystemPrompt("短文", smallDoc, emptyList())

        assertThat(prompt).contains("完整内容如下")
        assertThat(prompt).contains("# 标题\n正文")
    }

    @Test
    fun `注入的工具列表与 tools 参数一致`() {
        val tools = listOf(
            AiTool(name = "read_document", description = "读取文档", parameters = emptyMap())
        )
        val prompt = buildAgentSystemPrompt(null, null, tools)

        assertThat(prompt).contains("- read_document：读取文档")
        // 未下发的写工具不出现在指引里（editBlock/approvalBlock 由 hasEdit/hasWrite 门控）
        assertThat(prompt).doesNotContain("# 外科式编辑与整篇重写")
        assertThat(prompt).doesNotContain("# 审批")
    }
}
