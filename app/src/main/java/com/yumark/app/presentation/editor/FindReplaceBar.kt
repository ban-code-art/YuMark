package com.yumark.app.presentation.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yumark.app.R
import com.yumark.app.core.text.FindOptions
import com.yumark.app.core.text.FindReplace
import com.yumark.app.core.text.MatchRange
import com.yumark.app.presentation.theme.AppMotion
import com.yumark.app.presentation.theme.AppSpacing

/**
 * 编辑器查找/替换栏。
 *
 * ### 高亮为什么只有"当前一处"
 * `BasicTextField` 的 [TextFieldValue] 只有一个 selection，给任意多处文本同时上底色得自己拿
 * `TextLayoutResult` 画路径，长文档下还要跟着滚动重算，收益远不及代价。所以这里的高亮就是
 * **把当前匹配写成父级的选区**，系统选区色即高亮，光标也顺带跳过去。
 *
 * ### 状态归属
 * 查询串/替换串/选项/匹配列表全部留在本组件内部，父级只提供文本与选区、接收写回，
 * 这样 EditorScreen 不用为查找功能多背四五个状态。
 *
 * @param value 当前编辑器内容与选区；查找基于 `value.text`，未定位过时从 `value.selection` 起算。
 * @param onSelect 只移动选区（文本未变）：父级据此高亮并滚动到可见，记历史时会走"纯光标移动"分支。
 * @param onReplace 文本已变：父级须写回、记一次**强制边界**的撤销单元并通知 ViewModel 落库。
 * @param onMessage 一次性提示（替换条数、截断告知），父级弹 Snackbar。
 * @param onClose 收起查找栏。
 */
@Composable
fun FindReplaceBar(
    value: TextFieldValue,
    onSelect: (TextRange) -> Unit,
    onReplace: (TextFieldValue) -> Unit,
    onMessage: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val text = value.text
    // 下面几个 replace* 是普通局部函数，不是 @Composable 作用域，里面调不了 stringResource；
    // 提示文案带动态计数也没法在组合期预先取好，所以留一个取字符串的入口给它们。
    //
    // 用 LocalResources 而不是 LocalContext：LocalContext.current.getString(...) 会被 lint 的
    // LocalContextGetResourceValueCall 判为 error（那个 Context 是组合当时的，配置变了读出来
    // 就是旧语言的文案）。LocalResources 由 Compose 在配置变化时重新提供，而这几个局部函数
    // 每次组合都重建，于是它们拿到的永远是当前配置下的 Resources。
    val resources = LocalResources.current
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(FindOptions()) }

    // 匹配列表刻意做成 State + LaunchedEffect，而不是 remember(text, query, options) { findAll(...) }：
    // 逐个替换要走 shiftMatches 增量对齐，而带 key 的 remember 会在文本一变就把增量结果丢掉重扫，
    // shiftMatches 等于白写。副作用里扫描还顺带把大文档的扫描挪出了组合阶段。
    var matches by remember { mutableStateOf<List<MatchRange>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(FindReplace.NO_MATCH) }

    // 自己替换产出的文本：匹配已用 shiftMatches 对齐过，跳过这一次全量重扫（用完立刻清掉，
    // 否则查询串变了却还命中这个标记，会把旧查询的匹配当成新查询的结果留着）
    var alignedText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(text, query, options) {
        if (alignedText != null && alignedText == text) {
            alignedText = null
            return@LaunchedEffect
        }
        matches = FindReplace.findAll(text, query, options)
        // 重扫后旧下标已无意义。这里刻意不自动跳到第一个匹配：文档正被输入时把选区拽到别处，
        // 用户下一个字就打错地方了。
        currentIndex = FindReplace.NO_MATCH
    }

    // 正则模式下"半截正则"是常态，findAll 只会返回空列表，必须单独编译一次才能把
    // "没有匹配"和"正则写错了"分开告诉用户。
    // 注意：全词匹配会把查询串包进 lookaround，极少数模式单独合法、包起来不合法，
    // 那种情况这里判不出来，只会显示成"无匹配"。
    val regexInvalid = remember(query, options.useRegex) {
        options.useRegex && query.isNotEmpty() && runCatching { Regex(query) }.isFailure
    }

    val total = matches.size
    val hasMatch = total > 0
    val truncated = total >= FindReplace.MAX_MATCHES

    /** 跳到相对当前匹配的下一个/上一个；到头由 [FindReplace.nextIndex] 回绕。 */
    fun jump(forward: Boolean) {
        val list = matches
        if (list.isEmpty()) return
        // 起算点取当前匹配的远端边界，否则 nextIndex 会把当前这一处再选一遍（原地打转）
        val from = list.getOrNull(currentIndex)?.let { if (forward) it.end else it.start }
            ?: (if (forward) caretMax(value) else caretMin(value))
        val index = FindReplace.nextIndex(list, from, forward)
        if (index == FindReplace.NO_MATCH) return
        currentIndex = index
        val match = list[index]
        onSelect(TextRange(match.start, match.end))
    }

    /** 替换当前匹配，并把选区推进到下一处（替换即前进，与主流编辑器一致）。 */
    fun replaceCurrent() {
        val list = matches
        if (list.isEmpty()) return
        // 还没定位过（刚改完查询串，或文档刚被编辑触发了重扫）时自己先从光标处找一处，
        // 否则用户得先点一次"下一个"才能替换，白多一步
        val index = if (currentIndex in list.indices) {
            currentIndex
        } else {
            FindReplace.nextIndex(list, caretMax(value), forward = true)
        }
        val match = list.getOrNull(index) ?: return
        // 极端时序下 matches 可能已按新文本重扫、而本次回调仍持有上一帧的 text；
        // 宁可放弃这一次替换，也不能在错位置上改文本
        if (match.end > text.length) return

        val newText = FindReplace.replaceOne(text, match, replacement)
        // 替换串自身含查询串时（a → aa）新产生的匹配不在 shifted 里——shiftMatches 只平移旧匹配。
        // 要看到新匹配必须重新 findAll：改查询串/选项、再编辑一次文档，或直接用"全部替换"。
        val shifted = FindReplace.shiftMatches(list, index, replacement.length - match.length)
        matches = shifted
        // 只在文本真的变了时才立这个「已对齐、跳过重扫」的标记。
        //
        // 替换前后文本相同（查 foo 替换成 foo；正则替换回恰好一样的字面量）时，上面那个
        // `LaunchedEffect(text, query, options)` 的 key 没变、副作用不会重跑，标记就留在原地；
        // 等下一次 key 真的变化（用户改查询串、或切大小写/全词/正则开关）时被误命中提前返回，
        // **跳掉那一次全量重扫** —— matches / currentIndex 仍是旧查询的结果，状态栏计数与高亮全错，
        // 此时再点「替换」就会把新替换串写到旧查询的匹配位置上（静默改错地方）。
        // 文本没变时位移恒为 0，shiftMatches 是 no-op，matches 本来就还是对的，不需要任何标记。
        if (newText != text) alignedText = newText

        val nextIndex = if (shifted.isEmpty()) FindReplace.NO_MATCH else index.coerceAtMost(shifted.lastIndex)
        currentIndex = nextIndex
        val next = shifted.getOrNull(nextIndex)
        val selection = if (next != null) {
            TextRange(next.start, next.end)
        } else {
            // 没有下一处了：光标收在刚替换进去的内容末尾
            TextRange((match.start + replacement.length).coerceIn(0, newText.length))
        }
        onReplace(TextFieldValue(newText, selection))
    }

    /** 全部替换。刻意不设 [alignedText]，让副作用重扫一遍——只有重扫才能发现 a → aa 新造出来的匹配。 */
    fun replaceAllMatches() {
        val result = FindReplace.replaceAll(text, query, replacement, options)
        if (result.count == 0) {
            onMessage(resources.getString(R.string.find_replace_none))
            return
        }
        val caret = caretMin(value).coerceIn(0, result.text.length)
        onReplace(TextFieldValue(result.text, TextRange(caret)))
        onMessage(
            if (result.count >= FindReplace.MAX_MATCHES) {
                resources.getString(R.string.find_replaced_truncated, FindReplace.MAX_MATCHES)
            } else {
                // getQuantityString 而不是 pluralStringResource：这里是组合内的普通局部函数，
                // 不是 @Composable 作用域。count 传两次 —— 第一个选 quantity 分支，第二个填 %1$d。
                resources.getQuantityString(
                    R.plurals.find_replaced_count,
                    result.count,
                    result.count
                )
            }
        )
    }

    val status = when {
        query.isEmpty() -> ""
        regexInvalid -> stringResource(R.string.find_regex_invalid)
        total == 0 -> stringResource(R.string.find_no_match)
        currentIndex in matches.indices ->
            stringResource(R.string.find_status_position, currentIndex + 1, total)
        // total 传两次：第一个实参选 quantity 分支（英文 1 → "1 match"），第二个填 %1$d
        else -> pluralStringResource(R.plurals.find_status_total, total, total)
    }

    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = FindReplaceBarMetrics.SurfaceElevation) {
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.Default, vertical = AppSpacing.Tight),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Tight)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Micro),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.find_hint)) },
                    singleLine = true,
                    isError = regexInvalid,
                    modifier = Modifier.weight(1f)
                )
                if (status.isNotEmpty()) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (regexInvalid) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        // 计数会挤压查找框，给个上限，长文本靠省略号收尾
                        modifier = Modifier.widthIn(max = FindReplaceBarMetrics.StatusMaxWidth)
                    )
                }
                IconButton(onClick = { jump(forward = false) }, enabled = hasMatch) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.cd_find_prev))
                }
                IconButton(onClick = { jump(forward = true) }, enabled = hasMatch) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.cd_find_next))
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, stringResource(R.string.cd_find_close))
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Micro),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    placeholder = { Text(stringResource(R.string.find_replace_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                // 替换串留空就是删除，所以按钮只看有没有匹配，不看替换串是否为空
                TextButton(onClick = { replaceCurrent() }, enabled = hasMatch) {
                    Text(stringResource(R.string.find_replace), style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = { replaceAllMatches() }, enabled = hasMatch) {
                    Text(stringResource(R.string.find_replace_all), style = MaterialTheme.typography.labelLarge)
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Snug),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OptionChip(stringResource(R.string.find_case_sensitive), options.caseSensitive) {
                    options = options.copy(caseSensitive = it)
                }
                OptionChip(stringResource(R.string.find_whole_word), options.wholeWord) {
                    options = options.copy(wholeWord = it)
                }
                OptionChip(stringResource(R.string.find_regex), options.useRegex) {
                    options = options.copy(useRegex = it)
                }
            }

            AnimatedVisibility(
                visible = truncated,
                enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
                exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
            ) {
                Text(
                    text = stringResource(R.string.find_truncated, FindReplace.MAX_MATCHES),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 查找选项开关。
 *
 * 刻意用文字标签而非图标：material-icons 里没有"区分大小写""正则"这类图标，自己拼的图形
 * 对 TalkBack 也说不清；文字标签本身就是无障碍名称，不需要再补 contentDescription。
 */
@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = { onSelectedChange(!selected) },
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.height(FindReplaceBarMetrics.ChipHeight)
    )
}

/**
 * 选区左/右边界。
 *
 * 不用 `TextRange.min`/`max`：反向拖选时 start > end，直接取 start 当"光标在前面"是错的，
 * 而 minOf/maxOf 把方向问题一次说清，也不必赌某个版本上这两个属性叫什么。
 */
private fun caretMin(value: TextFieldValue): Int = minOf(value.selection.start, value.selection.end)

private fun caretMax(value: TextFieldValue): Int = maxOf(value.selection.start, value.selection.end)

/**
 * 本栏特有的组件尺寸，刻意不并入全局 [AppSpacing]：Surface 色调高程、计数状态文本宽度上限、
 * FilterChip 目视高度，各自成轴（高程 / 宽界 / 组件高度），离散于间距标度，保留原像素、不硬凑。
 * 按 FileListMetrics 先例落屏幕局部。
 */
private object FindReplaceBarMetrics {
    /** Surface 色调高程：M3 tonal elevation，非间距轴。 */
    val SurfaceElevation = 3.dp
    /** 计数状态文本宽度上限：超出省略号收尾，避免挤压查找输入框；off-grid。 */
    val StatusMaxWidth = 92.dp
    /** 选项 FilterChip 目视高度（M3 默认 32），是组件高度而非 Section 间距。 */
    val ChipHeight = 32.dp
}
