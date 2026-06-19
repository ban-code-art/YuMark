package com.yumark.app.presentation.adaptive

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FoldPostureTest {

    @Test
    fun `null hinge falls back to weights`() {
        assertThat(splitForHinge(totalWidthPx = 1280, hinge = null)).isNull()
    }

    @Test
    fun `horizontal hinge does not split left-right`() {
        val horizontal = HingeInfo(isVerticalSeparating = false, boundsLeftPx = 640, boundsWidthPx = 0)
        assertThat(splitForHinge(1280, horizontal)).isNull()
    }

    @Test
    fun `vertical crease splits at the fold with zero gap`() {
        val crease = HingeInfo(isVerticalSeparating = true, boundsLeftPx = 640, boundsWidthPx = 0)
        val split = splitForHinge(1280, crease)!!
        assertThat(split.leftWidthPx).isEqualTo(640)
        assertThat(split.hingeWidthPx).isEqualTo(0)
        assertThat(split.rightWidthPx).isEqualTo(640)
        assertThat(split.leftWidthPx + split.hingeWidthPx + split.rightWidthPx).isEqualTo(1280)
    }

    @Test
    fun `dual-screen gap is reserved between panes`() {
        val gap = HingeInfo(isVerticalSeparating = true, boundsLeftPx = 600, boundsWidthPx = 40)
        val split = splitForHinge(1280, gap)!!
        assertThat(split.leftWidthPx).isEqualTo(600)
        assertThat(split.hingeWidthPx).isEqualTo(40)
        assertThat(split.rightWidthPx).isEqualTo(640)
    }

    @Test
    fun `hinge at the left edge is rejected`() {
        val edge = HingeInfo(isVerticalSeparating = true, boundsLeftPx = 0, boundsWidthPx = 0)
        assertThat(splitForHinge(1280, edge)).isNull()
    }

    @Test
    fun `hinge leaving no right space is rejected`() {
        val noRight = HingeInfo(isVerticalSeparating = true, boundsLeftPx = 1240, boundsWidthPx = 40)
        assertThat(splitForHinge(1280, noRight)).isNull()
    }
}
