package com.openautolink.app.video

/** Density calculations shared by automatic video negotiation. */
object AutoDpiPolicy {
    /**
     * Convert the measured render width and panel density into the logical width
     * that every advertised resolution tier should preserve.
     */
    fun layoutWidthDp(renderWidthPx: Int, panelDensityDpi: Int): Int {
        if (renderWidthPx <= 0 || panelDensityDpi <= 0) return 0
        return ((renderWidthPx.toLong() * 160L) / panelDensityDpi)
            .coerceAtLeast(1L)
            .toInt()
    }

    /** Compute the density needed for [codecWidthPx] to retain [layoutWidthDp]. */
    fun densityForWidth(codecWidthPx: Int, layoutWidthDp: Int): Int {
        if (codecWidthPx <= 0 || layoutWidthDp <= 0) return 0
        return ((codecWidthPx.toLong() * 160L) / layoutWidthDp)
            .coerceAtLeast(80L)
            .toInt()
    }
}
