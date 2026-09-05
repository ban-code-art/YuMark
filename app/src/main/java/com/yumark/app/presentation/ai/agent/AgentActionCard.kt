package com.yumark.app.presentation.ai.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.core.util.diff.DiffComposer
import com.yumark.app.core.util.diff.LineDiffer
import com.yumark.app.domain.model.AgentAction
import com.yumark.app.domain.model.AgentActionStatus
import com.yumark.app.domain.model.AgentActionType
import com.yumark.app.presentation.ai.common.AiDesign
import com.yumark.app.presentation.ai.common.DiffView
import com.yumark.app.presentation.ai.common.StatusPill
import com.yumark.app.presentation.ai.common.actionStatusVisual
import com.yumark.app.presentation.theme.AppIconSize
import com.yumark.app.presentation.theme.AppMotion
import com.yumark.app.presentation.theme.AppSpacing

/**
 * Agent 操作卡片。
 * - EDIT_DOCUMENT 且提供 [baseContent]：进入 **diff 闸门**——展示逐行改动、逐 hunk 接受/拒绝，
 *   批准时把合成内容经 [onApproveDiff] 回传（D1：改用户既有文档前必经审阅）。
 * - 其余（CREATE_DOCUMENT 或未提供 base）：整体预览 + [onApprove]/[onReject]。
 */
@Composable
fun AgentActionCard(
    action: AgentAction,
    baseContent: String? = null,
    onApproveDiff: (finalContent: String) -> Unit = {},
    onApprove: () -> Unit = {},
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val diffMode = action.type == AgentActionType.EDIT_DOCUMENT && baseContent != null
    if (diffMode) {
        EditDiffCard(action, baseContent, onApproveDiff, onReject, modifier)
    } else {
        WholeContentCard(action, onApprove, onReject, modifier)
    }
}

/** EDIT 路径：行级 diff + 逐 hunk 接受/拒绝 + 合成应用。 */
@Composable
private fun EditDiffCard(
    action: AgentAction,
    baseContent: String,
    onApproveDiff: (String) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier
) {
    val diff = remember(baseContent, action.content) { LineDiffer.diff(baseContent, action.content) }
    val accepted = remember(diff) {
        mutableStateListOf<Boolean>().apply { repeat(diff.hunks.size) { add(true) } }
    }
    var expanded by remember(diff) { mutableStateOf(true) }

    Card(
        modifier = modifier.fillMaxWidth().padding(top = AppSpacing.Default).testTag(AgentActionTestTags.DIFF_CARD),
        shape = RoundedCornerShape(AiDesign.CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(AppSpacing.Cozy), verticalArrangement = Arrangement.spacedBy(AppSpacing.Snug)) {
            ActionHeader(AgentActionType.EDIT_DOCUMENT, action.status)
            Text(
                action.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(AgentActionTestTags.DESCRIPTION)
            )

            // 用 if/else 而非 early return：应用后 baseContent 更新会让 diff 重算为「无改动」，
            // 若用 return@Column 会改变可组合组结构 → 重组时 Compose 组栈下溢崩溃。
            if (!diff.hasChanges) {
                Text(
                    stringResource(R.string.agent_action_no_change),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(AgentActionTestTags.NO_CHANGE)
                )
                if (action.status == AgentActionStatus.PENDING) {
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.testTag(AgentActionTestTags.DISMISS)
                    ) { Text(stringResource(R.string.agent_action_dismiss)) }
                }
            } else {
                if (diff.degraded) {
                    Text(
                        stringResource(R.string.agent_action_diff_degraded),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(AgentActionTestTags.DEGRADED)
                    )
                }

                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(AppSpacing.None),
                    modifier = Modifier.testTag(AgentActionTestTags.DIFF_TOGGLE)
                ) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    Spacer(Modifier.width(AppSpacing.Tight))
                    Text(
                        stringResource(
                            if (expanded) R.string.agent_action_collapse_diff
                            else R.string.agent_action_expand_diff
                        )
                    )
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
                    exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
                ) {
                    DiffView(
                        result = diff,
                        accepted = accepted,
                        onToggleHunk = { id -> accepted[id] = !accepted[id] },
                        // 标签挂在 DiffView 本体而不是外层 AnimatedVisibility：收起时内容不进组合，
                        // 测试用 assertDoesNotExist 判「已收起」才有确定答案。
                        modifier = Modifier.testTag(AgentActionTestTags.DIFF_VIEW)
                    )
                }

                if (action.status == AgentActionStatus.PENDING) {
                    val acceptedCount = accepted.count { it }
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Default)) {
                        Button(
                            onClick = { onApproveDiff(DiffComposer.applyHunks(diff, accepted)) },
                            modifier = Modifier.weight(1f).testTag(AgentActionTestTags.APPLY)
                        ) {
                            Icon(Icons.Default.Check, null, Modifier.size(AppIconSize.Small))
                            Spacer(Modifier.width(AppSpacing.Tight))
                            // count 传两次：一次选 quantity，一次做 %1$d 的实参
                            Text(
                                pluralStringResource(
                                    R.plurals.agent_action_apply_selected,
                                    acceptedCount,
                                    acceptedCount
                                )
                            )
                        }
                        OutlinedButton(
                            onClick = onReject,
                            modifier = Modifier.weight(1f).testTag(AgentActionTestTags.REJECT_ALL)
                        ) {
                            Icon(Icons.Default.Close, null, Modifier.size(AppIconSize.Small))
                            Spacer(Modifier.width(AppSpacing.Tight))
                            Text(stringResource(R.string.agent_action_reject_all))
                        }
                    }
                }
            }
        }
    }
}

/** CREATE / 无 base 路径：整体内容预览 + 批准/拒绝（原行为）。 */
@Composable
private fun WholeContentCard(
    action: AgentAction,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().padding(top = AppSpacing.Default).testTag(AgentActionTestTags.WHOLE_CARD),
        shape = RoundedCornerShape(AiDesign.CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(AppSpacing.Cozy), verticalArrangement = Arrangement.spacedBy(AppSpacing.Snug)) {
            ActionHeader(action.type, action.status)

            Text(
                action.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(AgentActionTestTags.DESCRIPTION)
            )

            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(AppSpacing.None),
                modifier = Modifier.testTag(AgentActionTestTags.CONTENT_TOGGLE)
            ) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                Spacer(Modifier.width(AppSpacing.Tight))
                Text(
                    stringResource(
                        if (expanded) R.string.agent_action_collapse_content
                        else R.string.agent_action_expand_content
                    )
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
                exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        action.content,
                        style = MaterialTheme.typography.bodySmall,
                        // 标签挂在正文 Text 而不是外层 Surface：Surface 不合并子节点语义，
                        // 挂外层就只能拿到空节点，断言正文内容取不到文本。
                        modifier = Modifier
                            .heightIn(max = AgentActionMetrics.ContentPreviewMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(AppSpacing.Default)
                            .testTag(AgentActionTestTags.CONTENT_PREVIEW)
                    )
                }
            }

            if (action.status == AgentActionStatus.PENDING) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Default)) {
                    Button(
                        onClick = onApprove,
                        modifier = Modifier.weight(1f).testTag(AgentActionTestTags.APPROVE)
                    ) {
                        Icon(Icons.Default.Check, null, Modifier.size(AppIconSize.Small))
                        Spacer(Modifier.width(AppSpacing.Tight))
                        Text(stringResource(R.string.agent_action_approve))
                    }
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f).testTag(AgentActionTestTags.REJECT)
                    ) {
                        Icon(Icons.Default.Close, null, Modifier.size(AppIconSize.Small))
                        Spacer(Modifier.width(AppSpacing.Tight))
                        Text(stringResource(R.string.agent_action_reject))
                    }
                }
            }
        }
    }
}

/** 操作卡头部：类型字形徽标 + 类型名 + 右侧状态药丸。创建/编辑共用。 */
@Composable
private fun ActionHeader(type: AgentActionType, status: AgentActionStatus) {
    val cs = MaterialTheme.colorScheme
    // 配对里放的是 @StringRes id 而不是已解析文案：when 只做「类型→资源」映射，
    // 解析统一在下面的 Text 里做一次。
    val (icon, labelRes) = when (type) {
        AgentActionType.CREATE_DOCUMENT -> Icons.Default.Add to R.string.agent_action_type_create
        AgentActionType.EDIT_DOCUMENT -> Icons.Default.Edit to R.string.agent_action_type_edit
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Default)
    ) {
        Box(
            modifier = Modifier.size(AgentActionMetrics.GlyphBadge).clip(CircleShape).background(cs.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(AgentActionMetrics.GlyphIcon), tint = cs.onPrimaryContainer)
        }
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag(AgentActionTestTags.TITLE)
        )
        Spacer(Modifier.weight(1f))
        StatusPill(
            actionStatusVisual(status),
            modifier = Modifier.testTag(AgentActionTestTags.STATUS)
        )
    }
}

/**
 * UI 测试锚点：文案会随语言变，测试只能靠稳定的英文 tag 定位。
 *
 * 两种卡片各有自己的根标签（[DIFF_CARD] / [WHOLE_CARD]）：测试首先要断言的就是
 * 「进的是 diff 闸门还是整体预览」，共用一个根标签的话这件事就查不出来。
 * 头部与描述是两种卡片共用的节点，标签也共用；同一轮对话里可能出现多张卡片，
 * 测试侧按 onAllNodesWithTag 取集合再筛，与侧栏树的做法一致。
 */
private object AgentActionTestTags {
    /** EDIT + 有 base：diff 闸门卡片根容器 */
    const val DIFF_CARD = "agent_action_diff_card"
    /** CREATE / 无 base：整体预览卡片根容器 */
    const val WHOLE_CARD = "agent_action_whole_card"
    const val TITLE = "agent_action_title"
    /** 状态药丸（待批准/已批准/已拒绝/已执行） */
    const val STATUS = "agent_action_status"
    const val DESCRIPTION = "agent_action_description"
    /** 「无改动」提示：diff 算出空结果时的终态文案 */
    const val NO_CHANGE = "agent_action_no_change"
    /** diff 降级提示（文档过大，退化成单块整体对照） */
    const val DEGRADED = "agent_action_degraded"
    const val DIFF_TOGGLE = "agent_action_diff_toggle"
    /** 展开后的逐行 diff 区；收起时不存在 */
    const val DIFF_VIEW = "agent_action_diff_view"
    const val CONTENT_TOGGLE = "agent_action_content_toggle"
    /** 展开后的正文预览；收起时不存在 */
    const val CONTENT_PREVIEW = "agent_action_content_preview"
    const val APPLY = "agent_action_apply"
    const val REJECT_ALL = "agent_action_reject_all"
    const val APPROVE = "agent_action_approve"
    const val REJECT = "agent_action_reject"
    /** 无改动时的「知道了」按钮，与 [REJECT] 走同一回调但语义不同 */
    const val DISMISS = "agent_action_dismiss"
}

/**
 * 本卡特有的三处尺寸，刻意不并入全局 [AppSpacing] / [AppIconSize] 或 [AiDesign]：
 * 前两者是操作卡头部行内类型徽标的几何，比 [AiDesign.GlyphSize]（主 Agent 徽标 34dp）小是
 * 有意的——它是行内标记而非主头像，两者不共用 token；[GlyphIcon] 取 15dp 而非
 * [AppIconSize.Inline] 的 16dp，是为保留原像素、不为凑标度改动外观。[ContentPreviewMaxHeight]
 * 是整体预览的可视上界（超出内部滚动），属「布局上界」而非间距，按 FileListMetrics 先例落屏幕局部。
 */
private object AgentActionMetrics {
    /** 类型字形徽标圆形直径。 */
    val GlyphBadge = 26.dp
    /** 徽标内类型图标直径。 */
    val GlyphIcon = 15.dp
    /** 整体预览正文最大可视高度，超出内部滚动，不让卡片顶穿气泡列表。 */
    val ContentPreviewMaxHeight = 240.dp
}
