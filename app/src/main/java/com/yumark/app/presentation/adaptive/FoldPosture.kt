package com.yumark.app.presentation.adaptive

/**
 * 解耦 androidx.window 类型的折叠铰链信息，便于纯逻辑单测。
 *
 * 这里只关心「竖直分隔铰链」——它把屏幕分成左右两半，正好对应左右双窗格；横向铰链（tabletop）
 * 不影响左右分栏，按普通展开处理。
 */
data class HingeInfo(
    /** 是否为竖直方向且「分隔」的铰链（HALF_OPENED 折叠态，或双屏的物理间隙）。 */
    val isVerticalSeparating: Boolean,
    /** 铰链左边界相对窗口的像素位置。 */
    val boundsLeftPx: Int,
    /** 铰链像素宽度（柔性折痕可能为 0，双屏物理间隙 > 0）。 */
    val boundsWidthPx: Int
)

/** 双窗格沿铰链对齐后的像素分配（左 / 铰链 / 右）。 */
data class PaneSplit(
    val leftWidthPx: Int,
    val hingeWidthPx: Int,
    val rightWidthPx: Int
)

/**
 * 依据竖直分隔铰链把 [totalWidthPx] 切成 左/铰链/右 三段，使两个窗格分别落在铰链两侧、
 * 内容不跨折痕。
 *
 * 返回 null 表示「没有可用于对齐的铰链」（无铰链、非竖直分隔、铰链越界、或某侧无可用空间），
 * 此时调用方应退回按权重布局。
 */
fun splitForHinge(totalWidthPx: Int, hinge: HingeInfo?): PaneSplit? {
    if (hinge == null || !hinge.isVerticalSeparating) return null
    val left = hinge.boundsLeftPx
    val hingeWidth = hinge.boundsWidthPx
    // 铰链必须真正落在窗口内（左侧有空间、宽度非负），否则对齐无意义
    if (left <= 0 || hingeWidth < 0) return null
    val right = totalWidthPx - left - hingeWidth
    if (right <= 0) return null
    return PaneSplit(leftWidthPx = left, hingeWidthPx = hingeWidth, rightWidthPx = right)
}
