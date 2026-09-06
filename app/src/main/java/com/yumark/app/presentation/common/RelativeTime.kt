package com.yumark.app.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.yumark.app.R
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 相对时间文案（「刚刚 / N 分钟前 / N 小时前 / N 天前 / N 周前」，8 周以上退 `yyyy-MM-dd`）。
 *
 * 从 FileListScreen 抽到公共层：回收站页的「移入于 N 天前」与文档卡片的「N 分钟前」
 * 必须是同一套口径——两处各写一份的话，语区、复数、边界（59 分 vs 1 小时）迟早漂移。
 * 抽出时顺带补了 8 周以上的绝对日期分支：「20 周前」对定位一个时间点毫无信息量，
 * `2026-06-12` 才是答案；文档列表与回收站从此遵循同一条退让线。
 *
 * 语义沿用原实现，两处刻意保留的取舍：
 * - **不做秒级自走**：调用点用 `remember(instant)` 钉住结果，静止卡片一直显示旧值。
 *   做到秒级自走要再引一个 ticker 状态，收益配不上重组成本。
 * - **未来时间戳显示「刚刚」**：设备时钟回拨、同步下来的文档带未来时间，落进第一个
 *   分支显示零而不是负数，这是有意的降级。
 *
 * 命名沿用 `formatElapsedTime` 而不是 `formatRelativeTime`：ConversationListSheet 里另有一个
 * 算**日历归档**的函数（今天报 HH:mm、昨天报「昨天」、本周报星期几），两者**不合并**——
 * 这个算已流逝时长，那个算日历档位，合并只有两条路：改掉某个页面用户已经看惯的格式，
 * 或者加个开关参数把两套逻辑塞进一个函数体，都比保持两个名字更糟。各自按自己算的东西命名。
 * 另注意 SettingsScreen 还有一个格式化 ISO 绝对日期的 `formatDate`，与这里无关。
 */
@Composable
fun formatElapsedTime(instant: Instant): String {
    val now = kotlinx.datetime.Clock.System.now()
    val diff = now - instant
    return when {
        diff.inWholeMinutes < MINUTES_PER_HOUR -> stringResource(R.string.updated_just_now)
        diff.inWholeMinutes < MINUTES_PER_DAY -> {
            val minutes = diff.inWholeMinutes.toInt()
            pluralStringResource(R.plurals.updated_minutes_ago, minutes, minutes)
        }
        diff.inWholeHours < HOURS_PER_DAY -> {
            val hours = diff.inWholeHours.toInt()
            pluralStringResource(R.plurals.updated_hours_ago, hours, hours)
        }
        diff.inWholeDays < DAYS_PER_WEEK -> {
            val days = diff.inWholeDays.toInt()
            pluralStringResource(R.plurals.updated_days_ago, days, days)
        }
        diff.inWholeDays < ABSOLUTE_DATE_CUTOFF_DAYS -> {
            val weeks = (diff.inWholeDays / DAYS_PER_WEEK).toInt()
            pluralStringResource(R.plurals.updated_weeks_ago, weeks, weeks)
        }
        else -> {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneId.systemDefault())
            formatter.format(instant.toJavaInstant())
        }
    }
}

private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24L * 60
private const val HOURS_PER_DAY = 24L
private const val DAYS_PER_WEEK = 7L

/** 8 周（56 天）是「N 周前」仍有信息量的极限，超过就退绝对日期。 */
private const val ABSOLUTE_DATE_CUTOFF_DAYS = 56L
