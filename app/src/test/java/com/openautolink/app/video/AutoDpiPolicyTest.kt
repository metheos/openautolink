package com.openautolink.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoDpiPolicyTest {
    @Test
    fun `auto negotiation preserves one layout width across 1440p and 4K`() {
        val targetDp = AutoDpiPolicy.layoutWidthDp(
            renderWidthPx = 2914,
            panelDensityDpi = 200,
        )

        assertEquals(2331, targetDp)
        assertEquals(175, AutoDpiPolicy.densityForWidth(2560, targetDp))
        assertEquals(263, AutoDpiPolicy.densityForWidth(3840, targetDp))
    }

    @Test
    fun `invalid render geometry does not activate per-tier scaling`() {
        assertEquals(0, AutoDpiPolicy.layoutWidthDp(0, 200))
        assertEquals(0, AutoDpiPolicy.layoutWidthDp(2914, 0))
    }

    @Test
    fun `density uses the drawable inner width after video margins`() {
        val targetDp = AutoDpiPolicy.layoutWidthDp(
            renderWidthPx = 1000,
            panelDensityDpi = 200,
        )

        assertEquals(800, targetDp)
        assertEquals(320, AutoDpiPolicy.densityForWidth(1600, targetDp))
    }
}
