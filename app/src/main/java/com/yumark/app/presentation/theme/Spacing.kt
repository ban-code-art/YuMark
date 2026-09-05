package com.yumark.app.presentation.theme

import androidx.compose.ui.unit.dp

/**
 * 间距标度。
 *
 * 为什么需要它：颜色（[AppThemes]）、圆角（[AppShapes]）、字体（[Typography]）都各有一层令牌
 * 并接进了 `MaterialTheme`，唯独间距没有——`MaterialTheme` 本身也不提供间距槽位，于是
 * `presentation` 下散着 291 处间距字面量（padding 164 / Spacer 82 / spacedBy 45）。
 * 实测分布是 8dp×85、16dp×58、4dp×56、12dp×33 撑起主干，另有 6dp×21、10dp×12、2dp×6，
 * 以及 3dp、5dp 各一处的孤例。同一块界面上因此并存 6/8/10 三种「小间距」，肉眼看是没对齐。
 *
 * 这一层**不改观感**，只是把已在用的那几档命名下来，让「调整某一档间距」从此有唯一入口——
 * 和 [AppShapes] 当初解决 8/12/14/16/20 五种圆角并存时是同一个做法。
 *
 * 阶梯就是 0/2/4/6/8/12/16/20/24/32，刻意**不**强推 4dp 网格：2dp 与 6dp 是状态药丸、图标贴字
 * 这类紧凑排布真正需要的半档，硬拉到 4/8 会让那些地方明显变松。真正该收掉的是 3dp、5dp、10dp
 * 这些既不成档也没有理由的值，迁移时逐处判断并入相邻档。
 *
 * 名字是角色提示不是法律：拿 [Screen] 当卡片间距、拿 [Default] 当行内间距都合理，
 * 重点是别再写裸数字。
 */
object AppSpacing {
    /** 0dp——刻意不留间距，比省略参数更能表达意图 */
    val None = 0.dp

    /** 2dp——图标与紧贴其后的文字、药丸内部竖向 */
    val Micro = 2.dp

    /** 4dp——同组元素之间、图标与标签 */
    val Tight = 4.dp

    /** 6dp——紧凑行内排布、状态药丸内边距 */
    val Snug = 6.dp

    /** 8dp——最常用的一档：行内元素、卡片内部竖向 */
    val Default = 8.dp

    /** 12dp——卡片内边距、列表行竖向 */
    val Cozy = 12.dp

    /** 16dp——屏幕左右安全边距、卡片之间。与 `AiDesign.ScreenPadding` 同值 */
    val Screen = 16.dp

    /** 20dp——底部工作表内边距 */
    val Roomy = 20.dp

    /** 24dp——设置分组之间 */
    val Group = 24.dp

    /** 32dp——大区块之间、空状态上下留白 */
    val Section = 32.dp

    /**
     * 48dp——Material 无障碍规定的最小触摸目标，是**下限**不是间距。
     *
     * 放在这里是因为「按钮太小」和「间距不齐」总是一起改：把一个 32dp 的 IconButton 撑到 48dp，
     * 周围的 padding 必然跟着重算。用 `Modifier.sizeIn(minWidth = …, minHeight = …)` 或
     * `minimumInteractiveComponentSize()` 施加，而不是直接 `size()`——后者会把图标本身也放大。
     */
    val MinTouchTarget = 48.dp
}

/**
 * 图标尺寸标度。
 *
 * 与 [AppSpacing] 同放一个文件：两者都是「尺寸」而非语义样式，各自拆成三十行一个文件没有收益。
 *
 * 实测 `presentation` 下 `Modifier.size(N.dp)` 共 53 处，行内图标用了 **18dp×18、20dp×10、
 * 16dp×5、14dp×4** 四种——同一种「文字旁边的小图标」角色配了四个尺寸，是能看出来的不齐。
 * 收敛依据是图标该与同行文字的字号匹配，所以按排版层级分档而不是按视觉手感取值。
 *
 * 迁移注意：14dp 那四处并入 [Inline]（16dp）**会改观感**，属于有意调整，需逐处看一眼再动；
 * 18dp → [Small] 与 20dp → [Medium] 是纯改名，不动像素。
 */
object AppIconSize {
    /** 16dp——与 bodySmall / labelMedium 同行 */
    val Inline = 16.dp

    /** 18dp——与 bodyMedium 同行，现网用得最多的一档 */
    val Small = 18.dp

    /** 20dp——与 bodyLarge / titleSmall 同行 */
    val Medium = 20.dp

    /** 24dp——M3 默认图标尺寸，独立 IconButton 内部用这档 */
    val Large = 24.dp

    /** 32dp——头像、角色徽标。与 `AiDesign.GlyphSize`（34dp）是不同角色，别互相替换 */
    val Avatar = 32.dp

    /**
     * 48dp——**示意性**大图标：空状态插图、对话框里的结果图标（「下载完成」那一类）。
     *
     * 与 [AppSpacing.MinTouchTarget] 同值但是完全不同的角色，别互相替换：那个是可点区域的
     * **下限**、用 `sizeIn` 施加在交互控件上；这个是一个不可点的图形本身该画多大。两个角色
     * 恰好都落在 48dp 是巧合（一个来自手指尺寸，一个来自图标与 titleMedium 的视觉配重），
     * 哪天其中一个要调，另一个不该跟着动。
     */
    val Hero = 48.dp
}
