package com.yumark.app.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * M3 基线字阶。字号、字重、字距全部沿用规范，下面只动与中文排版直接相关的两项。
 *
 * 之所以从基线 `copy` 而不是像原先那样把 15 档逐个写全：原先那 104 行的每一个数值都与
 * M3 默认**完全一致**，看着像定制、实际什么也没改，而且升版后规范调了值这边还压着旧数。
 */
private val Baseline = Typography()

/**
 * 给一档字阶补上中文排版所需的设置。
 *
 * [lineBreak] —— 断行策略。M3 基线不设这一项，于是全应用走 `LineBreak.Simple`（贪心断行）。
 * 中文没有词间空格，贪心断行会把标题断成「保存到本地文件」+「夹」这种末行只剩一个字的样子。
 * 正文用 `Paragraph`（HighQuality + 严格禁则，管住行首的「，。」和行尾的「（「」），
 * 标题/短语用 `Heading`（Balanced，把各行长度拉匀），按钮与标签用 `Simple`（这些地方文字短、
 * 数量多，不值得为它们付平衡断行的开销）。
 *
 * 注意这几档最终落到 `android.text.LineBreaker`：`WordBreak.Phrase` 要 API 29+，
 * 更严格的禁则表要 API 33+。低版本上 Compose 会静默退化成能做到的那一档，不会报错，
 * 也就意味着 API 26–28 的设备看到的仍接近贪心断行——可接受，不为此放弃高版本的效果。
 *
 * [lineHeightStyle] —— 行高怎么分配。默认会把多出来的行高按字体度量堆到**首行上方**，
 * 于是「加大行距」的结果是整段往下掉一截、段内反而没松开。改成 `Center` 让每行上下均分，
 * `Trim.None` 保留首末行的行距，段落上下才对称。
 *
 * [lineHeight] 只有正文三档需要传：中文字形上下顶满 em 框，没有拉丁小写字母那样的天然余白，
 * 同一个数值的行高读起来更挤，1.5 倍偏紧，抬到约 1.6 倍才是舒服的密度。
 */
private fun TextStyle.cjk(
    lineBreak: LineBreak,
    lineHeight: TextUnit = this.lineHeight
): TextStyle = copy(
    lineHeight = lineHeight,
    lineBreak = lineBreak,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    )
)

val Typography = Baseline.copy(
    // display / headline / title：短文本，要的是各行长度匀，不要末行孤字
    displayLarge = Baseline.displayLarge.cjk(LineBreak.Heading),
    displayMedium = Baseline.displayMedium.cjk(LineBreak.Heading),
    displaySmall = Baseline.displaySmall.cjk(LineBreak.Heading),
    headlineLarge = Baseline.headlineLarge.cjk(LineBreak.Heading),
    headlineMedium = Baseline.headlineMedium.cjk(LineBreak.Heading),
    headlineSmall = Baseline.headlineSmall.cjk(LineBreak.Heading),
    titleLarge = Baseline.titleLarge.cjk(LineBreak.Heading),
    titleMedium = Baseline.titleMedium.cjk(LineBreak.Heading),
    titleSmall = Baseline.titleSmall.cjk(LineBreak.Heading),
    // body：成段中文，要禁则与高质量断行，行高抬到约 1.6 倍
    bodyLarge = Baseline.bodyLarge.cjk(LineBreak.Paragraph, lineHeight = 26.sp),
    bodyMedium = Baseline.bodyMedium.cjk(LineBreak.Paragraph, lineHeight = 22.sp),
    bodySmall = Baseline.bodySmall.cjk(LineBreak.Paragraph, lineHeight = 18.sp),
    // label：按钮文字、chip、徽章。几乎不换行，用最便宜的策略
    labelLarge = Baseline.labelLarge.cjk(LineBreak.Simple),
    labelMedium = Baseline.labelMedium.cjk(LineBreak.Simple),
    labelSmall = Baseline.labelSmall.cjk(LineBreak.Simple)
)
